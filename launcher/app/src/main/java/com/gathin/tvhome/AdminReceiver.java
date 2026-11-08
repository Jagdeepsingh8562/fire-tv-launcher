package com.gathin.tvhome;

import android.app.admin.DeviceAdminReceiver;

/**
 * Device admin with one policy (force-lock), so the power button can put the TV to sleep: Fire OS keeps its own
 * sleep call for Amazon-signed apps. Turned on over adb, since Fire OS 5 has no screen for it:
 *   adb shell dpm set-active-admin com.gathin.tvhome/.AdminReceiver
 */
public class AdminReceiver extends DeviceAdminReceiver {
}
