package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Empty on purpose: Android only lets an app read other apps' media sessions (MediaSessionManager) if it has a
 * notification listener turned on. Turned on over adb (see build-and-install.sh); this reads no notifications.
 */
public class MediaWatcherService extends NotificationListenerService {
}
