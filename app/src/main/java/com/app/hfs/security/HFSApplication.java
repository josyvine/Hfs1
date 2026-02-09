package com.hfs.security;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

/**
 * Global Application class for HFS - Hybrid File Security.
 * Initializes the security notification channels required for the 
 * background monitoring service to run persistently.
 */
public class HFSApplication extends Application {

    // Unique ID for the security monitoring notification channel
    public static final String CHANNEL_ID = "hfs_security_monitor_channel";

    @Override
    public void onCreate() {
        super.onCreate();

        // Initialize the notification channel required for Foreground Security Services
        createSecurityNotificationChannel();
    }

    /**
     * Creates a Notification Channel for the HFS Background Guard.
     * Required for Android 8.0 (API 26) and above.
     */
    private void createSecurityNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // User-visible name for the channel (shown in system settings)
            CharSequence name = "HFS Security Guard";
            
            // Description of what this channel does
            String description = "Ensures the HFS silent intruder detection is active.";
            
            /* 
             * IMPORTANCE_LOW: The notification is shown in the tray 
             * but does not make an intrusive sound or pop up, 
             * maintaining the "Silent Detection" core requirement.
             */
            int importance = NotificationManager.IMPORTANCE_LOW;
            
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);

            // Register the channel with the Android system
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }
}