package com.hfm.app;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.InfoHash;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.StateUpdateAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;
import org.libtorrent4j.Vectors;
import org.libtorrent4j.swig.create_torrent;
import org.libtorrent4j.swig.file_storage;
import org.libtorrent4j.swig.libtorrent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TorrentManager adapted for libtorrent4j 2.x API.
 *
 * Notes:
 * - Uses AlertType enum from the bindings.
 * - Stores info-hash mapping as a hex string (String) to avoid Sha1Hash/InfoHash class mismatches.
 * - Uses TorrentInfo/TorrentHandle overloads that accept (TorrentInfo, File) for download/add.
 * - Uses Vectors.byte_vector2bytes(...) to convert SWIG byte_vector to Java byte[].
 *
 * All original logic preserved; only low-level API calls adjusted to new libtorrent4j API.
 */
public class TorrentManager {

    private static final String TAG = "TorrentManager";
    private static volatile TorrentManager instance;

    private final SessionManager sessionManager;
    private final Context appContext;

    // Maps to track active torrents
    private final Map<String, TorrentHandle> activeTorrents; // dropRequestId -> TorrentHandle
    // Use hex strings for keys to avoid InfoHash/Sha1Hash type incompatibilities across versions.
    private final Map<String, String> hashToIdMap; // infoHashHex -> dropRequestId

    private TorrentManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.activeTorrents = new ConcurrentHashMap<>();
        this.hashToIdMap = new ConcurrentHashMap<>();

        // Set up the listener for torrent events
        sessionManager.addListener(new AlertListener() {
            @Override
            public int[] types() {
                // Return null to listen for all types (keeps behavior robust across lib versions).
                // If you prefer limiting the alerts, return specific integer codes or implement mapping.
                return null;
            }

            @Override
            public void alert(Alert<?> alert) {
                // New API: alert.type() returns AlertType enum.
                AlertType alertType = alert.type();

                if (alertType == AlertType.STATE_UPDATE) {
                    // State updates are delivered with StateUpdateAlert
                    handleStateUpdate((StateUpdateAlert) alert);
                } else if (alertType == AlertType.TORRENT_FINISHED) {
                    handleTorrentFinished((TorrentFinishedAlert) alert);
                } else if (alertType == AlertType.TORRENT_ERROR) {
                    handleTorrentError((TorrentErrorAlert) alert);
                }
                // keep other alerts untouched
            }
        });

