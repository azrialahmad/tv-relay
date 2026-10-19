package com.gathin.relay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

// Foreground service that owns the HTTP server on port 7000.
public class RelayService extends Service {
    static final int PORT = 7000;
    static volatile String status = "starting";

    private HttpServer server;
    private Relay relay;
    private PowerManager.WakeLock wake;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    static void start(Context c) {
        Intent i = new Intent(c, RelayService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        goForeground();

        Store store = new Store(this);
        ExecutorService pool = Executors.newCachedThreadPool();
        relay = new Relay(store, pool);
        Addons addons = new Addons(store);
        Setup setup = new Setup(store, relay);
        server = new HttpServer(PORT, r -> {
            if (r.path.equals("/setup") || r.path.startsWith("/setup/") || r.path.equals("/")) return setup.handle(r);
            if ("OPTIONS".equals(r.method)) { // CORS + Chrome Local Network Access preflight (config.wuplay.app)
                r.send(204, "text/plain", new byte[0], "Access-Control-Allow-Methods", "GET, HEAD, OPTIONS",
                        "Access-Control-Allow-Headers", "*", "Access-Control-Allow-Private-Network", "true",
                        "Access-Control-Allow-Local-Network", "true");
                return true;
            }
            // Manifests carry no secrets, so browsers on the LAN may validate them. Stream lists and /play
            // contain the key-bearing upstream URLs: only this TV gets those.
            boolean manifest = r.path.startsWith("/a/") && r.path.endsWith("/manifest.json");
            if (!r.loopback && !manifest) { r.send(403, "text/plain", "only /setup and manifests are reachable from the network"); return false; }
            if (r.path.startsWith("/play")) return relay.handle(r);
            if (r.path.startsWith("/a/")) return addons.handle(r);
            r.send(404, "text/plain", "not found");
            return true;
        }, pool);
        try {
            server.start();
            status = "running on port " + PORT;
        } catch (IOException e) {
            status = "could not open port " + PORT + ": " + e.getMessage();
            Log.e(HttpServer.TAG, status);
        }
        // A partial wake lock keeps the CPU (and so the relay) running while a player streams with the screen dimmed.
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "relay:server");
        timer.scheduleWithFixedDelay(this::tick, 10, 10, TimeUnit.SECONDS);
    }

    // Hold the wake lock only while something is streaming; free idle chunk buffers.
    private void tick() {
        boolean busy = false;
        long now = System.currentTimeMillis();
        synchronized (relay.recent) {
            for (Relay.Stat s : relay.recent) if (s.active && now - s.last < 120_000) busy = true;
        }
        if (busy && !wake.isHeld()) wake.acquire(30 * 60_000L);
        if (!busy && wake.isHeld()) wake.release();
        relay.buffers().trim();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(); // TCL may have refused it on an earlier start (from a receiver); retry on every start
        return START_STICKY;
    }

    // Foreground keeps TCL's FreezeManager from freezing the process while another app plays.
    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("relay", "Relay", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "relay")
                .setContentTitle("Relay running on port " + PORT)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
    }

    @Override
    public void onDestroy() {
        if (server != null) server.stop();
        timer.shutdownNow();
        if (wake != null && wake.isHeld()) wake.release();
        status = "stopped";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
