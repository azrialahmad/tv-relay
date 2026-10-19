package com.gathin.relay;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

// Status screen: starts the service and shows where to set it up.
public class MainActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView text;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RelayService.start(this);
        text = new TextView(this);
        text.setTextSize(22);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.parseColor("#1d2b44"));
        text.setGravity(Gravity.CENTER);
        text.setPadding(80, 80, 80, 80);
        setContentView(text);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(new Runnable() {
            @Override public void run() {
                text.setText("Relay " + RelayService.status + "\n\nSet up from a phone or PC on the same Wi-Fi:\nhttp://" + lanIp() + ":"
                        + RelayService.PORT + "/setup\n\nTest add-on for Wuplay / Stremio:\nhttp://127.0.0.1:" + RelayService.PORT + "/a/test/manifest.json");
                h.postDelayed(this, 2000);
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacksAndMessages(null);
    }

    static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return "<tv-ip>";
    }
}
