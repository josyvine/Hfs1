package com.hfs.security.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SmsManager;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Advanced Alert & SMS Transmission Utility.
 * UPDATED for "Zero-Fail" Plan:
 * 1. Breach Specificity: Differentiates between Face and Fingerprint failures.
 * 2. Routing Fix: Ensures international formatting (+91) for external phones.
 * 3. Strict Cooldown: Limits alerts to 3 messages per 5-minute window.
 */
public class SmsHelper {

    private static final String TAG = "HFS_SmsHelper";
    private static final String PREF_SMS_LIMITER = "hfs_sms_limiter_prefs";
    private static final long WINDOW_MS = 5 * 60 * 1000; // 5 Minutes
    private static final int MAX_MSGS = 3; // Exactly 3 messages limit

    /**
     * Sends a detailed security alert SMS.
     * 
     * @param context App context.
     * @param targetApp Name of the app triggered.
     * @param mapLink Google Maps URL.
     * @param alertType "Face Mismatch" or "Fingerprint Failure".
     */
    public static void sendAlertSms(Context context, String targetApp, String mapLink, String alertType) {
        
        // 1. VERIFY COOLDOWN STATUS (3 msgs / 5 mins)
        if (!isSmsAllowed(context)) {
            Log.w(TAG, "SMS Limit Reached: Blocking transmission for 5-minute cooldown.");
            return;
        }

        HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
        String savedNumber = db.getTrustedNumber();

        if (savedNumber == null || savedNumber.isEmpty()) {
            Log.e(TAG, "SMS Failure: No trusted number set in settings.");
            return;
        }

        // 2. INTERNATIONAL FORMATTING (+91 Fix)
        String finalRecipient = formatInternationalNumber(savedNumber);

        // 3. CONSTRUCT ENHANCED ALERT TEXT
        String time = new SimpleDateFormat("dd-MMM HH:mm", Locale.getDefault()).format(new Date());
        
        StringBuilder smsBody = new StringBuilder();
        smsBody.append("⚠ HFS SECURITY ALERT\n");
        smsBody.append("Breach: ").append(alertType).append("\n");
        smsBody.append("App: ").append(targetApp).append("\n");
        smsBody.append("Time: ").append(time).append("\n");

        if (mapLink != null && !mapLink.isEmpty()) {
            smsBody.append("Map: ").append(mapLink);
        } else {
            smsBody.append("Location: GPS signal pending");
        }

        // 4. EXECUTE SEND
        try {
            SmsManager smsManager;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                smsManager = context.getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }

            if (smsManager != null) {
                java.util.ArrayList<String> parts = smsManager.divideMessage(smsBody.toString());
                smsManager.sendMultipartTextMessage(finalRecipient, null, parts, null, null);
                
                Log.i(TAG, "Detailed alert sent to: " + finalRecipient);
                
                // 5. UPDATE COOLDOWN COUNTER
                trackSmsSent(context);
            }
        } catch (Exception e) {
            Log.e(TAG, "Carrier Block: Failed to deliver external SMS: " + e.getMessage());
        }
    }

    /**
     * Normalizes the phone number to bypass carrier routing blocks.
     */
    private static String formatInternationalNumber(String number) {
        String clean = number.replaceAll("[^\\d]", "");
        
        // If the number doesn't start with '+', we prepend the standard code
        if (!number.startsWith("+")) {
            if (clean.length() == 10) {
                return "+91" + clean; // Defaulting to India for your specific testing
            }
        }
        return number.startsWith("+") ? number : "+" + number;
    }

    /**
     * Logic: Implements the 3-msg/5-min safety window.
     */
    private static boolean isSmsAllowed(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_SMS_LIMITER, Context.MODE_PRIVATE);
        long windowStart = prefs.getLong("start_time", 0);
        int currentCount = prefs.getInt("msg_count", 0);
        long now = System.currentTimeMillis();

        // Check if 5 minutes have passed since the first message of the current window
        if (now - windowStart > WINDOW_MS) {
            // Window expired: Reset counter and timestamp
            prefs.edit().putLong("start_time", now).putInt("msg_count", 0).apply();
            return true;
        }

        // Return true only if we haven't hit the 3-message ceiling
        return currentCount < MAX_MSGS;
    }

    private static void trackSmsSent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_SMS_LIMITER, Context.MODE_PRIVATE);
        int count = prefs.getInt("msg_count", 0);
        prefs.edit().putInt("msg_count", count + 1).apply();
    }

    /**
     * Internal Placeholder for future MMS Photo Packaging.
     */
    public static void sendMmsPhoto(Context context, File image) {
        if (image == null || !image.exists()) return;
        Log.d(TAG, "MMS Queue: Intruder photo detected, ready for packaging.");
    }
}