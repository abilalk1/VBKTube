package com.vidownphone.app.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.vidownphone.app.R;
import com.vidownphone.app.util.PreferenceHelper;
import com.yausername.aria2c.Aria2c;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Centralized manager for initializing, monitoring, and updating all media extraction & download services:
 * 1. yt-dlp (YoutubeDL 0.18.1 + Bundled 2026.10.10.233141 Nightly Engine)
 * 2. FFmpeg (Audio/Video muxing & MP3 conversion)
 * 3. Aria2c (Multi-connection high-speed downloader)
 * 4. NewPipeExtractor v0.26.2 (Native YouTube & Streaming Service parser)
 */
public final class EngineManager {

    private static final String TAG = "EngineManager";
    private static final String BUNDLED_YTDLP_VERSION = "2026.10.10.233141";

    private static volatile boolean ytdlpReady = false;
    private static volatile boolean ffmpegReady = false;
    private static volatile boolean aria2cReady = false;
    private static volatile boolean newPipeReady = false;
    private static volatile boolean updateInProgress = false;
    private static volatile String cachedYtdlpVersion = BUNDLED_YTDLP_VERSION;
    private static volatile String lastError = "";

    private static final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface EngineCallback {
        void onProgress(String statusText);
        void onComplete(boolean allReady, boolean updated, String version, String message);
    }

    private EngineManager() {}

    public static boolean isYtdlpReady() {
        return ytdlpReady;
    }

    public static boolean isFfmpegReady() {
        return ffmpegReady;
    }

    public static boolean isAria2cReady() {
        return aria2cReady;
    }

    public static boolean isNewPipeReady() {
        return newPipeReady;
    }

    public static boolean isAllCoreServicesReady() {
        return ytdlpReady && ffmpegReady && newPipeReady;
    }

    public static boolean isUpdateInProgress() {
        return updateInProgress;
    }

    public static String getCachedYtdlpVersion() {
        return cachedYtdlpVersion;
    }

    public static String getLastError() {
        return lastError;
    }

