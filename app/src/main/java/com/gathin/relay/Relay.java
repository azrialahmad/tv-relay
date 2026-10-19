package com.gathin.relay;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// /play: multi-connection Range relay. The firmware caps each TCP socket's receive buffer at 2 MB, which
// limits one connection to ~90 Mbps on this TV. The relay answers the player's (Range) request itself and
// fills it over several parallel Range connections, each streaming a long run of the file (one request per run,
// not per chunk), writing the bytes to the player in order. Memory is bounded by a shared pool of 4 MB buffers.
final class Relay {
    static final String TAG = HttpServer.TAG;

    private final Store store;
    private final ExecutorService pool;
    private final Map<String, Target> targets = new HashMap<>();
    private BufferPool buffers;


    // Stats for the status page (hosts only, never URLs).
    static final class Stat {
        String host, note = "";
        long start, bytes, from, to, last;
        int conns, retries;
        boolean active = true;
    }
    final ArrayDeque<Stat> recent = new ArrayDeque<>();

    Relay(Store store, ExecutorService pool) { this.store = store; this.pool = pool; }

    // One resolved stream URL (resolver redirects followed, length and range support probed).
    static final class Target {
        final String orig;
        final Map<String, String> headers;
        volatile String url;
        volatile long length = -1, resolvedAt;
        volatile String type, host = "", note = "";
        volatile boolean ranges;
        Session newest;
        Target(String orig, Map<String, String> headers) { this.orig = orig; this.headers = headers; }
    }

    // Wrapped stream URL as handed to the players.
    static String wrap(String url, JSONObject proxyRequestHeaders, String filename) {
        String name = filename;
        if (name == null || name.isEmpty()) {
            String p = url.replaceAll("[?#].*$", "");
            name = p.substring(p.lastIndexOf('/') + 1);
        }
        name = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (name.length() > 80) name = name.substring(name.length() - 80);
        if (name.isEmpty() || name.equals("_")) name = "stream";
        StringBuilder s = new StringBuilder("http://127.0.0.1:").append(RelayService.PORT).append("/play/").append(name)
                .append("?u=").append(b64(url));
        if (proxyRequestHeaders != null && proxyRequestHeaders.length() > 0) s.append("&h=").append(b64(proxyRequestHeaders.toString()));
        return s.toString();
    }

    static String b64(String s) {
        return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    static String unb64(String s) {
        return new String(Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8);
    }

    boolean handle(HttpServer.Req r) throws IOException {
        Map<String, String> p = r.params();
        String u = p.get("u");
        if (u == null) { r.send(400, "text/plain", "missing u"); return true; }
        String url;
        Map<String, String> hdrs = new LinkedHashMap<>();
        try {
            url = unb64(u);
            if (p.get("h") != null) {
                JSONObject h = new JSONObject(unb64(p.get("h")));
                for (Iterator<String> it = h.keys(); it.hasNext(); ) { String k = it.next(); hdrs.put(k, h.getString(k)); }
            }
        } catch (Exception e) { r.send(400, "text/plain", "bad u"); return true; }
        if (!url.startsWith("http://") && !url.startsWith("https://")) { r.send(400, "text/plain", "bad u"); return true; }

        Target t;
        synchronized (targets) {
            String key = u + "|" + p.get("h");
            t = targets.get(key);
            if (t == null) {
                if (targets.size() > 50) targets.clear();
                t = new Target(url, hdrs);
                targets.put(key, t);
            }
        }
        try {
            synchronized (t) {
                if (t.url == null || System.currentTimeMillis() - t.resolvedAt > 60 * 60_000L) resolve(t);
            }
        } catch (IOException e) {
            Log.w(TAG, "resolve failed: " + Addons.safe(e.getMessage()));
            r.send(502, "text/plain", "upstream: " + Addons.safe(e.getMessage()));
            return true;
        }
        if (!t.ranges || t.length <= 0) return passthrough(r, t);

        long start = 0, end = t.length - 1;
        String range = r.header("range");
        boolean partial = false;
        if (range != null) {
            Matcher m = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
            if (m.find()) {
                partial = true;
                if (m.group(1).isEmpty()) {
                    if (!m.group(2).isEmpty()) start = Math.max(0, t.length - Long.parseLong(m.group(2)));
                } else {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) end = Math.min(end, Long.parseLong(m.group(2)));
                }
            }
        }
        if (start >= t.length || start > end) {
            r.send(416, "text/plain", "", "Content-Range", "bytes */" + t.length);
            return true;
        }
        long len = end - start + 1;
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
        h.append("Content-Type: ").append(contentType(t, r.path)).append("\r\n");
        h.append("Content-Length: ").append(len).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        if (partial) h.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(t.length).append("\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n\r\n");
        r.out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        r.out.flush();
        if ("HEAD".equals(r.method)) return true;

        Session s = new Session(t, start, end);
        Session old;
        synchronized (t) {
            old = t.newest;
            t.newest = s;
        }
        if (old != null) old.supersede(); // a seek: the older reader of this file stops reading ahead
        return s.run(r);
    }

