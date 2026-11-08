package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Opens the launcher once per boot (Fire OS 5 can't make it the Home app). Fire OS delivers BOOT_COMPLETED
 * about a minute late, so the first connectivity broadcast after boot also counts. Only acts in the first
 * three minutes after boot, so a later Wi-Fi change never pulls the user out of an app.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final long WINDOW_MS = 3 * 60_000;
    private static boolean done;

    @Override
    public void onReceive(Context context, Intent intent) {
        long up = SystemClock.elapsedRealtime();
        if (up > WINDOW_MS) return;
        if (done) return;   // the launcher process lives on after it opens, so this resets only on a new boot
        done = true;
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
