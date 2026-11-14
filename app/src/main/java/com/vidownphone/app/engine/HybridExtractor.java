package com.vidownphone.app.engine;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.vidownphone.app.R;
import com.vidownphone.app.model.AnalyzedMedia;
import com.vidownphone.app.model.MediaFormatOption;
import com.vidownphone.app.util.FormatUtils;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 4-Layer Hybrid Media Extraction Engine:
 * Layer 1: Direct Media Stream Inspector (.mp4, .m3u8, .mp3, .m4a, .webm)
 * Layer 2: Native Platform Extractors (NewPipeExtractor v0.26.2 for YouTube, plus native Instagram, X/Twitter, TikTok, Reddit APIs)
 * Layer 3: yt-dlp 0.18.1 + FFmpeg + WebView CookieBridge (1,000+ supported sites)
 * Layer 4: Deep HTML/JSON-LD/OpenGraph/HLS Sniffer (beIN Sports, news/sports sites, custom players)
 */
public class HybridExtractor {

    private static final String TAG = "HybridExtractor";
    private final Context appContext;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient;
    private final SocialPlatformExtractor socialExtractor;

    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static boolean isKnownPlatformUrl(String url) {
        return SocialPlatformExtractor.isYouTubeUrl(url)
                || SocialPlatformExtractor.isTwitterUrl(url)
                || SocialPlatformExtractor.isInstagramUrl(url)
                || SocialPlatformExtractor.isTikTokUrl(url)
                || SocialPlatformExtractor.isRedditUrl(url);
    }

    public interface ExtractionCallback {
        void onSuccess(AnalyzedMedia media);
        void onError(String message);
    }

    public HybridExtractor(Context context) {
        this.appContext = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
        this.socialExtractor = new SocialPlatformExtractor(this.appContext, this.httpClient);
    }

