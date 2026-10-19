package com.gathin.relay;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

// Tiny HTTP/1.1 server: one thread per connection, keep-alive, no chunked request bodies.
final class HttpServer {
    static final String TAG = "relay";

    interface Handler { boolean handle(Req r) throws IOException; } // returns false to close the connection

    static final class Req {
        String method, path, query = "";
        final Map<String, String> headers = new HashMap<>(); // lower-case names
        byte[] body = new byte[0];
        boolean loopback;
        Socket socket;
        InputStream in; // buffered, supports mark/reset
        OutputStream out;

        String header(String name) { return headers.get(name); }

        Map<String, String> params() {
            Map<String, String> m = new LinkedHashMap<>();
            parseForm(query, m);
            if (body.length > 0) parseForm(new String(body, StandardCharsets.UTF_8), m);
            return m;
        }

        void send(int code, String type, byte[] data, String... extra) throws IOException {
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
            h.append("Content-Type: ").append(type).append("\r\n");
            h.append("Content-Length: ").append(data.length).append("\r\n");
            h.append("Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Private-Network: true\r\n");
            for (int i = 0; i + 1 < extra.length; i += 2) h.append(extra[i]).append(": ").append(extra[i + 1]).append("\r\n");
            h.append("\r\n");
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));
            if (!"HEAD".equals(method)) out.write(data);
            out.flush();
        }

        void send(int code, String type, String text, String... extra) throws IOException {
            send(code, type, text.getBytes(StandardCharsets.UTF_8), extra);
        }
    }

    private final int port;
    private final Handler handler;
    private final ExecutorService pool;
    private volatile ServerSocket server;

    HttpServer(int port, Handler handler, ExecutorService pool) {
        this.port = port; this.handler = handler; this.pool = pool;
    }

    void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port), 64); // all interfaces: LAN clients only get /setup
        server = s;
        pool.execute(() -> {
            while (!s.isClosed()) {
                try {
                    Socket c = s.accept();
                    pool.execute(() -> serve(c));
                } catch (IOException e) {
                    if (!s.isClosed()) Log.w(TAG, "accept: " + e);
                }
            }
        });
    }

    void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    private void serve(Socket c) {
        try (Socket sock = c) {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(60_000);
            sock.setSendBufferSize(1 << 20);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 16 * 1024);
            OutputStream out = sock.getOutputStream();
            // "Local" = this TV itself: loopback, or one of its own addresses (Wuplay uses the TV's LAN IP).
            boolean loop = sock.getInetAddress().isLoopbackAddress() || sock.getInetAddress().equals(sock.getLocalAddress());
            while (true) {
                String line = readLine(in);
                if (line == null) return;
                if (line.isEmpty()) continue;
                String[] parts = line.split(" ");
                if (parts.length < 2) return;
                Req r = new Req();
                r.method = parts[0].toUpperCase(Locale.ROOT);
                String target = parts[1];
                int q = target.indexOf('?');
                r.path = q >= 0 ? target.substring(0, q) : target;
                if (q >= 0) r.query = target.substring(q + 1);
                String h;
                while ((h = readLine(in)) != null && !h.isEmpty()) {
                    int i = h.indexOf(':');
                    if (i > 0) r.headers.put(h.substring(0, i).trim().toLowerCase(Locale.ROOT), h.substring(i + 1).trim());
                }
                if (h == null) return;
                String len = r.header("content-length");
                if (len != null) {
                    int n = Math.min(Integer.parseInt(len), 1 << 20);
                    byte[] b = new byte[n];
                    int got = 0;
                    while (got < n) {
                        int k = in.read(b, got, n - got);
                        if (k < 0) return;
                        got += k;
                    }
                    r.body = b;
                }
                r.loopback = loop;
                r.socket = sock;
                r.in = in;
                r.out = out;
                boolean keep;
                try {
                    keep = handler.handle(r);
                } catch (IOException e) {
                    return; // client went away or upstream broke mid-body
                } catch (Exception e) {
                    Log.w(TAG, "handler: " + e.getClass().getSimpleName());
                    try { r.send(500, "text/plain", "relay error"); } catch (IOException ignored) { }
                    return;
                }
                if (!keep || "close".equalsIgnoreCase(r.header("connection"))) return;
            }
        } catch (IOException ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 16 * 1024) throw new IOException("header too long");
        }
        if (ch < 0 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    static void parseForm(String s, Map<String, String> m) {
        if (s == null || s.isEmpty()) return;
        for (String kv : s.split("&")) {
            int i = kv.indexOf('=');
            try {
                if (i < 0) m.put(URLDecoder.decode(kv, "UTF-8"), "");
                else m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
    }

    static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 302: return "Found";
            case 303: return "See Other";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            case 502: return "Bad Gateway";
            default: return "Status";
        }
    }
}
