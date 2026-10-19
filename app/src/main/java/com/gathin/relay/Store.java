package com.gathin.relay;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Settings and upstream add-ons, in app-private SharedPreferences. Upstream URLs carry API keys:
// they are never logged and never sent anywhere except to their own host.
final class Store {
    static final class Upstream {
        final String id, name, url;
        Upstream(String id, String name, String url) { this.id = id; this.name = name; this.url = url; }
        // Manifest URL without "/manifest.json": resources are fetched relative to it.
        String base() {
            String u = url;
            int q = u.indexOf('?');
            if (q >= 0) u = u.substring(0, q);
            return u.endsWith("/manifest.json") ? u.substring(0, u.length() - 14) : u.replaceAll("/+$", "");
        }
    }

    private final SharedPreferences p;

    Store(Context c) { p = c.getSharedPreferences("relay", Context.MODE_PRIVATE); }

    int conns() { return p.getInt("conns", 4); }
    boolean testEnabled() { return p.getBoolean("test", false); }
    void setTestEnabled(boolean on) { p.edit().putBoolean("test", on).apply(); }
    int runMb() { return p.getInt("runMb", 32); }
    int budgetMb() { return p.getInt("budgetMb", 96); }

    void setTuning(int conns, int runMb, int budgetMb) {
        p.edit().putInt("conns", clamp(conns, 1, 8)).putInt("runMb", clamp(runMb, 4, 64))
                .putInt("budgetMb", clamp(budgetMb, 16, 128)).apply();
    }

    synchronized List<Upstream> upstreams() {
        List<Upstream> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p.getString("upstreams", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Upstream(o.getString("id"), o.optString("name", o.getString("id")), o.getString("url")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    Upstream find(String id) {
        for (Upstream u : upstreams()) if (u.id.equals(id)) return u;
        return null;
    }

    synchronized void put(Upstream up) {
        List<Upstream> all = upstreams();
        all.removeIf(u -> u.id.equals(up.id));
        all.add(up);
        save(all);
    }

    synchronized void remove(String id) {
        List<Upstream> all = upstreams();
        all.removeIf(u -> u.id.equals(id));
        save(all);
    }

    private void save(List<Upstream> all) {
        JSONArray a = new JSONArray();
        try {
            for (Upstream u : all) a.put(new JSONObject().put("id", u.id).put("name", u.name).put("url", u.url));
        } catch (Exception ignored) { }
        p.edit().putString("upstreams", a.toString()).apply();
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
