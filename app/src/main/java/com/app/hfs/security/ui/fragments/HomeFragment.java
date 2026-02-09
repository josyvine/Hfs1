package com.hfs.security.ui.fragments;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.hfs.security.R;
import com.hfs.security.databinding.FragmentHomeBinding;
import com.hfs.security.services.AppMonitorService;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * The Main Dashboard of the HFS App.
 * Provides the user with a master toggle to activate/deactivate 
 * the Silent Intruder Detection Service.
 */
public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;
    private HFSDatabaseHelper db;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the Home layout
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = HFSDatabaseHelper.getInstance(requireContext());

        setupClickListeners();
        refreshUI();
    }

    /**
     * Connects the UI buttons to their respective security functions.
     */
    private void setupClickListeners() {
        // Master Toggle: Starts or Stops the background monitoring
        binding.btnToggleSecurity.setOnClickListener(v -> {
            if (isServiceRunning(AppMonitorService.class)) {
                stopSecurityService();
            } else {
                startSecurityService();
            }
        });

        // Shortcut to view the Intruder Evidence Logs
        binding.btnViewLogs.setOnClickListener(v -> 
            Navigation.findNavController(v).navigate(R.id.nav_history)
        );

        // Shortcut to manage which apps are protected
        binding.cardProtectedApps.setOnClickListener(v -> 
            Navigation.findNavController(v).navigate(R.id.nav_protected_apps)
        );
    }

    /**
     * Updates the text and colors on the dashboard based on 
     * whether the security service is currently active.
     */
    private void refreshUI() {
        boolean active = isServiceRunning(AppMonitorService.class);

        if (active) {
            binding.tvSecurityStatus.setText("PROTECTION: ACTIVE");
            binding.tvSecurityStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.hfs_active_green));
            binding.btnToggleSecurity.setText("DEACTIVATE SYSTEM");
            binding.ivStatusShield.setImageResource(R.drawable.ic_shield_active);
        } else {
            binding.tvSecurityStatus.setText("PROTECTION: INACTIVE");
            binding.tvSecurityStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.hfs_inactive_red));
            binding.btnToggleSecurity.setText("ACTIVATE SILENT GUARD");
            binding.ivStatusShield.setImageResource(R.drawable.ic_shield_inactive);
        }

        // Display summary counts from the database
        int protectedCount = db.getProtectedAppsCount();
        binding.tvProtectedAppsSummary.setText(protectedCount + " Apps currently protected");
    }

    /**
     * Launches the Foreground Service for Phase 2/3 operations.
     */
    private void startSecurityService() {
        Intent serviceIntent = new Intent(requireContext(), AppMonitorService.class);
        ContextCompat.startForegroundService(requireContext(), serviceIntent);
        
        // Refresh UI after a short delay to allow service state to update
        binding.getRoot().postDelayed(this::refreshUI, 500);
    }

    /**
     * Stops the background monitor.
     */
    private void stopSecurityService() {
        Intent serviceIntent = new Intent(requireContext(), AppMonitorService.class);
        requireContext().stopService(serviceIntent);
        
        binding.getRoot().postDelayed(this::refreshUI, 500);
    }

    /**
     * Utility method to check if the AppMonitorService is active.
     */
    private boolean isServiceRunning(Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) requireContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceClass.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshUI(); // Ensure status is current when user returns to app
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}