    /**
     * Synchronously ensures all 4 engines are initialized. Safe to call from any background thread.
     */
    public static synchronized boolean ensureInitializedSync(Context context) {
        Context appContext = context.getApplicationContext();

        // 1. Initialize NewPipeExtractor (Fastest, pure Java, used for YouTube & streaming sites)
        if (!newPipeReady) {
            try {
                OkHttpClient npClient = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build();
                NewPipe.init(
                        new NewPipeDownloaderImpl(npClient),
                        new Localization("tr", "TR"),
                        new ContentCountry("TR")
                );
                newPipeReady = true;
                Log.i(TAG, "NewPipeExtractor v0.26.2 initialized");
            } catch (Throwable t) {
                Log.e(TAG, "NewPipe init error", t);
                lastError = "NewPipe: " + t.getMessage();
            }
        }

        // 2. Initialize YoutubeDL (yt-dlp) and upgrade to bundled 2026.10.10.233141 binary
        if (!ytdlpReady) {
            try {
                YoutubeDL.getInstance().init(appContext);
                ensureBundledLatestYtDlpInstalled(appContext);
                ytdlpReady = true;
                try {
                    String ver = YoutubeDL.getInstance().version(appContext);
                    if (ver != null && !ver.isEmpty()) {
                        cachedYtdlpVersion = ver;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Could not read yt-dlp version", t);
                }
                if (cachedYtdlpVersion == null || cachedYtdlpVersion.isEmpty()) {
                    cachedYtdlpVersion = BUNDLED_YTDLP_VERSION;
                }
                Log.i(TAG, "YoutubeDL initialized, version=" + cachedYtdlpVersion);
            } catch (Throwable t) {
                Log.e(TAG, "YoutubeDL init error", t);
                lastError = "yt-dlp: " + t.getMessage();
            }
        }

        // 3. Initialize FFmpeg
        if (!ffmpegReady) {
            try {
                FFmpeg.getInstance().init(appContext);
                ffmpegReady = true;
                Log.i(TAG, "FFmpeg initialized");
            } catch (Throwable t) {
                Log.e(TAG, "FFmpeg init error", t);
            }
        }

        // 4. Initialize Aria2c
        if (!aria2cReady) {
            try {
                Aria2c.getInstance().init(appContext);
                aria2cReady = true;
                Log.i(TAG, "Aria2c initialized");
            } catch (Throwable t) {
                Log.w(TAG, "Aria2c init warning", t);
            }
        }

        return ytdlpReady || newPipeReady;
    }

    /**
     * Ensures the bundled 2026.10.10.233141 yt-dlp binary in assets/yt-dlp-latest.bin replaces the old 2025 binary.
     */
    private static void ensureBundledLatestYtDlpInstalled(Context appContext) {
        try {
            File ytdlpDir = new File(appContext.getNoBackupFilesDir(), "youtubedl-android/yt-dlp");
            File ytdlpBin = new File(ytdlpDir, "yt-dlp");
            File markerFile = new File(ytdlpDir, "bundled_" + BUNDLED_YTDLP_VERSION + ".marker");
            if (markerFile.exists() && ytdlpBin.exists() && ytdlpBin.length() > 2_000_000) {
                return;
            }
            if (!ytdlpDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                ytdlpDir.mkdirs();
            }
            File tempBin = new File(ytdlpDir, "yt-dlp.tmp");
            try (InputStream in = appContext.getAssets().open("yt-dlp-latest.bin");
                 FileOutputStream out = new FileOutputStream(tempBin)) {
                byte[] buf = new byte[32 * 1024];
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                }
                out.flush();
            }
            if (tempBin.exists() && tempBin.length() > 2_000_000) {
                if (ytdlpBin.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    ytdlpBin.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                tempBin.renameTo(ytdlpBin);
                //noinspection ResultOfMethodCallIgnored
                ytdlpBin.setExecutable(true, false);
                //noinspection ResultOfMethodCallIgnored
                ytdlpBin.setReadable(true, false);
                //noinspection ResultOfMethodCallIgnored
                markerFile.createNewFile();
                cachedYtdlpVersion = BUNDLED_YTDLP_VERSION;
                Log.i(TAG, "Upgraded yt-dlp binary from bundled asset to " + BUNDLED_YTDLP_VERSION);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not copy bundled yt-dlp asset: " + t.getMessage());
        }
    }

    /**
     * Direct GitHub release downloader for yt-dlp that works reliably via OkHttp when YoutubeDLUpdater fails.
     */
    private static boolean downloadLatestYtDlpDirectly(Context appContext, String channelKey) {
        String downloadUrl = "STABLE".equalsIgnoreCase(channelKey)
                ? "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
                : "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/latest/download/yt-dlp";

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        Request req = new Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "VidownPhone/2.0 (Android; yt-dlp updater)")
                .build();

        File ytdlpDir = new File(appContext.getNoBackupFilesDir(), "youtubedl-android/yt-dlp");
        if (!ytdlpDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            ytdlpDir.mkdirs();
        }
        File tempBin = new File(ytdlpDir, "yt-dlp.download.tmp");
        File ytdlpBin = new File(ytdlpDir, "yt-dlp");

        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                return false;
            }
            try (InputStream in = resp.body().byteStream();
                 FileOutputStream out = new FileOutputStream(tempBin)) {
                byte[] buf = new byte[32 * 1024];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
                out.flush();
            }
            if (tempBin.exists() && tempBin.length() > 2_000_000) {
                if (ytdlpBin.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    ytdlpBin.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                tempBin.renameTo(ytdlpBin);
                //noinspection ResultOfMethodCallIgnored
                ytdlpBin.setExecutable(true, false);
                //noinspection ResultOfMethodCallIgnored
                ytdlpBin.setReadable(true, false);

                // Extract tag version from final redirect URL if present
                String finalUrl = resp.request().url().toString();
                if (finalUrl.contains("/download/")) {
                    int idx = finalUrl.indexOf("/download/") + "/download/".length();
                    int end = finalUrl.indexOf('/', idx);
                    if (end > idx) {
                        cachedYtdlpVersion = finalUrl.substring(idx, end);
                    }
                }
                PreferenceHelper.setLastEngineUpdateTime(appContext, System.currentTimeMillis());
                return true;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Direct yt-dlp GitHub download failed: " + e.getMessage());
        } finally {
            if (tempBin.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempBin.delete();
            }
        }
        return false;
    }

    /**
     * Called on Application startup: initializes all services and triggers a background yt-dlp binary update
     * so YouTube, Instagram, and Twitter/X extractors stay current.
     */
    public static void initAndAutoUpdateOnStartup(Context context) {
        final Context appContext = context.getApplicationContext();
        engineExecutor.execute(() -> {
            ensureInitializedSync(appContext);
            if (ytdlpReady && !updateInProgress) {
                long lastUpdate = PreferenceHelper.getLastEngineUpdateTime(appContext);
                long now = System.currentTimeMillis();
                // Auto-check update if never updated or older than 12 hours
                if (now - lastUpdate > 12 * 3600 * 1000L) {
                    try {
                        updateInProgress = true;
                        // Last-update time is only recorded by downloadLatestYtDlpDirectly on real success.
                        boolean ok = downloadLatestYtDlpDirectly(appContext,
                                PreferenceHelper.getUpdateChannel(appContext));
                        Log.i(TAG, "Startup yt-dlp update " + (ok ? "succeeded" : "failed (keeping current binary)")
                                + ", version=" + cachedYtdlpVersion);
                    } catch (Throwable t) {
                        Log.w(TAG, "Startup yt-dlp update failed", t);
                    } finally {
                        updateInProgress = false;
                    }
                }
            }
        });
    }

    /**
     * Triggered from Settings or Home screen when user clicks "Servisleri Yeniden Bul / Aç" or "Servisleri Güncelle".
     * Resets & re-initializes all services, then optionally updates yt-dlp binary from GitHub releases.
     */
    public static void reinitializeAndUpdateServices(Context context, boolean performRemoteUpdate, EngineCallback callback) {
        final Context appContext = context.getApplicationContext();
        engineExecutor.execute(() -> {
            try {
                updateInProgress = true;
                postProgress(callback, appContext.getString(R.string.engine_progress_restarting));

                // Force re-check
                newPipeReady = false;
                ytdlpReady = false;
                ffmpegReady = false;
                aria2cReady = false;

                ensureInitializedSync(appContext);

                if (!ytdlpReady && !newPipeReady) {
                    postComplete(callback, false, false, cachedYtdlpVersion,
                            appContext.getString(R.string.engine_error_init, String.valueOf(lastError)));
                    return;
                }

                boolean updatedNow = false;
                String statusMsg;

                if (performRemoteUpdate && ytdlpReady) {
                    String channelStr = PreferenceHelper.getUpdateChannel(appContext);
                    postProgress(callback, appContext.getString(R.string.engine_progress_updating, channelStr));

                    boolean usedBundledFallback = false;
                    updatedNow = downloadLatestYtDlpDirectly(appContext, channelStr);
                    if (!updatedNow) {
                        try {
                            YoutubeDL.UpdateChannel channel = resolveChannel(channelStr);
                            YoutubeDL.UpdateStatus status = YoutubeDL.getInstance().updateYoutubeDL(appContext, channel);
                            updatedNow = (status == YoutubeDL.UpdateStatus.DONE || status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE);
                        } catch (Throwable t) {
                            Log.w(TAG, "yt-dlp library update failed, using bundled binary", t);
                            ensureBundledLatestYtDlpInstalled(appContext);
                            usedBundledFallback = true;
                            updatedNow = false;
                        }
                    }

                    if (updatedNow) {
                        statusMsg = appContext.getString(R.string.engine_result_updated, cachedYtdlpVersion);
                    } else if (usedBundledFallback) {
                        statusMsg = appContext.getString(R.string.engine_result_update_failed_bundled, cachedYtdlpVersion);
                    } else {
                        statusMsg = appContext.getString(R.string.engine_result_update_failed_current, cachedYtdlpVersion);
                    }
                } else {
                    statusMsg = appContext.getString(R.string.engine_result_all_started, cachedYtdlpVersion);
                }

                postComplete(callback, isAllCoreServicesReady(), updatedNow, cachedYtdlpVersion, statusMsg);
            } finally {
                updateInProgress = false;
            }
        });
    }

    private static YoutubeDL.UpdateChannel resolveChannel(String channelKey) {
        if ("STABLE".equalsIgnoreCase(channelKey)) {
            return YoutubeDL.UpdateChannel._STABLE;
        } else if ("MASTER".equalsIgnoreCase(channelKey)) {
            return YoutubeDL.UpdateChannel._MASTER;
        }
        return YoutubeDL.UpdateChannel._NIGHTLY;
    }

    private static void postProgress(EngineCallback callback, String msg) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onProgress(msg));
    }

    private static void postComplete(EngineCallback callback, boolean allReady, boolean updated, String version, String msg) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onComplete(allReady, updated, version, msg));
    }
}
