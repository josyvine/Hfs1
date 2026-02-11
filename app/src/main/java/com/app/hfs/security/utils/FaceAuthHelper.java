package com.hfs.security.utils;

import android.content.Context;
import android.graphics.PointF;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.List;

/**
 * Strict Biometric Verification Engine.
 * FIXED: 
 * 1. Implemented Landmark Ratio Map diagnostic logging.
 * 2. Added lastDiagnosticInfo getter to provide data for the Java Error Popup.
 * 3. Optimized proportions logic with a 15% tolerance window.
 */
public class FaceAuthHelper {

    private static final String TAG = "HFS_FaceAuthHelper";
    private final FaceDetector detector;
    private final HFSDatabaseHelper db;
    private String lastDiagnosticInfo = "No data captured.";

    /**
     * Interface to communicate strict authentication results.
     */
    public interface AuthCallback {
        void onMatchFound();
        void onMismatchFound();
        void onError(String error);
    }

    public FaceAuthHelper(Context context) {
        this.db = HFSDatabaseHelper.getInstance(context);

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.25f)
                .build();

        this.detector = FaceDetection.getClient(options);
    }

    /**
     * Returns the technical details of the last match attempt.
     * Used by LockScreenActivity to show the Java Error Popup.
     */
    public String getLastDiagnosticInfo() {
        return lastDiagnosticInfo;
    }

    @SuppressWarnings("UnsafeOptInUsageError")
    public void authenticate(@NonNull ImageProxy imageProxy, @NonNull AuthCallback callback) {
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), 
                imageProxy.getImageInfo().getRotationDegrees()
        );

        detector.process(image)
                .addOnSuccessListener(new OnSuccessListener<List<Face>>() {
                    @Override
                    public void onSuccess(List<Face> faces) {
                        if (faces.isEmpty()) {
                            callback.onError("Searching for landmarks...");
                        } else {
                            verifyFaceProportions(faces.get(0), callback);
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        lastDiagnosticInfo = "ML_KIT_ERROR: " + e.getMessage();
                        callback.onError(e.getMessage());
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    /**
     * The Diagnostic Logic: Compares mathematical proportions.
     */
    private void verifyFaceProportions(Face face, AuthCallback callback) {
        String savedRatioStr = db.getOwnerFaceData();

        FaceLandmark leftEye = face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE);
        FaceLandmark nose = face.getLandmark(FaceLandmark.NOSE_BASE);

        if (leftEye == null || rightEye == null || nose == null) {
            lastDiagnosticInfo = "VISIBILITY_ERROR: Camera cannot see eyes/nose clearly.";
            callback.onError("Incomplete features");
            return;
        }

        // Calculate distances
        float liveEyeDist = calculateDistance(leftEye.getPosition(), rightEye.getPosition());
        float liveNoseDist = calculateDistance(leftEye.getPosition(), nose.getPosition());
        
        if (liveNoseDist == 0) return;
        float liveRatio = liveEyeDist / liveNoseDist;

        if (savedRatioStr == null || savedRatioStr.isEmpty() || savedRatioStr.equals("REGISTERED_OWNER_ID")) {
            lastDiagnosticInfo = "DATABASE_ERROR: No Owner Identity Ratio found. Please perform 'Rescan'.";
            callback.onMismatchFound();
            return;
        }

        try {
            float savedRatio = Float.parseFloat(savedRatioStr);
            float difference = Math.abs(liveRatio - savedRatio);
            float diffPercentage = (difference / savedRatio);

            // LOG FOR POPUP DIAGNOSTIC
            lastDiagnosticInfo = String.format("Biometric Trace:\nSaved Ratio: %.2f\nDetected Ratio: %.2f\nVariance: %.1f%%", 
                    savedRatio, liveRatio, (diffPercentage * 100));

            // 15% tolerance standard
            if (diffPercentage <= 0.15f) {
                Log.i(TAG, "Owner Verified.");
                callback.onMatchFound();
            } else {
                Log.w(TAG, "Mismatch: " + lastDiagnosticInfo);
                callback.onMismatchFound();
            }
            
        } catch (NumberFormatException e) {
            lastDiagnosticInfo = "DATA_CORRUPTION: Saved ratio format invalid.";
            callback.onMismatchFound();
        }
    }

    private float calculateDistance(PointF p1, PointF p2) {
        return (float) Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
    }

    public void stop() {
        if (detector != null) {
            detector.close();
        }
    }
}