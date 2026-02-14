package com.hfs.security.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages local persistent storage for HFS Security.
 * FIXED: 
 * 1. Updated isSetupComplete() logic to verify PIN existence.
 * 2. Provides thread-safe access to all security configurations.
 */
public class HFSDatabaseHelper {

    private static final String PREF_NAME = "hfs_security_prefs";
    
    // Database Keys
    private static final String KEY_PROTECTED_PACKAGES = "protected_packages";
    private static final String KEY_MASTER_PIN = "master_pin";
    private static final String KEY_TRUSTED_NUMBER = "trusted_number";
    private static final String KEY_SETUP_COMPLETE = "setup_complete";
    private static final String KEY_STEALTH_MODE = "stealth_mode_enabled";
    private static final String KEY_FAKE_GALLERY = "fake_gallery_enabled";
    private static final String KEY_OWNER_FACE_DATA = "owner_face_template";

    private static HFSDatabaseHelper instance;
    private final SharedPreferences prefs;
    private final Gson gson;

    private HFSDatabaseHelper(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public static synchronized HFSDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new HFSDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    // --- PROTECTED APPS STORAGE ---

    public void saveProtectedPackages(Set<String> packages) {
        String json = gson.toJson(packages);
        prefs.edit().putString(KEY_PROTECTED_PACKAGES, json).apply();
    }

    public Set<String> getProtectedPackages() {
        String json = prefs.getString(KEY_PROTECTED_PACKAGES, null);
        if (json == null) {
            return new HashSet<>();
        }
        Type type = new TypeToken<HashSet<String>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public int getProtectedAppsCount() {
        return getProtectedPackages().size();
    }

    // --- SECURITY CREDENTIALS ---

    public void saveMasterPin(String pin) {
        prefs.edit().putString(KEY_MASTER_PIN, pin).apply();
    }

    public String getMasterPin() {
        // Returns "0000" if no PIN has ever been set
        return prefs.getString(KEY_MASTER_PIN, "0000");
    }

    public void saveTrustedNumber(String number) {
        prefs.edit().putString(KEY_TRUSTED_NUMBER, number).apply();
    }

    public String getTrustedNumber() {
        return prefs.getString(KEY_TRUSTED_NUMBER, "");
    }

    // --- APP SETUP STATUS ---

    /**
     * FIXED: This method now verifies the physical existence of an MPIN
     * in addition to the setup flag. This solves the persistent 'Welcome' toast issue.
     */
    public boolean isSetupComplete() {
        boolean flag = prefs.getBoolean(KEY_SETUP_COMPLETE, false);
        String pin = getMasterPin();
        
        // Setup is only truly complete if flag is true AND pin is not the default
        return flag && !pin.equals("0000") && !pin.isEmpty();
    }

    public void setSetupComplete(boolean status) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETE, status).apply();
    }

    // --- FEATURE TOGGLES ---

    public void setStealthMode(boolean enabled) {
        prefs.edit().putBoolean(KEY_STEALTH_MODE, enabled).apply();
    }

    public boolean isStealthModeEnabled() {
        return prefs.getBoolean(KEY_STEALTH_MODE, false);
    }

    public void setFakeGalleryEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FAKE_GALLERY, enabled).apply();
    }

    public boolean isFakeGalleryEnabled() {
        return prefs.getBoolean(KEY_FAKE_GALLERY, false);
    }

    // --- LEGACY/UNUSED DATA ---

    public void saveOwnerFaceData(String faceData) {
        prefs.edit().putString(KEY_OWNER_FACE_DATA, faceData).apply();
    }

    public String getOwnerFaceData() {
        return prefs.getString(KEY_OWNER_FACE_DATA, "");
    }

    /**
     * Resets the app to factory settings.
     */
    public void clearDatabase() {
        prefs.edit().clear().apply();
    }
}