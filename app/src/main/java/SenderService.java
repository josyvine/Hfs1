package com.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.EventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class SenderService extends Service {

    private static final String TAG = "SenderService";
    private static final String NOTIFICATION_CHANNEL_ID = "SenderServiceChannel";
    private static final int NOTIFICATION_ID = 1001;

    // Helper class to return multiple values from cloaking process
    private static class CloakResult {
        final File cloakedFile;
        final String cloakedFilename;

        CloakResult(File file, String name) {
            this.cloakedFile = file;
            this.cloakedFilename = name;
        }
    }

    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean isRunning = false;
    private File cloakedFileToSend;
    private String cloakedFilenameToSend;

    private FirebaseFirestore db;
    private ListenerRegistration requestListener;
    private String dropRequestId;

    @Override
    public void onCreate() {
        super.onCreate();
        db = FirebaseFirestore.getInstance();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopService("Intent was null");
            return START_NOT_STICKY;
        }

        final Uri fileUri = intent.getParcelableExtra("fileUri");
        final String receiverUsername = intent.getStringExtra("receiverUsername");
        final String senderUsername = intent.getStringExtra("senderUsername");
        final String originalFilename = intent.getStringExtra("originalFilename");

        Notification notification = buildNotification("Preparing to send " + originalFilename, true);
        startForeground(NOTIFICATION_ID, notification);

        // This is the core logic fix: Cloak the file *before* any network operations.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Step 1: Generate a secret number for encryption
                    final String secretNumber = generateSecretNumber();

                    // Step 2: Create the disguised .log file from the original file FIRST.
                    updateNotification("Cloaking file...", true);
                    File originalFile = new File(URIPathHelper.getPath(getApplicationContext(), fileUri));

                    // Use the cloaking manager which performs AES encryption and Base64 encoding
                    final CloakResult cloakResult = cloakFileAndGetName(originalFile, secretNumber);

                    if (cloakResult == null || cloakResult.cloakedFile == null || !cloakResult.cloakedFile.exists()) {
                        throw new IOException("Failed to create cloaked file.");
                    }
                    
                    cloakedFileToSend = cloakResult.cloakedFile;
                    cloakedFilenameToSend = cloakResult.cloakedFilename;

                    // Step 3: Start the local server to be ready for the connection
                    updateNotification("Starting local server...", true);
                    startServer();
                    int localPort = serverSocket.getLocalPort();
                    if (localPort == -1) {
                        throw new IOException("Failed to start server socket.");
                    }

                    // Step 4: NOW, create the Firestore document with all necessary info
                    updateNotification("Creating drop request...", true);
                    createDropRequest(receiverUsername, senderUsername, originalFilename, cloakedFilenameToSend, cloakedFileToSend.length(), secretNumber, localPort);

                } catch (Exception e) {
                    Log.e(TAG, "Failed to initiate send process", e);
                    stopService("Error: " + e.getMessage());
                }
            }
        }).start();

        return START_STICKY;
    }

    private CloakResult cloakFileAndGetName(File inputFile, String secret) {
        File cloakedFile = CloakingManager.cloakFile(this, inputFile, secret);
        if (cloakedFile != null) {
            return new CloakResult(cloakedFile, cloakedFile.getName());
        }
        return null;
    }

    private void createDropRequest(String receiverUsername, String senderUsername, String originalFilename, String cloakedFilename, long cloakedFileSize, String secretNumber, int port) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser == null) {
            stopService("User not authenticated.");
            return;
        }

        Map<String, Object> dropRequest = new HashMap<>();
        dropRequest.put("senderId", currentUser.getUid());
        dropRequest.put("senderUsername", senderUsername);
        dropRequest.put("receiverUsername", receiverUsername);

        // Bug Fix: Store both original and cloaked filenames
        dropRequest.put("filename", originalFilename);
        dropRequest.put("cloakedFilename", cloakedFilename);

        dropRequest.put("filesize", cloakedFileSize);
        dropRequest.put("secretNumber", secretNumber);
        dropRequest.put("senderPublicIp", null); // To be filled by cloud function
        dropRequest.put("senderPublicPort", port);
        dropRequest.put("status", "pending");
        dropRequest.put("timestamp", System.currentTimeMillis());

        db.collection("drop_requests")
                .add(dropRequest)
                .addOnSuccessListener(new OnSuccessListener<DocumentReference>() {
                    @Override
                    public void onSuccess(DocumentReference documentReference) {
                        Log.d(TAG, "Drop request created with ID: " + documentReference.getId());
                        dropRequestId = documentReference.getId();
                        listenForStatusChange(documentReference);
                        updateNotification("Waiting for receiver to accept...", true);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        Log.e(TAG, "Error adding drop request", e);
                        stopService("Failed to create drop request.");
                    }
                });
    }

    private void listenForStatusChange(DocumentReference docRef) {
        requestListener = docRef.addSnapshotListener(new EventListener<DocumentSnapshot>() {
            @Override
            public void onEvent(DocumentSnapshot snapshot, FirebaseFirestoreException e) {
                if (e != null) {
                    Log.w(TAG, "Listener failed.", e);
                    return;
                }

                if (snapshot != null && snapshot.exists()) {
                    String status = snapshot.getString("status");
                    Log.d(TAG, "Drop request status updated to: " + status);
                    if ("accepted".equals(status)) {
                        updateNotification("Transferring file...", true);
                    } else if ("complete".equals(status) || "error".equals(status) || "declined".equals(status)) {
                        stopService("Transfer " + status + ".");
                    }
                } else {
                    stopService("Drop request was deleted.");
                }
            }
        });
    }


    private void startServer() {
        isRunning = true;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(0); // Use 0 to let the system pick an available port
                    Log.d(TAG, "Server started on port: " + serverSocket.getLocalPort());
                    while (isRunning) {
                        try {
                            Socket client = serverSocket.accept();
                            Log.d(TAG, "Client connected: " + client.getInetAddress());
                            // Each client connection is handled in its own thread
                            new Thread(new ClientHandler(client, cloakedFileToSend)).start();
                        } catch (IOException e) {
                            if (!isRunning) {
                                Log.d(TAG, "Server socket closed.");
                                break;
                            }
                            Log.e(TAG, "Error accepting client connection", e);
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Could not start server", e);
                    stopService("Could not start local server.");
                }
            }
        });
        serverThread.start();
    }

    private String generateSecretNumber() {
        Random random = new Random();
        int number = 100000 + random.nextInt(900000); // 6 digit number
        return String.valueOf(number);
    }

    private void stopService(final String reason) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(SenderService.this, reason, Toast.LENGTH_LONG).show();
            }
        });
        stopSelf();
    }


    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SenderService onDestroy.");
        isRunning = false;

        if (requestListener != null) {
            requestListener.remove();
        }

        if (dropRequestId != null) {
            db.collection("drop_requests").document(dropRequestId).delete()
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override
        				public void onSuccess(Void aVoid) {
                            Log.d(TAG, "Drop request document successfully deleted by sender.");
                        }
        			})
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            Log.w(TAG, "Error deleting drop request document on sender side.", e);
                        }
                    });
        }

        if (serverThread != null) {
            serverThread.interrupt();
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing server socket", e);
            }
        }
        if (cloakedFileToSend != null && cloakedFileToSend.exists()) {
            cloakedFileToSend.delete();
        }
        stopForeground(true);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "HFM Drop Sender",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void updateNotification(String text, boolean ongoing) {
        Notification notification = buildNotification(text, ongoing);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String text, boolean ongoing) {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("HFM Drop")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(ongoing)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}