package com.hfs.security.utils;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build; 
import android.os.Process;
import android.provider.Settings;

import androidx.biometric.BiometricManager;
import androidx.core.content.ContextCompat;

/**
 * Advanced Permission & Hardware Manager for HFS Security.
 * UPDATED: 
 * 1. Step 1 Implementation: Added Class 3 (Strong) Biometric hardware detection.
 * 2. Maintained GPS, Phone, and Overlay verification methods.
 */
public class PermissionHelper {

    /**
     * Step 1: Hardware Capability Check.
     * Detects if the device has official secure hardware (Class 3) for Face/Fingerprint.
     * Logic: Asks Android if 'BIOMETRIC_STRONG' is supported and enrolled.
     */
    public static boolean hasClass3Biometrics(Context context) {
        BiometricManager biometricManager = BiometricManager.from(context);
        
        // BIOMETRIC_STRONG (Class 3) indicates hardware that meets high security 
        // standards (3D sensors / secure TEE processing).
        int canAuthenticate = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        
        return canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * Checks if the app can access GPS coordinates for the Map link enhancement.
     */
    public static boolean hasLocationPermissions(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Checks if the app can intercept dialed numbers (Oppo Dialer Fix).
     */
    public static boolean hasPhonePermissions(Context context) {
        boolean statePerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) 
                == PackageManager.PERMISSION_GRANTED;
        
        boolean callPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.PROCESS_OUTGOING_CALLS) 
                == PackageManager.PERMISSION_GRANTED;

        return statePerm && callPerm;
    }

    /**
     * Checks if the app has permission to show the Lock Screen overlay.
     */
    public static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true; 
    }

    /**
     * Checks if the app can detect foreground app launches.
     */
    public static boolean hasUsageStatsPermission(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, 
                Process.myUid(), context.getPackageName());
        
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) 
                    == PackageManager.PERMISSION_GRANTED;
        }
        
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Checks for standard Runtime Permissions (Camera and SMS).
     */
    public static boolean hasBasePermissions(Context context) {
        boolean camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED;
        
        boolean sendSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) 
                == PackageManager.PERMISSION_GRANTED;

        return camera && sendSms;
    }

    /**
     * Master check to see if HFS is fully authorized to protect the phone.
     */
    public static boolean isAllSecurityGranted(Context context) {
        return hasBasePermissions(context) && 
               hasPhonePermissions(context) && 
               hasLocationPermissions(context) && 
               hasUsageStatsPermission(context) && 
               canDrawOverlays(context);
    }

    /**
     * Helper to open app settings for manual Oppo Auto-startup enabling.
     */
    public static void openAppSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", context.getPackageName(), null);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}