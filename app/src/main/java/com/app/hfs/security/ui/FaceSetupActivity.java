package com.hfs.security.ui;

import android.graphics.PointF;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;
import com.hfs.security.databinding.ActivityFaceSetupBinding;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owner Face Registration Screen.
 * UPDATED: Implemented Landmark Geometry Calibration.
 * This activity calculates the mathematical ratio of the owner's facial 
 * features to create a unique biometric template, solving the 'Match Failure' issue.
 */
public class FaceSetupActivity extends AppCompatActivity {

    private static final String TAG = "HFS_FaceSetup";
    private ActivityFaceSetupBinding binding;
    private ExecutorService cameraExecutor;
    private FaceDetector detector;
    private HFSDatabaseHelper db;
    private boolean isFaceCaptured = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize ViewBinding
        binding = ActivityFaceSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Configure ML Kit for maximum landmark accuracy
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.35f) // Ensure the face is close enough for a good scan
                .build();
        
        detector = FaceDetection.getClient(options);

        // UI Controls
        binding.btnBack.setOnClickListener(v -> finish());
        
        // Start the camera for biometric registration
        startCamera();
    }

    /**
     * Initializes CameraX for the visible registration preview.
     */
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // 1. Preview Provider
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());

                // 2. Identity Analyzer
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    if (isFaceCaptured) {
                        image.close();
                        return;
                    }
                    processRegistrationFrame(image);
                });

                // Front camera for identity setup
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Analyzes the setup frames to extract biometric landmark proportions.
     */
    @SuppressWarnings("UnsafeOptInUsageError")
    private void processRegistrationFrame(androidx.camera.core.ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), 
                imageProxy.getImageInfo().getRotationDegrees()
        );

        detector.process(image)
                .addOnSuccessListener(faces -> {
                    if (!faces.isEmpty() && !isFaceCaptured) {
                        Face face = faces.get(0);
                        
                        // Verify we can see eyes and nose clearly before saving
                        FaceLandmark leftEye = face.getLandmark(FaceLandmark.LEFT_EYE);
                        FaceLandmark rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE);
                        FaceLandmark nose = face.getLandmark(FaceLandmark.NOSE_BASE);

                        if (leftEye != null && rightEye != null && nose != null) {
                            calibrateAndSaveIdentity(leftEye, rightEye, nose);
                        }
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    /**
     * Calculates the unique mathematical ratio of your face and saves it.
     */
    private void calibrateAndSaveIdentity(FaceLandmark left, FaceLandmark right, FaceLandmark nose) {
        isFaceCaptured = true;

        // Calculate distances between points
        float eyeToEyeDist = calculateDistance(left.getPosition(), right.getPosition());
        float eyeToNoseDist = calculateDistance(left.getPosition(), nose.getPosition());
        
        // This ratio (Proportion) is what makes the match accurate
        if (eyeToNoseDist == 0) {
            isFaceCaptured = false;
            return;
        }
        
        float biometricRatio = eyeToEyeDist / eyeToNoseDist;
        final String ratioString = String.valueOf(biometricRatio);

        runOnUiThread(() -> {
            // Update UI to show success
            binding.captureAnimation.setVisibility(View.VISIBLE);
            binding.tvStatus.setText("IDENTITY CALIBRATED");
            
            // 1. Save the actual ratio map to the database
            db.saveOwnerFaceData(ratioString);
            
            // 2. Mark setup as complete
            db.setSetupComplete(true);

            Log.i(TAG, "Face Registered with Ratio: " + ratioString);
            Toast.makeText(this, "Face Identity Saved Successfully", Toast.LENGTH_LONG).show();

            // Auto-close after success
            binding.rootLayout.postDelayed(this::finish, 2000);
        });
    }

    private float calculateDistance(PointF p1, PointF p2) {
        return (float) Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (detector != null) {
            detector.close();
        }
    }
}