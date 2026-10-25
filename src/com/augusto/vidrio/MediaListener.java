package com.augusto.vidrio;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing by itself. Android only lets an app see what other apps are playing if it holds
 * notification access, and that access is granted to a listener like this one. It is turned on
 * once with: adb shell cmd notification allow_listener com.augusto.vidrio/.MediaListener
 */
public class MediaListener extends NotificationListenerService {
}
