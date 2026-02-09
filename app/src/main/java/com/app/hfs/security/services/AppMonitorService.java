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
 * The core Background Service for HFS.
 * FIXED: Increased detection frequency and improved overlay trigger 
 * reliability for Oppo/Realme devices to ensure the lock screen 
 * appears the millisecond a protected app is opened.
 */
public class AppMonitorService extends Service {

    private static final String TAG = "HFS_MonitorService";
    private static final int NOTIFICATION_ID = 2002;
    
    // SPEED INCREASE: Reduced from 1000ms to 500ms for aggressive detection
    private static final long MONITOR_TICK_MS = 500; 

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
        // 1. Start as a high-priority Foreground Service
        startForeground(NOTIFICATION_ID, createSecurityNotification());

        // 2. Start the aggressive monitoring loop
        startMonitoringLoop();

        // START_STICKY: Tells Android to restart this service if it gets killed
        return START_STICKY; 
    }

    /**
     * Creates the persistent notification that keeps the service alive.
     */
    private Notification createSecurityNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, 
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, HFSApplication.CHANNEL_ID)
                .setContentTitle("HFS Silent Guard Active")
                .setContentText("Protecting your private applications...")
                .setSmallIcon(R.drawable.hfs)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /**
     * The aggressive loop that checks for foreground app changes.
     */
    private void startMonitoringLoop() {
        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                String currentApp = getForegroundPackageName();

                // 1. Only act if the foreground app is different from the last check
                if (!currentApp.equals(lastPackageInForeground)) {
                    
                    // 2. SELF-PROTECTION: Don't lock if the user is inside HFS itself
                    if (!currentApp.equals(getPackageName())) {
                        
                        lastPackageInForeground = currentApp;
                        
                        // 3. Check if the app is in the user's protected list
                        Set<String> protectedApps = db.getProtectedPackages();
                        
                        if (protectedApps.contains(currentApp)) {
                            Log.i(TAG, "PROTECTED APP DETECTED: " + currentApp);
                            triggerLockOverlay(currentApp);
                        }
                    }
                }

                // Repeat every 500ms
                monitorHandler.postDelayed(this, MONITOR_TICK_MS);
            }
        };
        monitorHandler.post(monitorRunnable);
    }

    /**
     * Uses UsageStatsManager to identify the app currently visible on screen.
     */
    private String getForegroundPackageName() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long endTime = System.currentTimeMillis();
        long startTime = endTime - 1000 * 60; // Look at the last 1 minute of events

        UsageEvents events = usm.queryEvents(startTime, endTime);
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
     * Launches the LockScreenActivity overlay.
     */
    private void triggerLockOverlay(String packageName) {
        String appName = getAppNameFromPackage(packageName);
        
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.putExtra("TARGET_APP_PACKAGE", packageName);
        lockIntent.putExtra("TARGET_APP_NAME", appName);
        
        // CRITICAL FLAGS FOR OPPO/REALME:
        // NEW_TASK: Required for service launch
        // SINGLE_TOP/CLEAR_TOP: Prevents multiple lock screens from opening
        // NO_USER_ACTION: Helps bypass some background restrictions
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                          | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                          | Intent.FLAG_ACTIVITY_CLEAR_TOP
                          | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        
        try {
            startActivity(lockIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch lock screen: " + e.getMessage());
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
        Log.d(TAG, "Security Monitor Service Destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}