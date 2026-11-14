package com.vidownphone.app.engine;

import android.content.Context;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Locale;
import java.util.UUID;

/**
 * Bridges cookies between the built-in Android WebView (CookieManager) and
 * the extraction engines (yt-dlp Netscape cookies.txt, NewPipe, and direct HTTP).
 * This allows users who log into Instagram, X/Twitter, YouTube, TikTok, or Facebook
 * in the built-in Browser tab to seamlessly extract restricted or rate-limited videos.
 *
 * Security: cookies are only exported for the target URL's own domain, into a per-call
 * unique temp file that callers should delete with {@link #deleteQuietly(File)} when the
 * yt-dlp call finishes. Stale files are also swept automatically on every export.
 */
public final class CookieBridge {

    private static final String TAG = "CookieBridge";
    private static final String FILE_PREFIX = "cookies_";
    private static final String FILE_SUFFIX = ".txt";
    private static final long STALE_AFTER_MS = 30L * 60 * 1000;

    private CookieBridge() {}

    public static String getCookieHeaderForUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            CookieManager cm = CookieManager.getInstance();
            String cookie = cm.getCookie(url);
            if (cookie == null || cookie.isEmpty()) {
                String host = SocialPlatformExtractor.hostOf(url);
                if ("x.com".equals(host) || (host != null && host.endsWith(".x.com"))) {
                    cookie = cm.getCookie("https://twitter.com");
                } else if ("twitter.com".equals(host) || (host != null && host.endsWith(".twitter.com"))) {
                    cookie = cm.getCookie("https://x.com");
                }
            }
            return cookie;
        } catch (Throwable t) {
            Log.w(TAG, "Could not read WebView cookies", t);
            return null;
        }
    }

    /**
     * Generates a Netscape-formatted cookies file containing only cookies for the target URL's
     * domain, in a unique file (cache/cookies_&lt;uuid&gt;.txt). Returns null if no cookies exist.
     * The caller must delete the returned file after the yt-dlp call (see {@link #deleteQuietly}).
     */
    public static File exportNetscapeCookieFile(Context context, String targetUrl) {
        try {
            if (targetUrl == null || targetUrl.isEmpty()) {
                return null;
            }
            String host = getDomainFromUrl(targetUrl);
            if (host == null) {
                return null;
            }
            sweepStaleFiles(context);

            String header = getCookieHeaderForUrl(targetUrl);
            if (header == null || header.isEmpty()) {
                return null;
            }

            File cookieFile = new File(context.getCacheDir(), FILE_PREFIX + UUID.randomUUID() + FILE_SUFFIX);
            int writtenCount = 0;
            long expiry = (System.currentTimeMillis() / 1000L) + (24L * 3600);
            String domain = host.startsWith(".") ? host : "." + host;

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(cookieFile, false))) {
                writer.write("# Netscape HTTP Cookie File\n\n");
                for (String pair : header.split(";")) {
                    String trimmed = pair.trim();
                    int eqIdx = trimmed.indexOf('=');
                    if (eqIdx > 0) {
                        String name = trimmed.substring(0, eqIdx).trim();
                        String value = trimmed.substring(eqIdx + 1).trim();
                        if (!name.isEmpty()) {
                            // domain, flag, path, secure, expiration, name, value
                            writer.write(domain + "\tTRUE\t/\tTRUE\t" + expiry + "\t" + name + "\t" + value + "\n");
                            writtenCount++;
                        }
                    }
                }
            }

            if (writtenCount == 0) {
                deleteQuietly(cookieFile);
                return null;
            }
            return cookieFile;
        } catch (Throwable e) {
            Log.w(TAG, "Could not export WebView cookies", e);
            return null;
        }
    }

    /** Deletes a cookie file produced by {@link #exportNetscapeCookieFile}; safe with null. */
    public static void deleteQuietly(File file) {
        if (file == null) return;
        try {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete cookie file " + file.getName());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Cookie file cleanup failed", t);
        }
    }

    /** Removes leftover cookie files (e.g. from callers that never deleted theirs). */
    private static void sweepStaleFiles(Context context) {
        try {
            File[] files = context.getCacheDir().listFiles();
            if (files == null) return;
            long now = System.currentTimeMillis();
            for (File f : files) {
                String n = f.getName();
                boolean legacy = "vidown_webview_cookies.txt".equals(n);
                boolean ours = n.startsWith(FILE_PREFIX) && n.endsWith(FILE_SUFFIX);
                if ((legacy || ours) && (legacy || now - f.lastModified() > STALE_AFTER_MS)) {
                    deleteQuietly(f);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Cookie sweep failed", t);
        }
    }

    private static String getDomainFromUrl(String url) {
        String host = SocialPlatformExtractor.hostOf(url);
        if (host == null) return null;
        if (host.startsWith("www.")) {
            host = host.substring(4);
        } else if (host.startsWith("m.")) {
            host = host.substring(2);
        }
        return host.toLowerCase(Locale.US);
    }
}
