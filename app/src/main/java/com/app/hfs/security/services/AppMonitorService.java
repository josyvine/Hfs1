package com.hfs.security.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hfs.security.HFSApplication;
import com.hfs.security.R;
import com.hfs.security.ui.LockScreenActivity;
import com.hfs.security.ui.MainActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.Set;

/**
 * The core Background Service for HFS (Phase 2).
 * This service runs persistently to monitor foreground app changes.
 * When a user opens a 'Protected App', this service launches the 
 * LockScreenActivity overlay immediately.
 */
public class AppMonitorService extends Service {

    private static final String TAG = "AppMonitorService";
    private static final int NOTIFICATION_ID = 2002;
    private static final long MONITOR_TICK_MS = 1000; // Check every 1 second

    private Handler monitorHandler;
    private Runnable monitorRunnable;
    private HFSDatabaseHelper db;
    private String lastPackageInForeground = "";

    @Override
    public void onCreate() {
        super.onCreate();
        db = HFSDatabaseHelper.getInstance(this);
        monitorHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Security Monitor Service Created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1. Start as a Foreground Service to prevent system from killing it
        startForeground(NOTIFICATION_ID, createSecurityNotification());

        // 2. Start the recursive monitoring loop
        startMonitoringLoop();

        return START_STICKY; // Ensure service restarts if ever killed by OS
    }

    /**
     * Creates the mandatory persistent notification for the Foreground Service.
     */
    private Notification createSecurityNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, 
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, HFSApplication.CHANNEL_ID)
                .setContentTitle("HFS Silent Guard Active")
                .setContentText("Monitoring protected applications...")
                .setSmallIcon(R.drawable.hfs) // Using your hfs.png icon
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Cannot be swiped away
                .setPriority(NotificationCompat.PRIORITY_LOW) // Silent in status bar
                .build();
    }

    /**
     * The main loop that checks for foreground app changes.
     */
    private void startMonitoringLoop() {
        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                String currentApp = getForegroundPackageName();

                // Logic: Only trigger if the foreground app has changed
                if (!currentApp.equals(lastPackageInForeground)) {
                    lastPackageInForeground = currentApp;
                    
                    // Check if the newly opened app is in the protected list
                    Set<String> protectedApps = db.getProtectedPackages();
                    
                    if (protectedApps.contains(currentApp)) {
                        Log.i(TAG, "Protected App Detected: " + currentApp);
                        triggerLockOverlay(currentApp);
                    }
                }

                // Repeat the check after the specified interval
                monitorHandler.postDelayed(this, MONITOR_TICK_MS);
            }
        };
        monitorHandler.post(monitorRunnable);
    }

    /**
     * Uses UsageStatsManager to identify the app currently on top.
     * Requires 'Usage Access' permission from the user.
     */
    private String getForegroundPackageName() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long endTime = System.currentTimeMillis();
        long startTime = endTime - 5000; // Check last 5 seconds of events

        UsageEvents events = usm.queryEvents(startTime, endTime);
        UsageEvents.Event event = new UsageEvents.Event();
        String currentPkg = "";

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            // We specifically look for the MOVE_TO_FOREGROUND event type
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                currentPkg = event.getPackageName();
            }
        }
        return currentPkg;
    }

    /**
     * Launches the LockScreenActivity as a high-priority overlay.
     */
    private void triggerLockOverlay(String packageName) {
        String appName = getAppNameFromPackage(packageName);
        
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.putExtra("TARGET_APP_PACKAGE", packageName);
        lockIntent.putExtra("TARGET_APP_NAME", appName);
        
        // Critical flags for launching an activity from a background service
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        
        startActivity(lockIntent);
    }

    /**
     * Converts a package name (e.g., com.whatsapp) to a readable name (e.g., WhatsApp).
     */
    private String getAppNameFromPackage(String packageName) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return (String) pm.getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName; // Fallback to package name if display name fails
        }
    }

    @Override
    public void onDestroy() {
        // Cleanup loop to prevent memory leaks
        if (monitorHandler != null && monitorRunnable != null) {
            monitorHandler.removeCallbacks(monitorRunnable);
        }
        super.onDestroy();
        Log.d(TAG, "Security Monitor Service Destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Not using bound service pattern
        return null;
    }
}