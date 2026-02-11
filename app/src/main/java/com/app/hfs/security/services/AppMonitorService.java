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
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.Set;

/**
 * The core Background Service for HFS.
 * FIXED: 
 * 1. Removed 'Last Package' trap to ensure lock triggers every time.
 * 2. Implemented 'Instant Re-Arm': Security resets the moment you leave a protected app.
 */
public class AppMonitorService extends Service {

    private static final String TAG = "HFS_MonitorService";
    private static final int NOTIFICATION_ID = 2002;
    private static final long MONITOR_TICK_MS = 500; 

    private Handler monitorHandler;
    private Runnable monitorRunnable;
    private HFSDatabaseHelper db;

    // SESSION MANAGEMENT
    private static String unlockedPackage = "";
    private static long lastUnlockTimestamp = 0;
    private static final long SESSION_TIMEOUT_MS = 10000; // 10 Seconds max

    /**
     * Called by LockScreenActivity upon successful verify.
     */
    public static void unlockSession(String packageName) {
        unlockedPackage = packageName;
        lastUnlockTimestamp = System.currentTimeMillis();
        Log.d(TAG, "Session Unlocked for: " + packageName);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        db = HFSDatabaseHelper.getInstance(this);
        monitorHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createSecurityNotification());
        startMonitoringLoop();
        return START_STICKY; 
    }

    private Notification createSecurityNotification() {
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.putExtra("TARGET_APP_NAME", "HFS Settings");
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, lockIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, HFSApplication.CHANNEL_ID)
                .setContentTitle("HFS Silent Guard Active")
                .setContentText("Privacy Protection is running")
                .setSmallIcon(R.drawable.hfs)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void startMonitoringLoop() {
        monitorRunnable = new Runnable() {
            @Override
            public void run() {
                String currentApp = getForegroundPackageName();
                Set<String> protectedApps = db.getProtectedPackages();

                // 1. RE-ARM LOGIC: 
                // If the current app is NOT the one we unlocked, and it's not HFS itself,
                // we reset the session immediately.
                if (!currentApp.equals(unlockedPackage) && !currentApp.equals(getPackageName())) {
                    if (!unlockedPackage.isEmpty()) {
                        Log.d(TAG, "User left app. Re-arming security for: " + unlockedPackage);
                        unlockedPackage = "";
                    }
                }

                // 2. TRIGGER LOGIC:
                if (protectedApps.contains(currentApp)) {
                    
                    // Only skip if the session is still valid for THIS specific app
                    boolean isSessionValid = currentApp.equals(unlockedPackage) && 
                            (System.currentTimeMillis() - lastUnlockTimestamp < SESSION_TIMEOUT_MS);

                    if (!isSessionValid) {
                        Log.i(TAG, "STRICT LOCK TRIGGER: " + currentApp);
                        triggerLockOverlay(currentApp);
                    }
                }

                monitorHandler.postDelayed(this, MONITOR_TICK_MS);
            }
        };
        monitorHandler.post(monitorRunnable);
    }

    private String getForegroundPackageName() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        long time = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(time - 1000 * 10, time);
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
            Log.e(TAG, "Trigger Failed: " + e.getMessage());
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