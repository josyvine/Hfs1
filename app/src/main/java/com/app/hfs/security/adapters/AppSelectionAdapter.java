package com.hfs.security.adapters;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hfs.security.databinding.ItemAppSelectionBinding;
import com.hfs.security.models.AppInfo;

import java.util.List;

/**
 * Adapter for the Protected App Selection list.
 * Binds installed application metadata (Icon, Name, Package) to the UI items.
 * Handles the logic for toggling the protection status of each app.
 */
public class AppSelectionAdapter extends RecyclerView.Adapter<AppSelectionAdapter.AppViewHolder> {

    private List<AppInfo> appList;
    private final OnAppSelectionListener listener;

    /**
     * Interface to communicate selection changes back to the ProtectedAppsFragment.
     */
    public interface OnAppSelectionListener {
        /**
         * Triggered when a user checks or unchecks an app for protection.
         * @param packageName The unique ID of the app.
         * @param isSelected True if protection is enabled, false otherwise.
         */
        void onAppToggle(String packageName, boolean isSelected);
    }

    /**
     * Constructor for the adapter.
     * @param appList Initial list of apps to display.
     * @param listener Callback interface for selection events.
     */
    public AppSelectionAdapter(List<AppInfo> appList, OnAppSelectionListener listener) {
        this.appList = appList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Use ViewBinding for the item layout to avoid findViewById overhead
        ItemAppSelectionBinding binding = ItemAppSelectionBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new AppViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        // Retrieve the app data for the current position
        AppInfo app = appList.get(position);
        holder.bind(app, listener);
    }

    @Override
    public int getItemCount() {
        return appList != null ? appList.size() : 0;
    }

    /**
     * Updates the data set and refreshes the UI. 
     * Used for initial load and real-time search filtering.
     * @param newList The new filtered or complete list of apps.
     */
    public void updateList(List<AppInfo> newList) {
        this.appList = newList;
        // Notifies the recycler view that the data has changed
        notifyDataSetChanged();
    }

    /**
     * ViewHolder class that caches view references for better performance.
     */
    static class AppViewHolder extends RecyclerView.ViewHolder {
        private final ItemAppSelectionBinding binding;

        public AppViewHolder(ItemAppSelectionBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Binds model data to the XML views.
         */
        public void bind(AppInfo app, OnAppSelectionListener listener) {
            // Set basic info
            binding.tvAppName.setText(app.getAppName());
            binding.tvPackageName.setText(app.getPackageName());
            binding.ivAppIcon.setImageDrawable(app.getIcon());

            // 1. Reset the listener to null before setting the state 
            // to prevent triggering the callback during list scrolling.
            binding.cbProtected.setOnCheckedChangeListener(null);
            binding.cbProtected.setChecked(app.isSelected());

            // 2. Attach the toggle listener
            binding.cbProtected.setOnCheckedChangeListener((buttonView, isChecked) -> {
                app.setSelected(isChecked);
                if (listener != null) {
                    listener.onAppToggle(app.getPackageName(), isChecked);
                }
            });

            // 3. User Experience: Allow clicking the entire row to toggle the checkbox
            this.itemView.setOnClickListener(v -> {
                binding.cbProtected.toggle();
            });
        }
    }
}