package com.ahu.ahutong.bootstrap;

import android.app.Activity;
import android.content.Intent;
import android.os.*;
import android.os.Process;

/** A short-lived separate process relaunches deterministically; no inexact alarm or data reset. */
public final class RestartActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int previous = getIntent().getIntExtra("previous_pid", -1);
        if (previous <= 0 || previous == Process.myPid()) { finish(); return; }
        Process.killProcess(previous);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (intent != null) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            new Handler(Looper.getMainLooper()).postDelayed(() -> Process.killProcess(Process.myPid()), 200);
        }, 250);
    }
}
