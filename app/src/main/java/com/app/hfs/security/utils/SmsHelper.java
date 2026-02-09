package com.hfs.security.utils;

import android.content.Context;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Automatic Alert Utility (Phase 4).
 * Handles the background transmission of alert messages to the 
 * owner's secondary trusted phone number.
 */
public class SmsHelper {

    private static final String TAG = "HFS_SmsHelper";

    /**
     * Constructs and sends a security alert SMS to the trusted number.
     * 
     * @param context App context.
     * @param targetAppName The name of the protected app that was accessed (e.g., "WhatsApp").
     */
    public static void sendAlertSms(Context context, String targetAppName) {
        HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
        
        // Retrieve the trusted secondary phone number from the database
        String trustedNumber = db.getTrustedNumber();
        
        // Safety check: Don't attempt to send if no number is configured
        if (trustedNumber == null || trustedNumber.isEmpty()) {
            Log.e(TAG, "SMS Alert failed: No trusted number configured in settings.");
            return;
        }

        // Generate current timestamp for the alert message
        String currentTime = new SimpleDateFormat("dd-MMM-yyyy hh:mm a", Locale.getDefault()).format(new Date());

        // Construct the message body as per the requirements document
        // ⚠ ALERT: Someone accessed your [App]
        // Time: [Timestamp]
        // Action: App locked + Intruder photo saved
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("⚠ ALERT: Someone accessed your ").append(targetAppName).append("\n");
        messageBuilder.append("Time: ").append(currentTime).append("\n");
        messageBuilder.append("Action: App locked + Intruder photo saved");

        String finalMessage = messageBuilder.toString();

        try {
            // Initialize the Android SMS Manager
            SmsManager smsManager;
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                // For Android 12 and above
                smsManager = context.getSystemService(SmsManager.class);
            } else {
                // For older versions
                smsManager = SmsManager.getDefault();
            }

            if (smsManager != null) {
                // Send the message silently in the background
                // We use divideMessage to handle cases where the text exceeds 160 characters
                java.util.ArrayList<String> parts = smsManager.divideMessage(finalMessage);
                smsManager.sendMultipartTextMessage(trustedNumber, null, parts, null, null);
                
                Log.i(TAG, "Alert SMS successfully sent to: " + trustedNumber);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS alert: " + e.getMessage());
            // We do not show a Toast here to remain "Silent" as per requirements
        }
    }

    /**
     * Optional: Method to include GPS location in the SMS if enabled in Phase 6.
     * 
     * @param context App context.
     * @param latitude Device latitude.
     * @param longitude Device longitude.
     */
    public static void sendLocationAlertSms(Context context, String targetAppName, double latitude, double longitude) {
        HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
        String trustedNumber = db.getTrustedNumber();
        
        if (trustedNumber == null || trustedNumber.isEmpty()) return;

        String locationUrl = "https://maps.google.com/maps?q=" + latitude + "," + longitude;
        String message = "⚠ ALERT: HFS detected intrusion in " + targetAppName + 
                         ". Location: " + locationUrl;

        try {
            SmsManager smsManager = SmsManager.getDefault();
            smsManager.sendTextMessage(trustedNumber, null, message, null, null);
        } catch (Exception ignored) {}
    }
}