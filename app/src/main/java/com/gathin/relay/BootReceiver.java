package com.gathin.relay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // TCL's AppBoot policy blocks startForeground from a receiver, so go through an (invisible) activity.
        // Background activity starts need SYSTEM_ALERT_WINDOW, granted over ADB by the install script.
        try {
            context.startActivity(new Intent(context, BootActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (Exception e) {
            RelayService.start(context);
        }
    }
}
