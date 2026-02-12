package com.hfs.security.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.hfs.security.databinding.ActivityStealthUnlockBinding;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.concurrent.Executor;

/**
 * Advanced Stealth Unlock/Hide Popup (Phase 8 Enhancement).
 * This activity acts as a Master Toggle for app visibility.
 * 
 * Functions:
 * 1. Checks current Stealth state (Hidden or Visible).
 * 2. Dynamically adjusts UI to offer "HIDE" or "UNHIDE" actions.
 * 3. Mandates a Fingerprint scan before altering the App Icon state.
 * 4. Switches launcher component state based on user confirmation.
 */
public class StealthUnlockActivity extends AppCompatActivity {

    private ActivityStealthUnlockBinding binding;
    private HFSDatabaseHelper db;
    private Executor executor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;
    private boolean isCurrentlyHidden;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Initialize ViewBinding
        binding = ActivityStealthUnlockBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        
        // 2. Determine current state from Database
        isCurrentlyHidden = db.isStealthModeEnabled();

        // 3. Update UI text dynamically based on current stealth status
        updatePopupUI();

        // 4. Setup the UI Listeners
        binding.btnCancel.setOnClickListener(v -> finish());
        
        binding.btnUnhide.setOnClickListener(v -> {
            // Trigger the Biometric Gate before performing the toggle
            biometricPrompt.authenticate(promptInfo);
        });

        // 5. Initialize Biometric components
        setupBiometricLogic();
    }

    /**
     * Dynamically sets the text based on whether the user is 
     * Hiding or Unhiding the app.
     */
    private void updatePopupUI() {
        if (isCurrentlyHidden) {
            binding.tvStealthDescription.setText("Identity Verified. Would you like to restore the app icon (UNHIDE) to the launcher?");
            binding.btnUnhide.setText("UNHIDE ICON");
        } else {
            binding.tvStealthDescription.setText("Identity Verified. Would you like to remove the app icon (HIDE) from the launcher?");
            binding.btnUnhide.setText("HIDE ICON");
        }
    }

    /**
     * Configures the fingerprint scanner logic.
     */
    private void setupBiometricLogic() {
        executor = ContextCompat.getMainExecutor(this);
        
        biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(StealthUnlockActivity.this, "Security Error: " + errString, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                
                // SUCCESS: Proceed with the requested Toggle action
                handleStealthToggle();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(StealthUnlockActivity.this, "Fingerprint not recognized. Access Denied.", Toast.LENGTH_SHORT).show();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFS Security Gate")
                .setSubtitle("Authenticate to change app visibility")
                .setNegativeButtonText("Cancel")
                .build();
    }

    /**
     * Executes the actual Hide or Unhide logic after fingerprint success.
     */
    private void handleStealthToggle() {
        PackageManager pm = getPackageManager();
        ComponentName componentName = new ComponentName(this, SplashActivity.class);
        
        if (isCurrentlyHidden) {
            // ACTION: UNHIDE
            pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
            );
            db.setStealthMode(false);
            Toast.makeText(this, "HFS: App Icon Restored.", Toast.LENGTH_LONG).show();
            
            // Launch the main app entry point
            Intent intent = new Intent(this, SplashActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        } else {
            // ACTION: HIDE
            pm.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
            db.setStealthMode(true);
            Toast.makeText(this, "HFS: App Icon Hidden.", Toast.LENGTH_LONG).show();
        }

        // Close the popup regardless of hide/unhide choice
        finish();
    }

    @Override
    public void onBackPressed() {
        // Prevent bypassing the security prompt via back button
        super.onBackPressed();
    }
}