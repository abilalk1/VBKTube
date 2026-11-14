package com.vidownphone.app.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.vidownphone.app.R;
import com.vidownphone.app.VidownApp;
import com.vidownphone.app.data.AppDatabase;
import com.vidownphone.app.data.DownloadDao;
import com.vidownphone.app.data.DownloadTask;
import com.vidownphone.app.engine.CookieBridge;
import com.vidownphone.app.engine.EngineManager;
import com.vidownphone.app.engine.HybridExtractor;
import com.vidownphone.app.engine.SocialPlatformExtractor;
import com.vidownphone.app.model.AnalyzedMedia;
import com.vidownphone.app.model.MediaFormatOption;
import com.vidownphone.app.ui.MainActivity;
import com.vidownphone.app.ui.browser.BrowserFragment;
import com.vidownphone.app.util.FormatUtils;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLException;

import kotlin.Unit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DownloadService extends Service {

    private static final String TAG = "DownloadService";
    public static final String ACTION_ENQUEUE = "com.vidownphone.app.ACTION_ENQUEUE";
    public static final String ACTION_CANCEL = "com.vidownphone.app.ACTION_CANCEL";
    public static final String ACTION_RETRY = "com.vidownphone.app.ACTION_RETRY";
    public static final String EXTRA_TASK_ID = "extra_task_id";

    public static final String EXTRA_PENDING_TOKEN = "extra_pending_token";

    /** Single summary progress notification shared by all running tasks (also the foreground notification). */
    private static final int PROGRESS_NOTIF_ID = 1001;
    /** Completed/failed notifications use RESULT_NOTIF_BASE + taskId on the events channel. */
    private static final int RESULT_NOTIF_BASE = 2000;

    /** Application context used to resolve localized strings from static helpers. */
    private static volatile Context sAppCtx;

    private static String str(int resId, Object... args) {
        Context c = sAppCtx;
        if (c == null) return "";
        return args.length == 0 ? c.getString(resId) : c.getString(resId, args);
    }

    private static final Map<Long, DownloadTask> PENDING_INSERTS = new ConcurrentHashMap<>();
    private static final AtomicLong PENDING_TOKEN = new AtomicLong(1);
    private static final Object FILE_NAME_LOCK = new Object();

    private static final List<String> IMAGE_EXTENSIONS = Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "heic", "heif");
    private static final List<String> KNOWN_MEDIA_EXTENSIONS = Arrays.asList(
            "mp4", "m4v", "mkv", "webm", "mov", "3gp", "avi", "ts", "m3u8",
            "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac",
            "jpg", "jpeg", "png", "webp", "gif", "avif", "bmp", "heic", "heif");

    private ExecutorService downloadExecutor;
    private ExecutorService dbExecutor;
    private DownloadDao downloadDao;
    private NotificationManager notificationManager;
    private OkHttpClient httpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<Long, Call> activeHttpCalls = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> canceledTasks = new ConcurrentHashMap<>();
    private final Map<Long, Process> activeProcesses = new ConcurrentHashMap<>();
    private final Map<Long, TaskRunner> runningTasks = new ConcurrentHashMap<>();
    private final Set<Long> fallbackUsed = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingRestart = ConcurrentHashMap.newKeySet();
    /** Active + queued tasks (plus pending DB inserts). Service leaves foreground only when this reaches 0. */
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final Map<Long, int[]> notifProgress = new ConcurrentHashMap<>();
    private final Map<Long, String> notifTitles = new ConcurrentHashMap<>();
    private final AtomicBoolean notifRefreshScheduled = new AtomicBoolean(false);
    private volatile int lastStartId;

    /** Thrown for errors whose message is already user-facing (Turkish). */
    static class UserFacingException extends Exception {
        UserFacingException(String message) {
            super(message);
        }
    }

    /** Non-2xx HTTP response. */
    static class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    public static void enqueueDownload(Context context, DownloadTask task) {
        final Context app = context.getApplicationContext();
        final long token = PENDING_TOKEN.getAndIncrement();
        PENDING_INSERTS.put(token, task);
        // O-07: start the foreground service right away on the main thread; the DB insert happens inside the service.
        runOnMain(() -> {
            Intent serviceIntent = new Intent(app, DownloadService.class);
            serviceIntent.setAction(ACTION_ENQUEUE);
            serviceIntent.putExtra(EXTRA_PENDING_TOKEN, token);
            if (!startServiceSafely(app, serviceIntent, true)) {
                DownloadTask pending = PENDING_INSERTS.remove(token);
                if (pending != null) {
                    pending.setStatus(DownloadTask.STATUS_FAILED);
                    pending.setErrorMessage(app.getString(R.string.error_start_not_allowed));
                    pending.setSpeedAndEta("");
                    Executors.newSingleThreadExecutor().execute(() -> {
                        try {
                            AppDatabase.getInstance(app).downloadDao().insertTask(pending);
                        } catch (Exception e) {
                            Log.e(TAG, "Could not persist task after service start failure", e);
                        }
                    });
                }
            }
        });
    }

    public static void cancelDownload(Context context, long taskId) {
        final Context app = context.getApplicationContext();
        runOnMain(() -> {
            Intent serviceIntent = new Intent(app, DownloadService.class);
            serviceIntent.setAction(ACTION_CANCEL);
            serviceIntent.putExtra(EXTRA_TASK_ID, taskId);
            if (!startServiceSafely(app, serviceIntent, false)) {
                // Service is not running (nothing to stop); just make sure the DB row is not left active.
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        AppDatabase.getInstance(app).downloadDao().markCanceledIfActive(taskId);
                    } catch (Exception e) {
                        Log.w(TAG, "markCanceledIfActive failed", e);
                    }
                });
            }
        });
    }

    public static void retryDownload(Context context, long taskId) {
        final Context app = context.getApplicationContext();
        runOnMain(() -> {
            Intent serviceIntent = new Intent(app, DownloadService.class);
            serviceIntent.setAction(ACTION_RETRY);
            serviceIntent.putExtra(EXTRA_TASK_ID, taskId);
            if (!startServiceSafely(app, serviceIntent, true)) {
                Executors.newSingleThreadExecutor().execute(() -> {
                    try {
                        DownloadDao dao = AppDatabase.getInstance(app).downloadDao();
                        DownloadTask t = dao.getTaskByIdSync(taskId);
                        if (t != null) {
                            t.setStatus(DownloadTask.STATUS_FAILED);
                            t.setErrorMessage(app.getString(R.string.error_start_not_allowed));
                            t.setSpeedAndEta("");
                            dao.updateTask(t);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not mark retry failure", e);
                    }
                });
            }
        });
    }

    private static void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            new Handler(Looper.getMainLooper()).post(r);
        }
    }

    /**
     * Starts the service, catching ForegroundServiceStartNotAllowedException (API 31+, a subclass of
     * IllegalStateException) and background-start IllegalStateException.
     */
    private static boolean startServiceSafely(Context app, Intent intent, boolean foreground) {
        try {
            if (foreground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent);
            } else {
                app.startService(intent);
            }
            return true;
        } catch (IllegalStateException | SecurityException e) {
            Log.e(TAG, "Could not start DownloadService (" + intent.getAction() + ")", e);
            return false;
        }
    }

    /**
     * Marks tasks left in QUEUED/DOWNLOADING by a previous process as FAILED (O-06).
     * Must be called off the main thread.
     */
    public static int markInterruptedTasksOnStartup(Context context, long processStartMs) {
        try {
            return AppDatabase.getInstance(context).downloadDao()
                    .markInterruptedTasksFailed(processStartMs, context.getString(R.string.error_interrupted));
        } catch (Exception e) {
            Log.w(TAG, "Could not mark interrupted tasks", e);
            return 0;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sAppCtx = getApplicationContext();
        downloadExecutor = Executors.newFixedThreadPool(2);
        dbExecutor = Executors.newSingleThreadExecutor();
        downloadDao = AppDatabase.getInstance(this).downloadDao();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        if (intent == null || intent.getAction() == null) {
            stopIfIdle();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        long taskId = intent.getLongExtra(EXTRA_TASK_ID, -1);

        if (ACTION_CANCEL.equals(action)) {
            if (taskId != -1) {
                handleCancel(taskId);
            }
            stopIfIdle();
            return START_NOT_STICKY;
        }

        if (ACTION_ENQUEUE.equals(action) || ACTION_RETRY.equals(action)) {
            // Every startForegroundService() must be followed by startForeground().
            promoteToForeground();
            long token = intent.getLongExtra(EXTRA_PENDING_TOKEN, -1);
            if (token != -1) {
                DownloadTask pending = PENDING_INSERTS.remove(token);
                if (pending != null) {
                    startPendingInsert(pending);
                }
            } else if (taskId != -1) {
                startTask(taskId, false);
            }
        }

        stopIfIdle();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        for (Long id : new ArrayList<>(runningTasks.keySet())) {
            abortActiveWork(id);
        }
        if (downloadExecutor != null) {
            downloadExecutor.shutdownNow();
        }
        if (dbExecutor != null) {
            dbExecutor.shutdown();
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (notificationManager != null) {
            notificationManager.cancel(PROGRESS_NOTIF_ID);
        }
        runningTasks.clear();
        canceledTasks.clear();
        activeHttpCalls.clear();
        activeProcesses.clear();
        fallbackUsed.clear();
        pendingRestart.clear();
        notifProgress.clear();
        notifTitles.clear();
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------------------
    // Task scheduling / lifecycle
    // ---------------------------------------------------------------------------------------------

    private final class TaskRunner implements Runnable {
        final long taskId;
        final boolean freshInsert;
        final AtomicBoolean started = new AtomicBoolean(false);
        final AtomicBoolean finished = new AtomicBoolean(false);
        volatile Future<?> future;

        TaskRunner(long taskId, boolean freshInsert) {
            this.taskId = taskId;
            this.freshInsert = freshInsert;
        }

        @Override
        public void run() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            try {
                executeTask(taskId, freshInsert);
            } catch (Throwable t) {
                Log.e(TAG, "Unexpected error in task " + taskId, t);
            } finally {
                finish();
            }
        }

        /** Cancels the task if it has not started yet. Returns true if it was canceled before running. */
        boolean cancelIfNotStarted() {
            if (started.compareAndSet(false, true)) {
                Future<?> f = future;
                if (f != null) {
                    f.cancel(false);
                }
                finish();
                return true;
            }
            return false;
        }

        void finish() {
            if (finished.compareAndSet(false, true)) {
                onTaskFinished(this);
            }
        }
    }

    private void startPendingInsert(DownloadTask pending) {
        activeCount.incrementAndGet(); // hold foreground while inserting
        try {
            dbExecutor.execute(() -> {
                try {
                    long id = downloadDao.insertTask(pending);
                    startTask(id, true);
                } catch (Exception e) {
                    Log.e(TAG, "Could not insert download task", e);
                } finally {
                    releaseSlot();
                }
            });
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "DB executor rejected insert", e);
            releaseSlot();
        }
    }

    private void startTask(long taskId, boolean freshInsert) {
        synchronized (runningTasks) {
            TaskRunner existing = runningTasks.get(taskId);
            if (existing != null) {
                // O-02: never run the same task twice concurrently. If the running one is being canceled,
                // restart it once it has fully stopped.
                if (Boolean.TRUE.equals(canceledTasks.get(taskId))) {
                    pendingRestart.add(taskId);
                }
                return;
            }
            canceledTasks.remove(taskId);
            TaskRunner runner = new TaskRunner(taskId, freshInsert);
            runningTasks.put(taskId, runner);
            activeCount.incrementAndGet();
            notifProgress.put(taskId, new int[]{0, 1});
            try {
                runner.future = downloadExecutor.submit(runner);
            } catch (RejectedExecutionException e) {
                Log.e(TAG, "Executor rejected task " + taskId, e);
                runner.cancelIfNotStarted();
                return;
            }
        }
        if (!freshInsert) {
            try {
                dbExecutor.execute(() -> downloadDao.markQueuedForRetry(taskId));
            } catch (RejectedExecutionException ignored) {
            }
        }
        scheduleNotificationRefresh();
    }

    private void onTaskFinished(TaskRunner runner) {
        long id = runner.taskId;
        boolean restart;
        synchronized (runningTasks) {
            runningTasks.remove(id, runner);
            canceledTasks.remove(id);
            activeProcesses.remove(id);
            activeHttpCalls.remove(id);
            fallbackUsed.remove(id);
            notifProgress.remove(id);
            notifTitles.remove(id);
            restart = pendingRestart.remove(id);
        }
        if (restart) {
            startTask(id, false);
        }
        releaseSlot();
    }

    private void releaseSlot() {
        int left = activeCount.decrementAndGet();
        if (left <= 0) {
            if (left < 0) {
                activeCount.set(0);
            }
            mainHandler.post(this::stopIfIdle);
        } else {
            scheduleNotificationRefresh();
        }
    }

    /** Leaves foreground, removes the progress notification and stops the service when nothing is running. */
    private void stopIfIdle() {
        if (activeCount.get() > 0) {
            return;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (notificationManager != null) {
            notificationManager.cancel(PROGRESS_NOTIF_ID);
        }
        stopSelfResult(lastStartId);
    }

    private boolean isCanceled(long taskId) {
        return Boolean.TRUE.equals(canceledTasks.get(taskId));
    }

    private void throwIfCanceled(long taskId) throws InterruptedException {
        if (isCanceled(taskId)) {
            throw new InterruptedException("Canceled");
        }
    }

    /** Re-throws {@code e} if it represents (or happened because of) a cancellation. */
    private void rethrowIfCanceled(long taskId, Exception e) throws Exception {
        if (e instanceof InterruptedException || isCanceled(taskId)) {
            throw e;
        }
    }

    private void handleCancel(long taskId) {
        synchronized (runningTasks) {
            pendingRestart.remove(taskId);
            TaskRunner runner = runningTasks.get(taskId);
            if (runner != null) {
                canceledTasks.put(taskId, true);
                if (!runner.cancelIfNotStarted()) {
                    // Running: stop the active work; the worker thread deletes its own work files.
                    abortActiveWork(taskId);
                }
            }
        }
        try {
            dbExecutor.execute(() -> downloadDao.markCanceledIfActive(taskId));
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void abortActiveWork(long taskId) {
        try {
            YoutubeDL.getInstance().destroyProcessById("ytdlp_task_" + taskId);
        } catch (Exception ignored) {
        }
        Call call = activeHttpCalls.remove(taskId);
        if (call != null) {
            call.cancel();
        }
        Process p = activeProcesses.remove(taskId);
        if (p != null) {
            try {
                p.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
    }

    private void executeTask(long taskId, boolean freshInsert) {
        DownloadTask task = downloadDao.getTaskByIdSync(taskId);
        if (task == null) {
            return;
        }

        // O-02: honor a cancel that arrived while the task was queued.
        if (isCanceled(taskId) || (freshInsert && task.getStatus() == DownloadTask.STATUS_CANCELED)) {
            task.setStatus(DownloadTask.STATUS_CANCELED);
            task.setSpeedAndEta("");
            downloadDao.updateTask(task);
            return;
        }

        EngineManager.ensureInitializedSync(getApplicationContext());

        final String originalFormatId = task.getFormatId();
        final String originalExtension = task.getExtension();
        final boolean originalDirectHttp = task.isDirectHttp();

        task.setStatus(DownloadTask.STATUS_DOWNLOADING);
        task.setProgress(0);
        task.setErrorMessage(null);
        task.setSpeedAndEta("...");
        downloadDao.updateTask(task);

        updateNotification(task, 0, true);

        File workDir = getInternalWorkDirectory();
        String workPrefix = "task_" + taskId;
        String ext = task.getExtension() != null && !task.getExtension().isEmpty()
                ? task.getExtension()
                : defaultExtensionFor(task);
        String formatId = task.getFormatId() != null ? task.getFormatId() : "";

        try {
            throwIfCanceled(taskId);
            runDownload(task, formatId, workDir, workPrefix, ext);
        } catch (Exception e) {
            // The worker (not handleCancel) owns cleanup of its work files.
            deleteTaskWorkFiles(getApplicationContext(), taskId);
            // Keep the user's original choice so "Retry" behaves the same as the first attempt.
            task.setFormatId(originalFormatId);
            task.setExtension(originalExtension);
            task.setDirectHttp(originalDirectHttp);
            if (isCanceled(taskId)) {
                task.setStatus(DownloadTask.STATUS_CANCELED);
                task.setSpeedAndEta("");
                downloadDao.updateTask(task);
                return;
            }
            Log.e(TAG, "Download error for task " + taskId, e);
            task.setStatus(DownloadTask.STATUS_FAILED);
            task.setSpeedAndEta("");
            task.setErrorMessage(friendlyError(e));
            downloadDao.updateTask(task);
            showCompletionNotification((int) taskId + RESULT_NOTIF_BASE,
                    getString(R.string.notif_failed_title, task.getTitle()), task.getErrorMessage());
        }
    }

    private static String defaultExtensionFor(DownloadTask task) {
        String urlExt = extensionFromUrl(task.getUrl());
        if (urlExt != null && IMAGE_EXTENSIONS.contains(urlExt)) {
            return urlExt;
        }
        return task.isAudioOnly() ? "mp3" : "mp4";
    }

    private static String extensionFromUrl(String url) {
        if (url == null) return null;
        try {
            String path = Uri.parse(url).getLastPathSegment();
            if (path == null) return null;
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot == path.length() - 1) return null;
            return path.substring(dot + 1).toLowerCase(Locale.US);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extensionOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    private static boolean isImageExt(String ext) {
        return ext != null && IMAGE_EXTENSIONS.contains(ext.toLowerCase(Locale.US));
    }

    /** Maps a Content-Type to a file extension (images first, then audio/video), or null when unknown/generic. */
    private static String extensionFromContentType(String contentType) {
        if (contentType == null) return null;
        String img = FormatUtils.imageExtFromContentType(contentType);
        return img != null ? img : FormatUtils.mediaExtFromContentType(contentType);
    }

    /**
     * After a direct HTTP download, fixes the work file's extension from the response Content-Type
     * (Y-01: images must never be saved as mp4/mp3). Returns the (possibly renamed) file.
     */
    private File fixExtensionFromContentType(DownloadTask task, File file, String contentType) {
        String ctExt = extensionFromContentType(contentType);
        String current = extensionOf(file);
        boolean isImage = (contentType != null && contentType.toLowerCase(Locale.US).startsWith("image/"))
                || isImageExt(current);
        String newExt = null;
        if (isImage) {
            if (ctExt != null && isImageExt(ctExt)) {
                newExt = ctExt;
            } else if (!isImageExt(current)) {
                newExt = "jpg";
            }
        } else if (ctExt != null && !ctExt.equals(current)) {
            String ct = contentType.toLowerCase(Locale.US);
            // Only trust audio/video types that match what the task expects.
            if ((task.isAudioOnly() && ct.startsWith("audio/")) || (!task.isAudioOnly() && ct.startsWith("video/"))) {
                newExt = ctExt;
            }
        }
        if (newExt == null || newExt.equals(current)) {
            if (isImage) task.setExtension(current);
            return file;
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File renamed = new File(file.getParentFile(), base + "." + newExt);
        if (renamed.exists()) {
            //noinspection ResultOfMethodCallIgnored
            renamed.delete();
        }
        if (file.renameTo(renamed)) {
            task.setExtension(newExt);
            return renamed;
        }
        return file;
    }

    /** Downloads a single direct stream (direct_url: / direct HTTP task) to the work dir and completes the task. */
    private void downloadDirect(DownloadTask task, String directUrl, File workDir, String workPrefix, String ext) throws Exception {
        long taskId = task.getId();
        if (!task.isAudioOnly() && "mp4".equalsIgnoreCase(ext)) {
            String companionAudio = BrowserFragment.findAudioForVideoUrl(directUrl);
            if (companionAudio != null && !companionAudio.isEmpty() && !companionAudio.equals(directUrl)) {
                downloadAndMuxStreams(task, directUrl, companionAudio, workDir, workPrefix);
                return;
            }
        }
        File outputFile = new File(workDir, workPrefix + "." + ext);
        String contentType = downloadStreamToFile(task, directUrl, outputFile, 0, 100);
        outputFile = fixExtensionFromContentType(task, outputFile, contentType);
        String finalExt = extensionOf(outputFile);
        if (isImageExt(finalExt)) {
            completeTask(task, outputFile);
            return;
        }
        if (task.isAudioOnly()) {
            outputFile = ensureAudioOnly(task, outputFile, workDir, workPrefix);
            completeTask(task, outputFile);
            return;
        }
        // If an MP4 video arrived without an audio track and BrowserFragment received its audio while downloading, mux it now
        if ("mp4".equalsIgnoreCase(finalExt) && !hasAudioTrack(outputFile)) {
            String lateAudio = BrowserFragment.findAudioForVideoUrl(directUrl);
            if (lateAudio != null && !lateAudio.isEmpty() && !lateAudio.equals(directUrl)) {
                File audioTemp = new File(workDir, workPrefix + "_late_atmp.m4a");
                File muxedFile = new File(workDir, workPrefix + "_muxed.mp4");
                try {
                    downloadStreamToFile(task, lateAudio, audioTemp, 90, 96);
                    throwIfCanceled(taskId);
                    if (audioTemp.exists() && audioTemp.length() > 256
                            && muxVideoAndAudioWithFfmpeg(taskId, outputFile, audioTemp, muxedFile)
                            && muxedFile.exists() && muxedFile.length() > 1024) {
                        //noinspection ResultOfMethodCallIgnored
                        outputFile.delete();
                        //noinspection ResultOfMethodCallIgnored
                        muxedFile.renameTo(outputFile);
                    }
                } catch (Exception e) {
                    rethrowIfCanceled(taskId, e);
                    Log.w(TAG, "Late audio mux failed; keeping video as downloaded", e);
                } finally {
                    if (audioTemp.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        audioTemp.delete();
                    }
                    if (muxedFile.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        muxedFile.delete();
                    }
                }
            }
        }
        completeTask(task, outputFile);
    }

    /**
     * O-09: an audio-only task must never save a full video renamed as .m4a. If the file contains a
     * video track, try to extract the audio with FFmpeg; otherwise fail with a clear message.
     */
    private File ensureAudioOnly(DownloadTask task, File file, File workDir, String workPrefix) throws Exception {
        if (!hasVideoTrack(file)) {
            return file;
        }
        long taskId = task.getId();
        if (!hasAudioTrack(file)) {
            throw new UserFacingException(str(R.string.error_no_audio_in_source));
        }
        File audioOut = new File(workDir, workPrefix + "_aonly.m4a");
        task.setSpeedAndEta(str(R.string.status_extracting_audio));
        downloadDao.updateTask(task);
        List<String> args = new ArrayList<>(Arrays.asList(
                "-y", "-i", file.getAbsolutePath(), "-vn", "-c:a", "aac", "-b:a", "192k",
                "-movflags", "+faststart", audioOut.getAbsolutePath()));
        boolean ok = runFfmpeg(taskId, args, audioOut, ffmpegTimeoutSeconds(10, file));
        throwIfCanceled(taskId);
        if (!ok || !audioOut.exists() || audioOut.length() <= 1024) {
            throw new UserFacingException(str(R.string.error_audio_extract_failed));
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        File finalAudio = new File(workDir, workPrefix + ".m4a");
        if (finalAudio.exists()) {
            //noinspection ResultOfMethodCallIgnored
            finalAudio.delete();
        }
        if (!audioOut.renameTo(finalAudio)) {
            finalAudio = audioOut;
        }
        task.setExtension("m4a");
        return finalAudio;
    }

    /** Dispatches a download by format id. Never recurses back into the yt-dlp fallback for prefixed ids. */
    private void runDownload(DownloadTask task, String formatId, File workDir, String workPrefix, String ext) throws Exception {
        long taskId = task.getId();
        if (formatId.startsWith("direct_url:")) {
            String directUrl = formatId.substring("direct_url:".length());
            downloadDirect(task, directUrl, workDir, workPrefix, ext);
        } else if (formatId.startsWith("image_audio_mux:")) {
            String payload = formatId.substring("image_audio_mux:".length());
            int sep = payload.indexOf('|');
            if (sep > 0) {
                String imageUrl = payload.substring(0, sep);
                String audioUrl = payload.substring(sep + 1);
                downloadAndMuxImageWithAudio(task, imageUrl, audioUrl, workDir, workPrefix);
            } else {
                File outputFile = new File(workDir, workPrefix + ".jpg");
                String contentType = downloadStreamToFile(task, payload, outputFile, 0, 100);
                outputFile = fixExtensionFromContentType(task, outputFile, contentType);
                completeTask(task, outputFile);
            }
        } else if (formatId.startsWith("newpipe_mux:")) {
            String payload = formatId.substring("newpipe_mux:".length());
            int sep = payload.indexOf('|');
            if (sep > 0) {
                String videoUrl = payload.substring(0, sep);
                String audioUrl = payload.substring(sep + 1);
                downloadAndMuxStreams(task, videoUrl, audioUrl, workDir, workPrefix);
            } else {
                File outputFile = new File(workDir, workPrefix + ".mp4");
                String contentType = downloadStreamToFile(task, payload, outputFile, 0, 100);
                outputFile = fixExtensionFromContentType(task, outputFile, contentType);
                completeTask(task, outputFile);
            }
        } else if (formatId.startsWith("ffmpeg_mp3:")) {
            String mediaUrl = formatId.substring("ffmpeg_mp3:".length());
            downloadAudioFromStream(task, mediaUrl, workDir, workPrefix);
        } else if (formatId.startsWith("ffmpeg_hls:")) {
            String hlsUrl = formatId.substring("ffmpeg_hls:".length());
            downloadHlsViaYtDlp(task, hlsUrl, workDir, workPrefix, ext);
        } else if (task.isDirectHttp() || FormatUtils.isDirectMediaUrl(task.getUrl())) {
            downloadDirect(task, task.getUrl(), workDir, workPrefix, ext);
        } else {
            throwIfCanceled(taskId);
            downloadViaYtDlpWithFallback(task, workDir, workPrefix, ext);
        }
    }

    /**
     * Downloads separate adaptive video + audio streams (e.g. YouTube 1080p/4K from NewPipe, Instagram DASH, or Reddit DASH)
     * and muxes them into a single MP4 container with guaranteed video and audio tracks using bundled FFmpeg 7.1.1.
     */
    private void downloadAndMuxStreams(DownloadTask task, String videoUrl, String audioUrl, File dir, String fileBaseName) throws Exception {
        File videoTemp = new File(dir, fileBaseName + "_vtmp.mp4");
        File audioTemp = new File(dir, fileBaseName + "_atmp.m4a");
        File finalFile = new File(dir, fileBaseName + ".mp4");
        long taskId = task.getId();

        try {
            // 1. Download video stream (0% -> 75%)
            throwIfCanceled(taskId);
            downloadStreamToFile(task, videoUrl, videoTemp, 0, 75);
            throwIfCanceled(taskId);

            // If the video stream already contains a valid built-in audio track (e.g. progressive video_versions),
            // preserve it directly unless it has no audio track.
            if (hasVideoTrack(videoTemp) && hasAudioTrack(videoTemp)) {
                if (finalFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    finalFile.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                videoTemp.renameTo(finalFile);
                completeTask(task, finalFile);
                return;
            }

            // 2. Download audio stream (75% -> 94%)
            boolean hasAudio = false;
            boolean audioExpected = audioUrl != null && !audioUrl.isEmpty() && !audioUrl.equals(videoUrl);
            try {
                if (audioExpected) {
                    throwIfCanceled(taskId);
                    downloadStreamToFile(task, audioUrl, audioTemp, 75, 94);
                    hasAudio = audioTemp.exists() && audioTemp.length() > 256;
                }
            } catch (Exception audioEx) {
                rethrowIfCanceled(taskId, audioEx);
                Log.w(TAG, "Audio track download warning, continuing with video track", audioEx);
            }

            // 3. Mux video + audio into finalFile (94% -> 100%)
            throwIfCanceled(taskId);
            task.setProgress(95);
            task.setSpeedAndEta(str(R.string.status_merging_ffmpeg));
            downloadDao.updateTask(task);
            updateNotification(task, 95, false);

            String warning = null;
            if (hasAudio) {
                // Primary muxer: Bundled FFmpeg 7.1.1 (handles Shaka Packager CMAF fMP4, xHE-AAC, HE-AAC, VP9, H.264 natively at ~60x speed)
                boolean muxSuccess = muxVideoAndAudioWithFfmpeg(taskId, videoTemp, audioTemp, finalFile);
                throwIfCanceled(taskId);

                // Fallback to native Android MediaMuxer if FFmpeg binary was unavailable
                if (!muxSuccess || !finalFile.exists() || finalFile.length() < 1024) {
                    if (finalFile.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        finalFile.delete();
                    }
                    muxSuccess = muxVideoAndAudioNative(taskId, videoTemp, audioTemp, finalFile);
                    throwIfCanceled(taskId);
                }

                if (!muxSuccess || !finalFile.exists() || finalFile.length() < 1024) {
                    // O-04: don't report silent success - keep the video but flag the missing audio.
                    Log.w(TAG, "Muxing was not successful or produced incomplete file, falling back to video track");
                    if (finalFile.exists()) finalFile.delete();
                    //noinspection ResultOfMethodCallIgnored
                    videoTemp.renameTo(finalFile);
                    warning = str(R.string.warn_mux_no_audio);
                }
            } else {
                if (finalFile.exists()) finalFile.delete();
                //noinspection ResultOfMethodCallIgnored
                videoTemp.renameTo(finalFile);
                if (audioExpected) {
                    warning = str(R.string.warn_audio_download_failed);
                }
            }

            completeTask(task, finalFile, warning);
        } finally {
            if (videoTemp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                videoTemp.delete();
            }
            if (audioTemp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                audioTemp.delete();
            }
        }
    }

    /**
     * Downloads a Story/Post photo image and its companion music/audio stream,
     * and synthesizes an H.264 + AAC MP4 video file using bundled FFmpeg 7.1.1.
     */
    private void downloadAndMuxImageWithAudio(DownloadTask task, String imageUrl, String audioUrl, File dir, String fileBaseName) throws Exception {
        File imgTemp = new File(dir, fileBaseName + "_itmp.jpg");
        File audioTemp = new File(dir, fileBaseName + "_atmp.m4a");
        File finalFile = new File(dir, fileBaseName + ".mp4");

        long taskId = task.getId();
        try {
            throwIfCanceled(taskId);
            String imageContentType = downloadStreamToFile(task, imageUrl, imgTemp, 0, 50);
            boolean hasAudio = false;
            if (audioUrl != null && !audioUrl.isEmpty()) {
                try {
                    throwIfCanceled(taskId);
                    downloadStreamToFile(task, audioUrl, audioTemp, 50, 88);
                    hasAudio = audioTemp.exists() && audioTemp.length() > 256;
                } catch (Exception e) {
                    rethrowIfCanceled(taskId, e);
                    Log.w(TAG, "Audio download failed in downloadAndMuxImageWithAudio", e);
                }
            }

            throwIfCanceled(taskId);
            task.setProgress(92);
            task.setSpeedAndEta(str(R.string.status_creating_music_video));
            downloadDao.updateTask(task);
            updateNotification(task, 92, false);

            if (hasAudio && imgTemp.exists() && imgTemp.length() > 128
                    && createVideoFromImageAndAudioWithFfmpeg(taskId, imgTemp, audioTemp, finalFile)
                    && finalFile.exists() && finalFile.length() > 1024) {
                task.setExtension("mp4");
                completeTask(task, finalFile);
            } else if (imgTemp.exists() && imgTemp.length() > 128) {
                throwIfCanceled(taskId);
                String imgExt = extensionFromContentType(imageContentType);
                if (imgExt == null || !isImageExt(imgExt)) imgExt = "jpg";
                File imgFinal = new File(dir, fileBaseName + "." + imgExt);
                //noinspection ResultOfMethodCallIgnored
                imgTemp.renameTo(imgFinal);
                task.setExtension(imgExt);
                completeTask(task, imgFinal, hasAudio ? str(R.string.warn_music_failed) : null);
            } else {
                throw new UserFacingException(str(R.string.error_photo_failed));
            }
        } finally {
            if (imgTemp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                imgTemp.delete();
            }
            if (audioTemp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                audioTemp.delete();
            }
        }
    }

    private File getFfmpegBinary() {
        String nativeDir = getApplicationContext().getApplicationInfo().nativeLibraryDir;
        if (nativeDir == null) return null;
        File bin = new File(nativeDir, "libffmpeg.so");
        return (bin.exists() && bin.canExecute()) ? bin : null;
    }

    private String getFfmpegLdLibraryPath() {
        File noBackup = getApplicationContext().getNoBackupFilesDir();
        File ffmpegLib = new File(noBackup, "youtubedl-android/packages/ffmpeg/usr/lib");
        File pythonLib = new File(noBackup, "youtubedl-android/packages/python/usr/lib");
        StringBuilder sb = new StringBuilder();
        if (ffmpegLib.exists()) {
            sb.append(ffmpegLib.getAbsolutePath());
        }
        if (pythonLib.exists()) {
            if (sb.length() > 0) sb.append(":");
            sb.append(pythonLib.getAbsolutePath());
        }
        String nativeDir = getApplicationContext().getApplicationInfo().nativeLibraryDir;
        if (nativeDir != null && !nativeDir.isEmpty()) {
            if (sb.length() > 0) sb.append(":");
            sb.append(nativeDir);
        }
        return sb.toString();
    }

    /** Timeout for an FFmpeg run: 120 s base plus {@code secPerMb} seconds per MB of input. */
    private static long ffmpegTimeoutSeconds(int secPerMb, File... inputs) {
        long bytes = 0;
        for (File f : inputs) {
            if (f != null && f.exists()) bytes += f.length();
        }
        return 120L + (bytes / (1024L * 1024L)) * secPerMb;
    }

    /**
     * O-03: runs FFmpeg with output drained on a separate thread, a size-scaled timeout and the Process
     * registered per task so cancel can destroy it. Returns true on exit code 0 and a non-trivial output.
     * Throws InterruptedException when the task is canceled.
     */
    private boolean runFfmpeg(long taskId, List<String> args, File outputFile, long timeoutSec) throws InterruptedException {
        File ffmpegBin = getFfmpegBinary();
        if (ffmpegBin == null) {
            return false;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegBin.getAbsolutePath());
        cmd.addAll(args);
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LD_LIBRARY_PATH", getFfmpegLdLibraryPath());
            pb.redirectErrorStream(true);
            process = pb.start();
            activeProcesses.put(taskId, process);
            final InputStream in = process.getInputStream();
            Thread drainer = new Thread(() -> {
                byte[] buf = new byte[4096];
                try {
                    //noinspection StatementWithEmptyBody
                    while (in.read(buf) != -1) {
                    }
                } catch (IOException ignored) {
                    // stream closed when the process is destroyed
                }
            }, "ffmpeg-drain-" + taskId);
            drainer.setDaemon(true);
            drainer.start();

            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            while (!process.waitFor(1, TimeUnit.SECONDS)) {
                if (isCanceled(taskId)) {
                    process.destroyForcibly();
                    throw new InterruptedException("Canceled");
                }
                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "FFmpeg timed out after " + timeoutSec + "s for task " + taskId);
                    process.destroyForcibly();
                    return false;
                }
            }
            throwIfCanceled(taskId);
            return process.exitValue() == 0 && outputFile.exists() && outputFile.length() > 1024;
        } catch (IOException e) {
            Log.e(TAG, "FFmpeg start/run error", e);
            return false;
        } finally {
            if (process != null) {
                activeProcesses.remove(taskId, process);
                try {
                    process.destroy();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean muxVideoAndAudioWithFfmpeg(long taskId, File videoFile, File audioFile, File outputFile) throws InterruptedException {
        if (videoFile == null || !videoFile.exists() || audioFile == null || !audioFile.exists()) {
            return false;
        }
        List<String> args = new ArrayList<>(Arrays.asList(
                "-y",
                "-i", videoFile.getAbsolutePath(),
                "-i", audioFile.getAbsolutePath(),
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "copy",
                "-c:a", "aac",
                "-b:a", "192k",
                "-shortest",
                "-movflags", "+faststart",
                outputFile.getAbsolutePath()));
        return runFfmpeg(taskId, args, outputFile, ffmpegTimeoutSeconds(2, videoFile, audioFile));
    }

    private boolean createVideoFromImageAndAudioWithFfmpeg(long taskId, File imageFile, File audioFile, File outputFile) throws InterruptedException {
        if (imageFile == null || !imageFile.exists() || audioFile == null || !audioFile.exists()) {
            return false;
        }
        List<String> args = new ArrayList<>(Arrays.asList(
                "-y",
                "-loop", "1",
                "-framerate", "2",
                "-i", imageFile.getAbsolutePath(),
                "-i", audioFile.getAbsolutePath(),
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-tune", "stillimage",
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p",
                "-c:a", "aac",
                "-b:a", "192k",
                "-shortest",
                "-movflags", "+faststart",
                outputFile.getAbsolutePath()));
        return runFfmpeg(taskId, args, outputFile, 60 + ffmpegTimeoutSeconds(30, audioFile));
    }

    private boolean hasVideoTrack(File file) {
        if (file == null || !file.exists()) return false;
        MediaExtractor ext = new MediaExtractor();
        try {
            ext.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < ext.getTrackCount(); i++) {
                MediaFormat f = ext.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { ext.release(); } catch (Exception ignored) {}
        }
        return false;
    }

    private boolean hasAudioTrack(File file) {
        if (file == null || !file.exists()) return false;
        MediaExtractor ext = new MediaExtractor();
        try {
            ext.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < ext.getTrackCount(); i++) {
                MediaFormat f = ext.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { ext.release(); } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Hardware-assisted zero-reencode MP4 muxer using Android's MediaExtractor & MediaMuxer.
     * Interleaves video and audio samples in monotonic presentation timestamp order.
     */
    @SuppressLint("WrongConstant")
    private boolean muxVideoAndAudioNative(long taskId, File videoFile, File audioFile, File outputFile) {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            videoExtractor.setDataSource(videoFile.getAbsolutePath());
            audioExtractor.setDataSource(audioFile.getAbsolutePath());

            MediaFormat videoFormat = null;
            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                MediaFormat format = videoExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoExtractor.selectTrack(i);
                    videoFormat = format;
                    break;
                }
            }

            MediaFormat audioFormat = null;
            for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                MediaFormat format = audioExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioExtractor.selectTrack(i);
                    audioFormat = format;
                    break;
                }
            }

            if (videoFormat == null) {
                return false;
            }

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxVideoTrack = muxer.addTrack(videoFormat);
            int muxAudioTrack = -1;
            if (audioFormat != null) {
                try {
                    muxAudioTrack = muxer.addTrack(audioFormat);
                } catch (Exception e) {
                    Log.w(TAG, "Audio track incompatible with MP4 muxer", e);
                    muxAudioTrack = -1;
                }
            }

            if (audioFormat != null && muxAudioTrack == -1) {
                return false;
            }

            muxer.start();

            // O-04: size the sample buffer from KEY_MAX_INPUT_SIZE (4K samples can exceed 1 MB); at least 4 MB.
            int bufferSize = 4 * 1024 * 1024;
            if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                bufferSize = Math.max(bufferSize, videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            if (audioFormat != null && audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                bufferSize = Math.max(bufferSize, audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            boolean videoDone = false;
            boolean audioDone = (muxAudioTrack == -1);
            long lastVideoPts = -1;
            long lastAudioPts = -1;
            int sampleCounter = 0;

            while (!videoDone || !audioDone) {
                if ((++sampleCounter & 0xFF) == 0 && isCanceled(taskId)) {
                    return false;
                }
                long videoTime = videoDone ? Long.MAX_VALUE : videoExtractor.getSampleTime();
                long audioTime = audioDone ? Long.MAX_VALUE : audioExtractor.getSampleTime();

                if (videoTime < 0) videoDone = true;
                if (audioTime < 0) audioDone = true;

                if (videoDone && audioDone) break;

                // Write whichever sample has the earlier presentation timestamp for clean MPEG-4 interleaving
                if (!videoDone && (audioDone || videoTime <= audioTime)) {
                    int sampleSize = videoExtractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        videoDone = true;
                    } else {
                        bufferInfo.offset = 0;
                        bufferInfo.size = sampleSize;
                        long pts = Math.max(0, videoTime);
                        if (pts < lastVideoPts) pts = lastVideoPts + 1;
                        lastVideoPts = pts;
                        bufferInfo.presentationTimeUs = pts;
                        int rawFlags = videoExtractor.getSampleFlags();
                        bufferInfo.flags = ((rawFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        muxer.writeSampleData(muxVideoTrack, buffer, bufferInfo);
                        videoExtractor.advance();
                    }
                } else if (!audioDone) {
                    int sampleSize = audioExtractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        audioDone = true;
                    } else {
                        bufferInfo.offset = 0;
                        bufferInfo.size = sampleSize;
                        long pts = Math.max(0, audioTime);
                        if (pts < lastAudioPts) pts = lastAudioPts + 1;
                        lastAudioPts = pts;
                        bufferInfo.presentationTimeUs = pts;
                        int rawFlags = audioExtractor.getSampleFlags();
                        bufferInfo.flags = ((rawFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        muxer.writeSampleData(muxAudioTrack, buffer, bufferInfo);
                        audioExtractor.advance();
                    }
                }
            }

            muxer.stop();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Native MediaMuxer failed", e);
            return false;
        } finally {
            try {
                videoExtractor.release();
            } catch (Exception ignored) {
            }
            try {
                audioExtractor.release();
            } catch (Exception ignored) {
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean transcodeAudioToAac(File inputFile, File outputFile) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(inputFile.getAbsolutePath());
            int trackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    inputFormat = f;
                    extractor.selectTrack(i);
                    break;
                }
            }
            if (trackIndex == -1 || inputFormat == null) return false;

            String inputMime = inputFormat.getString(MediaFormat.KEY_MIME);
            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channelCount = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            decoder = MediaCodec.createDecoderByType(inputMime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            MediaFormat outputFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            outputFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            outputFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] muxerTrackIndex = {-1};
            boolean[] muxerStarted = {false};

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean extractorDone = false;
            boolean decoderDone = false;
            boolean encoderDone = false;

            long timeoutUs = 5000L;
            int maxLoopsWithoutProgress = 5000;
            int loopsWithoutProgress = 0;

            while (!encoderDone && loopsWithoutProgress < maxLoopsWithoutProgress) {
                boolean madeProgress = false;

                // 1. Feed extractor to decoder
                if (!extractorDone) {
                    int inIndex = decoder.dequeueInputBuffer(timeoutUs);
                    if (inIndex >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(inIndex);
                        if (buf != null) {
                            buf.clear();
                            int sampleSize = extractor.readSampleData(buf, 0);
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                extractorDone = true;
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                            madeProgress = true;
                        }
                    }
                }

                // 2. Pull from decoder and feed to encoder
                if (!decoderDone) {
                    int outIndex = decoder.dequeueOutputBuffer(info, timeoutUs);
                    if (outIndex >= 0) {
                        ByteBuffer outBuf = decoder.getOutputBuffer(outIndex);
                        if (outBuf != null && info.size > 0) {
                            int encInIndex = encoder.dequeueInputBuffer(timeoutUs);
                            if (encInIndex >= 0) {
                                ByteBuffer encBuf = encoder.getInputBuffer(encInIndex);
                                if (encBuf != null) {
                                    encBuf.clear();
                                    outBuf.position(info.offset);
                                    outBuf.limit(info.offset + info.size);
                                    encBuf.put(outBuf);
                                    encoder.queueInputBuffer(encInIndex, 0, info.size, info.presentationTimeUs, 0);
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            decoderDone = true;
                            int encInIndex = encoder.dequeueInputBuffer(timeoutUs);
                            if (encInIndex >= 0) {
                                encoder.queueInputBuffer(encInIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            }
                        }
                        madeProgress = true;
                    }
                }

                // 3. Pull from encoder and write to muxer
                int encOutIndex = encoder.dequeueOutputBuffer(info, timeoutUs);
                if (encOutIndex >= 0) {
                    ByteBuffer encOutBuf = encoder.getOutputBuffer(encOutIndex);
                    if (encOutBuf != null) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            info.size = 0;
                        }
                        if (info.size > 0 && muxerStarted[0]) {
                            encOutBuf.position(info.offset);
                            encOutBuf.limit(info.offset + info.size);
                            muxer.writeSampleData(muxerTrackIndex[0], encOutBuf, info);
                        }
                        encoder.releaseOutputBuffer(encOutIndex, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true;
                        }
                        madeProgress = true;
                    }
                } else if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = encoder.getOutputFormat();
                    muxerTrackIndex[0] = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted[0] = true;
                    madeProgress = true;
                }

                if (madeProgress) {
                    loopsWithoutProgress = 0;
                } else {
                    loopsWithoutProgress++;
                }
            }

            if (muxerStarted[0]) {
                muxer.stop();
            }
            return encoderDone;
        } catch (Exception e) {
            Log.e(TAG, "Audio transcode to AAC failed", e);
            return false;
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
            try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
            try { if (muxer != null) { muxer.release(); } } catch (Exception ignored) {}
        }
    }

    private void downloadAudioFromStream(DownloadTask task, String streamUrl, File dir, String fileBaseName) throws Exception {
        long taskId = task.getId();
        // First try yt-dlp + FFmpeg to convert to MP3
        if (EngineManager.isYtdlpReady()) {
            File cookieFile = null;
            try {
                throwIfCanceled(taskId);
                String processId = "ytdlp_task_" + taskId;
                File outputTemplate = new File(dir, fileBaseName + ".%(ext)s");
                YoutubeDLRequest request = new YoutubeDLRequest(streamUrl);
                cookieFile = HybridExtractor.configureYtDlpRequestWithCookies(getApplicationContext(), request, streamUrl);
                request.addOption("-o", outputTemplate.getAbsolutePath());
                request.addOption("-x");
                request.addOption("--audio-format", "mp3");
                request.addOption("--audio-quality", "0");

                final long[] lastUpdateMs = {0L};
                YoutubeDL.getInstance().execute(request, processId, (progress, etaInSeconds, line) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastUpdateMs[0] > 500 && !isCanceled(taskId)) {
                        lastUpdateMs[0] = now;
                        int pct = Math.max(0, Math.min(100, Math.round(progress)));
                        task.setProgress(pct);
                        task.setSpeedAndEta(str(R.string.status_converting_mp3));
                        downloadDao.updateTask(task);
                        updateNotification(task, pct, false);
                    }
                    return Unit.INSTANCE;
                });
                throwIfCanceled(taskId);

                File finalFile = findOutputFile(dir, fileBaseName, "mp3");
                if (finalFile != null && finalFile.exists() && finalFile.length() > 0) {
                    task.setExtension(extensionOf(finalFile));
                    completeTask(task, finalFile);
                    return;
                }
            } catch (Exception e) {
                rethrowIfCanceled(taskId, e);
                Log.w(TAG, "yt-dlp MP3 conversion failed, falling back to direct audio download", e);
                deleteTaskWorkFiles(getApplicationContext(), taskId);
            } finally {
                CookieBridge.deleteQuietly(cookieFile);
            }
        }

        // Fallback: download the stream directly. Extension comes from Content-Type; if the stream is
        // actually a full video, extract its audio or fail (O-09) - never save an MP4 renamed as .m4a.
        throwIfCanceled(taskId);
        File fallbackFile = new File(dir, fileBaseName + ".m4a");
        String contentType = downloadStreamToFile(task, streamUrl, fallbackFile, 0, 100);
        String ct = contentType != null ? contentType.toLowerCase(Locale.US) : "";
        if (ct.startsWith("audio/")) {
            boolean prevAudioOnly = task.isAudioOnly();
            task.setAudioOnly(true); // in-memory only, so the audio Content-Type is trusted for the extension
            try {
                fallbackFile = fixExtensionFromContentType(task, fallbackFile, contentType);
            } finally {
                task.setAudioOnly(prevAudioOnly);
            }
        }
        fallbackFile = ensureAudioOnly(task, fallbackFile, dir, fileBaseName);
        task.setExtension(extensionOf(fallbackFile));
        completeTask(task, fallbackFile);
    }

    private void downloadHlsViaYtDlp(DownloadTask task, String hlsUrl, File dir, String fileBaseName, String ext) throws Exception {
        long taskId = task.getId();
        String processId = "ytdlp_task_" + taskId;
        File outputTemplate = new File(dir, fileBaseName + ".%(ext)s");
        YoutubeDLRequest request = new YoutubeDLRequest(hlsUrl);
        File cookieFile = HybridExtractor.configureYtDlpRequestWithCookies(getApplicationContext(), request, hlsUrl);
        try {
            request.addOption("-o", outputTemplate.getAbsolutePath());
            request.addOption("--merge-output-format", "mp4");

            final long[] lastUpdateMs = {0L};
            YoutubeDL.getInstance().execute(request, processId, (progress, etaInSeconds, line) -> {
                long now = System.currentTimeMillis();
                if (now - lastUpdateMs[0] > 500 && !isCanceled(taskId)) {
                    lastUpdateMs[0] = now;
                    int pct = Math.max(0, Math.min(100, Math.round(progress)));
                    task.setProgress(pct);
                    task.setSpeedAndEta(str(R.string.status_downloading_hls));
                    downloadDao.updateTask(task);
                    updateNotification(task, pct, false);
                }
                return Unit.INSTANCE;
            });
        } finally {
            CookieBridge.deleteQuietly(cookieFile);
        }
        throwIfCanceled(taskId);

        File finalFile = findOutputFile(dir, fileBaseName, ext);
        if (finalFile != null && finalFile.exists()) {
            completeTask(task, finalFile);
        } else {
            throw new UserFacingException(str(R.string.error_stream_no_output));
        }
    }

    private void downloadViaYtDlpWithFallback(DownloadTask task, File dir, String fileBaseName, String ext) throws Exception {
        long taskId = task.getId();
        try {
            downloadViaYtDlp(task, dir, fileBaseName, ext);
        } catch (Exception ytdlpErr) {
            rethrowIfCanceled(taskId, ytdlpErr);
            // Y-02: the NewPipe/social fallback runs at most once per task run.
            if (!fallbackUsed.add(taskId)) {
                throw ytdlpErr;
            }
            Log.w(TAG, "yt-dlp execution failed, attempting SocialPlatformExtractor self-healing fallback", ytdlpErr);
            AnalyzedMedia fallback = null;
            try {
                SocialPlatformExtractor social = new SocialPlatformExtractor(getApplicationContext(), httpClient);
                String url = task.getUrl();
                if (SocialPlatformExtractor.isYouTubeUrl(url)) {
                    fallback = social.extractYouTubeWithNewPipe(url);
                } else if (SocialPlatformExtractor.isTwitterUrl(url)) {
                    fallback = social.extractTwitterOrX(url);
                } else if (SocialPlatformExtractor.isInstagramUrl(url)) {
                    fallback = social.extractInstagram(url);
                } else if (SocialPlatformExtractor.isTikTokUrl(url)) {
                    fallback = social.extractTikTok(url);
                } else if (SocialPlatformExtractor.isRedditUrl(url)) {
                    fallback = social.extractReddit(url);
                }
            } catch (Exception fbErr) {
                Log.w(TAG, "Fallback extraction failed", fbErr);
            }
            throwIfCanceled(taskId);

            MediaFormatOption chosen = pickFallbackOption(fallback, task.isAudioOnly());
            if (chosen == null) {
                throw ytdlpErr;
            }
            deleteTaskWorkFiles(getApplicationContext(), taskId);
            task.setProgress(0);
            task.setSpeedAndEta(str(R.string.status_trying_fallback));
            downloadDao.updateTask(task);
            String fbExt = chosen.getExtension() != null && !chosen.getExtension().isEmpty()
                    ? chosen.getExtension().toLowerCase(Locale.US) : ext;
            // Run the fallback directly (no executeTask recursion, prefixed ids never reach yt-dlp again).
            // The user's formatId is not overwritten in the DB, so Retry starts from the original selection.
            runDownload(task, chosen.getFormatId(), dir, fileBaseName, fbExt);
        }
    }

    /** Picks the first direct_url:/newpipe_mux: option matching the task's audio/video kind (never images). */
    private static MediaFormatOption pickFallbackOption(AnalyzedMedia media, boolean audioOnly) {
        if (media == null || media.getFormatOptions() == null) return null;
        for (MediaFormatOption opt : media.getFormatOptions()) {
            String id = opt.getFormatId();
            if (id == null) continue;
            boolean allowedPrefix = id.startsWith("direct_url:") || (!audioOnly && id.startsWith("newpipe_mux:"));
            if (!allowedPrefix || opt.isAudioOnly() != audioOnly) continue;
            if (isImageExt(opt.getExtension())) continue;
            return opt;
        }
        return null;
    }

    private void downloadViaYtDlp(DownloadTask task, File dir, String fileBaseName, String ext) throws Exception {
        long taskId = task.getId();
        String processId = "ytdlp_task_" + taskId;
        File outputTemplate = new File(dir, fileBaseName + ".%(ext)s");

        YoutubeDLRequest request = new YoutubeDLRequest(task.getUrl());
        File cookieFile = HybridExtractor.configureYtDlpRequestWithCookies(getApplicationContext(), request, task.getUrl());
        try {
            request.addOption("-o", outputTemplate.getAbsolutePath());

            if (task.isAudioOnly()) {
                request.addOption("-x");
                if ("mp3".equalsIgnoreCase(ext)) {
                    request.addOption("--audio-format", "mp3");
                    request.addOption("--audio-quality", "0");
                } else {
                    request.addOption("-f", task.getFormatId() != null ? task.getFormatId() : "bestaudio");
                }
            } else {
                String formatSelector = task.getFormatId() != null && !task.getFormatId().isEmpty()
                        ? task.getFormatId()
                        : "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best";
                request.addOption("-f", formatSelector);
                request.addOption("--merge-output-format", "mp4");
            }

            final long[] lastUpdateMs = {0L};
            YoutubeDL.getInstance().execute(request, processId, (progress, etaInSeconds, line) -> {
                long now = System.currentTimeMillis();
                if (now - lastUpdateMs[0] > 500 && !isCanceled(taskId)) {
                    lastUpdateMs[0] = now;
                    int pct = Math.max(0, Math.min(100, Math.round(progress)));
                    String etaStr = etaInSeconds != null && etaInSeconds > 0
                            ? str(R.string.eta_remaining) + " " + FormatUtils.formatDuration(etaInSeconds)
                            : "yt-dlp";
                    task.setProgress(pct);
                    task.setSpeedAndEta(etaStr);
                    downloadDao.updateTask(task);
                    updateNotification(task, pct, false);
                }
                return Unit.INSTANCE;
            });
        } finally {
            CookieBridge.deleteQuietly(cookieFile);
        }
        throwIfCanceled(taskId);

        File finalFile = findOutputFile(dir, fileBaseName, ext);
        if (finalFile != null && finalFile.exists()) {
            completeTask(task, finalFile);
        } else {
            throw new UserFacingException(str(R.string.error_no_output));
        }
    }

    /**
     * Downloads {@code streamUrl} into {@code outputFile}. O-10: rejects non-2xx and HTML responses and
     * verifies the byte count against Content-Length. Returns the response Content-Type (may be null).
     */
    private String downloadStreamToFile(DownloadTask task, String streamUrl, File outputFile, int startPct, int endPct) throws Exception {
        long taskId = task.getId();
        throwIfCanceled(taskId);
        Request.Builder reqBuilder = new Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*");

        String cookie = CookieBridge.getCookieHeaderForUrl(streamUrl);
        if (cookie != null && !cookie.isEmpty()) {
            reqBuilder.header("Cookie", cookie);
        }
        if (streamUrl.contains("instagram") || streamUrl.contains("cdninstagram") || streamUrl.contains("fbcdn")) {
            reqBuilder.header("Referer", "https://www.instagram.com/");
        } else if (streamUrl.contains("twimg.com") || streamUrl.contains("twitter.com")) {
            reqBuilder.header("Referer", "https://x.com/");
        } else if (streamUrl.contains("tiktok") || streamUrl.contains("tikwm")) {
            reqBuilder.header("Referer", "https://www.tiktok.com/");
        }

        Call call = httpClient.newCall(reqBuilder.build());
        activeHttpCalls.put(taskId, call);
        // A cancel may have arrived between the check above and registering the call.
        if (isCanceled(taskId)) {
            call.cancel();
        }

        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new HttpStatusException(response.code());
            }
            if (response.body() == null) {
                throw new UserFacingException(str(R.string.error_empty_response));
            }

            String contentType = response.header("Content-Type");
            if (contentType == null && response.body().contentType() != null) {
                contentType = response.body().contentType().toString();
            }
            String ctLower = contentType != null ? contentType.toLowerCase(Locale.US) : "";
            if (ctLower.startsWith("text/html") || ctLower.startsWith("application/xhtml")) {
                throw new UserFacingException(str(R.string.error_html_response));
            }

            long totalBytes = response.body().contentLength();
            long downloadedBytes = 0;
            long startTime = System.currentTimeMillis();
            long lastUpdate = 0;
            int pctSpan = Math.max(1, endPct - startPct);

            try (InputStream in = response.body().byteStream();
                 FileOutputStream out = new FileOutputStream(outputFile)) {

                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    throwIfCanceled(taskId);
                    out.write(buffer, 0, read);
                    downloadedBytes += read;

                    long now = System.currentTimeMillis();
                    if (now - lastUpdate > 350) {
                        lastUpdate = now;
                        int pct = totalBytes > 0
                                ? startPct + (int) ((downloadedBytes * pctSpan) / totalBytes)
                                : startPct + (pctSpan / 2);
                        long elapsedSec = Math.max(1, (now - startTime) / 1000);
                        long bytesPerSec = downloadedBytes / elapsedSec;
                        String speed = FormatUtils.formatFileSize(bytesPerSec) + "/s";

                        task.setProgress(pct);
                        task.setSpeedAndEta(speed);
                        downloadDao.updateTask(task);
                        updateNotification(task, pct, totalBytes <= 0);
                    }
                }
                out.flush();
            }
            throwIfCanceled(taskId);
            if (downloadedBytes <= 0) {
                throw new UserFacingException(str(R.string.error_empty_file));
            }
            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                throw new UserFacingException(str(R.string.error_incomplete, downloadedBytes, totalBytes));
            }
            return contentType;
        } finally {
            activeHttpCalls.remove(taskId, call);
        }
    }

    private void completeTask(DownloadTask task, File workFile) throws Exception {
        completeTask(task, workFile, null);
    }

    /**
     * Moves the finished work file to the target directory and marks the task COMPLETED.
     * Y-05: fails if the file is missing/empty and never overwrites a cancellation.
     *
     * @param warning optional user-facing note stored in errorMessage (e.g. "no audio", O-04)
     */
    private void completeTask(DownloadTask task, File workFile, @Nullable String warning) throws Exception {
        long taskId = task.getId();
        throwIfCanceled(taskId);
        if (workFile == null || !workFile.exists() || workFile.length() == 0) {
            throw new UserFacingException(str(R.string.error_file_missing));
        }

        File finalFile = moveWorkFileToTargetDirectory(task, workFile);
        if (!finalFile.exists() || finalFile.length() == 0) {
            throw new UserFacingException(str(R.string.error_file_missing));
        }
        if (isCanceled(taskId)) {
            //noinspection ResultOfMethodCallIgnored
            finalFile.delete();
            throw new InterruptedException("Canceled");
        }

        Log.i(TAG, "DOWNLOAD_COMPLETED: id=" + taskId
                + " | file=" + finalFile.getAbsolutePath()
                + " | bytes=" + finalFile.length());

        String finalExt = extensionOf(finalFile);
        if (!finalExt.isEmpty()) {
            task.setExtension(finalExt);
        }
        task.setStatus(DownloadTask.STATUS_COMPLETED);
        task.setProgress(100);
        task.setFilePath(finalFile.getAbsolutePath());
        task.setFileSize(finalFile.length());
        task.setErrorMessage(warning);
        task.setSpeedAndEta(FormatUtils.formatFileSize(finalFile.length()));
        downloadDao.updateTask(task);

        MediaScannerConnection.scanFile(
                getApplicationContext(),
                new String[]{finalFile.getAbsolutePath()},
                null,
                null
        );

        exportToPublicMediaStore(finalFile, task);

        showCompletionNotification((int) taskId + RESULT_NOTIF_BASE,
                getString(R.string.notif_completed_title, task.getTitle()), warning);
    }

    /**
     * O-01: final name is "<title>.<ext>" (trailing media extension stripped from the title); on collision
     * "<title> (1).<ext>", "<title> (2).<ext>" ... Existing files are never overwritten.
     */
    private File moveWorkFileToTargetDirectory(DownloadTask task, File workFile) throws Exception {
        File targetDir = getTargetDirectory();
        if (!targetDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            targetDir.mkdirs();
        }
        String actualExt = extensionOf(workFile);
        if (actualExt.isEmpty()) {
            actualExt = defaultExtensionFor(task);
        }
        String cleanBase = FormatUtils.sanitizeFileName(FormatUtils.stripMediaExtension(task.getTitle()));
        cleanBase = FormatUtils.stripMediaExtension(cleanBase);
        if (cleanBase == null || cleanBase.trim().isEmpty()) {
            cleanBase = "VBKTube_" + task.getId();
        }

        File destFile = reserveUniqueFile(targetDir, cleanBase.trim(), actualExt);
        if (workFile.renameTo(destFile) && destFile.exists() && destFile.length() > 0) {
            deleteTaskWorkFiles(getApplicationContext(), task.getId());
            return destFile;
        }
        try {
            try (InputStream in = new java.io.FileInputStream(workFile);
                 OutputStream out = new FileOutputStream(destFile)) {
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    if (isCanceled(task.getId())) {
                        throw new InterruptedException("Canceled");
                    }
                    out.write(buf, 0, len);
                }
                out.flush();
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            destFile.delete();
            if (e instanceof InterruptedException) throw e;
            Log.w(TAG, "Could not copy work file to target dir", e);
            throw new UserFacingException(str(R.string.error_save_failed));
        }
        if (destFile.exists() && destFile.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            workFile.delete();
            deleteTaskWorkFiles(getApplicationContext(), task.getId());
            return destFile;
        }
        //noinspection ResultOfMethodCallIgnored
        destFile.delete();
        throw new UserFacingException(str(R.string.error_save_failed));
    }

    /** Atomically reserves "base.ext", "base (1).ext", ... by creating an empty placeholder file. */
    private static File reserveUniqueFile(File dir, String base, String ext) throws IOException {
        synchronized (FILE_NAME_LOCK) {
            for (int i = 0; i < 10000; i++) {
                String name = i == 0 ? base + "." + ext : base + " (" + i + ")." + ext;
                File candidate = new File(dir, name);
                if (!candidate.exists() && candidate.createNewFile()) {
                    return candidate;
                }
            }
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + "." + ext);
    }

    /** Y-04: exact match for task work files: ^task_<id>([._].*)?$ (so task_1 never matches task_12). */
    static boolean isTaskWorkFile(String name, long taskId) {
        if (name == null) return false;
        String prefix = "task_" + taskId;
        if (!name.startsWith(prefix)) return false;
        if (name.length() == prefix.length()) return true;
        char next = name.charAt(prefix.length());
        return next == '.' || next == '_';
    }

    /**
     * Deletes all internal temporary/work files associated with a specific task ID.
     * Matches task_<id>, task_<id>.*, task_<id>_* exactly (not task_<id>0...).
     */
    public static void deleteTaskWorkFiles(Context context, long taskId) {
        if (context == null || taskId < 0) return;
        try {
            File workDir = new File(context.getFilesDir(), "work_downloads");
            if (workDir.exists() && workDir.isDirectory()) {
                File[] files = workDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (isTaskWorkFile(f.getName(), taskId)) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to clean work files for task " + taskId, e);
        }
    }

    /**
     * Garbage collection: sweeps all stranded temp/work files in work_downloads
     * that do not belong to an actively downloading/queued task.
     */
    public static long cleanOrphanedWorkFiles(Context context) {
        if (context == null) return 0L;
        long freedBytes = 0L;
        try {
            File workDir = new File(context.getFilesDir(), "work_downloads");
            if (!workDir.exists() || !workDir.isDirectory()) {
                return 0L;
            }

            List<Long> activeIds = new ArrayList<>();
            try {
                List<DownloadTask> activeTasks = AppDatabase.getInstance(context)
                        .downloadDao()
                        .getActiveTasksSync();
                if (activeTasks != null) {
                    for (DownloadTask t : activeTasks) {
                        activeIds.add(t.getId());
                    }
                }
            } catch (Exception e) {
                // If we cannot tell which tasks are active, do not delete anything.
                Log.w(TAG, "Could not read active tasks; skipping work file cleanup", e);
                return 0L;
            }

            File[] files = workDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    boolean isActive = false;
                    for (Long id : activeIds) {
                        if (isTaskWorkFile(f.getName(), id)) {
                            isActive = true;
                            break;
                        }
                    }
                    if (!isActive) {
                        long len = f.length();
                        if (f.delete()) {
                            freedBytes += len;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error cleaning orphaned work files", e);
        }
        return freedBytes;
    }

    /**
     * Cleans temporary files in work_downloads and the app cache directories.
     * Y-03: never touches the user's download/target directory.
     */
    public static long cleanAllCacheAndTempFiles(Context context) {
        if (context == null) return 0L;
        long freedBytes = cleanOrphanedWorkFiles(context);
        try {
            File cacheDir = context.getCacheDir();
            if (cacheDir != null && cacheDir.exists()) {
                freedBytes += deleteDirRecursive(cacheDir, false);
            }
            File externalCache = context.getExternalCacheDir();
            if (externalCache != null && externalCache.exists()) {
                freedBytes += deleteDirRecursive(externalCache, false);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error cleaning cache dirs", e);
        }
        return freedBytes;
    }

    private static long deleteDirRecursive(File dir, boolean deleteRoot) {
        if (dir == null || !dir.exists()) return 0L;
        long bytes = 0L;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File child : files) {
                if (child.isDirectory()) {
                    bytes += deleteDirRecursive(child, true);
                } else {
                    bytes += child.length();
                    //noinspection ResultOfMethodCallIgnored
                    child.delete();
                }
            }
        }
        if (deleteRoot) {
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }
        return bytes;
    }

    /**
     * Calculates the total size of work_downloads and cache directories (what "clear cache" can free).
     */
    public static long getWorkDirectoryAndCacheSize(Context context) {
        if (context == null) return 0L;
        long total = 0L;
        try {
            File workDir = new File(context.getFilesDir(), "work_downloads");
            if (workDir.exists() && workDir.isDirectory()) {
                File[] files = workDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        total += f.length();
                    }
                }
            }
            File cacheDir = context.getCacheDir();
            if (cacheDir != null && cacheDir.exists()) {
                total += calculateFolderSize(cacheDir);
            }
            File externalCache = context.getExternalCacheDir();
            if (externalCache != null && externalCache.exists()) {
                total += calculateFolderSize(externalCache);
            }
        } catch (Exception ignored) {
        }
        return total;
    }

    private static long calculateFolderSize(File dir) {
        if (dir == null || !dir.exists()) return 0L;
        long total = 0L;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    total += calculateFolderSize(f);
                } else {
                    total += f.length();
                }
            }
        }
        return total;
    }

    private File getInternalWorkDirectory() {
        File workDir = new File(getFilesDir(), "work_downloads");
        if (!workDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            workDir.mkdirs();
        }
        return workDir;
    }

    private static String mimeTypeForExtension(String ext, String category) {
        String mime = null;
        if (ext != null && !ext.isEmpty()) {
            switch (ext) {
                case "jpg":
                case "jpeg":
                    mime = "image/jpeg";
                    break;
                case "avif":
                    mime = "image/avif";
                    break;
                case "heic":
                    mime = "image/heic";
                    break;
                case "heif":
                    mime = "image/heif";
                    break;
                case "m4a":
                    mime = "audio/mp4";
                    break;
                default:
                    mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            }
        }
        if (mime != null) return mime;
        switch (category) {
            case "image":
                return "image/jpeg";
            case "audio":
                return "audio/mpeg";
            default:
                return "video/mp4";
        }
    }

    /**
     * Copies the finished file into the public gallery (Pictures/Music/Movies + "/VBKTube").
     * O-05: IS_PENDING during the copy on API 29+, deletes the entry on failure, MIME from extension.
     */
    private void exportToPublicMediaStore(File file, DownloadTask task) {
        if (file == null || !file.exists()) return;
        String ext = extensionOf(file);
        String extMime = mimeTypeForExtension(ext, "");
        String category;
        if (isImageExt(ext) || extMime.startsWith("image/")) {
            category = "image";
        } else if (extMime.startsWith("audio/") || (task.isAudioOnly() && !extMime.startsWith("video/"))) {
            category = "audio";
        } else {
            category = "video";
        }
        String mimeType = mimeTypeForExtension(ext, category);
        if (!mimeType.startsWith(category + "/")) {
            mimeType = mimeTypeForExtension(null, category);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String relativeDir;
            Uri externalUri;
            switch (category) {
                case "image":
                    relativeDir = Environment.DIRECTORY_PICTURES + "/VBKTube";
                    externalUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                    break;
                case "audio":
                    relativeDir = Environment.DIRECTORY_MUSIC + "/VBKTube";
                    externalUri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    break;
                default:
                    relativeDir = Environment.DIRECTORY_MOVIES + "/VBKTube";
                    externalUri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            }
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
            values.put(android.provider.MediaStore.MediaColumns.TITLE, task.getTitle());
            values.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativeDir);
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = null;
            try {
                uri = getContentResolver().insert(externalUri, values);
                if (uri == null) {
                    Log.w(TAG, "MediaStore insert returned null for " + file.getName());
                    return;
                }
                try (InputStream in = new java.io.FileInputStream(file);
                     OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) {
                        throw new IOException("openOutputStream returned null");
                    }
                    byte[] buf = new byte[64 * 1024];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                }
                android.content.ContentValues done = new android.content.ContentValues();
                done.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, done, null, null);
            } catch (Exception e) {
                Log.w(TAG, "Failed to export file to public MediaStore: " + e.getMessage());
                if (uri != null) {
                    try {
                        getContentResolver().delete(uri, null, null);
                    } catch (Exception ignored) {
                    }
                }
            }
        } else {
            String publicDirType = "image".equals(category) ? Environment.DIRECTORY_PICTURES
                    : ("audio".equals(category) ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES);
            File publicDir = new File(Environment.getExternalStoragePublicDirectory(publicDirType), "VBKTube");
            File destFile = null;
            try {
                if (publicDir.exists() || publicDir.mkdirs()) {
                    String name = file.getName();
                    int dot = name.lastIndexOf('.');
                    String base = dot > 0 ? name.substring(0, dot) : name;
                    destFile = reserveUniqueFile(publicDir, base, ext.isEmpty() ? "bin" : ext);
                    try (InputStream in = new java.io.FileInputStream(file);
                         OutputStream out = new FileOutputStream(destFile)) {
                        byte[] buf = new byte[64 * 1024];
                        int len;
                        while ((len = in.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                        out.flush();
                    }
                    MediaScannerConnection.scanFile(
                            getApplicationContext(),
                            new String[]{destFile.getAbsolutePath()},
                            new String[]{mimeType},
                            null
                    );
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to export file to public storage: " + e.getMessage());
                if (destFile != null) {
                    //noinspection ResultOfMethodCallIgnored
                    destFile.delete();
                }
            }
        }
    }

    /** Finds the yt-dlp output for a task, preferring "<prefix>.<defaultExt>"; exact task-id matching (Y-04). */
    private File findOutputFile(File dir, String prefix, String defaultExt) {
        File preferred = new File(dir, prefix + "." + defaultExt);
        if (preferred.exists() && preferred.length() > 0) {
            return preferred;
        }
        long taskId = -1;
        try {
            taskId = Long.parseLong(prefix.substring("task_".length()));
        } catch (Exception ignored) {
        }
        File[] files = dir.listFiles();
        if (files != null && taskId >= 0) {
            for (File f : files) {
                String name = f.getName();
                if (!isTaskWorkFile(name, taskId) || f.length() == 0) continue;
                if (name.endsWith(".part") || name.endsWith(".ytdl") || name.endsWith(".temp")
                        || name.contains(".part-Frag") || name.contains("_vtmp") || name.contains("_atmp")
                        || name.contains("_itmp") || name.matches(".*\\.f\\d+\\.[A-Za-z0-9]+$")) {
                    continue;
                }
                return f;
            }
        }
        return null;
    }

    /** O-08: one folder name, "VBKTube", everywhere. */
    public static File getTargetDirectoryForDisplay(Context context) {
        File appExternal = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (appExternal != null) {
            File vbkDir = new File(appExternal, "VBKTube");
            if (vbkDir.exists() || vbkDir.mkdirs()) {
                return vbkDir;
            }
            return appExternal;
        }
        return context.getFilesDir();
    }

    private File getTargetDirectory() {
        return getTargetDirectoryForDisplay(this);
    }

    // ---------------------------------------------------------------------------------------------
    // Notifications (Y-06 / D-02)
    // ---------------------------------------------------------------------------------------------

    private void promoteToForeground() {
        Notification notification = buildSummaryNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(PROGRESS_NOTIF_ID, notification);
        }
    }

    private void updateNotification(DownloadTask task, int progress, boolean indeterminate) {
        long id = task.getId();
        if (!runningTasks.containsKey(id)) return;
        notifTitles.put(id, task.getTitle() != null ? task.getTitle() : "");
        notifProgress.put(id, new int[]{progress, indeterminate ? 1 : 0});
        scheduleNotificationRefresh();
    }

    /** Coalesces refreshes onto the main thread so they are ordered with stopIfIdle (no stale ongoing notification). */
    private void scheduleNotificationRefresh() {
        if (notifRefreshScheduled.compareAndSet(false, true)) {
            mainHandler.post(() -> {
                notifRefreshScheduled.set(false);
                if (notificationManager == null || activeCount.get() <= 0 || runningTasks.isEmpty()) return;
                notificationManager.notify(PROGRESS_NOTIF_ID, buildSummaryNotification());
            });
        }
    }

    /** One summary notification for all running tasks (no flicker between tasks). */
    private Notification buildSummaryNotification() {
        List<Long> ids = new ArrayList<>(notifProgress.keySet());
        if (ids.isEmpty()) {
            return buildProgressNotification(getString(R.string.app_name), getString(R.string.status_queued), 0, true);
        }
        int sum = 0;
        int determinate = 0;
        String firstTitle = null;
        for (Long id : ids) {
            int[] p = notifProgress.get(id);
            String t = notifTitles.get(id);
            if (firstTitle == null && t != null && !t.isEmpty()) firstTitle = t;
            if (p != null && p[1] == 0) {
                sum += p[0];
                determinate++;
            }
        }
        if (firstTitle == null) firstTitle = getString(R.string.app_name);
        String label = ids.size() > 1 ? firstTitle + " +" + (ids.size() - 1) : firstTitle;
        boolean indeterminate = determinate == 0;
        int pct = indeterminate ? 0 : sum / determinate;
        String content = indeterminate ? getString(R.string.status_queued) : String.format(Locale.US, "%d%%", pct);
        return buildProgressNotification(getString(R.string.notif_downloading_title, label), content, pct, indeterminate);
    }

    private Notification buildProgressNotification(String title, String text, int progress, boolean indeterminate) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, VidownApp.DOWNLOAD_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_downloads)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(100, progress, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void showCompletionNotification(int notifId, String title, @Nullable String text) {
        if (notificationManager == null) return;
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, notifId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, VidownApp.DOWNLOAD_EVENTS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_downloads)
                .setContentTitle(title)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(pendingIntent);
        if (text != null && !text.isEmpty()) {
            builder.setContentText(text);
        }
        notificationManager.notify(notifId, builder.build());
    }

    // ---------------------------------------------------------------------------------------------
    // D-01: short user-facing error messages (localized via strings.xml).
    // ---------------------------------------------------------------------------------------------

    static String friendlyError(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth < 10; t = t.getCause(), depth++) {
            if (t instanceof UserFacingException) {
                return t.getMessage();
            }
            if (t instanceof HttpStatusException) {
                return httpErrorMessage(((HttpStatusException) t).code);
            }
            if (t instanceof UnknownHostException) {
                return str(R.string.error_no_internet);
            }
            if (t instanceof ConnectException || t instanceof NoRouteToHostException) {
                return str(R.string.error_connect);
            }
            if (t instanceof SocketTimeoutException) {
                return str(R.string.error_timeout);
            }
            if (t instanceof SSLException) {
                return str(R.string.error_ssl);
            }
            String m = t.getMessage();
            if (m == null) continue;
            String lower = m.toLowerCase(Locale.US);
            if (lower.contains("enospc") || lower.contains("no space left")) {
                return str(R.string.error_no_space);
            }
            if (lower.contains("unable to resolve host") || lower.contains("failed to resolve")
                    || lower.contains("name or service not known") || lower.contains("getaddrinfo")
                    || lower.contains("temporary failure in name resolution")) {
                return str(R.string.error_no_internet);
            }
            if (lower.contains("failed to connect") || lower.contains("connection refused")) {
                return str(R.string.error_connect);
            }
            if (lower.contains("timed out") || lower.contains("timeout")) {
                return str(R.string.error_timeout);
            }
            java.util.regex.Matcher hm = java.util.regex.Pattern
                    .compile("http error (\\d{3})").matcher(lower);
            if (hm.find()) {
                return httpErrorMessage(Integer.parseInt(hm.group(1)));
            }
            if (lower.contains("unsupported url")) {
                return str(R.string.error_unsupported_url);
            }
            if (lower.contains("private video") || lower.contains("login required")
                    || lower.contains("sign in to confirm") || lower.contains("log in")) {
                return str(R.string.error_login_required);
            }
        }
        return str(R.string.error_download_failed);
    }

    private static String httpErrorMessage(int code) {
        if (code == 401 || code == 403) {
            return str(R.string.error_http_forbidden, code);
        }
        if (code == 404 || code == 410) {
            return str(R.string.error_http_not_found, code);
        }
        if (code == 429) {
            return str(R.string.error_http_429);
        }
        if (code >= 500) {
            return str(R.string.error_http_server, code);
        }
        return str(R.string.error_http_other, code);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
