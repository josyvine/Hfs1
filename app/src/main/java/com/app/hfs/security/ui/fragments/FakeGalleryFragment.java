package com.hfs.security.ui.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.hfs.security.adapters.DecoyGalleryAdapter;
import com.hfs.security.databinding.FragmentFakeGalleryBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * Decoy System UI (Phase 9).
 * This fragment displays a harmless collection of nature and wallpaper images.
 * It is used as a "Fake Gallery" to mislead intruders who may have gained 
 * temporary access or are being shown a decoy interface.
 */
public class FakeGalleryFragment extends Fragment {

    private FragmentFakeGalleryBinding binding;
    private DecoyGalleryAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the decoy gallery layout
        binding = FragmentFakeGalleryBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setupDecoyGrid();
        loadDecoyContent();
    }

    /**
     * Sets up a standard 3-column photo grid typical of a mobile gallery app.
     */
    private void setupDecoyGrid() {
        binding.rvFakeGallery.setLayoutManager(new GridLayoutManager(requireContext(), 3));
        adapter = new DecoyGalleryAdapter(new ArrayList<>());
        binding.rvFakeGallery.setAdapter(adapter);
    }

    /**
     * Populates the decoy list with static resources or nature-themed URLs.
     * In a production environment, these could be stored in the app assets.
     */
    private void loadDecoyContent() {
        List<String> decoyImages = new ArrayList<>();
        
        // Example placeholders for nature/wallpaper images
        // These can be replaced with local drawable resource paths or asset paths
        decoyImages.add("Nature_01.jpg");
        decoyImages.add("Landscape_Sky.jpg");
        decoyImages.add("Flowers_Spring.jpg");
        decoyImages.add("Mountain_View.jpg");
        decoyImages.add("Forest_Path.jpg");
        decoyImages.add("Ocean_Waves.jpg");
        decoyImages.add("Desert_Sunset.jpg");
        decoyImages.add("Winter_Snow.jpg");
        decoyImages.add("Abstract_Wallpaper_01.jpg");
        decoyImages.add("Macro_Leaf.jpg");
        decoyImages.add("Waterfall_Flow.jpg");
        decoyImages.add("Starry_Night.jpg");

        // Update the adapter to display the fake content
        adapter.setItems(decoyImages);
        
        // Set the header title to look like a real system gallery
        if (binding.tvGalleryTitle != null) {
            binding.tvGalleryTitle.setText("My Photos (12)");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}