    // Follow the resolver's redirects to the CDN and probe length and Range support with a 1-byte request.
    private void resolve(Target t) throws IOException {
        String url = t.orig;
        boolean viaProxy = false;
        for (int hop = 0; hop < 10; hop++) {
            if (looksLikeProxy(url)) viaProxy = true;
            HttpURLConnection c = open(url, t.headers, 15_000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            t.host = new URL(url).getHost();
            t.type = c.getContentType();
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                long total = -1;
                if (cr != null && cr.contains("/")) {
                    try { total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim()); } catch (NumberFormatException ignored) { }
                }
                try (InputStream in = c.getInputStream()) { in.read(); }
                t.length = total;
                t.ranges = total > 0;
            } else if (code == 200) {
                t.length = c.getContentLengthLong();
                t.ranges = false;
                c.disconnect();
            } else {
                c.disconnect();
                throw new IOException("HTTP " + code + " from " + t.host);
            }
            t.url = url;
            t.resolvedAt = System.currentTimeMillis();
            t.note = viaProxy || looksLikeProxy(url) ? "via proxy (MediaFlow/StremThru?): only the hop to the proxy is sped up" : "";
            Log.i(TAG, "resolved -> " + t.host + " len=" + t.length + " ranges=" + t.ranges + (t.note.isEmpty() ? "" : " PROXY"));
            return;
        }
        throw new IOException("too many redirects");
    }

    static boolean looksLikeProxy(String url) {
        String l = url.toLowerCase(Locale.ROOT);
        return l.contains("mediaflow") || l.contains("stremthru") || l.contains("/proxy/stream") || l.contains("/v0/proxy");
    }

    static HttpURLConnection open(String url, Map<String, String> headers, int readTimeout) throws IOException {
        URL u = new URL(url);
        String auth = null;
        if (u.getUserInfo() != null) { // https://user:pass@host/... : Java doesn't send these by itself
            auth = "Basic " + Base64.encodeToString(java.net.URLDecoder.decode(u.getUserInfo(), "UTF-8")
                    .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            u = new URL(u.getProtocol(), u.getHost(), u.getPort(), u.getFile());
        }
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        if (auth != null) c.setRequestProperty("Authorization", auth);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) relay/1.0");
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        return c;
    }

    private static String contentType(Target t, String path) {
        String type = t.type;
        if (type != null && !type.startsWith("application/octet-stream") && !type.startsWith("binary/")) return type;
        String l = path.toLowerCase(Locale.ROOT);
        if (l.endsWith(".mkv")) return "video/x-matroska";
        if (l.endsWith(".mp4") || l.endsWith(".m4v")) return "video/mp4";
        if (l.endsWith(".webm")) return "video/webm";
        if (l.endsWith(".avi")) return "video/x-msvideo";
        if (l.endsWith(".ts")) return "video/mp2t";
        return type != null ? type : "application/octet-stream";
    }

    // Server without Range support (or unknown length): one connection, piped through.
    private boolean passthrough(HttpServer.Req r, Target t) throws IOException {
        HttpURLConnection c = open(t.url, t.headers, 30_000);
        if (r.header("range") != null) c.setRequestProperty("Range", r.header("range"));
        if ("HEAD".equals(r.method)) c.setRequestMethod("HEAD");
        int code = c.getResponseCode();
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(HttpServer.reason(code)).append("\r\n");
        for (String name : new String[]{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"}) {
            String v = c.getHeaderField(name);
            if (v != null) h.append(name).append(": ").append(v).append("\r\n");
        }
        boolean known = c.getHeaderField("Content-Length") != null;
        if (!known) h.append("Connection: close\r\n");
        h.append("Access-Control-Allow-Origin: *\r\n\r\n");
        r.out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        Stat st = stat(t, 1);
        try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
            if (in != null && !"HEAD".equals(r.method)) {
                byte[] b = new byte[64 * 1024];
                int n;
                while ((n = in.read(b)) > 0) { r.out.write(b, 0, n); st.bytes += n; st.last = System.currentTimeMillis(); }
            }
        } finally {
            st.active = false;
            c.disconnect();
        }
        r.out.flush();
        return known;
    }

    private Stat stat(Target t, int conns) {
        Stat s = new Stat();
        s.host = t.host; s.note = t.note; s.conns = conns;
        s.start = s.last = System.currentTimeMillis();
        synchronized (recent) {
            recent.addFirst(s);
            while (recent.size() > 12) recent.removeLast();
        }
        return s;
    }

    static final int PIECE = 4 << 20; // buffer unit

    synchronized BufferPool buffers() {
        int max = Math.max(4, (store.budgetMb() << 20) / PIECE);
        if (buffers == null || buffers.max != max) buffers = new BufferPool(PIECE, max);
        return buffers;
    }

    // Fixed-size piece buffers shared by all sessions. Buffers are kept for reuse while streaming and
    // dropped (left to GC) after a minute without use.
    static final class BufferPool {
        final int size, max;
        private final ArrayDeque<byte[]> free = new ArrayDeque<>();
        private int out, allocated;
        private long lastUse;

        BufferPool(int size, int max) { this.size = size; this.max = max; }

        // Blocks while the budget is used up, except for the piece the player needs next, so a stalled
        // reader (paused player) can never starve a new one. A blocked worker keeps its connection open:
        // TCP flow control pauses the server until a buffer frees up.
        synchronized byte[] take(Session s, int index) throws InterruptedException {
            while (out >= max && !s.isHead(index) && !s.cancelled && !s.isAborted(index)) wait(500);
            if (s.cancelled || s.isAborted(index)) return null;
            out++;
            lastUse = System.currentTimeMillis();
            byte[] b = free.poll();
            if (b == null) { b = new byte[size]; allocated++; }
            return b;
        }

        synchronized void give(byte[] b) {
            out--;
            lastUse = System.currentTimeMillis();
            if (b.length == size && free.size() + out < max) free.push(b); else allocated--;
            notifyAll();
        }

        synchronized void wake() { notifyAll(); }

        synchronized void trim() {
            if (out == 0 && System.currentTimeMillis() - lastUse > 60_000) { allocated -= free.size(); free.clear(); }
        }

        synchronized String describe() { return out + " in use, " + free.size() + " idle, " + (size >> 20) + " MB each, budget " + max; }
    }

    private static final class Piece {
        final int index;
        final long begin;
        final int len;
        byte[] buf;
        int filled;
        boolean reading, aborted; // reading: a worker owns the buffer right now
        HttpURLConnection conn;   // set while bytes for this piece are arriving
        long progress;            // time of the last bytes received
        IOException err;
        Piece(int index, long begin, int len) { this.index = index; this.begin = begin; this.len = len; }
    }

    // Consecutive pieces [cur, endIdx) fetched by ONE Range request that streams straight through them, so a
    // connection pays its request latency and TCP slow start once per run, not once per piece. An idle worker
    // may take over the unstarted second half of a run (endIdx shrinks; the owner stops there).
    private static final class Run {
        int cur, endIdx;
        boolean streaming = true; // counts toward `conns` until it is near its end
        Run(int cur, int endIdx) { this.cur = cur; this.endIdx = endIdx; }
    }

    static final int TAIL = 6 << 20; // a run this close to its end lets another connection start (hides its TTFB)

    final class Session {
        final Target t;
        final long start, end;
        final int piece, conns, count, runPieces;
        final BufferPool bp;
        final Map<Integer, Piece> pieces = new HashMap<>();
        final List<Run> runs = new ArrayList<>();
        int next, writeIdx, streaming;
        volatile boolean cancelled, superseded;
        final Stat st;

        Session(Target t, long start, long end) {
            this.t = t; this.start = start; this.end = end;
            bp = buffers();
            piece = bp.size;
            conns = store.conns();
            runPieces = Math.max(1, (store.runMb() << 20) / piece);
            count = (int) ((end - start + piece) / piece);
            st = stat(t, conns);
            st.from = start; st.to = end;
        }

        synchronized boolean isHead(int index) { return index == writeIdx; }

        synchronized boolean isAborted(int index) {
            Piece p = pieces.get(index);
            return p == null || p.aborted;
        }

        // How far past the player's position new runs may start.
        private int window() { return superseded ? 1 : bp.max; }

        boolean run(HttpServer.Req r) throws IOException {
            OutputStream out = r.out;
            // A couple of spare workers take over when a run nears its end.
            int workers = Math.min(conns + 2, count);
            for (int i = 0; i < workers; i++) { final int id = i; pool.execute(() -> work(id)); }
            try {
                for (int k = 0; k < count; k++) {
                    Piece c;
                    int written = 0;
                    while (true) {
                        int upto;
                        synchronized (this) {
                            long waited = 0;
                            while (true) {
                                if (cancelled) throw new IOException("cancelled");
                                c = pieces.get(k);
                                if (c != null && c.err != null) throw c.err;
                                if (c != null && c.buf != null && (upto = c.filled) > written) break;
                                try { wait(250); } catch (InterruptedException e) { throw new IOException(e); }
                                waited += 250;
                                // Nothing to write for a while: check whether the player hung up.
                                if (waited % 1000 == 0 && clientGone(r)) throw new IOException("client gone");
                                kickStalled(c);
                            }
                        }
                        out.write(c.buf, written, upto - written);
                        st.bytes += upto - written;
                        st.last = System.currentTimeMillis();
                        written = upto;
                        if (written >= c.len) break;
                    }
                    byte[] release;
                    synchronized (this) {
                        pieces.remove(k);
                        release = c.buf;
                        c.buf = null;
                        writeIdx = k + 1;
                        notifyAll();
                    }
                    bp.give(release);
                }
                out.flush();
                return true;
            } catch (IOException e) {
                String m = e.getMessage();
                if (!"cancelled".equals(m) && !"client gone".equals(m)) Log.i(TAG, "session ended: " + e.getClass().getSimpleName() + " " + Addons.safe(m));
                return false;
            } finally {
                cancel();
                st.active = false;
            }
        }

        // The player is waiting on this piece: if its connection has gone quiet, drop it so the worker retries
        // on a fresh one at once instead of waiting for the read timeout.
        private void kickStalled(Piece c) {
            if (c == null || c.conn == null || c.filled >= c.len) return;
            long now = System.currentTimeMillis();
            if (now - c.progress > 2500) {
                HttpURLConnection h = c.conn;
                c.progress = now;
                st.retries++;
                pool.execute(h::disconnect);
            }
        }

        private boolean clientGone(HttpServer.Req r) {
            try {
                int old = r.socket.getSoTimeout();
                r.socket.setSoTimeout(1);
                r.in.mark(1);
                try {
                    if (r.in.read() < 0) return true;
                    r.in.reset();
                } catch (java.net.SocketTimeoutException alive) {
                    // nothing to read: still connected
                } finally {
                    r.socket.setSoTimeout(old);
                }
            } catch (IOException e) {
                return true;
            }
            return false;
        }

        // Another request for the same file arrived (a seek): stop reading ahead here and free the bandwidth.
        void supersede() { drop(false); }

        private void cancel() {
            drop(true);
            synchronized (t) { if (t.newest == this) t.newest = null; }
        }

        // Abort every piece past the player's position (all of them when cancelling). Buffers of pieces no worker
        // holds are released here; a worker holding one releases it when it notices.
        private void drop(boolean all) {
            List<HttpURLConnection> conns = new ArrayList<>();
            List<byte[]> release = new ArrayList<>();
            synchronized (this) {
                if (cancelled) return;
                if (all) cancelled = true; else superseded = true;
                for (Iterator<Piece> it = pieces.values().iterator(); it.hasNext(); ) {
                    Piece c = it.next();
                    if (!all && c.index <= writeIdx) continue;
                    c.aborted = true;
                    if (c.conn != null) conns.add(c.conn);
                    if (!c.reading) {
                        if (c.buf != null) release.add(c.buf);
                        c.buf = null;
                        it.remove();
                    }
                }
                for (Run run : runs) run.endIdx = Math.min(run.endIdx, Math.max(run.cur, writeIdx + 1));
                next = Math.min(next, writeIdx + 1);
                notifyAll();
            }
            for (HttpURLConnection h : conns) pool.execute(h::disconnect);
            for (byte[] b : release) bp.give(b);
            bp.wake();
        }

        private void work(int id) {
            while (true) {
                Run run = null;
                synchronized (this) {
                    while (true) {
                        if (cancelled) return;
                        if (superseded && id > 0) return;
                        if (streaming < (superseded ? 1 : conns)) {
                            if (next < count && next - writeIdx < window()) {
                                run = new Run(next, Math.min(count, next + (superseded ? 1 : runPieces)));
                                for (int k = run.cur; k < run.endIdx; k++) {
                                    long b = start + (long) k * piece;
                                    pieces.put(k, new Piece(k, b, (int) Math.min(piece, end + 1 - b)));
                                }
                                next = run.endIdx;
                                break;
                            }
                            if (!superseded && (run = split()) != null) break;
                        }
                        if (next >= count && runs.isEmpty()) return;
                        try { wait(250); } catch (InterruptedException e) { return; }
                    }
                    runs.add(run);
                    streaming++;
                }
                stream(run);
                synchronized (this) {
                    runs.remove(run);
                    if (run.streaming) streaming--;
                    notifyAll();
                }
            }
        }

        // Take over the unstarted second half of the run with the most unstarted pieces (nearest the player).
        private Run split() {
            Run best = null;
            int bestLeft = 1;
            for (Run run : runs) {
                int left = run.endIdx - run.cur - 1; // pieces after the one being read
                if (left > bestLeft || (left == bestLeft && best != null && run.cur < best.cur)) { best = run; bestLeft = left; }
            }
            if (best == null) return null;
            int mid = best.endIdx - bestLeft / 2;
            Run tail = new Run(mid, best.endIdx);
            best.endIdx = mid;
            return tail;
        }

        // Called under the lock: bytes this run still has to read.
        private long remaining(Run run, Piece c) {
            Piece last = pieces.get(run.endIdx - 1);
            long runEnd = last != null ? last.begin + last.len : c.begin + c.len;
            return runEnd - (c.begin + c.filled);
        }

        // One run: a single Range request read straight through its pieces; on errors, a new request from where
        // it stopped.
        private void stream(Run run) {
            int attempts = 0;
            while (true) {
                Piece c;
                long from, to;
                synchronized (this) {
                    if (cancelled || run.cur >= run.endIdx) return;
                    c = pieces.get(run.cur);
                    if (c == null || c.aborted) return;
                    Piece last = pieces.get(run.endIdx - 1);
                    from = c.begin + c.filled;
                    to = (last != null ? last.begin + last.len : c.begin + c.len) - 1;
                }
                long issued = System.currentTimeMillis();
                HttpURLConnection conn = null;
                try {
                    conn = open(t.url, t.headers, 4_000); // 4 s of silence = stalled connection: retry on a fresh one
                    conn.setRequestProperty("Range", "bytes=" + from + "-" + to);
                    int code = conn.getResponseCode();
                    if (code == 401 || code == 403 || code == 404 || code == 410) {
                        conn.disconnect();
                        synchronized (t) { if (t.resolvedAt < issued) resolve(t); } // CDN link expired: ask the resolver again
                        throw new IOException("HTTP " + code + ", re-resolved");
                    }
                    if (code != 206) throw new IOException("HTTP " + code + " for a range");
                    String cr = conn.getHeaderField("Content-Range");
                    if (cr != null && !cr.startsWith("bytes " + from + "-")) throw new IOException("range mismatch");
                    long pos = from;
                    boolean more = true;
                    try (InputStream in = conn.getInputStream()) {
                        while (true) {
                            // Next piece of the run (it may have been shortened by split() or a seek).
                            synchronized (this) {
                                if (cancelled || run.cur >= run.endIdx) { more = false; break; }
                                c = pieces.get(run.cur);
                                if (c == null || c.aborted) { more = false; break; }
                                if (c.begin + c.filled != pos) throw new IOException("out of step");
                                c.reading = true;
                            }
                            if (c.buf == null) {
                                byte[] buf = null;
                                try { buf = bp.take(this, c.index); } catch (InterruptedException ignored) { }
                                synchronized (this) {
                                    if (buf != null && !c.aborted && !cancelled) { c.buf = buf; buf = null; }
                                }
                                if (buf != null) bp.give(buf);
                                if (c.buf == null) { finish(c); more = false; break; }
                            }
                            synchronized (this) { c.conn = conn; c.progress = System.currentTimeMillis(); }
                            while (c.filled < c.len && !c.aborted && !cancelled) {
                                int got = in.read(c.buf, c.filled, Math.min(256 * 1024, c.len - c.filled));
                                if (got < 0) throw new IOException("short read");
                                pos += got;
                                synchronized (this) {
                                    c.filled += got;
                                    c.progress = System.currentTimeMillis();
                                    if (run.streaming && remaining(run, c) <= TAIL) { run.streaming = false; streaming--; }
                                    notifyAll();
                                }
                            }
                            if (!finish(c)) { more = false; break; }
                            synchronized (this) { run.cur++; attempts = 0; }
                        }
                    }
                    if (pos <= to) conn.disconnect(); // the unread rest of the response isn't ours any more
                    if (!more) return;
                } catch (IOException e) {
                    if (conn != null) conn.disconnect();
                    boolean stop;
                    synchronized (this) { stop = cancelled || c == null || c.aborted; }
                    if (c != null && c.reading && !finish(c)) stop = true;
                    if (stop) return;
                    st.retries++;
                    if (++attempts >= 8) {
                        Log.w(TAG, "piece failed: " + Addons.safe(e.getMessage()));
                        synchronized (this) { c.err = e; notifyAll(); }
                        return;
                    }
                    try { Thread.sleep(100L * attempts); } catch (InterruptedException ie) { return; }
                }
            }
        }

        // The worker lets go of a piece. Returns false if the piece was aborted meanwhile (its buffer is freed).
        private boolean finish(Piece c) {
            byte[] release = null;
            boolean ok;
            synchronized (this) {
                c.reading = false;
                c.conn = null;
                ok = !c.aborted && !cancelled;
                if (!ok) {
                    release = c.buf;
                    c.buf = null;
                    if (pieces.get(c.index) == c) pieces.remove(c.index);
                }
                notifyAll();
            }
            if (release != null) bp.give(release);
            return ok;
        }
    }
}