    public void analyzeUrl(String rawUrl, ExtractionCallback callback) {
        executor.execute(() -> {
            try {
                String cleanUrl = FormatUtils.extractFirstUrl(rawUrl);
                if (cleanUrl == null) {
                    cleanUrl = rawUrl != null ? rawUrl.trim() : "";
                }
                if (cleanUrl.isEmpty() || (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://"))) {
                    postError(callback, appContext.getString(R.string.error_invalid_url));
                    return;
                }

                // Google Images wrapper (google.*/imgres?imgurl=...): use the real image URL
                String wrapped = FormatUtils.extractWrappedImageUrl(cleanUrl);
                if (wrapped != null) {
                    cleanUrl = wrapped;
                }

                // Convert Instagram 'exln=' (External Link token from 'Bağlantıyı Kopyala')
                // to 'rpxt=' (RePost Export token from 'Paylaş'), allowing public web access and extractor resolution!
                if (cleanUrl.contains("instagram.com/") && cleanUrl.contains("exln=")) {
                    cleanUrl = cleanUrl.replace("exln=", "rpxt=");
                }

                // Ensure all 4 engines (NewPipe, yt-dlp, FFmpeg, Aria2c) are initialized
                EngineManager.ensureInitializedSync(appContext);

                // LAYER 1: Fast path for direct media URLs (.mp4, .mp3, .m3u8, .webm, images, etc.)
                if (FormatUtils.isDirectMediaUrl(cleanUrl)) {
                    AnalyzedMedia directMedia = inspectDirectUrl(cleanUrl);
                    postSuccess(callback, directMedia);
                    return;
                }

                // LAYER 1B: URLs without a recognized extension (e.g. Google Images, CDN links):
                // probe Content-Type so images are never treated as video/music.
                if (!isKnownPlatformUrl(cleanUrl)) {
                    AnalyzedMedia probed = probeExtensionlessUrl(cleanUrl);
                    if (probed != null) {
                        postSuccess(callback, probed);
                        return;
                    }
                }

                // LAYER 2A: YouTube -> Primary: NewPipeExtractor v0.26.2 (Fast, anti-bot resilient), Fallback: yt-dlp 0.18.1
                if (SocialPlatformExtractor.isYouTubeUrl(cleanUrl)) {
                    String ytUrl = SocialPlatformExtractor.normalizeYouTubeUrl(cleanUrl);
                    AnalyzedMedia ytNewPipe = socialExtractor.extractYouTubeWithNewPipe(ytUrl);
                    if (ytNewPipe != null && !ytNewPipe.getFormatOptions().isEmpty()) {
                        postSuccess(callback, ytNewPipe);
                        return;
                    }
                    AnalyzedMedia ytDlpMedia = extractWithYtDlp(ytUrl, true);
                    if (ytDlpMedia != null) {
                        postSuccess(callback, ytDlpMedia);
                        return;
                    }
                }

                // LAYER 2B: X / Twitter -> Primary: Native Syndication/FxTwitter/VxTwitter, Fallback: yt-dlp 0.18.1
                if (SocialPlatformExtractor.isTwitterUrl(cleanUrl)) {
                    AnalyzedMedia xMedia = socialExtractor.extractTwitterOrX(cleanUrl);
                    if (xMedia != null && !xMedia.getFormatOptions().isEmpty()) {
                        postSuccess(callback, xMedia);
                        return;
                    }
                    AnalyzedMedia xYtDlp = extractWithYtDlp(cleanUrl, false);
                    if (xYtDlp != null) {
                        postSuccess(callback, xYtDlp);
                        return;
                    }
                }

                // LAYER 2C: Instagram (Reels / Posts / IGTV / Share / Stories / Highlights)
                if (SocialPlatformExtractor.isInstagramUrl(cleanUrl)) {
                    SocialPlatformExtractor.InstagramStoryTarget st = SocialPlatformExtractor.parseStoryOrHighlightUrl(cleanUrl);
                    String igTargetUrl = (st != null && st.canonicalUrl != null) ? st.canonicalUrl : cleanUrl;

                    AnalyzedMedia igMedia = socialExtractor.extractInstagram(cleanUrl);
                    if (igMedia != null && !igMedia.getFormatOptions().isEmpty()) {
                        postSuccess(callback, igMedia);
                        return;
                    }

                    // Try yt-dlp first with cleanUrl (contains rpxt token), then with canonical URL
                    AnalyzedMedia igYtDlp = extractWithYtDlp(cleanUrl, false);
                    if (igYtDlp != null) {
                        postSuccess(callback, igYtDlp);
                        return;
                    }
                    if (st != null && !igTargetUrl.equals(cleanUrl)) {
                        igYtDlp = extractWithYtDlp(igTargetUrl, false);
                        if (igYtDlp != null) {
                            postSuccess(callback, igYtDlp);
                            return;
                        }
                    }
                    if (st != null) {
                        String cookie = CookieBridge.getCookieHeaderForUrl("https://www.instagram.com");
                        if (cookie == null || !cookie.contains("sessionid")) {
                            postError(callback, appContext.getString(R.string.error_instagram_story_login_required));
                            return;
                        }
                    }
                }

                // LAYER 2D: TikTok -> Primary: Native TikWM HD No-Watermark, Fallback: yt-dlp 0.18.1
                if (SocialPlatformExtractor.isTikTokUrl(cleanUrl)) {
                    AnalyzedMedia ttMedia = socialExtractor.extractTikTok(cleanUrl);
                    if (ttMedia != null && !ttMedia.getFormatOptions().isEmpty()) {
                        postSuccess(callback, ttMedia);
                        return;
                    }
                    AnalyzedMedia ttYtDlp = extractWithYtDlp(cleanUrl, false);
                    if (ttYtDlp != null) {
                        postSuccess(callback, ttYtDlp);
                        return;
                    }
                }

                // LAYER 2E: Reddit -> Primary: Native JSON + Audio Mux, Fallback: yt-dlp 0.18.1
                if (SocialPlatformExtractor.isRedditUrl(cleanUrl)) {
                    AnalyzedMedia redditMedia = socialExtractor.extractReddit(cleanUrl);
                    if (redditMedia != null && !redditMedia.getFormatOptions().isEmpty()) {
                        postSuccess(callback, redditMedia);
                        return;
                    }
                }

                // LAYER 3: Universal yt-dlp 0.18.1 Engine (Facebook, Vimeo, Dailymotion, Twitch, Kick, VK, SoundCloud, etc.)
                AnalyzedMedia ytdlpMedia = extractWithYtDlp(cleanUrl, false);
                if (ytdlpMedia != null) {
                    postSuccess(callback, ytdlpMedia);
                    return;
                }

                // LAYER 4: Deep HTTP / HTML / JSON-LD / HLS .m3u8 Scraper (beIN Sports, news/sports highlight sites, etc.)
                AnalyzedMedia fallbackMedia = inspectHttpFallback(cleanUrl);
                if (fallbackMedia != null) {
                    postSuccess(callback, fallbackMedia);
                } else {
                    postError(callback, appContext.getString(R.string.error_extraction_failed));
                }

            } catch (Exception e) {
                Log.e(TAG, "Extraction error", e);
                postError(callback, e.getMessage() != null ? e.getMessage() : appContext.getString(R.string.error_extraction_failed));
            }
        });
    }

    public static void configureYtDlpRequest(Context context, YoutubeDLRequest request, String url) {
        configureYtDlpRequestWithCookies(context, request, url);
    }

    /**
     * Same as {@link #configureYtDlpRequest} but returns the per-call temp cookie file (or null).
     * Callers should pass it to {@link CookieBridge#deleteQuietly(File)} in a finally block after
     * the yt-dlp call finishes. (Files left behind are swept after 30 minutes.)
     */
    public static File configureYtDlpRequestWithCookies(Context context, YoutubeDLRequest request, String url) {
        request.addOption("--no-playlist");
        request.addOption("--no-mtime");
        request.addOption("--geo-bypass");
        request.addOption("--socket-timeout", "20");

        if (SocialPlatformExtractor.isYouTubeUrl(url)) {
            request.addOption("--extractor-args", "youtube:player_client=android,ios,mweb");
        } else {
            request.addOption("--user-agent", DESKTOP_UA);
        }

        File cookieFile = CookieBridge.exportNetscapeCookieFile(context, url);
        if (cookieFile != null && cookieFile.exists()) {
            request.addOption("--cookies", cookieFile.getAbsolutePath());
            return cookieFile;
        }
        return null;
    }

    private AnalyzedMedia extractWithYtDlp(String cleanUrl, boolean isYouTube) {
        if (!EngineManager.isYtdlpReady()) {
            return null;
        }
        File cookieFile = null;
        try {
            YoutubeDLRequest request = new YoutubeDLRequest(cleanUrl);
            cookieFile = configureYtDlpRequestWithCookies(appContext, request, cleanUrl);

            VideoInfo info = YoutubeDL.getInstance().getInfo(request);
            if (info != null) {
                return mapVideoInfo(cleanUrl, info);
            }
        } catch (Exception ytdlpError) {
            Log.w(TAG, "yt-dlp extraction failed for " + cleanUrl + ": " + ytdlpError.getMessage());
        } finally {
            CookieBridge.deleteQuietly(cookieFile);
        }
        return null;
    }

    private static long formatSize(VideoFormat vf) {
        return vf.getFileSize() > 0 ? vf.getFileSize() : vf.getFileSizeApproximate();
    }

    private AnalyzedMedia mapVideoInfo(String sourceUrl, VideoInfo info) {
        String urlTitle = FormatUtils.titleFromUrlPath(sourceUrl);
        String title = info.getTitle() != null && !info.getTitle().isEmpty()
                ? info.getTitle()
                : (urlTitle != null ? urlTitle : "Video");
        String uploader = info.getUploader() != null ? info.getUploader() : "";
        long duration = info.getDuration();
        String thumbnail = info.getThumbnail();

        List<MediaFormatOption> options = new ArrayList<>();

        // Collect real resolutions + the largest audio-only stream size (for merged size estimates)
        Map<Integer, VideoFormat> resolutionMap = new LinkedHashMap<>();
        long bestAudioSize = 0;
        List<VideoFormat> rawFormats = info.getFormats();
        if (rawFormats != null) {
            List<VideoFormat> sorted = new ArrayList<>(rawFormats);
            Collections.sort(sorted, (a, b) -> Integer.compare(b.getHeight(), a.getHeight()));
            for (VideoFormat vf : sorted) {
                String vcodec = vf.getVcodec();
                String acodec = vf.getAcodec();
                boolean hasVideo = vcodec != null && !"none".equalsIgnoreCase(vcodec);
                boolean hasAudio = acodec != null && !"none".equalsIgnoreCase(acodec);
                if (hasVideo) {
                    int height = vf.getHeight();
                    VideoFormat existing = resolutionMap.get(height);
                    if (height >= 144 && (existing == null || (formatSize(existing) <= 0 && formatSize(vf) > 0))) {
                        resolutionMap.put(height, vf);
                    }
                } else if (hasAudio) {
                    bestAudioSize = Math.max(bestAudioSize, formatSize(vf));
                }
            }
        }

        for (Map.Entry<Integer, VideoFormat> entry : resolutionMap.entrySet()) {
            int height = entry.getKey();
            VideoFormat vf = entry.getValue();
            long size = formatSize(vf);
            String acodec = vf.getAcodec();
            boolean videoOnly = acodec == null || "none".equalsIgnoreCase(acodec);
            if (size > 0 && videoOnly && bestAudioSize > 0) {
                size += bestAudioSize;
            }
            String ext = vf.getExt() != null ? vf.getExt() : "mp4";
            String selector = String.format(Locale.US,
                    "bestvideo[height<=%d]+bestaudio/best[height<=%d]/best",
                    height, height);
            String qualityTag = height >= 2160 ? "4K Ultra HD"
                    : height >= 1440 ? "2K QHD"
                    : height >= 1080 ? "Full HD"
                    : height >= 720 ? "HD" : "SD";
            options.add(new MediaFormatOption(
                    selector,
                    height + "p (" + qualityTag + ")",
                    ext.toUpperCase(Locale.US) + (vf.getFps() > 0 ? " • " + vf.getFps() + "fps" : ""),
                    "mp4",
                    size,
                    false,
                    false
            ));
        }

        // Nothing real known about resolutions: a single automatic best-quality option
        if (resolutionMap.isEmpty()) {
            options.add(new MediaFormatOption(
                    "bestvideo+bestaudio/best",
                    appContext.getString(R.string.format_auto_best),
                    "MP4 • yt-dlp + FFmpeg",
                    "mp4",
                    info.getFileSize() > 0 ? info.getFileSize() : info.getFileSizeApproximate(),
                    false,
                    false
            ));
        }

        // Fast direct progressive stream, when yt-dlp exposes one
        if (info.getUrl() != null && info.getUrl().startsWith("http") && !info.getUrl().contains(".m3u8")) {
            options.add(new MediaFormatOption(
                    "direct_url:" + info.getUrl(),
                    appContext.getString(R.string.format_direct_fast_stream),
                    appContext.getString(R.string.sub_direct_server_download),
                    "mp4",
                    info.getFileSize() > 0 ? info.getFileSize() : 0,
                    false,
                    true
            ));
        }

        // Audio-only options, labelled honestly
        options.add(new MediaFormatOption(
                "bestaudio/best",
                appContext.getString(R.string.format_best_audio),
                appContext.getString(R.string.sub_mp3_converted),
                "mp3",
                bestAudioSize,
                true,
                false
        ));
        options.add(new MediaFormatOption(
                "bestaudio[ext=m4a]/bestaudio",
                appContext.getString(R.string.format_m4a_original),
                appContext.getString(R.string.sub_m4a_no_conversion),
                "m4a",
                bestAudioSize,
                true,
                false
        ));

        return new AnalyzedMedia(sourceUrl, title, uploader, duration, thumbnail, options);
    }

    /** Result of a lightweight HEAD / Range-GET probe. */
    private static final class Probe {
        int code;
        String contentType = "";
        long contentLength;
    }

    private static final long PROBE_TIMEOUT_SEC = 8;

    private Request.Builder probeRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_UA)
                .header("Accept", "*/*");
    }

    private static long parseLongSafe(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * HEAD the URL; if HEAD fails or is rejected (403/405/501...), GET with "Range: bytes=0-0".
     * Returns null when the server cannot be reached at all.
     */
    private Probe probeUrl(String url) {
        OkHttpClient probeClient = httpClient.newBuilder()
                .connectTimeout(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .callTimeout(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        Probe result = null;
        try (Response response = probeClient.newCall(probeRequest(url).head().build()).execute()) {
            result = new Probe();
            result.code = response.code();
            String ct = response.header("Content-Type");
            result.contentType = ct != null ? ct : "";
            result.contentLength = parseLongSafe(response.header("Content-Length"));
        } catch (Exception e) {
            Log.w(TAG, "HEAD probe failed for " + url + ": " + e.getMessage());
        }
        boolean headUsable = result != null && result.code >= 200 && result.code < 300
                && !result.contentType.isEmpty();
        if (headUsable || (result != null && (result.code == 404 || result.code == 410))) {
            return result;
        }
        // HEAD failed or was refused: try a one-byte ranged GET (body is closed unread)
        try (Response response = probeClient.newCall(
                probeRequest(url).header("Range", "bytes=0-0").get().build()).execute()) {
            Probe p = new Probe();
            p.code = response.code();
            String ct = response.header("Content-Type");
            p.contentType = ct != null ? ct : "";
            String range = response.header("Content-Range"); // bytes 0-0/12345
            if (range != null && range.contains("/")) {
                p.contentLength = parseLongSafe(range.substring(range.lastIndexOf('/') + 1));
            } else if (p.code == 200) {
                p.contentLength = parseLongSafe(response.header("Content-Length"));
            }
            return p;
        } catch (Exception e) {
            Log.w(TAG, "Range GET probe failed for " + url + ": " + e.getMessage());
            return result;
        }
    }

    private String httpErrorMessage(int code) {
        return appContext.getString(R.string.error_http_not_found, code);
    }

    /** Builds an image-only result: one photo option, no MP3 option, thumbnail = the URL itself. */
    private AnalyzedMedia buildImageMedia(String url, String ext, long contentLength) {
        String title = FormatUtils.isKnownImageHostUrl(url) ? null : FormatUtils.titleFromUrlPath(url);
        if (title == null || title.length() > 80) {
            title = appContext.getString(R.string.format_image_default_title);
        }
        List<MediaFormatOption> options = new ArrayList<>();
        options.add(new MediaFormatOption(
                "direct_url:" + url,
                appContext.getString(R.string.format_photo, ext.toUpperCase(Locale.US)),
                appContext.getString(R.string.sub_photo_direct),
                ext,
                contentLength,
                false,
                true
        ));
        return new AnalyzedMedia(url, title, "Direct Server", 0, url, options);
    }

    /**
     * Probes an extension-less URL. Returns an image result for image/*, a direct media result for
     * video/audio, or null to continue with the normal pipeline.
     */
    private AnalyzedMedia probeExtensionlessUrl(String url) {
        Probe probe = probeUrl(url);
        if (probe == null || probe.code >= 400) {
            return null;
        }
        String imageExt = FormatUtils.imageExtFromContentType(probe.contentType);
        if (imageExt != null) {
            return buildImageMedia(url, imageExt, probe.contentLength);
        }
        String ct = probe.contentType.toLowerCase(Locale.US);
        if (ct.startsWith("video/") || ct.startsWith("audio/")) {
            return inspectDirectUrl(url, probe);
        }
        return null;
    }

    private AnalyzedMedia inspectDirectUrl(String url) {
        return inspectDirectUrl(url, null);
    }

    private AnalyzedMedia inspectDirectUrl(String url, Probe knownProbe) {
        long contentLength = 0;
        String ext = null;
        boolean isAudio = false;
        boolean isHls = false;
        boolean isImage = false;

        String cleanPath = url.toLowerCase(Locale.US).split("[?#]")[0];
        if (cleanPath.endsWith(".mp3")) {
            ext = "mp3";
            isAudio = true;
        } else if (cleanPath.endsWith(".m4a")) {
            ext = "m4a";
            isAudio = true;
        } else if (cleanPath.endsWith(".wav")) {
            ext = "wav";
            isAudio = true;
        } else if (cleanPath.endsWith(".ogg")) {
            ext = "ogg";
            isAudio = true;
        } else if (cleanPath.endsWith(".webm")) {
            ext = "webm";
        } else if (cleanPath.endsWith(".mkv")) {
            ext = "mkv";
        } else if (cleanPath.endsWith(".mov")) {
            ext = "mov";
        } else if (cleanPath.endsWith(".mp4")) {
            ext = "mp4";
        } else if (cleanPath.endsWith(".m3u8")) {
            ext = "mp4";
            isHls = true;
        } else if (FormatUtils.hasImageExtension(cleanPath)) {
            String p = cleanPath.substring(cleanPath.lastIndexOf('.') + 1);
            ext = "jpeg".equals(p) ? "jpg" : p;
            isImage = true;
        }

        if (!isHls) {
            Probe probe = knownProbe != null ? knownProbe : probeUrl(url);
            if (probe != null) {
                if (probe.code == 404 || probe.code == 410 || probe.code >= 500
                        || (probe.code >= 400 && probe.code != 401 && probe.code != 403
                        && probe.code != 405 && probe.code != 429)) {
                    throw new IllegalStateException(httpErrorMessage(probe.code));
                }
                if (probe.code >= 400) {
                    Log.w(TAG, "Probe returned HTTP " + probe.code + " for " + url + "; continuing");
                } else {
                    contentLength = probe.contentLength;
                    String ct = probe.contentType != null ? probe.contentType.toLowerCase(Locale.US) : "";
                    String imgExt = FormatUtils.imageExtFromContentType(ct);
                    String mediaExt = FormatUtils.mediaExtFromContentType(ct);
                    if (imgExt != null) {
                        // Server says it is an image regardless of what the URL looked like
                        ext = imgExt;
                        isImage = true;
                        isAudio = false;
                    } else if (ct.startsWith("text/html")) {
                        throw new IllegalStateException(appContext.getString(R.string.error_not_media_html));
                    } else if (mediaExt != null && ext == null) {
                        ext = mediaExt;
                        isAudio = ct.startsWith("audio/");
                    } else if (ext == null && ct.startsWith("audio/")) {
                        isAudio = true;
                    }
                }
            }
        }

        if (isImage) {
            return buildImageMedia(url, ext != null ? ext : "jpg", contentLength);
        }
        if (ext == null) {
            ext = isAudio ? "m4a" : "mp4";
        }

        String fileName = FormatUtils.titleFromUrlPath(url);
        if (fileName == null || fileName.isEmpty()) {
            fileName = isAudio ? "Ses" : "Video";
        }

        List<MediaFormatOption> options = new ArrayList<>();
        if (isHls) {
            options.add(new MediaFormatOption(
                    "ffmpeg_hls:" + url,
                    appContext.getString(R.string.format_hls_merge),
                    appContext.getString(R.string.sub_hls_ffmpeg_downloader),
                    "mp4",
                    0,
                    false,
                    false
            ));
        } else {
            options.add(new MediaFormatOption(
                    "direct_url:" + url,
                    isAudio ? appContext.getString(R.string.format_direct_audio, ext.toUpperCase(Locale.US)) : appContext.getString(R.string.format_direct_video, ext.toUpperCase(Locale.US)),
                    appContext.getString(R.string.sub_direct_http_stream),
                    ext,
                    contentLength,
                    isAudio,
                    true
            ));
        }

        if (!isAudio) {
            // Single, clearly labelled converted-audio option (never for images)
            options.add(new MediaFormatOption(
                    "ffmpeg_mp3:" + url,
                    appContext.getString(R.string.format_best_audio),
                    appContext.getString(R.string.sub_mp3_from_video),
                    "mp3",
                    0,
                    true,
                    false
            ));
        }

        return new AnalyzedMedia(url, fileName, "Direct Server", 0, null, options);
    }

    private AnalyzedMedia inspectHttpFallback(String pageUrl) {
        try {
            Request.Builder reqBuilder = new Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                    .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7");

            String cookie = CookieBridge.getCookieHeaderForUrl(pageUrl);
            if (cookie != null && !cookie.isEmpty()) {
                reqBuilder.header("Cookie", cookie);
            }

            try (Response response = httpClient.newCall(reqBuilder.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return null;
                }
                String contentType = response.header("Content-Type", "");
                if (contentType != null) {
                    String imgExt = FormatUtils.imageExtFromContentType(contentType);
                    if (imgExt != null) {
                        return buildImageMedia(pageUrl, imgExt, parseLongSafe(response.header("Content-Length")));
                    }
                    if (contentType.startsWith("video/") || contentType.startsWith("audio/")) {
                        return inspectDirectUrl(pageUrl);
                    }
                }

                String html = response.body().string();
                String title = extractRegex(html, "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']");
                if (title == null) {
                    title = extractRegex(html, "<title[^>]*>([^<]+)</title>");
                }
                String ogImage = extractRegex(html, "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']");

                List<MediaFormatOption> options = new ArrayList<>();

                // 1. Check OpenGraph / Twitter Player / HTML5 <video> / JSON-LD contentUrl
                String directVideo = extractRegex(html, "<meta[^>]+property=[\"']og:video(?::url|:secure_url)?[\"'][^>]+content=[\"']([^\"']+)[\"']");
                if (!isLikelyDirectMediaStream(directVideo)) {
                    directVideo = null;
                }
                if (directVideo == null) {
                    String candidate = extractRegex(html, "<meta[^>]+name=[\"']twitter:player:stream[\"'][^>]+content=[\"']([^\"']+)[\"']");
                    if (isLikelyDirectMediaStream(candidate)) {
                        directVideo = candidate;
                    }
                }
                if (directVideo == null) {
                    directVideo = extractRegex(html, "\"contentUrl\"\\s*:\\s*\"(https?:[^\"]+\\.(?:mp4|m3u8)[^\"]*)\"");
                }
                if (directVideo == null) {
                    String candidate = extractRegex(html, "<video[^>]+src=[\"']([^\"']+)[\"']");
                    if (isLikelyDirectMediaStream(candidate)) {
                        directVideo = candidate;
                    }
                }
                if (directVideo == null) {
                    String candidate = extractRegex(html, "<source[^>]+src=[\"']([^\"']+)[\"']");
                    if (isLikelyDirectMediaStream(candidate)) {
                        directVideo = candidate;
                    }
                }
                if (directVideo == null) {
                    // Search for direct .mp4 URL in page scripts (e.g. beIN Sports, news players)
                    directVideo = extractRegex(html, "[\"'](https?://[^\"'\\s<>]+\\.mp4(?:\\?[^\"'\\s<>]*)?)[\"']");
                }

                // 2. Also search for HLS .m3u8 stream in page scripts
                String hlsVideo = extractRegex(html, "[\"'](https?://[^\"'\\s<>]+\\.m3u8(?:\\?[^\"'\\s<>]*)?)[\"']");

                if (directVideo != null && !directVideo.isEmpty()) {
                    directVideo = directVideo.replace("\\/", "/").replace("&amp;", "&");
                    boolean isM3u8 = directVideo.contains(".m3u8");
                    options.add(new MediaFormatOption(
                            isM3u8 ? ("ffmpeg_hls:" + directVideo) : ("direct_url:" + directVideo),
                            appContext.getString(R.string.format_web_video_stream),
                            isM3u8 ? "HLS M3U8 -> MP4 (FFmpeg)" : appContext.getString(R.string.sub_direct_http_stream_web),
                            "mp4",
                            0,
                            false,
                            !isM3u8
                    ));
                    options.add(new MediaFormatOption(
                            "ffmpeg_mp3:" + directVideo,
                            appContext.getString(R.string.format_best_audio),
                            appContext.getString(R.string.sub_mp3_audio_file),
                            "mp3",
                            0,
                            true,
                            false
                    ));
                }

                if (hlsVideo != null && !hlsVideo.isEmpty()) {
                    hlsVideo = hlsVideo.replace("\\/", "/").replace("&amp;", "&");
                    if (directVideo == null || !directVideo.equals(hlsVideo)) {
                        options.add(new MediaFormatOption(
                                "ffmpeg_hls:" + hlsVideo,
                                appContext.getString(R.string.format_live_hls_hd),
                                appContext.getString(R.string.sub_hls_ffmpeg_merger),
                                "mp4",
                                0,
                                false,
                                false
                        ));
                    }
                }

                if (!options.isEmpty()) {
                    String primaryUrl = directVideo != null ? directVideo : hlsVideo;
                    return new AnalyzedMedia(
                            primaryUrl,
                            (title != null && !title.trim().isEmpty()) ? title.trim() : (FormatUtils.titleFromUrlPath(primaryUrl) != null ? FormatUtils.titleFromUrlPath(primaryUrl) : "Web Video"),
                            "Web Stream",
                            0,
                            ogImage,
                            options
                    );
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "HTTP fallback inspection failed", e);
        }
        return null;
    }

    private boolean isLikelyDirectMediaStream(String url) {
        if (url == null || url.trim().isEmpty()) return false;
        String lower = url.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        // Reject HTML iframe/embed player pages
        if (lower.contains("/embed/")
                || lower.contains("youtube.com/")
                || lower.contains("youtu.be/")
                || lower.contains("player.vimeo.com/video/")
                || lower.contains("instagram.com/p/")
                || lower.contains("instagram.com/reel/")
                || lower.contains("twitter.com/i/videos/")
                || lower.contains("x.com/i/videos/")) {
            return false;
        }
        return lower.contains(".mp4")
                || lower.contains(".m3u8")
                || lower.contains(".webm")
                || lower.contains(".mov")
                || lower.contains(".mkv")
                || lower.contains("videoplayback")
                || lower.contains("akamaized.net")
                || lower.contains("cdninstagram.com")
                || lower.contains("fbcdn.net")
                || lower.contains("twimg.com")
                || lower.contains("tiktokcdn");
    }

    private String extractRegex(String input, String regex) {
        if (input == null) return null;
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(input);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private void postSuccess(ExtractionCallback callback, AnalyzedMedia media) {
        Log.i(TAG, "EXTRACTION_SUCCESS: title=" + media.getTitle()
                + " | formats=" + media.getFormatOptions().size()
                + " | firstFormat=" + (media.getFormatOptions().isEmpty() ? "none" : media.getFormatOptions().get(0).getLabel()));
        mainHandler.post(() -> callback.onSuccess(media));
    }

    private void postError(ExtractionCallback callback, String error) {
        Log.e(TAG, "EXTRACTION_ERROR: " + error);
        mainHandler.post(() -> callback.onError(error));
    }
}
