package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Sleep timer: an alarm that puts the TV to sleep (device admin lockNow, see AdminReceiver) after N minutes.
 * The end time is kept so the power menu can show what is left. A restart clears it, like a TV's own timer.
 */
public class SleepTimer extends BroadcastReceiver {
    private static final String PREFS = "tvhome", KEY_END = "sleep_end";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_END).apply();
        sleepNow(ctx);
    }

    /** False when device admin is not on (see AdminReceiver for the one-time adb command). */
    static boolean sleepNow(Context ctx) {
        DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isAdminActive(new ComponentName(ctx, AdminReceiver.class))) return false;
        dpm.lockNow();
        return true;
    }

    static void start(Context ctx, int minutes) {
        long end = SystemClock.elapsedRealtime() + minutes * 60_000L;
        alarms(ctx).set(AlarmManager.ELAPSED_REALTIME_WAKEUP, end, pending(ctx));
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_END, end).apply();
    }

    static void cancel(Context ctx) {
        alarms(ctx).cancel(pending(ctx));
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_END).apply();
    }

    /** Whole minutes left, rounded up, or 0 when no timer is set (or it was set before a restart). */
    static int minutesLeft(Context ctx) {
        long end = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_END, 0);
        long left = end - SystemClock.elapsedRealtime();
        return end == 0 || left <= 0 || left > 24 * 3_600_000L ? 0 : (int) ((left + 59_999) / 60_000);
    }

    private static AlarmManager alarms(Context ctx) {
        return (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent pending(Context ctx) {
        return PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, SleepTimer.class), PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
