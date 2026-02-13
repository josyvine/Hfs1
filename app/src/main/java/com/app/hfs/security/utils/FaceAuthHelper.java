package com.hfs.security.utils;

import android.content.Context;
import android.graphics.PointF;
import android.graphics.Rect;
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
 * Advanced Biometric Verification Engine.
 * FIXED for "Zero-Fail" Plan:
 * 1. Step 2: Normalizes live landmarks by Face Width % (Fixes Distance/Zoom failure).
 * 2. Step 3: Verifies 5-Point Triangulation (Eye-Eye, Eye-Nose, Mouth-Width).
 * 3. Diagnostic Trace: Provides data for the Java Error Popup.
 */
public class FaceAuthHelper {

    private static final String TAG = "HFS_FaceAuthHelper";
    private final FaceDetector detector;
    private final HFSDatabaseHelper db;
    private String lastDiagnosticInfo = "Awaiting landmark triangulation...";

    public interface AuthCallback {
        void onMatchFound();
        void onMismatchFound();
        void onError(String error);
    }

    public FaceAuthHelper(Context context) {
        this.db = HFSDatabaseHelper.getInstance(context);

        // Max accuracy settings to catch even similar-looking intruders
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.20f) 
                .build();

        this.detector = FaceDetection.getClient(options);
    }

    /**
     * Provides technical details of the failure to the LockScreen popup.
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
                            callback.onError("Face not detected in frame.");
                        } else {
                            // Step 2 & 3: Run the Normalized Triangulation check
                            verifyFaceGeometry(faces.get(0), callback);
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        lastDiagnosticInfo = "CRITICAL_ENGINE_ERROR: " + e.getMessage();
                        callback.onError(e.getMessage());
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    /**
     * Normalizes the face geometry and compares it against the saved signature.
     */
    private void verifyFaceGeometry(Face face, AuthCallback callback) {
        String savedMap = db.getOwnerFaceData();

        FaceLandmark leftEye = face.getLandmark(FaceLandmark.LEFT_EYE);
        FaceLandmark rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE);
        FaceLandmark nose = face.getLandmark(FaceLandmark.NOSE_BASE);
        FaceLandmark mouthL = face.getLandmark(FaceLandmark.MOUTH_LEFT);
        FaceLandmark mouthR = face.getLandmark(FaceLandmark.MOUTH_RIGHT);

        if (leftEye == null || rightEye == null || nose == null || mouthL == null || mouthR == null) {
            lastDiagnosticInfo = "VISIBILITY_ERROR: Features (Eyes/Nose/Mouth) partially obscured.";
            callback.onError("Incomplete features");
            return;
        }

        // Step 2: Calculate Normalization Factor (Current Face Width)
        Rect bounds = face.getBoundingBox();
        float currentFaceWidth = (float) bounds.width();
        if (currentFaceWidth <= 0) return;

        // Step 3: Calculate Current Landmark Proportions
        float eyeDist = calculateDistance(leftEye.getPosition(), rightEye.getPosition());
        float noseDist = calculateDistance(leftEye.getPosition(), nose.getPosition());
        float mouthWidth = calculateDistance(mouthL.getPosition(), mouthR.getPosition());

        // Convert to Width-Normalized Ratios (%)
        float liveRatioEE = eyeDist / currentFaceWidth;
        float liveRatioEN = noseDist / currentFaceWidth;
        float liveRatioMW = mouthWidth / currentFaceWidth;

        // Verify Database integrity
        if (savedMap == null || !savedMap.contains("|")) {
            lastDiagnosticInfo = "DB_ERROR: Owner Map invalid or missing landmark data.";
            callback.onMismatchFound();
            return;
        }

        try {
            // Saved Map format: RatioEE|RatioEN|RatioMW
            String[] parts = savedMap.split("\\|");
            float savedEE = Float.parseFloat(parts[0]);
            float savedEN = Float.parseFloat(parts[1]);
            float savedMW = Float.parseFloat(parts[2]);

            // Calculate Variance for each landmark triangulation point
            float varEE = Math.abs(liveRatioEE - savedEE) / savedEE;
            float varEN = Math.abs(liveRatioEN - savedEN) / savedEN;
            float varMW = Math.abs(liveRatioMW - savedMW) / savedMW;

            // Diagnostic trace for the Java Lang error popup
            float totalAvgVar = (varEE + varEN + varMW) / 3;
            lastDiagnosticInfo = String.format(
                "Biometric Map Variance:\nEye-Eye: %.1f%%\nEye-Nose: %.1f%%\nMouth-Width: %.1f%%\nTotal Average: %.1f%%", 
                (varEE * 100), (varEN * 100), (varMW * 100), (totalAvgVar * 100)
            );

            /*
             * ZERO-FAIL THRESHOLD:
             * Owner map matches if all three landmark points are within 12% tolerance.
             * This handles lens distortion while rejecting intruders who do not share 
             * your specific bone structure proportions.
             */
            if (varEE <= 0.12f && varEN <= 0.12f && varMW <= 0.12f) {
                Log.i(TAG, "Identity Match Verified.");
                callback.onMatchFound();
            } else {
                Log.w(TAG, "Identity Rejected: " + lastDiagnosticInfo);
                callback.onMismatchFound();
            }

        } catch (Exception e) {
            lastDiagnosticInfo = "MAP_PARSING_ERROR: Landmark data corrupt.";
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