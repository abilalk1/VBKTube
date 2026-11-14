package com.vidownphone.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.webkit.CookieManager;

import com.vidownphone.app.engine.EngineManager;
import com.vidownphone.app.service.DownloadService;
import com.vidownphone.app.util.PreferenceHelper;

public class VidownApp extends Application {

    /** Ongoing progress notification (low importance, silent). */
    public static final String DOWNLOAD_CHANNEL_ID = "vidown_downloads_channel";
    /** Completed / failed download notifications (default importance). */
    public static final String DOWNLOAD_EVENTS_CHANNEL_ID = "vidown_download_events_channel";

    @Override
    public void onCreate() {
        super.onCreate();
        final long processStartMs = System.currentTimeMillis();

        // Apply saved theme and language preferences on startup
        PreferenceHelper.applySavedTheme(this);
        PreferenceHelper.applySavedLocale(this);

        // Enable WebView cookie persistence so logins in Browser tab work across all extractors
        try {
            CookieManager.getInstance().setAcceptCookie(true);
        } catch (Throwable ignored) {
        }

        createNotificationChannels();

        // Initialize NewPipeExtractor, yt-dlp, FFmpeg, Aria2c and auto-update yt-dlp in background
        EngineManager.initAndAutoUpdateOnStartup(this);

        // O-06: tasks left QUEUED/DOWNLOADING by a previous process are marked FAILED ("interrupted"),
        // then their leftover work files are swept. Y-03: startup cleanup only touches work_downloads,
        // never the user's download folder.
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            DownloadService.markInterruptedTasksOnStartup(this, processStartMs);
            DownloadService.cleanOrphanedWorkFiles(this);
        });
    }

    public static boolean isYtdlpInitialized() {
        return EngineManager.isYtdlpReady();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager == null) return;

            NotificationChannel progress = new NotificationChannel(
                    DOWNLOAD_CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            progress.setDescription(getString(R.string.notif_channel_desc));
            manager.createNotificationChannel(progress);

            // D-02: results go to a separate DEFAULT-importance channel.
            NotificationChannel events = new NotificationChannel(
                    DOWNLOAD_EVENTS_CHANNEL_ID,
                    getString(R.string.notif_channel_events_name),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            events.setDescription(getString(R.string.notif_channel_events_desc));
            manager.createNotificationChannel(events);
        }
    }
}
