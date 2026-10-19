package com.gathin.relay;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// /a/<id>/manifest.json and /a/<id>/stream/...: a Stremio add-on that wraps an upstream add-on, declares only
// the `stream` resource (the apps keep using their usual metadata add-ons) and points every http(s) stream at
// the local relay. All other stream fields are kept, so labels, sorting and binge groups stay the same.
final class Addons {
    static final String TAG = HttpServer.TAG;
    static final String TEST_VIDEO = "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4";

    private final Store store;
    private final Map<String, Object[]> manifestCache = new HashMap<>(); // id -> {time, json}

    Addons(Store store) { this.store = store; }

    boolean handle(HttpServer.Req r) throws IOException {
        String[] seg = r.path.split("/", 4); // "", "a", id, rest
        if (seg.length < 4) { r.send(404, "text/plain", "not found"); return true; }
        String id = seg[2], rest = seg[3];
        if (id.equals("test")) return test(r, rest);
        Store.Upstream up = store.find(id);
        if (up == null) { r.send(404, "text/plain", "unknown add-on"); return true; }
        try {
            if (rest.equals("manifest.json")) {
                r.send(200, "application/json; charset=utf-8", manifest(up).toString());
            } else if (rest.startsWith("stream/")) {
                String q = r.query.isEmpty() ? "" : "?" + r.query;
                JSONObject res = new JSONObject(get(up.base() + "/" + rest + q, r.header("user-agent")));
                rewrite(res.optJSONArray("streams"));
                r.send(200, "application/json; charset=utf-8", res.toString(), "Cache-Control", "max-age=60");
            } else {
                r.send(404, "text/plain", "only stream is relayed");
            }
        } catch (Exception e) {
            Log.w(TAG, "addon " + id + ": " + e.getClass().getSimpleName() + " " + safe(e.getMessage()));
            r.send(502, "application/json", "{\"streams\":[]}");
        }
        return true;
    }

    JSONObject manifest(Store.Upstream up) throws Exception {
        synchronized (manifestCache) {
            Object[] c = manifestCache.get(up.id);
            if (c != null && System.currentTimeMillis() - (Long) c[0] < 10 * 60_000L) return new JSONObject((String) c[1]);
        }
        JSONObject m = new JSONObject(get(up.url, null));
        JSONObject w = new JSONObject();
        w.put("id", m.optString("id", up.id) + ".relay");
        w.put("version", m.optString("version", "1.0.0"));
        w.put("name", up.name + " ⚡");
        w.put("description", "Through the local multi-connection relay. " + m.optString("description", ""));
        if (m.has("logo")) w.put("logo", m.get("logo"));
        if (m.has("background")) w.put("background", m.get("background"));
        // Only the stream resource, with its types/idPrefixes if the upstream declared them per resource.
        JSONArray resources = new JSONArray();
        JSONArray types = m.optJSONArray("types");
        Object prefixes = m.opt("idPrefixes");
        JSONArray rs = m.optJSONArray("resources");
        if (rs != null) {
            for (int i = 0; i < rs.length(); i++) {
                Object o = rs.get(i);
                if ("stream".equals(o)) resources.put("stream");
                else if (o instanceof JSONObject && "stream".equals(((JSONObject) o).optString("name"))) resources.put(o);
            }
        }
        if (resources.length() == 0) resources.put("stream");
        w.put("resources", resources);
        w.put("types", types != null ? types : new JSONArray().put("movie").put("series"));
        if (prefixes != null) w.put("idPrefixes", prefixes);
        w.put("catalogs", new JSONArray());
        w.put("behaviorHints", new JSONObject().put("configurable", false).put("configurationRequired", false));
        synchronized (manifestCache) { manifestCache.put(up.id, new Object[]{System.currentTimeMillis(), w.toString()}); }
        return w;
    }

    static void rewrite(JSONArray streams) throws org.json.JSONException {
        if (streams == null) return;
        for (int i = 0; i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i);
            if (s == null) continue;
            String url = s.optString("url", "");
            if (!url.startsWith("http") || url.startsWith("http://127.0.0.1")) continue;
            JSONObject bh = s.optJSONObject("behaviorHints");
            JSONObject req = null;
            String filename = null;
            if (bh != null) {
                JSONObject ph = bh.optJSONObject("proxyHeaders");
                if (ph != null) req = ph.optJSONObject("request");
                filename = bh.optString("filename", null);
            }
            s.put("url", Relay.wrap(url, req, filename));
        }
    }

    // Step-1 check: a fixed test stream for any movie or episode.
    private boolean test(HttpServer.Req r, String rest) throws IOException {
        try {
            if (rest.equals("manifest.json")) {
                JSONObject m = new JSONObject()
                        .put("id", "local.relay.test").put("version", "1.0.0").put("name", "Relay test ⚡")
                        .put("description", "Local relay check: plays a sample video for any title.")
                        .put("resources", new JSONArray().put("stream"))
                        .put("types", new JSONArray().put("movie").put("series"))
                        .put("idPrefixes", new JSONArray().put("tt"))
                        .put("catalogs", new JSONArray());
                r.send(200, "application/json; charset=utf-8", m.toString());
            } else if (rest.startsWith("stream/")) {
                JSONObject s = new JSONObject().put("name", "Relay ⚡").put("title", "Relay test: Big Buck Bunny (sample)")
                        .put("url", TEST_VIDEO)
                        .put("behaviorHints", new JSONObject().put("bingeGroup", "relay-test").put("filename", "BigBuckBunny.mp4"));
                JSONArray a = new JSONArray().put(s);
                rewrite(a);
                r.send(200, "application/json; charset=utf-8", new JSONObject().put("streams", a).toString());
            } else {
                r.send(404, "text/plain", "not found");
            }
        } catch (org.json.JSONException e) {
            r.send(500, "text/plain", "json");
        }
        return true;
    }

    static String get(String url, String ua) throws IOException {
        HttpURLConnection c = Relay.open(url, new HashMap<>(), 30_000);
        c.setRequestProperty("Accept", "application/json");
        if (ua != null) c.setRequestProperty("User-Agent", ua);
        int code = c.getResponseCode();
        if (code != 200) { c.disconnect(); throw new IOException("HTTP " + code); }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        }
    }

    // Exception messages from URL classes can contain the URL (and its key): keep only the first word.
    static String safe(String msg) {
        if (msg == null) return "";
        return msg.contains("://") ? "(details hidden)" : msg;
    }
}
