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
 * FIXED: Added checks to prevent "Not attached to a context" crash when 
 * switching to the Dashboard while the app list is still loading.
 */
public class ProtectedAppsFragment extends Fragment implements AppSelectionAdapter.OnAppSelectionListener {

    private FragmentProtectedAppsBinding binding;
    private AppSelectionAdapter adapter;
    private List<AppInfo> fullAppList;
    private HFSDatabaseHelper db;
    
    // Executor for background processing
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
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
        
        // Load apps in a background thread
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
     * FIX: Uses isAdded() to prevent background crashes during tab switching.
     */
    private void loadInstalledApps() {
        binding.progressBar.setVisibility(View.VISIBLE);
        
        executor.execute(() -> {
            // CRITICAL CHECK: Ensure fragment is still active before starting work
            if (!isAdded() || getContext() == null) return;

            PackageManager pm = getContext().getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<AppInfo> tempInfoList = new ArrayList<>();
            
            Set<String> savedProtectedPackages = db.getProtectedPackages();

            for (ApplicationInfo app : packages) {
                if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                    if (app.packageName.equals(getContext().getPackageName())) continue;

                    String name = app.loadLabel(pm).toString();
                    Drawable icon = app.loadIcon(pm);
                    boolean isAlreadyProtected = savedProtectedPackages.contains(app.packageName);
                    
                    tempInfoList.add(new AppInfo(name, app.packageName, icon, isAlreadyProtected));
                }
            }

            Collections.sort(tempInfoList, (a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));

            // CRITICAL FIX: Verify fragment state before touching the UI thread
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    // Final check to ensure binding is still valid
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

    @Override
    public void onAppToggle(String packageName, boolean isSelected) {
        Set<String> currentProtectedSet = new HashSet<>(db.getProtectedPackages());
        
        if (isSelected) {
            currentProtectedSet.add(packageName);
        } else {
            currentProtectedSet.remove(packageName);
        }
        
        db.saveProtectedPackages(currentProtectedSet);
    }

    @Override
    public void onDestroyView() {
        // Shutdown the executor when fragment is destroyed to stop background tasks
        executor.shutdownNow();
        super.onDestroyView();
        binding = null;
    }
}