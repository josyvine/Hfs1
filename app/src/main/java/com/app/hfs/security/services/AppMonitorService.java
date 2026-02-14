package com.hfs.security.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.hfs.security.HFSApplication;
import com.hfs.security.R;
import com.hfs.security.ui.LockScreenActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.Set;

/**
 * The core Background Guard for HFS.
 * FIXED: 
 * 1. Integrated with System-Native Security logic (Removed ML Kit).
 * 2. Implements strict app monitoring with high-frequency polling.
 * 3. Manages owner sessions to prevent re-locking loops.
 */
public class AppMonitorService extends Service {

    private static final String TAG = "HFS_GuardService";
    private static final int NOTIFICATION_ID = 2002;
    private static final long MONITOR_TICK_MS = 500; 

    private Handler monitorHandler;
    private Runnable monitorRunnable;
    private HFSDatabaseHelper db;
    
    // LOOP CONTROL FLAGS
    public static boolean isLockActive = false;
    private static String unlockedPackage = "";
    private static long lastUnlockTimestamp = 0;
    private static final long SESSION_GRACE_MS = 10000; // 10 Seconds

    /**
     * Signals the service that the owner has successfully bypassed the lock.
     * This stops the service from re-locking the app for 10 seconds.
     */
    public static void unlockSession(String packageName) {
        unlockedPackage = packageName;
        lastUnlockTimestamp = System.currentTimeMillis();
        Log.d(TAG, "Owner Verified. Grace Period active for: " + packageName);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        db = HFSDatabaseHelper.getInstance(this);
        monitorHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Start as high-priority Foreground Service
        startForeground(NOTIFICATION_ID, createSecurityNotification());

        // Start the monitoring loop
        startMonitoringLoop();

        return START_STICKY; 
    }

    /**
     * Creates the persistent notification.
     * Redirects clicks to the Lock Screen to prevent security bypass.
     */
    private Notification createSecurityNotification() {
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.putExtra("TARGET_APP_NAME", "HFS Settings");
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, lockIntent, pendingFlags);

        return new NotificationCompat.Builder(this, HFSApplication.CHANNEL_ID)
                .setContentTitle("HFS Security Guard Active")
                .setContentText("Protecting your private applications...")
                .setSmallIcon(R.drawable.hfs)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /**
     * Main detection loop.
     */
    private void startMonitoringLoop() {
        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                // 1. Skip check if the Lock Screen is already visible
                if (isLockActive) {
                    monitorHandler.postDelayed(this, MONITOR_TICK_MS);
                    return;
                }

                String currentApp = getForegroundPackageName();
                Set<String> protectedApps = db.getProtectedPackages();

                // 2. RE-ARM LOGIC: If user leaves the app, reset the session instantly
                if (!currentApp.equals(unlockedPackage) && !currentApp.equals(getPackageName())) {
                    if (!unlockedPackage.isEmpty()) {
                        Log.d(TAG, "User left protected area. Security Re-armed.");
                        unlockedPackage = "";
                    }
                }

                // 3. TRIGGER LOGIC
                if (protectedApps.contains(currentApp)) {
                    // Check if current session is valid
                    boolean isSessionValid = currentApp.equals(unlockedPackage) && 
                            (System.currentTimeMillis() - lastUnlockTimestamp < SESSION_GRACE_MS);

                    if (!isSessionValid) {
                        Log.i(TAG, "Security Breach: Triggering System Lock for " + currentApp);
                        triggerLockOverlay(currentApp);
                    }
                }

                monitorHandler.postDelayed(this, MONITOR_TICK_MS);
            }
        };
        monitorHandler.post(monitorRunnable);
    }

    /**
     * Identifies the current app on screen.
     */
    private String getForegroundPackageName() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long time = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(time - 5000, time);
        UsageEvents.Event event = new UsageEvents.Event();
        String currentPkg = "";

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                currentPkg = event.getPackageName();
            }
        }
        return currentPkg;
    }

    /**
     * Launches the Lock Screen Overlay.
     */
    private void triggerLockOverlay(String packageName) {
        String appName = getAppNameFromPackage(packageName);
        
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.putExtra("TARGET_APP_PACKAGE", packageName);
        lockIntent.putExtra("TARGET_APP_NAME", appName);
        
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                          | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                          | Intent.FLAG_ACTIVITY_CLEAR_TOP
                          | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        
        try {
            startActivity(lockIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start lock overlay: " + e.getMessage());
        }
    }

    private String getAppNameFromPackage(String packageName) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return (String) pm.getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName; 
        }
    }

    @Override
    public void onDestroy() {
        if (monitorHandler != null && monitorRunnable != null) {
            monitorHandler.removeCallbacks(monitorRunnable);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}