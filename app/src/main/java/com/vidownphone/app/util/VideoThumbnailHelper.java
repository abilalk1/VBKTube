package com.vidownphone.app.util;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * High-performance helper to extract and cache first-frame thumbnails from remote or local video URLs.
 */
public final class VideoThumbnailHelper {

    private static final String TAG = "VideoThumbnailHelper";

    // 15% of available memory for thumbnail cache
    private static final int MAX_MEMORY = (int) (Runtime.getRuntime().maxMemory() / 1024);
    private static final int CACHE_SIZE = MAX_MEMORY / 8;

    private static final LruCache<String, Bitmap> MEMORY_CACHE = new LruCache<String, Bitmap>(CACHE_SIZE) {
        @Override
        protected int sizeOf(String key, Bitmap bitmap) {
            return bitmap.getByteCount() / 1024;
        }
    };

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private VideoThumbnailHelper() {
    }

    public static void loadVideoThumbnail(ImageView imageView, String videoUrl, int placeholderRes) {
        if (imageView == null) return;
        if (videoUrl == null || videoUrl.isEmpty()) {
            imageView.setImageResource(placeholderRes);
            return;
        }

        Bitmap cached = MEMORY_CACHE.get(videoUrl);
        if (cached != null) {
            imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            imageView.setImageTintList(null);
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        imageView.setImageResource(placeholderRes);
        imageView.setTag(videoUrl);

        EXECUTOR.execute(() -> {
            Bitmap frame = extractFirstFrame(videoUrl);
            if (frame != null) {
                // Scale to thumbnail bounds (max 320x320)
                Bitmap scaled = scaleBitmap(frame, 320);
                MEMORY_CACHE.put(videoUrl, scaled);

                MAIN_HANDLER.post(() -> {
                    if (videoUrl.equals(imageView.getTag())) {
                        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        imageView.setImageTintList(null);
                        imageView.setImageBitmap(scaled);
                    }
                });
            }
        });
    }

    private static Bitmap extractFirstFrame(String videoUrl) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            retriever.setDataSource(videoUrl, headers);

            // OPTION_CLOSEST_SYNC decodes the nearest keyframe at time 0 (instant)
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = retriever.getFrameAtTime(500000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) {
                frame = retriever.getFrameAtTime();
            }
            return frame;
        } catch (Throwable t) {
            Log.d(TAG, "Frame extraction failed for " + videoUrl + ": " + t.getMessage());
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static Bitmap scaleBitmap(Bitmap src, int maxDimension) {
        if (src == null) return null;
        int width = src.getWidth();
        int height = src.getHeight();
        if (width <= maxDimension && height <= maxDimension) {
            return src;
        }
        float ratio = Math.min((float) maxDimension / width, (float) maxDimension / height);
        int newWidth = Math.round(ratio * width);
        int newHeight = Math.round(ratio * height);
        return Bitmap.createScaledBitmap(src, newWidth, newHeight, true);
    }
}
