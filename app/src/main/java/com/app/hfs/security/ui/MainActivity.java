package com.hfs.security.ui;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.hfs.security.R;
import com.hfs.security.databinding.ActivityMainBinding;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * The Primary Host Activity for HFS Security.
 * FIXED: 
 * 1. Resolved Dashboard crash by implementing ReselectedListener.
 * 2. Added Outgoing Call permissions required for Oppo Dialer fix.
 * 3. Stabilized Navigation Controller state.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private NavController navController;
    private HFSDatabaseHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize ViewBinding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);

        // Setup custom Toolbar
        setSupportActionBar(binding.toolbar);

        // Setup Navigation Component
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();

            // Define top-level tabs
            AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                    R.id.nav_home, 
                    R.id.nav_protected_apps, 
                    R.id.nav_history)
                    .build();

            // Connect NavController to UI components
            NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
            NavigationUI.setupWithNavController(binding.bottomNav, navController);
            
            // FIX FOR DASHBOARD CRASH:
            // This prevents the system from trying to reload the fragment 
            // if the user clicks the icon of the tab they are already viewing.
            binding.bottomNav.setOnItemReselectedListener(item -> {
                // Do nothing on reselect to maintain stability
            });
        }

        // Check for required security permissions
        checkAllSecurityPermissions();
    }

    /**
     * Creates the top-right options menu.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    /**
     * Handles selection of menu items (Settings and Help).
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            navController.navigate(R.id.nav_settings);
            return true;
        } else if (id == R.id.action_help) {
            showHelpDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Checks for the 6 critical permissions required for HFS.
     * UPDATED: Now includes Phone/Dialer permissions for Oppo stability.
     */
    private void checkAllSecurityPermissions() {
        // List of core runtime permissions
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.PROCESS_OUTGOING_CALLS
        };

        // Add Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, 
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
        }

        // Verify core permissions
        boolean permissionMissing = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                permissionMissing = true;
                break;
            }
        }

        if (permissionMissing) {
            ActivityCompat.requestPermissions(this, permissions, 101);
        }

        // Check for System Overlay (Crucial for Lock Screen Activity)
        if (!Settings.canDrawOverlays(this)) {
            showPermissionDialog("Overlay Permission Required", 
                    "HFS needs to draw over other apps to lock them. Please enable this in settings.",
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }

        // Check for Usage Stats (Crucial for App Launch Detection)
        if (!hasUsageStatsPermission()) {
            showPermissionDialog("Usage Access Required", 
                    "HFS needs Usage Access to detect when protected apps are opened.",
                    new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, 
                Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void showPermissionDialog(String title, String message, Intent intent) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Go to Settings", (dialog, which) -> startActivity(intent))
                .setNegativeButton("Exit App", (dialog, which) -> finish())
                .show();
    }

    private void showHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle("HFS Security Help")
                .setMessage("1. Register your face in Settings.\n\n" +
                           "2. Select apps to protect in 'Apps' tab.\n\n" +
                           "3. Setup your Trusted Number for alerts.\n\n" +
                           "4. If Stealth Mode is on, dial your PIN and press CALL to open the app.")
                .setPositiveButton("Got it", null)
                .show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        return navController.navigateUp() || super.onSupportNavigateUp();
    }
}