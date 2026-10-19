package com.gathin.relay;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

// /setup: add or remove upstream add-ons and tune the relay, from a phone or PC on the LAN. Upstream URLs are
// write-only here: the page shows each add-on's name and host, never the URL (it contains the API key).
final class Setup {
    private final Store store;
    private final Relay relay;

    Setup(Store store, Relay relay) { this.store = store; this.relay = relay; }

    boolean handle(HttpServer.Req r) throws IOException {
        String msg = "";
        if ("POST".equals(r.method)) {
            Map<String, String> p = r.params();
            try {
                if (r.path.equals("/setup/add")) msg = add(p.get("url"), p.get("name"), p.get("id"));
                else if (r.path.equals("/setup/del")) { store.remove(p.get("id")); msg = "Removed " + esc(p.get("id")) + "."; }
                else if (r.path.equals("/setup/tune")) {
                    store.setTuning(num(p.get("conns"), 4), num(p.get("chunk"), 16), num(p.get("budget"), 96));
                    msg = "Saved. New streams use the new settings.";
                }
            } catch (Exception e) {
                msg = "Error: " + esc(Addons.safe(e.getMessage()));
            }
        }
        r.send(200, "text/html; charset=utf-8", page(msg), "Cache-Control", "no-store");
        return true;
    }

    private String add(String url, String name, String id) throws Exception {
        if (url == null) throw new IOException("no URL");
        url = url.trim();
        if (url.startsWith("stremio://")) url = "https://" + url.substring(10);
        if (!url.startsWith("https://") && !url.startsWith("http://")) throw new IOException("the URL must start with https://");
        if (!new URL(url).getPath().endsWith("manifest.json")) throw new IOException("paste the add-on's manifest.json URL");
        JSONObject m = new JSONObject(Addons.get(url, null));
        if (name == null || name.trim().isEmpty()) name = m.optString("name", "Add-on");
        name = name.trim();
        if (id == null || id.trim().isEmpty()) {
            String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
            if (base.isEmpty()) base = "addon";
            if (base.length() > 8) base = base.substring(0, 8);
            id = base;
            for (int i = 2; store.find(id) != null; i++) id = base + i;
        }
        id = id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
        if (id.isEmpty() || id.equals("test")) throw new IOException("pick another id");
        store.put(new Store.Upstream(id, name, url));
        return "Added \"" + esc(name) + "\". Its add-on URLs are listed below.";
    }

    private String page(String msg) {
        StringBuilder s = new StringBuilder();
        s.append("<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>TV relay</title><style>body{font:16px system-ui,sans-serif;max-width:760px;margin:20px auto;padding:0 16px;")
                .append("background:#10151f;color:#e8ecf3}input{font:inherit;padding:8px;border-radius:6px;border:1px solid #445;background:#1a2230;color:inherit;")
                .append("width:100%;box-sizing:border-box;margin:4px 0}button{font:inherit;padding:8px 14px;border-radius:6px;border:0;background:#f5c542;color:#111;cursor:pointer}")
                .append("table{border-collapse:collapse;width:100%}td,th{padding:6px;border-bottom:1px solid #2a3344;text-align:left;vertical-align:top}")
                .append("code{background:#1a2230;padding:2px 6px;border-radius:4px;word-break:break-all}.msg{background:#24324a;padding:10px;border-radius:6px}")
                .append(".warn{color:#ffb35c}small{color:#9aa6b8}</style></head><body><h1>TV relay</h1>");
        if (!msg.isEmpty()) s.append("<p class=msg>").append(msg).append("</p>");
        s.append("<p>Status: ").append(esc(RelayService.status)).append("</p>");

        String lan = MainActivity.lanIp();
        s.append("<h2>Wrapped add-ons</h2><p><small>Stremio: use the 127.0.0.1 URL. Wuplay (config.wuplay.app): use the ")
                .append(esc(lan)).append(" URL and tick \"hosted on my local network\".</small></p>")
                .append("<table><tr><th>Add-on URLs</th><th>Upstream</th><th></th></tr>");
        s.append("<tr><td>").append(urls(lan, "test")).append("</td><td>Built-in test (sample video)</td><td></td></tr>");
        for (Store.Upstream u : store.upstreams()) {
            String host;
            try { host = new URL(u.url).getHost(); } catch (Exception e) { host = "?"; }
            s.append("<tr><td>").append(urls(lan, u.id)).append("</td><td>")
                    .append(esc(u.name)).append("<br><small>").append(esc(host)).append("</small></td><td>")
                    .append("<form method=post action=/setup/del><input type=hidden name=id value='").append(esc(u.id))
                    .append("'><button>Remove</button></form></td></tr>");
        }
        s.append("</table>");

        s.append("<h2>Add an upstream add-on</h2><form method=post action=/setup/add>")
                .append("<label>Manifest URL (the one you installed, ending in manifest.json)<input name=url autocomplete=off required></label>")
                .append("<label>Name (optional)<input name=name placeholder='e.g. Duck Streams'></label>")
                .append("<label>Short id (optional, used in the local URL)<input name=id placeholder='e.g. duck'></label>")
                .append("<button>Add</button></form><p><small>Adding the same id again replaces its URL.</small></p>");

        s.append("<h2>Tuning</h2><form method=post action=/setup/tune>")
                .append("<label>Parallel connections per stream (1-8)<input name=conns type=number value=").append(store.conns()).append("></label>")
                .append("<label>Chunk size in MB (1-16)<input name=chunk type=number value=").append(store.chunkMb()).append("></label>")
                .append("<label>Memory budget in MB (16-128)<input name=budget type=number value=").append(store.budgetMb()).append("></label>")
                .append("<button>Save</button></form>");

        s.append("<h2>Recent streams</h2><table><tr><th>Host</th><th>Conns</th><th>MB</th><th>Avg Mbps</th><th>Retries</th><th></th></tr>");
        synchronized (relay.recent) {
            for (Relay.Stat st : relay.recent) {
                double secs = Math.max(0.5, (st.last - st.start) / 1000.0);
                s.append("<tr><td>").append(esc(st.host)).append(st.note.isEmpty() ? "" : "<br><span class=warn>" + esc(st.note) + "</span>")
                        .append("</td><td>").append(st.conns).append("</td><td>").append(st.bytes >> 20)
                        .append("</td><td>").append(String.format(Locale.ROOT, "%.0f", st.bytes * 8 / secs / 1e6))
                        .append("</td><td>").append(st.retries).append("</td><td>").append(st.active ? "active" : "").append("</td></tr>");
            }
        }
        s.append("</table><p><small>Buffers: ").append(esc(relay.buffers().describe())).append("</small></p></body></html>");
        return s.toString();
    }

    private static String urls(String lan, String id) {
        String path = ":" + RelayService.PORT + "/a/" + esc(id) + "/manifest.json";
        return "Stremio: <code>http://127.0.0.1" + path + "</code><br>Wuplay: <code>http://" + esc(lan) + path + "</code>";
    }

    private static int num(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }
}
