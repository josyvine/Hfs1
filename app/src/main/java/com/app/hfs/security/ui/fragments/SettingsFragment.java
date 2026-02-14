package com.hfs.security.ui.fragments;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.hfs.security.R;
import com.hfs.security.databinding.FragmentSettingsBinding;
import com.hfs.security.receivers.AdminReceiver;
import com.hfs.security.ui.SplashActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Advanced Settings Screen for HFS Security.
 * UPDATED PLAN:
 * 1. Removed ML Kit Rescan logic - Face identity is now handled by the System.
 * 2. Manages Trusted Number for alerts and the Master PIN (MPIN).
 * 3. Handles Anti-Uninstall (Device Admin) and Stealth Mode (Dialer Trigger).
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private HFSDatabaseHelper db;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the settings layout
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = HFSDatabaseHelper.getInstance(requireContext());
        
        // Initialize Device Admin components to prevent uninstallation
        devicePolicyManager = (DevicePolicyManager) requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(requireContext(), AdminReceiver.class);

        // UI Cleanup: Hide the Rescan section as it is no longer used in the new plan
        if (binding.btnRescanFace != null) {
            binding.btnRescanFace.setVisibility(View.GONE);
        }

        loadSettings();
        setupListeners();
    }

    /**
     * Populates the input fields and switches with currently saved data.
     */
    private void loadSettings() {
        // Load the secondary phone number that receives alert SMS
        binding.etTrustedNumber.setText(db.getTrustedNumber());
        
        // Load the 4-digit Master PIN (MPIN) used for manual override and dialer
        binding.etSecretPin.setText(db.getMasterPin());
        
        // Check if the phone's Device Admin is already granted to HFS
        boolean isAdminActive = devicePolicyManager.isAdminActive(adminComponent);
        binding.switchAntiUninstall.setChecked(isAdminActive);

        // Load feature toggles for Stealth and Decoy modes
        binding.switchStealthMode.setChecked(db.isStealthModeEnabled());
        binding.switchFakeGallery.setChecked(db.isFakeGalleryEnabled());
    }

    /**
     * Configures the interaction logic for the settings components.
     */
    private void setupListeners() {
        // 1. SAVE SECURITY DATA
        binding.btnSaveSettings.setOnClickListener(v -> {
            String number = binding.etTrustedNumber.getText().toString().trim();
            String pin = binding.etSecretPin.getText().toString().trim();

            if (TextUtils.isEmpty(number) || pin.length() < 4) {
                Toast.makeText(getContext(), "Please provide a valid Trusted Number and 4-digit PIN", Toast.LENGTH_SHORT).show();
                return;
            }

            // Update persistent storage
            db.saveTrustedNumber(number);
            db.saveMasterPin(pin);
            Toast.makeText(getContext(), "HFS Credentials Updated", Toast.LENGTH_SHORT).show();
        });

        // 2. STEALTH MODE TOGGLE (Dialer Trigger)
        binding.switchStealthMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setStealthMode(isChecked);
            if (isChecked) {
                showStealthWarning();
            } else {
                setAppIconVisible(true);
                Toast.makeText(getContext(), "App Icon Restored to Home Screen", Toast.LENGTH_SHORT).show();
            }
        });

        // 3. ANTI-UNINSTALL TOGGLE (Device Admin)
        binding.switchAntiUninstall.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                activateDeviceAdmin();
            } else {
                deactivateDeviceAdmin();
            }
        });

        // 4. DECOY SYSTEM TOGGLE
        binding.switchFakeGallery.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setFakeGalleryEnabled(isChecked);
        });
    }

    /**
     * Warns the user about the secret dial code before hiding the icon.
     */
    private void showStealthWarning() {
        String currentPin = db.getMasterPin();
        new AlertDialog.Builder(requireContext(), R.style.Theme_HFS_Dialog)
                .setTitle("Stealth Mode Enabled")
                .setMessage("The icon will be hidden. Dial your PIN (" + currentPin + ") and press CALL to open the HFS portal.")
                .setPositiveButton("I UNDERSTAND", (dialog, which) -> setAppIconVisible(false))
                .setNegativeButton("CANCEL", (dialog, which) -> binding.switchStealthMode.setChecked(false))
                .setCancelable(false)
                .show();
    }

    /**
     * Directly interacts with the PackageManager to Hide/Unhide the launcher icon.
     */
    private void setAppIconVisible(boolean visible) {
        PackageManager pm = requireContext().getPackageManager();
        ComponentName componentName = new ComponentName(requireContext(), SplashActivity.class);
        
        int state = visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED 
                           : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        
        pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP);
    }

    private void activateDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protects HFS from being uninstalled by intruders.");
        startActivity(intent);
    }

    private void deactivateDeviceAdmin() {
        devicePolicyManager.removeActiveAdmin(adminComponent);
        Toast.makeText(getContext(), "Anti-Uninstall Protection Disabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}