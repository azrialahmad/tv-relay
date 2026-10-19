package com.gathin.relay;

import android.app.Activity;
import android.os.Bundle;

// Invisible: TCL's AppBoot policy blocks a boot receiver from starting a foreground service, but allows it
// from an activity. The receiver opens this, it starts the service and closes at once.
public class BootActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RelayService.start(this);
        finish();
    }
}
