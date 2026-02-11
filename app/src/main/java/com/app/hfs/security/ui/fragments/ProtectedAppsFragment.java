package com.hfs.security.ui.fragments;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.hfs.security.adapters.AppSelectionAdapter;
import com.hfs.security.databinding.FragmentProtectedAppsBinding;
import com.hfs.security.models.AppInfo;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screen for Protected App Selection.
 * UPDATED & FIXED: 
 * 1. Enabled HFS Self-Protection: HFS now appears in its own list.
 * 2. Enabled System Apps: Gallery, Photos, and Files are now visible.
 * 3. Thread Safety: Includes isAdded() checks to prevent tab-switching crashes.
 */
public class ProtectedAppsFragment extends Fragment implements AppSelectionAdapter.OnAppSelectionListener {

    private FragmentProtectedAppsBinding binding;
    private AppSelectionAdapter adapter;
    private List<AppInfo> fullAppList;
    private HFSDatabaseHelper db;
    
    // Executor for background processing to keep the UI responsive
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the layout
        binding = FragmentProtectedAppsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        db = HFSDatabaseHelper.getInstance(requireContext());
        fullAppList = new ArrayList<>();
        
        setupRecyclerView();
        setupSearch();
        
        // Load all apps including system apps
        loadInstalledApps();
    }

    private void setupRecyclerView() {
        binding.rvApps.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new AppSelectionAdapter(new ArrayList<>(), this);
        binding.rvApps.setAdapter(adapter);
    }

    private void setupSearch() {
        binding.etSearchApps.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    /**
     * Logic: Scans the device for apps.
     * UPDATED: Now includes System Apps and the HFS app itself.
     */
    private void loadInstalledApps() {
        if (binding != null) {
            binding.progressBar.setVisibility(View.VISIBLE);
        }
        
        executor.execute(() -> {
            // Safety check: Ensure the fragment is still attached to the Activity
            if (!isAdded() || getContext() == null) return;

            PackageManager pm = getContext().getPackageManager();
            
            // Fetch all applications regardless of system or user status
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppInfo> tempInfoList = new ArrayList<>();
            
            // Get currently protected packages from local database
            Set<String> savedProtectedPackages = db.getProtectedPackages();

            for (ApplicationInfo app : packages) {
                /* 
                 * ENHANCEMENT: 
                 * We use getLaunchIntentForPackage. This is the professional way to 
                 * find every app that the user can actually "see" and "open", 
                 * including System Gallery, Files, and built-in Photos.
                 */
                if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                    
                    // Logic updated: We no longer 'continue' (skip) the HFS package.
                    // This allows you to lock the HFS app itself as requested.

                    String name = app.loadLabel(pm).toString();
                    Drawable icon = app.loadIcon(pm);
                    boolean isAlreadyProtected = savedProtectedPackages.contains(app.packageName);
                    
                    tempInfoList.add(new AppInfo(name, app.packageName, icon, isAlreadyProtected));
                }
            }

            // Sort the final list alphabetically for easy navigation
            Collections.sort(tempInfoList, (a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));

            // Return the result to the UI Thread safely
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    if (binding != null) {
                        fullAppList = tempInfoList;
                        adapter.updateList(fullAppList);
                        binding.progressBar.setVisibility(View.GONE);
                        
                        if (fullAppList.isEmpty()) {
                            binding.tvNoAppsFound.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    /**
     * Filters the list as the user types.
     */
    private void filterApps(String query) {
        if (fullAppList == null) return;
        
        List<AppInfo> filtered = new ArrayList<>();
        for (AppInfo info : fullAppList) {
            if (info.getAppName().toLowerCase().contains(query.toLowerCase()) ||
                info.getPackageName().toLowerCase().contains(query.toLowerCase())) {
                filtered.add(info);
            }
        }
        adapter.updateList(filtered);
    }

    /**
     * Interface callback: Triggered when a checkbox is toggled.
     */
    @Override
    public void onAppToggle(String packageName, boolean isSelected) {
        Set<String> currentProtectedSet = new HashSet<>(db.getProtectedPackages());
        
        if (isSelected) {
            currentProtectedSet.add(packageName);
        } else {
            currentProtectedSet.remove(packageName);
        }
        
        // Save selection to persistent storage
        db.saveProtectedPackages(currentProtectedSet);
    }

    @Override
    public void onDestroyView() {
        // Stop background loading immediately to prevent crashes
        executor.shutdownNow();
        super.onDestroyView();
        binding = null;
    }
}