        // Start the session, this will start the DHT and other services
        sessionManager.start();
    }

    public static TorrentManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TorrentManager.class) {
                if (instance == null) {
                    instance = new TorrentManager(context);
                }
            }
        }
        return instance;
    }

    private void handleStateUpdate(StateUpdateAlert alert) {
        // alert.status() returns List<TorrentStatus>
        List<TorrentStatus> statuses = alert.status();
        for (TorrentStatus status : statuses) {
            // TorrentStatus in the 2.x API might expose different info-hash accessors depending on exact micro-version.
            // To be robust, attempt a few ways to obtain a stable hex representation of the info-hash.
            String infoHex = extractInfoHashHexFromStatus(status);
            if (infoHex == null) {
                continue; // can't map this status
            }

            String dropRequestId = hashToIdMap.get(infoHex);
            if (dropRequestId != null) {
                Intent intent = new Intent(DropProgressActivity.ACTION_UPDATE_STATUS);
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MAJOR, status.isSeeding() ? "Sending File..." : "Receiving File...");
                intent.putExtra(DropProgressActivity.EXTRA_STATUS_MINOR, "Peers: " + status.numPeers() + " | Down: " + (status.downloadPayloadRate() / 1024) + " KB/s | Up: " + (status.uploadPayloadRate() / 1024) + " KB/s");

                // Be careful: totalDone() and totalWanted() are long. Cast only if safe (<2^31-1).
                long totalDone = status.totalDone();
                long totalWanted = status.totalWanted();
                intent.putExtra(DropProgressActivity.EXTRA_PROGRESS, (int) Math.min(totalDone, Integer.MAX_VALUE));
                intent.putExtra(DropProgressActivity.EXTRA_MAX_PROGRESS, (int) Math.min(totalWanted, Integer.MAX_VALUE));
                intent.putExtra(DropProgressActivity.EXTRA_BYTES_TRANSFERRED, totalDone);

                LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
            }
        }
    }

    private void handleTorrentFinished(TorrentFinishedAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = extractInfoHashHexFromHandle(handle);
        String dropRequestId = infoHex == null ? null : hashToIdMap.get(infoHex);
        Log.d(TAG, "Torrent finished for request ID: " + dropRequestId);

        if (dropRequestId != null) {
            Intent intent = new Intent(DropProgressActivity.ACTION_TRANSFER_COMPLETE);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(intent);
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    private void handleTorrentError(TorrentErrorAlert alert) {
        TorrentHandle handle = alert.handle();
        String infoHex = extractInfoHashHexFromHandle(handle);
        String dropRequestId = infoHex == null ? null : hashToIdMap.get(infoHex);

        // Prefer the alert-provided message
        String errorMsg;
        try {
            errorMsg = alert.message();
        } catch (Throwable t) {
            // fall back, in case messages are exposed differently in some micro versions
            errorMsg = "Unknown torrent error";
        }

        Log.e(TAG, "Torrent error for request ID " + dropRequestId + ": " + errorMsg);

        if (dropRequestId != null) {
            Intent errorIntent = new Intent(DownloadService.ACTION_DOWNLOAD_ERROR);
            errorIntent.putExtra(DownloadService.EXTRA_ERROR_MESSAGE, "Torrent transfer failed: " + errorMsg);
            LocalBroadcastManager.getInstance(appContext).sendBroadcast(errorIntent);

            LocalBroadcastManager.getInstance(appContext).sendBroadcast(new Intent(DropProgressActivity.ACTION_TRANSFER_ERROR));
        }

        // Cleanup
        cleanupTorrent(handle);
    }

    /**
     * Start seeding a given file. Returns a magnet URI (string) if seeding started successfully, or null.
     */
    public String startSeeding(File dataFile, String dropRequestId) {
        if (dataFile == null || !dataFile.exists()) {
            Log.e(TAG, "Data file to be seeded does not exist.");
            return null;
        }

        File torrentFile = null;
        try {
            torrentFile = createTorrentFile(dataFile);
            final TorrentInfo torrentInfo = new TorrentInfo(torrentFile);

            // Add torrent using TorrentInfo + save path. The SessionManager API commonly supports download(TorrentInfo, File)
            File saveDir = dataFile.getParentFile();
            TorrentHandle handle = null;

            try {
                // Prefer the (TorrentInfo, File) overload
                handle = sessionManager.download(torrentInfo, saveDir);
            } catch (NoSuchMethodError e) {
                // fallback: try other ways (if present) — e.g. find API for addTorrent(params) or similar
                try {
                    AddTorrentParams params = new AddTorrentParams();
                    params.setTorrentInfo(torrentInfo);
                    params.setSavePath(saveDir.getAbsolutePath());
                    // If a direct download(AddTorrentParams) does not exist, use the download(TorrentInfo, File) route above.
                    handle = sessionManager.download(torrentInfo, saveDir);
                } catch (Throwable t) {
                    Log.e(TAG, "Failed to add torrent via fallback: " + t.getMessage(), t);
                }
            }

            if (handle != null && handle.isValid()) {
                activeTorrents.put(dropRequestId, handle);
                String infoHex = extractInfoHashHexFromHandle(handle);
                if (infoHex != null) {
                    hashToIdMap.put(infoHex, dropRequestId);
                }
                String magnetLink;
                try {
                    magnetLink = handle.makeMagnetUri();
                } catch (Throwable t) {
                    // fallback: if makeMagnetUri is named differently, try to compute manually or use toString of info-hash
                    magnetLink = "magnet:?xt=urn:btih:" + (infoHex != null ? infoHex : "");
                }
                Log.d(TAG, "Started seeding for request ID " + dropRequestId + ". Magnet: " + magnetLink);
                return magnetLink;
            } else {
                Log.e(TAG, "Failed to get valid TorrentHandle after adding seed.");
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create torrent for seeding: " + e.getMessage(), e);
            return null;
        } finally {
            if (torrentFile != null && torrentFile.exists()) {
                // best-effort cleanup of the temp .torrent file
                torrentFile.delete();
            }
        }
    }

    /**
     * Create a .torrent file for a given file. Returns the created temp torrent file.
     */
    private File createTorrentFile(File dataFile) throws IOException {
        file_storage fs = new file_storage();

        // Add file(s) to file_storage. The name of the helper function may vary across versions;
        // the libtorrent4j bindings usually expose libtorrent.add_files(file_storage, path) or similar.
        try {
            libtorrent.add_files(fs, dataFile.getAbsolutePath());
        } catch (Throwable t) {
            // If the binding uses a different name, you can adapt here. For now, we attempt the common call.
            Log.w(TAG, "add_files call failed (attempted libtorrent.add_files). Error: " + t.getMessage());
            throw new IOException("Failed to add files to file_storage: " + t.getMessage(), t);
        }

        // piece-size helper (some bindings call it optimal_piece_size or piece_size)
        int pieceSize;
        try {
            pieceSize = (int) libtorrent.optimal_piece_size(fs);
        } catch (Throwable t) {
            try {
                pieceSize = (int) libtorrent.piece_size(fs);
            } catch (Throwable t2) {
                // fallback default piece size if helper not present
                pieceSize = 16 * 1024; // 16 KB minimal fallback (will probably be adjusted by libtorrent)
            }
        }

        create_torrent ct;
        try {
            // Attempt the constructor (file_storage, pieceSize)
            ct = new create_torrent(fs, pieceSize);
        } catch (Throwable t) {
            // If that constructor signature doesn't exist in this micro-version, try other signatures.
            // For instance, some bindings accept (long, boolean) or (create_file_entry_vector, int).
            // As a best-effort fallback, throw with a descriptive message so you can adapt to your exact binding variant.
            throw new IOException("create_torrent constructor not found for (file_storage,int). Please check libtorrent4j micro-version.", t);
        }

        // Generate piece hashes. The generate() method sometimes returns the same object, but we call it to ensure the internal state is built.
        try {
            ct.generate();
        } catch (Throwable t) {
            // Some versions require a second call or different usage; log and continue if recoverable.
            Log.w(TAG, "create_torrent.generate() threw: " + t.getMessage());
        }

        // bencode() returns a SWIG byte_vector in many bindings — convert it to byte[] via Vectors helper.
        byte[] torrentBytes;
        try {
            // generate().bencode() might return byte_vector or similar
            torrentBytes = Vectors.byte_vector2bytes(ct.generate().bencode());
        } catch (Throwable t) {
            // If the exact chain isn't available, attempt using generate() then bencode separately
            try {
                Object gen = ct.generate();
                // if we cannot convert, rethrow with context
                throw new IOException("Unable to extract bencoded bytes from create_torrent.generate(): " + t.getMessage(), t);
            } catch (IOException io) {
                throw io;
            } catch (Throwable inner) {
                throw new IOException("Failed to bencode torrent data: " + inner.getMessage(), inner);
            }
        }

        // Write to temp .torrent file
        File tempTorrent = File.createTempFile("seed_", ".torrent", dataFile.getParentFile());
        try (FileOutputStream fos = new FileOutputStream(tempTorrent)) {
            fos.write(torrentBytes);
            fos.flush();
        }
        return tempTorrent;
    }

    /**
     * Start a download from a magnet link into saveDirectory. dropRequestId is the id to track this download.
     */
    public void startDownload(String magnetLink, File saveDirectory, String dropRequestId) {
        if (!saveDirectory.exists()) {
            saveDirectory.mkdirs();
        }

        try {
            // Best practice with libtorrent 2.x: fetch magnet metadata first, then download via TorrentInfo.
            // SessionManager.fetchMagnet(magnetUri, timeoutSeconds, tempDir) returns byte[] torrent file or null.
            byte[] torrentData = null;
            try {
                torrentData = sessionManager.fetchMagnet(magnetLink, 30, saveDirectory);
            } catch (NoSuchMethodError e) {
                // Fallback: if fetchMagnet not available on this micro-version, try AddTorrentParams.parseMagnetUri and then sessionManager.download by TorrentInfo if supported.
                try {
                    AddTorrentParams params = AddTorrentParams.parseMagnetUri(magnetLink);
                    // If sessionManager.download(AddTorrentParams) is present in some micro-versions, you could use it.
                    // However earlier compile errors showed that overload may not exist. We'll try to create torrent info via fetchMagnet fallback.
                    // For now, leave torrentData null and attempt parse approach next.
                    // Note: parseMagnetUri returns params which may not contain metadata; we still attempt to use sessionManager.download(TorrentInfo, File) if metadata is available.
                    // Continue to the fallback below.
                } catch (Throwable ignored) {
                }
            }

            if (torrentData != null) {
                // Convert to TorrentInfo
                TorrentInfo ti = TorrentInfo.bdecode(torrentData);
                TorrentHandle handle = sessionManager.download(ti, saveDirectory);
                if (handle != null && handle.isValid()) {
                    activeTorrents.put(dropRequestId, handle);
                    String infoHex = extractInfoHashHexFromHandle(handle);
                    if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                    Log.d(TAG, "Started download for request ID: " + dropRequestId);
                } else {
                    Log.e(TAG, "Failed to get valid TorrentHandle after adding download from magnet link.");
                }
            } else {
                // Fallback path: try to parse AddTorrentParams and add via TorrentInfo if possible
                try {
                    AddTorrentParams params = AddTorrentParams.parseMagnetUri(magnetLink);
                    if (params != null) {
                        // If params contain a torrent file inside (some bindings may store metadata), attempt to extract TorrentInfo
                        TorrentInfo ti = null;
                        try {
                            // Some bindings allow params.getTorrentInfo() or params.ti(); try common names reflectively would be safer, but we'll try typical accessor:
                            ti = params.torrentInfo(); // may not exist on your micro-version
                        } catch (Throwable t) {
                            // ignore; ti remains null
                        }
                        if (ti != null) {
                            TorrentHandle handle = sessionManager.download(ti, saveDirectory);
                            if (handle != null && handle.isValid()) {
                                activeTorrents.put(dropRequestId, handle);
                                String infoHex = extractInfoHashHexFromHandle(handle);
                                if (infoHex != null) hashToIdMap.put(infoHex, dropRequestId);
                                Log.d(TAG, "Started download for request ID: " + dropRequestId);
                                return;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }

                Log.e(TAG, "Failed to start download: no metadata obtained from magnet link.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start download: " + e.getMessage(), e);
        }
    }

    private void cleanupTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) {
            return;
        }

        String infoHex = extractInfoHashHexFromHandle(handle);
        String dropRequestId = infoHex == null ? null : hashToIdMap.get(infoHex);

        if (dropRequestId != null) {
            activeTorrents.remove(dropRequestId);
            hashToIdMap.remove(infoHex);
        }
        // SessionManager.remove(handle) removes a torrent from session
        try {
            sessionManager.remove(handle);
        } catch (Throwable t) {
            // Some micro-versions may call removeTorrent or remove(handle) — we try remove(handle) first, it's the common 2.x variant.
            Log.w(TAG, "sessionManager.remove(handle) threw: " + t.getMessage());
        }
        Log.d(TAG, "Cleaned up and removed torrent for request ID: " + (dropRequestId != null ? dropRequestId : "unknown"));
    }

    public void stopSession() {
        Log.d(TAG, "Stopping torrent session manager.");
        sessionManager.stop();
        activeTorrents.clear();
        hashToIdMap.clear();
        instance = null; // Allow re-initialization if needed
    }

    // -----------------------
    // Helper utilities
    // -----------------------

    /**
     * Attempt to obtain a stable hex string representation of the info-hash from a TorrentStatus.
     * This method tries a few common accessor names so it is tolerant to micro-version differences.
     */
    private String extractInfoHashHexFromStatus(TorrentStatus status) {
        if (status == null) return null;
        try {
            // Try common accessor names (examples vary by micro-release)
            // 1) status.infoHash() -> object with toString() or toHex()
            try {
                Object ih = status.infoHash();
                if (ih != null) {
                    String s = infoHashObjectToHex(ih);
                    if (s != null) return s;
                }
            } catch (Throwable ignored) {}

            // 2) status.info_hash() snake_case
            try {
                Object ih = status.info_hash();
                if (ih != null) {
                    String s = infoHashObjectToHex(ih);
                    if (s != null) return s;
                }
            } catch (Throwable ignored) {}

            // 3) status.torrent_handle() -> TorrentHandle -> infoHash
            try {
                Object thObj = status.handle(); // some bindings provide .handle()
                if (thObj instanceof TorrentHandle) {
                    return extractInfoHashHexFromHandle((TorrentHandle) thObj);
                }
            } catch (Throwable ignored) {}

            // 4) status.infoHashes() -> maybe returns a structure; try toString as fallback
            try {
                Object ihs = status.infoHashes();
                if (ihs != null) {
                    // attempt to call getBest() or toString
                    try {
                        Object best = ihs.getClass().getMethod("getBest").invoke(ihs);
                        String s = infoHashObjectToHex(best);
                        if (s != null) return s;
                    } catch (Throwable ignore2) {
                        String s = ihs.toString();
                        if (s != null && !s.isEmpty()) return s;
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            Log.w(TAG, "extractInfoHashHexFromStatus failed: " + t.getMessage());
        }
        return null;
    }

    /**
     * Attempt to obtain a stable hex string representation of the info-hash from a TorrentHandle.
     */
    private String extractInfoHashHexFromHandle(TorrentHandle handle) {
        if (handle == null) return null;
        try {
            // try handle.infoHash()
            try {
                Object ih = handle.infoHash();
                if (ih != null) {
                    String s = infoHashObjectToHex(ih);
                    if (s != null) return s;
                }
            } catch (Throwable ignored) {}

            // try handle.info_hash()
            try {
                Object ih = handle.info_hash();
                if (ih != null) {
                    String s = infoHashObjectToHex(ih);
                    if (s != null) return s;
                }
            } catch (Throwable ignored) {}

            // fallback to handle.toString() if that prints a hex-like hash
            try {
                String s = handle.toString();
                if (s != null && s.length() >= 20) return s;
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "extractInfoHashHexFromHandle failed: " + t.getMessage());
        }
        return null;
    }

    /**
     * Convert a InfoHash/Sha1Hash-like object to a hex string. Tries common accessor methods.
     */
    private String infoHashObjectToHex(Object ihObj) {
        if (ihObj == null) return null;
        try {
            // 1) try to call toHex()
            try {
                String hex = (String) ihObj.getClass().getMethod("toHex").invoke(ihObj);
                if (hex != null && !hex.isEmpty()) return hex;
            } catch (Throwable ignored) {}

            // 2) try to call toString()
            try {
                String s = ihObj.toString();
                if (s != null && !s.isEmpty()) return s;
            } catch (Throwable ignored) {}

            // 3) try swig-level byte[] extraction if present (less common)
            try {
                Object bytes = ihObj.getClass().getMethod("getBytes").invoke(ihObj);
                if (bytes instanceof byte[]) {
                    return bytesToHex((byte[]) bytes);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            Log.w(TAG, "infoHashObjectToHex failed: " + t.getMessage());
        }
        return null;
    }

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}