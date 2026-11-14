package com.vidownphone.app.engine;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vidownphone.app.R;
import com.vidownphone.app.model.AnalyzedMedia;
import com.vidownphone.app.model.MediaFormatOption;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Multi-platform native extractor for YouTube (via NewPipeExtractor v0.26.2),
 * Instagram (Reels/Posts/Share), X/Twitter (Syndication & FxTwitter/VxTwitter),
 * TikTok (No-Watermark HD & MP3), and Reddit (Video + Audio muxing).
 */
public class SocialPlatformExtractor {

    private static final String TAG = "SocialExtractor";
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final Context appContext;
    private final OkHttpClient httpClient;

    public SocialPlatformExtractor(Context context, OkHttpClient httpClient) {
        this.appContext = context.getApplicationContext();
        this.httpClient = httpClient;
    }

    /** Extracts the lower-case host of a URL (null if it cannot be parsed). */
    public static String hostOf(String url) {
        if (url == null) return null;
        try {
            String u = url.trim();
            String h = new java.net.URI(u).getHost();
            if (h == null && !u.contains("://")) {
                h = new java.net.URI("https://" + u).getHost();
            }
            return h != null ? h.toLowerCase(Locale.US) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** True if the URL host equals one of the domains or is a subdomain of it. */
    public static boolean hostMatches(String url, String... domains) {
        String host = hostOf(url);
        if (host == null) return false;
        for (String d : domains) {
            if (host.equals(d) || host.endsWith("." + d)) return true;
        }
        return false;
    }

    public static boolean isYouTubeUrl(String url) {
        return hostMatches(url, "youtube.com", "youtu.be", "youtube-nocookie.com");
    }

    public static boolean isTwitterUrl(String url) {
        return hostMatches(url, "twitter.com", "x.com", "t.co", "fxtwitter.com", "vxtwitter.com");
    }

    public static boolean isInstagramUrl(String url) {
        return hostMatches(url, "instagram.com", "instagr.am", "ddinstagram.com");
    }

    public static boolean isTikTokUrl(String url) {
        return hostMatches(url, "tiktok.com");
    }

    public static boolean isRedditUrl(String url) {
        return hostMatches(url, "reddit.com", "redd.it");
    }

    // =========================================================================================
    // 1. YOUTUBE EXTRACTOR (NewPipeExtractor v0.26.2 with 3-Attempt Retry & Adaptive Muxing)
    // =========================================================================================

    public AnalyzedMedia extractYouTubeWithNewPipe(String rawUrl) {
        EngineManager.ensureInitializedSync(appContext);
        String cleanUrl = normalizeYouTubeUrl(rawUrl);

        StreamInfo info = null;
        Exception lastErr = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                StreamingService ytService = ServiceList.YouTube;
                info = StreamInfo.getInfo(ytService, cleanUrl);
                boolean hasMuxed = info.getVideoStreams() != null && !info.getVideoStreams().isEmpty();
                boolean hasAdaptive = info.getVideoOnlyStreams() != null && !info.getVideoOnlyStreams().isEmpty();
                if (hasMuxed || hasAdaptive) {
                    break;
                }
            } catch (Exception e) {
                lastErr = e;
                Log.w(TAG, "NewPipe YouTube attempt " + attempt + "/3 failed: " + e.getMessage());
                if (attempt < 3) {
                    try {
                        Thread.sleep(attempt * 600L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        if (info == null) {
            if (lastErr != null) {
                Log.w(TAG, "NewPipe YouTube extraction completely failed", lastErr);
            }
            return null;
        }

        String title = info.getName() != null && !info.getName().isEmpty()
                ? info.getName()
                : "YouTube_Video_" + System.currentTimeMillis();
        String uploader = info.getUploaderName() != null ? info.getUploaderName() : "YouTube";
        long duration = info.getDuration() > 0 ? info.getDuration() : 0;
        String thumbnail = getBestNewPipeImage(info.getThumbnails());

        // Find best audio stream (prefer M4A/MP4A so FFmpeg can mux into MP4 with -c copy)
        AudioStream bestM4aAudio = null;
        AudioStream bestAnyAudio = null;
        List<AudioStream> audioStreams = info.getAudioStreams();
        if (audioStreams != null) {
            for (AudioStream as : audioStreams) {
                if (as.getContent() == null || as.getContent().isEmpty()) continue;
                if (bestAnyAudio == null || as.getAverageBitrate() > bestAnyAudio.getAverageBitrate()) {
                    bestAnyAudio = as;
                }
                String suffix = as.getFormat() != null ? as.getFormat().getSuffix() : "";
                if ("m4a".equalsIgnoreCase(suffix) || "mp4".equalsIgnoreCase(suffix)) {
                    if (bestM4aAudio == null || as.getAverageBitrate() > bestM4aAudio.getAverageBitrate()) {
                        bestM4aAudio = as;
                    }
                }
            }
        }
        AudioStream chosenAudio = bestM4aAudio != null ? bestM4aAudio : bestAnyAudio;
        String bestAudioUrl = chosenAudio != null ? chosenAudio.getContent() : null;

        List<MediaFormatOption> options = new ArrayList<>();
        Map<Integer, MediaFormatOption> resolutionOptions = new LinkedHashMap<>();

        // 1A. Collect progressive (muxed video+audio) streams first (typically 720p, 360p)
        List<VideoStream> muxedStreams = info.getVideoStreams();
        if (muxedStreams != null) {
            List<VideoStream> sortedMuxed = new ArrayList<>(muxedStreams);
            Collections.sort(sortedMuxed, (a, b) -> Integer.compare(b.getHeight(), a.getHeight()));
            for (VideoStream vs : sortedMuxed) {
                if (vs.getContent() == null || vs.getContent().isEmpty()) continue;
                int h = vs.getHeight() > 0 ? vs.getHeight() : parseResolutionHeight(vs.getResolution());
                if (h <= 0) h = 360;
                if (!resolutionOptions.containsKey(h)) {
                    long size = vs.getItagItem() != null && vs.getItagItem().getContentLength() > 0
                            ? vs.getItagItem().getContentLength() : 0;
                    String qualityTag = getQualityBadge(h);
                    resolutionOptions.put(h, new MediaFormatOption(
                            "direct_url:" + vs.getContent(),
                            h + "p (" + qualityTag + ")",
                            appContext.getString(R.string.format_fast_direct_stream),
                            "mp4",
                            size,
                            false,
                            true
                    ));
                }
            }
        }

        // 1B. Collect high-res adaptive VideoOnlyStreams (4K, 1440p, 1080p, 720p, 480p, 240p, 144p)
        // and pair with bestAudioUrl for ultra-fast FFmpeg muxing!
        List<VideoStream> videoOnlyStreams = info.getVideoOnlyStreams();
        if (videoOnlyStreams != null && bestAudioUrl != null) {
            List<VideoStream> sortedVo = new ArrayList<>(videoOnlyStreams);
            Collections.sort(sortedVo, (a, b) -> Integer.compare(b.getHeight(), a.getHeight()));
            for (VideoStream vo : sortedVo) {
                if (vo.getContent() == null || vo.getContent().isEmpty()) continue;
                String suffix = vo.getFormat() != null ? vo.getFormat().getSuffix() : "mp4";
                // Prefer mp4 container streams for instant copy muxing
                int h = vo.getHeight() > 0 ? vo.getHeight() : parseResolutionHeight(vo.getResolution());
                if (h < 144) continue;

                boolean alreadyHas = resolutionOptions.containsKey(h);
                if (!alreadyHas || ("mp4".equalsIgnoreCase(suffix) && !resolutionOptions.get(h).isDirectHttp()
                        && resolutionOptions.get(h).getDetails().contains("WEBM"))) {
                    long vSize = vo.getItagItem() != null && vo.getItagItem().getContentLength() > 0
                            ? vo.getItagItem().getContentLength() : 0;
                    long aSize = chosenAudio != null && chosenAudio.getItagItem() != null
                            && chosenAudio.getItagItem().getContentLength() > 0
                            ? chosenAudio.getItagItem().getContentLength() : 0;
                    long totalSize = (vSize > 0 && aSize > 0) ? (vSize + aSize) : vSize;

                    String qualityTag = getQualityBadge(h);
                    int fps = vo.getFps();
                    String details = "MP4 • " + (fps > 30 ? fps + "fps • " : "") + "NewPipe + FFmpeg HD Birleştirme";

                    resolutionOptions.put(h, new MediaFormatOption(
                            "newpipe_mux:" + vo.getContent() + "|" + bestAudioUrl,
                            h + "p (" + qualityTag + ")",
                            details,
                            "mp4",
                            totalSize,
                            false,
                            false
                    ));
                }
            }
        }

        // Sort all resolution options descending (4K -> 2K -> 1080p -> 720p -> 480p -> 360p)
        List<Integer> heights = new ArrayList<>(resolutionOptions.keySet());
        Collections.sort(heights, Collections.reverseOrder());
        for (Integer h : heights) {
            options.add(resolutionOptions.get(h));
        }

        // Audio-Only options via NewPipe (or FFmpeg extraction from muxed stream)
        if (bestAudioUrl != null) {
            long audioSize = chosenAudio.getItagItem() != null && chosenAudio.getItagItem().getContentLength() > 0
                    ? chosenAudio.getItagItem().getContentLength() : 0;
            int bitrate = chosenAudio.getAverageBitrate() > 0 ? chosenAudio.getAverageBitrate() : 128;

            options.add(new MediaFormatOption(
                    "ffmpeg_mp3:" + bestAudioUrl,
                    appContext.getString(R.string.format_best_audio),
                    "MP3 (dönüştürülmüş) • " + bitrate + " kbps",
                    "mp3",
                    audioSize,
                    true,
                    false
            ));

            options.add(new MediaFormatOption(
                    "direct_url:" + bestAudioUrl,
                    "M4A Orijinal Ses (" + bitrate + " kbps)",
                    "M4A (orijinal) • Dönüştürmesiz doğrudan ses",
                    "m4a",
                    audioSize,
                    true,
                    true
            ));
        } else if (muxedStreams != null && !muxedStreams.isEmpty() && muxedStreams.get(0).getContent() != null) {
            String muxedUrl = muxedStreams.get(0).getContent();
            options.add(new MediaFormatOption(
                    "ffmpeg_mp3:" + muxedUrl,
                    appContext.getString(R.string.format_best_audio),
                    "MP3 • FFmpeg Yüksek Kalite Ses Dönüştürücü",
                    "mp3",
                    0,
                    true,
                    false
            ));
        }

        if (options.isEmpty()) {
            return null;
        }

        return new AnalyzedMedia(cleanUrl, title, uploader, duration, thumbnail, options);
    }

    public static String normalizeYouTubeUrl(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        Matcher m = Pattern.compile("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/|/live/|/v/)([a-zA-Z0-9_-]{11})").matcher(trimmed);
        if (m.find()) {
            return "https://www.youtube.com/watch?v=" + m.group(1);
        }
        return trimmed;
    }

    private String getBestNewPipeImage(List<Image> images) {
        if (images == null || images.isEmpty()) return null;
        Image best = null;
        int maxArea = -1;
        for (Image img : images) {
            int area = img.getWidth() * img.getHeight();
            if (area > maxArea) {
                maxArea = area;
                best = img;
            }
        }
        return best != null ? best.getUrl() : images.get(images.size() - 1).getUrl();
    }

    // =========================================================================================
    // 2. TWITTER / X EXTRACTOR (FxTwitter API + VxTwitter API + Official Syndication API)
    // =========================================================================================

    public AnalyzedMedia extractTwitterOrX(String rawUrl) {
        try {
            String resolvedUrl = resolveRedirectUrl(rawUrl);
            String tweetId = extractTweetId(resolvedUrl);
            if (tweetId == null) {
                tweetId = extractTweetId(rawUrl);
            }
            if (tweetId == null) {
                return null;
            }

            // Tier A: Try Official Twitter Syndication API first (returns all bitrate variants!)
            AnalyzedMedia syndicationMedia = extractTwitterSyndication(resolvedUrl, tweetId);
            if (syndicationMedia != null) {
                return syndicationMedia;
            }

            // Tier B: Try FxTwitter JSON API (very reliable for all public X videos & age-restricted tweets)
            AnalyzedMedia fxMedia = extractFxTwitter(resolvedUrl, tweetId);
            if (fxMedia != null) {
                return fxMedia;
            }

            // Tier C: Try VxTwitter JSON API
            return extractVxTwitter(resolvedUrl, tweetId);
        } catch (Exception e) {
            Log.w(TAG, "Twitter/X native extraction error", e);
            return null;
        }
    }

    private String extractTweetId(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("(?:status|statuses)/(\\d+)").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private AnalyzedMedia extractTwitterSyndication(String sourceUrl, String tweetId) {
        try {
            String token = String.valueOf(Math.round((Double.parseDouble(tweetId) / 1e15) * Math.PI));
            String apiUrl = "https://cdn.syndication.twimg.com/tweet-result?id=" + tweetId + "&lang=en&token=" + token;
            Request req = new Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "application/json")
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                String jsonStr = resp.body().string();
                JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();

                String text = root.has("text") ? root.get("text").getAsString() : "X Video " + tweetId;
                if (text.length() > 90) {
                    text = text.substring(0, 90).trim();
                }
                String uploader = "X / Twitter";
                if (root.has("user") && root.get("user").isJsonObject()) {
                    JsonObject user = root.getAsJsonObject("user");
                    if (user.has("name")) {
                        uploader = user.get("name").getAsString() + (user.has("screen_name") ? " (@" + user.get("screen_name").getAsString() + ")" : "");
                    }
                }

                String thumb = null;
                long durationSec = 0;
                List<Mp4Variant> variants = new ArrayList<>();

                if (root.has("mediaDetails") && root.get("mediaDetails").isJsonArray()) {
                    JsonArray mediaArr = root.getAsJsonArray("mediaDetails");
                    for (JsonElement el : mediaArr) {
                        JsonObject mediaObj = el.getAsJsonObject();
                        if (thumb == null && mediaObj.has("media_url_https")) {
                            thumb = mediaObj.get("media_url_https").getAsString();
                        }
                        if (mediaObj.has("video_info") && mediaObj.get("video_info").isJsonObject()) {
                            JsonObject videoInfo = mediaObj.getAsJsonObject("video_info");
                            if (videoInfo.has("duration_millis")) {
                                durationSec = videoInfo.get("duration_millis").getAsLong() / 1000L;
                            }
                            if (videoInfo.has("variants") && videoInfo.get("variants").isJsonArray()) {
                                for (JsonElement vEl : videoInfo.getAsJsonArray("variants")) {
                                    JsonObject vObj = vEl.getAsJsonObject();
                                    String contentType = vObj.has("content_type") ? vObj.get("content_type").getAsString() : "";
                                    String vUrl = vObj.has("url") ? vObj.get("url").getAsString() : "";
                                    int bitrate = vObj.has("bitrate") ? vObj.get("bitrate").getAsInt() : 0;
                                    if ("video/mp4".equalsIgnoreCase(contentType) && !vUrl.isEmpty()) {
                                        variants.add(new Mp4Variant(vUrl, bitrate));
                                    }
                                }
                            }
                        }
                    }
                }

                if (variants.isEmpty()) return null;

                Collections.sort(variants, (a, b) -> Integer.compare(b.bitrate, a.bitrate));
                List<MediaFormatOption> options = new ArrayList<>();

                for (int i = 0; i < variants.size(); i++) {
                    Mp4Variant v = variants.get(i);
                    String resLabel = extractResolutionFromTwitterUrl(v.url);
                    if (resLabel == null) {
                        resLabel = (i == 0) ? "HD (En Yüksek Kalite)" : "SD Kalite #" + (i + 1);
                    }
                    String bitrateStr = v.bitrate > 0 ? (v.bitrate / 1000) + " kbps" : "MP4";
                    options.add(new MediaFormatOption(
                            "direct_url:" + v.url,
                            resLabel + " Video",
                            "MP4 • " + bitrateStr + " • X CDN",
                            "mp4",
                            0,
                            false,
                            true
                    ));
                }

                // Add MP3 Audio extraction from best MP4 stream
                options.add(new MediaFormatOption(
                        "ffmpeg_mp3:" + variants.get(0).url,
                        appContext.getString(R.string.format_best_audio),
                        "MP3 • Ses Dosyasına Dönüştür (FFmpeg)",
                        "mp3",
                        0,
                        true,
                        false
                ));

                return new AnalyzedMedia(sourceUrl, text, uploader, durationSec, thumb, options);
            }
        } catch (Exception e) {
            Log.w(TAG, "Twitter Syndication fallback failed: " + e.getMessage());
            return null;
        }
    }

    private AnalyzedMedia extractFxTwitter(String sourceUrl, String tweetId) {
        try {
            String apiUrl = "https://api.fxtwitter.com/status/" + tweetId;
            Request req = new Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "VidownPhone/2.0 (Android)")
                    .header("Accept", "application/json")
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                if (!root.has("tweet") || !root.get("tweet").isJsonObject()) return null;

                JsonObject tweet = root.getAsJsonObject("tweet");
                String title = tweet.has("text") && !tweet.get("text").getAsString().isEmpty()
                        ? tweet.get("text").getAsString()
                        : "X_Video_" + tweetId;
                if (title.length() > 90) title = title.substring(0, 90).trim();

                String uploader = "X / Twitter";
                if (tweet.has("author") && tweet.get("author").isJsonObject()) {
                    JsonObject author = tweet.getAsJsonObject("author");
                    if (author.has("name")) {
                        uploader = author.get("name").getAsString();
                    }
                }

                if (!tweet.has("media") || !tweet.get("media").isJsonObject()) return null;
                JsonObject media = tweet.getAsJsonObject("media");
                if (!media.has("videos") || !media.get("videos").isJsonArray()) return null;

                JsonArray videos = media.getAsJsonArray("videos");
                if (videos.isEmpty()) return null;

                JsonObject firstVideo = videos.get(0).getAsJsonObject();
                String videoUrl = firstVideo.has("url") ? firstVideo.get("url").getAsString() : null;
                if (videoUrl == null || videoUrl.isEmpty()) return null;

                String thumb = firstVideo.has("thumbnail_url") ? firstVideo.get("thumbnail_url").getAsString() : null;
                long duration = firstVideo.has("duration") ? Math.round(firstVideo.get("duration").getAsDouble()) : 0;
                int height = firstVideo.has("height") ? firstVideo.get("height").getAsInt() : 720;
                int width = firstVideo.has("width") ? firstVideo.get("width").getAsInt() : 1280;

                List<MediaFormatOption> options = new ArrayList<>();
                options.add(new MediaFormatOption(
                        "direct_url:" + videoUrl,
                        Math.min(width, height) + "p HD Video (" + width + "x" + height + ")",
                        "MP4 • Orijinal X Video Akışı",
                        "mp4",
                        0,
                        false,
                        true
                ));

                // Also parse any extra variants if present in formats array
                if (firstVideo.has("formats") && firstVideo.get("formats").isJsonArray()) {
                    JsonArray formats = firstVideo.getAsJsonArray("formats");
                    for (JsonElement fEl : formats) {
                        JsonObject fObj = fEl.getAsJsonObject();
                        String container = fObj.has("container") ? fObj.get("container").getAsString() : "";
                        String u = fObj.has("url") ? fObj.get("url").getAsString() : "";
                        int br = fObj.has("bitrate") ? fObj.get("bitrate").getAsInt() : 0;
                        if ("mp4".equalsIgnoreCase(container) && !u.isEmpty() && !u.equals(videoUrl)) {
                            String res = extractResolutionFromTwitterUrl(u);
                            options.add(new MediaFormatOption(
                                    "direct_url:" + u,
                                    (res != null ? res : (br / 1000) + " kbps") + " Video",
                                    "MP4 • " + (br > 0 ? (br / 1000) + " kbps" : "Alternatif Kalite"),
                                    "mp4",
                                    0,
                                    false,
                                    true
                            ));
                        }
                    }
                }

                options.add(new MediaFormatOption(
                        "ffmpeg_mp3:" + videoUrl,
                        appContext.getString(R.string.format_best_audio),
                        "MP3 • Ses Dosyasına Dönüştür (FFmpeg)",
                        "mp3",
                        0,
                        true,
                        false
                ));

                return new AnalyzedMedia(sourceUrl, title, uploader, duration, thumb, options);
            }
        } catch (Exception e) {
            Log.w(TAG, "FxTwitter fallback failed: " + e.getMessage());
            return null;
        }
    }

    private AnalyzedMedia extractVxTwitter(String sourceUrl, String tweetId) {
        try {
            String apiUrl = "https://api.vxtwitter.com/Twitter/status/" + tweetId;
            Request req = new Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", "VidownPhone/2.0 (Android)")
                    .header("Accept", "application/json")
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                if (!root.has("media_extended") || !root.get("media_extended").isJsonArray()) return null;

                String title = root.has("text") ? root.get("text").getAsString() : "X_Video_" + tweetId;
                if (title.length() > 90) title = title.substring(0, 90).trim();
                String uploader = root.has("user_name") ? root.get("user_name").getAsString() : "X / Twitter";

                JsonArray mediaArr = root.getAsJsonArray("media_extended");
                for (JsonElement el : mediaArr) {
                    JsonObject m = el.getAsJsonObject();
                    String type = m.has("type") ? m.get("type").getAsString() : "";
                    String vUrl = m.has("url") ? m.get("url").getAsString() : "";
                    if (("video".equalsIgnoreCase(type) || "gif".equalsIgnoreCase(type)) && !vUrl.isEmpty()) {
                        String thumb = m.has("thumbnail_url") ? m.get("thumbnail_url").getAsString() : null;
                        long dur = m.has("duration_millis") ? m.get("duration_millis").getAsLong() / 1000L : 0;

                        List<MediaFormatOption> options = new ArrayList<>();
                        options.add(new MediaFormatOption(
                                "direct_url:" + vUrl,
                                "HD Video (MP4)",
                                "MP4 • Doğrudan X Akışı",
                                "mp4",
                                0,
                                false,
                                true
                        ));
                        options.add(new MediaFormatOption(
                                "ffmpeg_mp3:" + vUrl,
                                appContext.getString(R.string.format_best_audio),
                                "MP3 • Ses Dosyası",
                                "mp3",
                                0,
                                true,
                                false
                        ));
                        return new AnalyzedMedia(sourceUrl, title, uploader, dur, thumb, options);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "VxTwitter fallback failed: " + e.getMessage());
        }
        return null;
    }

    private String extractResolutionFromTwitterUrl(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("/(\\d+)x(\\d+)/").matcher(url);
        if (m.find()) {
            try {
                int w = Integer.parseInt(m.group(1));
                int h = Integer.parseInt(m.group(2));
                return Math.min(w, h) + "p (" + w + "x" + h + ")";
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    // =========================================================================================
    // 3. INSTAGRAM EXTRACTOR (Reels, Posts, IGTV, Share Redirects)
    // =========================================================================================

    public static class InstagramStoryTarget {
        public final boolean isHighlight;
        public final String username;
        public final String highlightId;
        public final String targetStoryPk;
        public final String canonicalUrl;

        public InstagramStoryTarget(boolean isHighlight, String username, String highlightId, String targetStoryPk, String canonicalUrl) {
            this.isHighlight = isHighlight;
            this.username = username;
            this.highlightId = highlightId;
            this.targetStoryPk = targetStoryPk;
            this.canonicalUrl = canonicalUrl;
        }
    }

    public static InstagramStoryTarget parseStoryOrHighlightUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            if (url.contains("instagram.com/") && url.contains("exln=")) {
                url = url.replace("exln=", "rpxt=");
            }

            String storyMediaIdParam = null;
            Matcher pMatcher = Pattern.compile("[?&]story_media_id=([A-Za-z0-9_]+)").matcher(url);
            if (pMatcher.find()) {
                storyMediaIdParam = pMatcher.group(1);
            }

            String targetStoryPk = storyMediaIdParam;
            if (targetStoryPk != null && targetStoryPk.contains("_")) {
                targetStoryPk = targetStoryPk.split("_")[0];
            }

            // Case 1: Shortlink /s/<base64>
            Matcher sMatcher = Pattern.compile("instagram\\.com/s/([A-Za-z0-9_-]+)").matcher(url);
            if (sMatcher.find()) {
                String token = sMatcher.group(1);
                String decoded = null;
                try {
                    // Try java.util.Base64 (standard in API 26+)
                    byte[] bytes;
                    try {
                        bytes = java.util.Base64.getUrlDecoder().decode(token);
                    } catch (Exception ex) {
                        bytes = java.util.Base64.getDecoder().decode(token);
                    }
                    decoded = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) { Log.w(TAG, "Ignored failure", e); }

                if (decoded != null && decoded.startsWith("highlight:")) {
                    String hid = decoded.substring("highlight:".length());
                    String canon = "https://www.instagram.com/stories/highlights/" + hid + "/";
                    if (storyMediaIdParam != null && !storyMediaIdParam.isEmpty()) {
                        canon += "?story_media_id=" + storyMediaIdParam;
                    }
                    return new InstagramStoryTarget(true, null, hid, targetStoryPk, canon);
                }
            }

            // Case 2: /stories/highlights/<highlight_id>
            Matcher hlMatcher = Pattern.compile("instagram\\.com/stories/highlights/(\\d+)").matcher(url);
            if (hlMatcher.find()) {
                String hid = hlMatcher.group(1);
                String canon = "https://www.instagram.com/stories/highlights/" + hid + "/";
                if (storyMediaIdParam != null && !storyMediaIdParam.isEmpty()) {
                    canon += "?story_media_id=" + storyMediaIdParam;
                }
                return new InstagramStoryTarget(true, null, hid, targetStoryPk, canon);
            }

            // Case 3: /stories/<username>/<story_pk> or /stories/<username>/
            Matcher stMatcher = Pattern.compile("instagram\\.com/stories/([^/?#]+)(?:/(\\d+))?").matcher(url);
            if (stMatcher.find()) {
                String user = stMatcher.group(1);
                if (!"highlights".equalsIgnoreCase(user)) {
                    String pk = stMatcher.group(2);
                    if ((pk == null || pk.isEmpty()) && targetStoryPk != null && !targetStoryPk.isEmpty()) {
                        pk = targetStoryPk;
                    }
                    String canon = "https://www.instagram.com/stories/" + user + "/" + (pk != null ? pk + "/" : "");
                    return new InstagramStoryTarget(false, user, null, pk, canon);
                }
            }
        } catch (Exception e) { Log.w(TAG, "Ignored failure", e); }
        return null;
    }

    public AnalyzedMedia extractInstagram(String rawUrl) {
        try {
            if (rawUrl != null && rawUrl.contains("instagram.com/") && rawUrl.contains("exln=")) {
                rawUrl = rawUrl.replace("exln=", "rpxt=");
            }

            // Check if this is an Instagram Story or Highlight URL (parse directly to avoid login redirect)
            InstagramStoryTarget storyTarget = parseStoryOrHighlightUrl(rawUrl);
            if (storyTarget == null) {
                String resolvedUrl = resolveRedirectUrl(rawUrl);
                storyTarget = parseStoryOrHighlightUrl(resolvedUrl);
            }

            if (storyTarget != null) {
                AnalyzedMedia storyMedia = extractInstagramStoryOrHighlight(storyTarget);
                if (storyMedia != null) {
                    return storyMedia;
                }
            }

            String resolvedUrl = resolveRedirectUrl(rawUrl);
            String shortcode = extractInstagramShortcode(resolvedUrl);
            if (shortcode == null) {
                shortcode = extractInstagramShortcode(rawUrl);
            }
            if (shortcode == null) {
                return null;
            }

            // Tier A0: Authenticated Instagram v1 API (Best for Carousels & Logged-in Posts)
            AnalyzedMedia v1Media = extractInstagramApiV1(resolvedUrl, shortcode);
            if (v1Media != null) {
                return v1Media;
            }

            // Tier A: Instagram GraphQL API with X-IG-App-ID
            AnalyzedMedia gqlMedia = extractInstagramGraphQl(resolvedUrl, shortcode);
            if (gqlMedia != null) {
                return gqlMedia;
            }

            // Tier B: Instagram Embed Captioned HTML/JSON Parser
            AnalyzedMedia embedMedia = extractInstagramEmbed(resolvedUrl, shortcode);
            if (embedMedia != null) {
                return embedMedia;
            }

            // Tier C: DDInstagram / KKInstagram OpenGraph Meta Parser
            return extractInstagramViaProxyMeta(resolvedUrl, shortcode);
        } catch (Exception e) {
            Log.w(TAG, "Instagram native extraction error", e);
            return null;
        }
    }

    private String shortcodeToMediaId(String shortcode) {
        if (shortcode == null || shortcode.isEmpty()) return null;
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        java.math.BigInteger id = java.math.BigInteger.ZERO;
        for (int i = 0; i < shortcode.length(); i++) {
            int idx = alphabet.indexOf(shortcode.charAt(i));
            if (idx < 0) break;
            id = id.multiply(java.math.BigInteger.valueOf(64)).add(java.math.BigInteger.valueOf(idx));
        }
        return id.compareTo(java.math.BigInteger.ZERO) > 0 ? id.toString() : null;
    }

    private String getBestCandidateUrl(JsonObject node) {
        if (node == null) return null;
        if (node.has("image_versions2") && node.get("image_versions2").isJsonObject()) {
            JsonObject iv2 = node.getAsJsonObject("image_versions2");
            if (iv2.has("candidates") && iv2.get("candidates").isJsonArray()) {
                JsonArray cands = iv2.getAsJsonArray("candidates");
                if (!cands.isEmpty() && cands.get(0).isJsonObject()) {
                    JsonObject first = cands.get(0).getAsJsonObject();
                    if (first.has("url")) return first.get("url").getAsString();
                }
            }
        }
        return null;
    }

    private String getBestVideoVersionUrl(JsonObject node) {
        if (node == null) return null;
        if (node.has("video_versions") && node.get("video_versions").isJsonArray()) {
            JsonArray vvs = node.getAsJsonArray("video_versions");
            if (!vvs.isEmpty() && vvs.get(0).isJsonObject()) {
                JsonObject first = vvs.get(0).getAsJsonObject();
                if (first.has("url")) return first.get("url").getAsString();
            }
        }
        return null;
    }

    private String extractAudioUrlFromDashManifest(JsonObject node) {
        if (node == null || !node.has("video_dash_manifest") || node.get("video_dash_manifest").isJsonNull()) {
            return null;
        }
        try {
            String manifest = node.get("video_dash_manifest").getAsString();
            if (manifest == null || manifest.isEmpty()) return null;
            Matcher m = Pattern.compile(
                    "(?s)<AdaptationSet[^>]*?(?:contentType=\"audio\"|mimeType=\"audio/[^\"]*\")[^>]*>.*?<BaseURL>([^<]+)</BaseURL>"
            ).matcher(manifest);
            if (!m.find()) {
                m = Pattern.compile("(?s)<Representation[^>]*mimeType=\"audio/[^\"]*\"[^>]*>.*?<BaseURL>([^<]+)</BaseURL>").matcher(manifest);
            }
            if (m.find()) {
                return m.group(1)
                        .replace("&amp;", "&")
                        .replace("\\u0026", "&")
                        .trim();
            }
        } catch (Exception e) { Log.w(TAG, "Ignored failure", e); }
        return null;
    }

    private String[] extractMusicTrack(JsonObject node) {
        if (node == null) return null;
        try {
            String[] stickerArrays = new String[]{
                    "story_music_stickers",
                    "music_stickers",
                    "story_audio_stickers"
            };
            for (String arrKey : stickerArrays) {
                if (node.has(arrKey) && node.get(arrKey).isJsonArray()) {
                    JsonArray arr = node.getAsJsonArray(arrKey);
                    for (JsonElement el : arr) {
                        if (!el.isJsonObject()) continue;
                        JsonObject sticker = el.getAsJsonObject();
                        if (sticker.has("music_asset_info") && sticker.get("music_asset_info").isJsonObject()) {
                            String[] found = parseMusicAssetObject(sticker.getAsJsonObject("music_asset_info"));
                            if (found != null) return found;
                        }
                        String[] direct = parseMusicAssetObject(sticker);
                        if (direct != null) return direct;
                    }
                }
            }
            if (node.has("story_bloks_stickers") && node.get("story_bloks_stickers").isJsonArray()) {
                String rawBloks = node.get("story_bloks_stickers").toString();
                Matcher m = Pattern.compile("(https?:\\\\?/\\\\?/[^\"'\\s]+?\\.(?:m4a|mp3|aac)[^\"'\\s]*)").matcher(rawBloks);
                if (m.find()) {
                    String u = m.group(1).replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&");
                    return new String[]{u, "Hikaye Müziği"};
                }
            }
            if (node.has("clips_metadata") && node.get("clips_metadata").isJsonObject()) {
                JsonObject clips = node.getAsJsonObject("clips_metadata");
                if (clips.has("music_info") && clips.get("music_info").isJsonObject()) {
                    JsonObject mi = clips.getAsJsonObject("music_info");
                    if (mi.has("music_asset_info") && mi.get("music_asset_info").isJsonObject()) {
                        String[] found = parseMusicAssetObject(mi.getAsJsonObject("music_asset_info"));
                        if (found != null) return found;
                    }
                }
                if (clips.has("original_sound_info") && clips.get("original_sound_info").isJsonObject()) {
                    String[] found = parseMusicAssetObject(clips.getAsJsonObject("original_sound_info"));
                    if (found != null) return found;
                }
            }
            if (node.has("music_metadata") && node.get("music_metadata").isJsonObject()) {
                JsonObject mm = node.getAsJsonObject("music_metadata");
                if (mm.has("music_info") && mm.get("music_info").isJsonObject()) {
                    JsonObject mi = mm.getAsJsonObject("music_info");
                    if (mi.has("music_asset_info") && mi.get("music_asset_info").isJsonObject()) {
                        String[] found = parseMusicAssetObject(mi.getAsJsonObject("music_asset_info"));
                        if (found != null) return found;
                    }
                }
                if (mm.has("original_sound_info") && mm.get("original_sound_info").isJsonObject()) {
                    String[] found = parseMusicAssetObject(mm.getAsJsonObject("original_sound_info"));
                    if (found != null) return found;
                }
            }
        } catch (Exception e) { Log.w(TAG, "Ignored failure", e); }
        return null;
    }

    private String[] parseMusicAssetObject(JsonObject asset) {
        if (asset == null) return null;
        String url = null;
        if (asset.has("progressive_download_url") && !asset.get("progressive_download_url").isJsonNull()) {
            url = asset.get("progressive_download_url").getAsString();
        } else if (asset.has("fast_start_progressive_download_url") && !asset.get("fast_start_progressive_download_url").isJsonNull()) {
            url = asset.get("fast_start_progressive_download_url").getAsString();
        } else if (asset.has("audio_src") && !asset.get("audio_src").isJsonNull()) {
            url = asset.get("audio_src").getAsString();
        }
        if (url == null || url.isEmpty()) return null;
        String title = "";
        String artist = "";
        if (asset.has("title") && !asset.get("title").isJsonNull()) {
            title = asset.get("title").getAsString();
        }
        if (asset.has("display_artist") && !asset.get("display_artist").isJsonNull()) {
            artist = asset.get("display_artist").getAsString();
        } else if (asset.has("ig_username") && !asset.get("ig_username").isJsonNull()) {
            artist = "@" + asset.get("ig_username").getAsString();
        }
        String label = (!artist.isEmpty() && !title.isEmpty()) ? (artist + " - " + title)
                : (!title.isEmpty() ? title : "Orijinal Ses / Müzik");
        return new String[]{url, label};
    }

    private AnalyzedMedia extractInstagramApiV1(String sourceUrl, String shortcode) {
        try {
            String mediaId = shortcodeToMediaId(shortcode);
            if (mediaId == null) return null;
            String cookie = CookieBridge.getCookieHeaderForUrl("https://www.instagram.com");
            if (cookie == null || !cookie.contains("sessionid")) return null;

            Request req = new Request.Builder()
                    .url("https://i.instagram.com/api/v1/media/" + mediaId + "/info/")
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", "*/*")
                    .header("X-IG-App-ID", "936619743392459")
                    .header("Cookie", cookie)
                    .header("Referer", "https://www.instagram.com/")
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                if (!root.has("items") || !root.get("items").isJsonArray()) return null;
                JsonArray items = root.getAsJsonArray("items");
                if (items.isEmpty() || !items.get(0).isJsonObject()) return null;

                JsonObject item = items.get(0).getAsJsonObject();
                String uploader = "Instagram";
                if (item.has("user") && item.get("user").isJsonObject()) {
                    JsonObject u = item.getAsJsonObject("user");
                    if (u.has("username") && !u.get("username").isJsonNull()) {
                        uploader = "@" + u.get("username").getAsString();
                    }
                }
                String title = uploader + " Gönderisi";
                if (item.has("caption") && item.get("caption").isJsonObject()) {
                    JsonObject cap = item.getAsJsonObject("caption");
                    if (cap.has("text") && !cap.get("text").isJsonNull()) {
                        String t = cap.get("text").getAsString().trim();
                        if (!t.isEmpty()) {
                            title = t.length() > 80 ? t.substring(0, 80) : t;
                        }
                    }
                }
                String mainThumb = getBestCandidateUrl(item);
                String[] postMusic = extractMusicTrack(item);
                String postDashAudio = extractAudioUrlFromDashManifest(item);
                List<MediaFormatOption> options = new ArrayList<>();

                if (item.has("carousel_media") && item.get("carousel_media").isJsonArray()) {
                    JsonArray carousel = item.getAsJsonArray("carousel_media");
                    int idx = 1;
                    for (JsonElement el : carousel) {
                        if (!el.isJsonObject()) continue;
                        JsonObject slide = el.getAsJsonObject();
                        String slideVid = getBestVideoVersionUrl(slide);
                        String slideImg = getBestCandidateUrl(slide);
                        String slideDashAudio = extractAudioUrlFromDashManifest(slide);
                        String[] slideMusic = extractMusicTrack(slide);
                        if (slideMusic == null) slideMusic = postMusic;
                        if (mainThumb == null && slideImg != null) mainThumb = slideImg;

                        String audioToMux = slideDashAudio != null ? slideDashAudio
                                : (slideMusic != null ? slideMusic[0] : postDashAudio);

                        if (slideVid != null && !slideVid.isEmpty()) {
                            String fmtId = (audioToMux != null && !audioToMux.isEmpty())
                                    ? ("newpipe_mux:" + slideVid + "|" + audioToMux)
                                    : ("direct_url:" + slideVid);
                            options.add(new MediaFormatOption(
                                    fmtId,
                                    "Kaydırma #" + idx + " • Video (MP4)",
                                    uploader + " • " + idx + ". Kaydırma Videosu",
                                    "mp4",
                                    0,
                                    false,
                                    true,
                                    slideImg
                            ));
                        } else if (slideImg != null && !slideImg.isEmpty()) {
                            if (audioToMux != null && !audioToMux.isEmpty()) {
                                options.add(new MediaFormatOption(
                                        "image_audio_mux:" + slideImg + "|" + audioToMux,
                                        "Kaydırma #" + idx + " • Müzikli Video (MP4)",
                                        uploader + " • Fotoğraf + Müzik Birleşik Video",
                                        "mp4",
                                        0,
                                        false,
                                        true,
                                        slideImg
                                ));
                            }
                            options.add(new MediaFormatOption(
                                    "direct_url:" + slideImg,
                                    "Kaydırma #" + idx + " • Fotoğraf (JPG)",
                                    uploader + " • " + idx + ". Kaydırma Görseli",
                                    "jpg",
                                    0,
                                    false,
                                    true,
                                    slideImg
                            ));
                        }
                        idx++;
                    }
                } else {
                    String vid = getBestVideoVersionUrl(item);
                    String audioToMux = postDashAudio != null ? postDashAudio
                            : (postMusic != null ? postMusic[0] : null);
                    if (vid != null && !vid.isEmpty()) {
                        String fmtId = (audioToMux != null && !audioToMux.isEmpty())
                                ? ("newpipe_mux:" + vid + "|" + audioToMux)
                                : ("direct_url:" + vid);
                        options.add(new MediaFormatOption(
                                fmtId,
                                "Full HD Video (MP4)",
                                uploader + " • Orijinal Instagram Videosu",
                                "mp4",
                                0,
                                false,
                                true,
                                mainThumb
                        ));
                        options.add(new MediaFormatOption(
                                "ffmpeg_mp3:" + (audioToMux != null ? audioToMux : vid),
                                appContext.getString(R.string.format_best_audio),
                                "MP3 • Ses Dosyası",
                                "mp3",
                                0,
                                true,
                                false,
                                mainThumb
                        ));
                    } else if (mainThumb != null && !mainThumb.isEmpty()) {
                        if (audioToMux != null && !audioToMux.isEmpty()) {
                            options.add(new MediaFormatOption(
                                    "image_audio_mux:" + mainThumb + "|" + audioToMux,
                                    "Müzikli Video (MP4)",
                                    uploader + " • Fotoğraf + Müzik Birleşik Video",
                                    "mp4",
                                    0,
                                    false,
                                    true,
                                    mainThumb
                            ));
                        }
                        options.add(new MediaFormatOption(
                                "direct_url:" + mainThumb,
                                "HD Fotoğraf (JPG)",
                                uploader + " • Orijinal Instagram Görseli",
                                "jpg",
                                0,
                                false,
                                true,
                                mainThumb
                        ));
                    }
                }

                if (!options.isEmpty()) {
                    return new AnalyzedMedia(sourceUrl, title, uploader, 0, mainThumb, options, options.size() > 1);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Instagram API v1 failed: " + e.getMessage());
        }
        return null;
    }

    private AnalyzedMedia extractInstagramStoryOrHighlight(InstagramStoryTarget target) {
        try {
            String cookie = CookieBridge.getCookieHeaderForUrl("https://www.instagram.com");
            List<MediaFormatOption> options = new ArrayList<>();
            String mainThumb = null;
            String uploader = target.username != null ? ("@" + target.username) : "@instagram";

            // Case A: Highlight Reel (/stories/highlights/<id> or /s/<base64>)
            if (target.isHighlight) {
                String reelId = "highlight:" + target.highlightId;
                Request.Builder reelReq = new Request.Builder()
                        .url("https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=" + reelId)
                        .header("User-Agent", MOBILE_UA)
                        .header("Accept", "*/*")
                        .header("X-IG-App-ID", "936619743392459");
                if (cookie != null && !cookie.isEmpty()) {
                    reelReq.header("Cookie", cookie);
                }

                try (Response reelResp = httpClient.newCall(reelReq.build()).execute()) {
                    if (reelResp.isSuccessful() && reelResp.body() != null) {
                        JsonObject root = JsonParser.parseString(reelResp.body().string()).getAsJsonObject();
                        if (root.has("reels_media") && root.get("reels_media").isJsonArray()) {
                            JsonArray reelsMedia = root.getAsJsonArray("reels_media");
                            if (!reelsMedia.isEmpty() && reelsMedia.get(0).isJsonObject()) {
                                JsonObject reelObj = reelsMedia.get(0).getAsJsonObject();
                                if (reelObj.has("user") && reelObj.get("user").isJsonObject()) {
                                    JsonObject userObj = reelObj.getAsJsonObject("user");
                                    if (userObj.has("username")) {
                                        uploader = "@" + userObj.get("username").getAsString();
                                    }
                                }
                                if (reelObj.has("items") && reelObj.get("items").isJsonArray()) {
                                    JsonArray items = reelObj.getAsJsonArray("items");
                                    List<MediaFormatOption> targetMatches = new ArrayList<>();
                                    List<MediaFormatOption> allOptions = new ArrayList<>();
                                    int idx = 1;
                                    for (JsonElement el : items) {
                                        if (!el.isJsonObject()) continue;
                                        JsonObject st = el.getAsJsonObject();
                                        String sVid = getBestVideoVersionUrl(st);
                                        String sImg = getBestCandidateUrl(st);
                                        String dashAudio = extractAudioUrlFromDashManifest(st);
                                        String[] musicTrack = extractMusicTrack(st);
                                        String audioToMux = dashAudio != null ? dashAudio
                                                : (musicTrack != null ? musicTrack[0] : null);
                                        if (mainThumb == null && sImg != null) mainThumb = sImg;

                                        String pk = st.has("pk") ? st.get("pk").getAsString() : "";
                                        String id = st.has("id") ? st.get("id").getAsString() : "";
                                        boolean isTarget = target.targetStoryPk != null && !target.targetStoryPk.isEmpty()
                                                && (pk.equals(target.targetStoryPk) || id.startsWith(target.targetStoryPk) || id.contains(target.targetStoryPk));

                                        List<MediaFormatOption> destList = isTarget ? targetMatches : allOptions;

                                        if (sVid != null && !sVid.isEmpty()) {
                                            String title = isTarget
                                                    ? ("🎯 Seçilen Öne Çıkan #" + idx + " • Video (MP4)")
                                                    : ("Öne Çıkan #" + idx + " • Video (MP4)");
                                            String fmtId = (audioToMux != null && !audioToMux.isEmpty())
                                                    ? ("newpipe_mux:" + sVid + "|" + audioToMux)
                                                    : ("direct_url:" + sVid);
                                            destList.add(new MediaFormatOption(
                                                    fmtId,
                                                    title,
                                                    uploader + " • Sesli Öne Çıkan Videosu",
                                                    "mp4",
                                                    0,
                                                    false,
                                                    true,
                                                    sImg
                                            ));
                                        } else if (sImg != null && !sImg.isEmpty()) {
                                            if (audioToMux != null && !audioToMux.isEmpty()) {
                                                String mTitle = isTarget
                                                        ? ("🎯 Seçilen Öne Çıkan #" + idx + " • Müzikli Video (MP4)")
                                                        : ("Öne Çıkan #" + idx + " • Müzikli Video (MP4)");
                                                destList.add(new MediaFormatOption(
                                                        "image_audio_mux:" + sImg + "|" + audioToMux,
                                                        mTitle,
                                                        uploader + " • Fotoğraf + Müzik Birleşik Video",
                                                        "mp4",
                                                        0,
                                                        false,
                                                        true,
                                                        sImg
                                                ));
                                            }
                                            String title = isTarget
                                                    ? ("🎯 Seçilen Öne Çıkan #" + idx + " • Fotoğraf (JPG)")
                                                    : ("Öne Çıkan #" + idx + " • Fotoğraf (JPG)");
                                            destList.add(new MediaFormatOption(
                                                    "direct_url:" + sImg,
                                                    title,
                                                    uploader + " • Öne Çıkan Hikaye Görseli",
                                                    "jpg",
                                                    0,
                                                    false,
                                                    true,
                                                    sImg
                                            ));
                                        }
                                        idx++;
                                    }

                                    if (!targetMatches.isEmpty()) {
                                        options.addAll(targetMatches);
                                        if (targetMatches.get(0).getThumbnailUrl() != null) {
                                            mainThumb = targetMatches.get(0).getThumbnailUrl();
                                        }
                                    }
                                    options.addAll(allOptions);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Highlight reels_media API failed: " + e.getMessage());
                }

                // If still empty and targetStoryPk is present, try media/{story_pk}/info/
                if (options.isEmpty() && target.targetStoryPk != null && !target.targetStoryPk.isEmpty()) {
                    AnalyzedMedia single = fetchSingleMediaInfo(target.targetStoryPk, uploader, cookie);
                    if (single != null && !single.getFormatOptions().isEmpty()) {
                        return single;
                    }
                }

                if (!options.isEmpty()) {
                    String title = uploader + " Öne Çıkanları (" + options.size() + ")";
                    return new AnalyzedMedia(target.canonicalUrl, title, uploader, 0, mainThumb, options, options.size() > 1);
                }
            }

            // Case B: User Story (/stories/<username>/<story_pk> or /stories/<username>/)
            if (!target.isHighlight && target.username != null) {
                // Step 1: If targetStoryPk is known, try direct media/{story_pk}/info/ first
                if (target.targetStoryPk != null && !target.targetStoryPk.isEmpty()) {
                    AnalyzedMedia single = fetchSingleMediaInfo(target.targetStoryPk, uploader, cookie);
                    if (single != null && !single.getFormatOptions().isEmpty()) {
                        options.addAll(single.getFormatOptions());
                        mainThumb = single.getThumbnailUrl();
                    }
                }

                // Step 2: Fetch user's active story reel
                Request.Builder profReq = new Request.Builder()
                        .url("https://i.instagram.com/api/v1/users/web_profile_info/?username=" + target.username)
                        .header("User-Agent", MOBILE_UA)
                        .header("Accept", "*/*")
                        .header("X-IG-App-ID", "936619743392459");
                if (cookie != null && !cookie.isEmpty()) {
                    profReq.header("Cookie", cookie);
                }
                String userId = null;
                try (Response profResp = httpClient.newCall(profReq.build()).execute()) {
                    if (profResp.isSuccessful() && profResp.body() != null) {
                        JsonObject root = JsonParser.parseString(profResp.body().string()).getAsJsonObject();
                        if (root.has("data") && root.get("data").isJsonObject()) {
                            JsonObject d = root.getAsJsonObject("data");
                            if (d.has("user") && d.get("user").isJsonObject()) {
                                JsonObject u = d.getAsJsonObject("user");
                                if (u.has("id") && !u.get("id").isJsonNull()) {
                                    userId = u.get("id").getAsString();
                                }
                            }
                        }
                    }
                } catch (Exception e) { Log.w(TAG, "Ignored failure", e); }

                if (userId != null) {
                    Request.Builder reelReq = new Request.Builder()
                            .url("https://i.instagram.com/api/v1/feed/reels_media/?reel_ids=" + userId)
                            .header("User-Agent", MOBILE_UA)
                            .header("Accept", "*/*")
                            .header("X-IG-App-ID", "936619743392459");
                    if (cookie != null && !cookie.isEmpty()) {
                        reelReq.header("Cookie", cookie);
                    }
                    try (Response reelResp = httpClient.newCall(reelReq.build()).execute()) {
                        if (reelResp.isSuccessful() && reelResp.body() != null) {
                            JsonObject root = JsonParser.parseString(reelResp.body().string()).getAsJsonObject();
                            if (root.has("reels_media") && root.get("reels_media").isJsonArray()) {
                                JsonArray reelsMedia = root.getAsJsonArray("reels_media");
                                if (!reelsMedia.isEmpty() && reelsMedia.get(0).isJsonObject()) {
                                    JsonObject reelObj = reelsMedia.get(0).getAsJsonObject();
                                    if (reelObj.has("items") && reelObj.get("items").isJsonArray()) {
                                        JsonArray items = reelObj.getAsJsonArray("items");
                                        List<MediaFormatOption> targetMatches = new ArrayList<>();
                                        List<MediaFormatOption> allOptions = new ArrayList<>();
                                        int idx = 1;
                                        for (JsonElement el : items) {
                                            if (!el.isJsonObject()) continue;
                                            JsonObject st = el.getAsJsonObject();
                                            String sVid = getBestVideoVersionUrl(st);
                                            String sImg = getBestCandidateUrl(st);
                                            String dashAudio = extractAudioUrlFromDashManifest(st);
                                            String[] musicTrack = extractMusicTrack(st);
                                            String audioToMux = dashAudio != null ? dashAudio
                                                    : (musicTrack != null ? musicTrack[0] : null);
                                            if (mainThumb == null && sImg != null) mainThumb = sImg;

                                            String pk = st.has("pk") ? st.get("pk").getAsString() : "";
                                            String id = st.has("id") ? st.get("id").getAsString() : "";
                                            boolean isTarget = target.targetStoryPk != null && !target.targetStoryPk.isEmpty()
                                                    && (pk.equals(target.targetStoryPk) || id.startsWith(target.targetStoryPk) || id.contains(target.targetStoryPk));

                                            List<MediaFormatOption> destList = isTarget ? targetMatches : allOptions;

                                            if (sVid != null && !sVid.isEmpty()) {
                                                String title = isTarget
                                                        ? ("🎯 Seçilen Hikaye #" + idx + " • Video (MP4)")
                                                        : ("Hikaye #" + idx + " • Video (MP4)");
                                                String fmtId = (audioToMux != null && !audioToMux.isEmpty())
                                                        ? ("newpipe_mux:" + sVid + "|" + audioToMux)
                                                        : ("direct_url:" + sVid);
                                                destList.add(new MediaFormatOption(
                                                        fmtId,
                                                        title,
                                                        uploader + " • Sesli Hikaye Videosu",
                                                        "mp4",
                                                        0,
                                                        false,
                                                        true,
                                                        sImg
                                                ));
                                            } else if (sImg != null && !sImg.isEmpty()) {
                                                if (audioToMux != null && !audioToMux.isEmpty()) {
                                                    String mTitle = isTarget
                                                            ? ("🎯 Seçilen Hikaye #" + idx + " • Müzikli Video (MP4)")
                                                            : ("Hikaye #" + idx + " • Müzikli Video (MP4)");
                                                    destList.add(new MediaFormatOption(
                                                            "image_audio_mux:" + sImg + "|" + audioToMux,
                                                            mTitle,
                                                            uploader + " • Fotoğraf + Müzik Birleşik Video",
                                                            "mp4",
                                                            0,
                                                            false,
                                                            true,
                                                            sImg
                                                    ));
                                                }
                                                String title = isTarget
                                                        ? ("🎯 Seçilen Hikaye #" + idx + " • Fotoğraf (JPG)")
                                                        : ("Hikaye #" + idx + " • Fotoğraf (JPG)");
                                                destList.add(new MediaFormatOption(
                                                        "direct_url:" + sImg,
                                                        title,
                                                        uploader + " • " + idx + ". Hikaye Görseli",
                                                        "jpg",
                                                        0,
                                                        false,
                                                        true,
                                                        sImg
                                                ));
                                            }
                                            idx++;
                                        }

                                        if (!targetMatches.isEmpty() || !allOptions.isEmpty()) {
                                            options.clear();
                                            if (!targetMatches.isEmpty()) {
                                                options.addAll(targetMatches);
                                                if (targetMatches.get(0).getThumbnailUrl() != null) mainThumb = targetMatches.get(0).getThumbnailUrl();
                                            }
                                            options.addAll(allOptions);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception e) { Log.w(TAG, "Ignored failure", e); }
                }

                if (!options.isEmpty()) {
                    String title = uploader + " Hikayeleri (" + options.size() + ")";
                    return new AnalyzedMedia(target.canonicalUrl, title, uploader, 0, mainThumb, options, options.size() > 1);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Instagram Story/Highlight extraction failed: " + e.getMessage());
        }
        return null;
    }

    private AnalyzedMedia fetchSingleMediaInfo(String targetStoryPk, String uploader, String cookie) {
        try {
            Request.Builder reqInfo = new Request.Builder()
                    .url("https://i.instagram.com/api/v1/media/" + targetStoryPk + "/info/")
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", "*/*")
                    .header("X-IG-App-ID", "936619743392459");
            if (cookie != null && !cookie.isEmpty()) {
                reqInfo.header("Cookie", cookie);
            }
            try (Response resp = httpClient.newCall(reqInfo.build()).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                    if (root.has("items") && root.get("items").isJsonArray()) {
                        JsonArray items = root.getAsJsonArray("items");
                        if (!items.isEmpty() && items.get(0).isJsonObject()) {
                            JsonObject st = items.get(0).getAsJsonObject();
                            String sVid = getBestVideoVersionUrl(st);
                            String sImg = getBestCandidateUrl(st);
                            String dashAudio = extractAudioUrlFromDashManifest(st);
                            String[] musicTrack = extractMusicTrack(st);
                            String audioToMux = dashAudio != null ? dashAudio
                                    : (musicTrack != null ? musicTrack[0] : null);
                            List<MediaFormatOption> singleOpts = new ArrayList<>();
                            if (sVid != null && !sVid.isEmpty()) {
                                String fmtId = (audioToMux != null && !audioToMux.isEmpty())
                                        ? ("newpipe_mux:" + sVid + "|" + audioToMux)
                                        : ("direct_url:" + sVid);
                                singleOpts.add(new MediaFormatOption(
                                        fmtId,
                                        "🎯 Seçilen Hikaye/Öne Çıkan • Video (MP4)",
                                        uploader + " • Sesli Orijinal Video",
                                        "mp4",
                                        0,
                                        false,
                                        true,
                                        sImg
                                ));
                            } else if (sImg != null && !sImg.isEmpty()) {
                                if (audioToMux != null && !audioToMux.isEmpty()) {
                                    singleOpts.add(new MediaFormatOption(
                                            "image_audio_mux:" + sImg + "|" + audioToMux,
                                            "🎯 Seçilen Hikaye/Öne Çıkan • Müzikli Video (MP4)",
                                            uploader + " • Fotoğraf + Müzik Birleşik Video",
                                            "mp4",
                                            0,
                                            false,
                                            true,
                                            sImg
                                    ));
                                }
                                singleOpts.add(new MediaFormatOption(
                                        "direct_url:" + sImg,
                                        "🎯 Seçilen Hikaye/Öne Çıkan • Fotoğraf (JPG)",
                                        uploader + " • Orijinal Görsel",
                                        "jpg",
                                        0,
                                        false,
                                        true,
                                        sImg
                                ));
                            }
                            if (!singleOpts.isEmpty()) {
                                return new AnalyzedMedia(
                                        "https://www.instagram.com/stories/",
                                        uploader + " Hikaye / Öne Çıkan",
                                        uploader,
                                        0,
                                        sImg,
                                        singleOpts,
                                        singleOpts.size() > 1
                                );
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { Log.w(TAG, "Ignored failure", e); }
        return null;
    }

    private String extractInstagramShortcode(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("instagram\\.com/(?:[^/]+/)?(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)").matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String getBestInstagramDisplayUrl(JsonObject node) {
        if (node == null) return null;
        if (node.has("display_resources") && node.get("display_resources").isJsonArray()) {
            JsonArray resources = node.getAsJsonArray("display_resources");
            int maxW = -1;
            String bestSrc = null;
            for (JsonElement el : resources) {
                if (!el.isJsonObject()) continue;
                JsonObject rObj = el.getAsJsonObject();
                int w = rObj.has("config_width") ? rObj.get("config_width").getAsInt() : 0;
                String src = rObj.has("src") ? rObj.get("src").getAsString() : null;
                if (src != null && w >= maxW) {
                    maxW = w;
                    bestSrc = src;
                }
            }
            if (bestSrc != null && !bestSrc.isEmpty()) {
                return bestSrc;
            }
        }
        if (node.has("display_url") && !node.get("display_url").isJsonNull()) {
            return node.get("display_url").getAsString();
        }
        return null;
    }

    private AnalyzedMedia extractInstagramGraphQl(String sourceUrl, String shortcode) {
        try {
            String variables = "{\"shortcode\":\"" + shortcode + "\",\"fetch_comment_count\":0,\"fetch_tagged_user_count\":0,\"parent_comment_count\":0,\"child_comment_count\":0,\"fetch_like_count\":0,\"fetch_preview_comment\":false,\"has_threaded_comments\":false}";
            FormBody formBody = new FormBody.Builder()
                    .add("av", "0")
                    .add("__d", "www")
                    .add("__user", "0")
                    .add("__a", "1")
                    .add("__req", "3")
                    .add("fb_api_caller_class", "RelayModern")
                    .add("fb_api_req_friendly_name", "PolarisPostActionLoadPostQueryQuery")
                    .add("variables", variables)
                    .add("server_timestamps", "true")
                    .add("doc_id", "8845758582119845")
                    .build();

            String cookie = CookieBridge.getCookieHeaderForUrl("https://www.instagram.com");
            String csrfToken = "missing";
            if (cookie != null && !cookie.isEmpty()) {
                Matcher csrfMatcher = Pattern.compile("csrftoken=([^;\\s]+)").matcher(cookie);
                if (csrfMatcher.find()) {
                    csrfToken = csrfMatcher.group(1);
                } else {
                    cookie = cookie + "; csrftoken=missing";
                }
            } else {
                cookie = "csrftoken=missing";
            }

            Request.Builder reqBuilder = new Request.Builder()
                    .url("https://www.instagram.com/graphql/query")
                    .post(formBody)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "*/*")
                    .header("X-IG-App-ID", "936619743392459")
                    .header("X-FB-Friendly-Name", "PolarisPostActionLoadPostQueryQuery")
                    .header("X-ASBD-ID", "129477")
                    .header("X-CSRFToken", csrfToken)
                    .header("Cookie", cookie)
                    .header("Origin", "https://www.instagram.com")
                    .header("Referer", "https://www.instagram.com/p/" + shortcode + "/");

            try (Response resp = httpClient.newCall(reqBuilder.build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                String body = resp.body().string();
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                if (!root.has("data") || !root.get("data").isJsonObject()) return null;

                JsonObject data = root.getAsJsonObject("data");
                JsonObject media = data.has("xdt_shortcode_media") && data.get("xdt_shortcode_media").isJsonObject()
                        ? data.getAsJsonObject("xdt_shortcode_media")
                        : (data.has("shortcode_media") && data.get("shortcode_media").isJsonObject()
                        ? data.getAsJsonObject("shortcode_media") : null);

                if (media == null) return null;

                String videoUrl = media.has("video_url") && !media.get("video_url").isJsonNull()
                        ? media.get("video_url").getAsString() : null;
                String thumbUrl = getBestInstagramDisplayUrl(media);
                long duration = media.has("video_duration") && !media.get("video_duration").isJsonNull()
                        ? Math.round(media.get("video_duration").getAsDouble()) : 0;

                String uploader = "Instagram";
                if (media.has("owner") && media.get("owner").isJsonObject()) {
                    JsonObject owner = media.getAsJsonObject("owner");
                    if (owner.has("username") && !owner.get("username").isJsonNull()) {
                        uploader = "@" + owner.get("username").getAsString();
                    }
                }

                String title = (videoUrl != null ? "Instagram_Reel_" : "Instagram_Post_") + shortcode;
                if (media.has("edge_media_to_caption") && media.get("edge_media_to_caption").isJsonObject()) {
                    JsonArray edges = media.getAsJsonObject("edge_media_to_caption").getAsJsonArray("edges");
                    if (edges != null && !edges.isEmpty()) {
                        JsonObject node = edges.get(0).getAsJsonObject().getAsJsonObject("node");
                        if (node != null && node.has("text")) {
                            String caption = node.get("text").getAsString().trim();
                            if (!caption.isEmpty()) {
                                title = caption.length() > 80 ? caption.substring(0, 80) : caption;
                            }
                        }
                    }
                }

                List<MediaFormatOption> options = new ArrayList<>();

                // Check carousel sidecar children (multi-photo and multi-video posts)
                if (media.has("edge_sidecar_to_children") && media.get("edge_sidecar_to_children").isJsonObject()) {
                    JsonArray children = media.getAsJsonObject("edge_sidecar_to_children").getAsJsonArray("edges");
                    if (children != null && !children.isEmpty()) {
                        int idx = 1;
                        for (JsonElement childEl : children) {
                            if (!childEl.isJsonObject()) continue;
                            JsonObject childNode = childEl.getAsJsonObject().getAsJsonObject("node");
                            if (childNode == null) continue;

                            String childVid = childNode.has("video_url") && !childNode.get("video_url").isJsonNull()
                                    ? childNode.get("video_url").getAsString() : null;
                            String childImg = getBestInstagramDisplayUrl(childNode);

                            if (childVid != null && !childVid.isEmpty()) {
                                options.add(new MediaFormatOption(
                                        "direct_url:" + childVid,
                                        "Kaydırma #" + idx + " • Video (MP4)",
                                        uploader + " • " + idx + ". Kaydırma Videosu",
                                        "mp4",
                                        0,
                                        false,
                                        true,
                                        childImg
                                ));
                            } else if (childImg != null && !childImg.isEmpty()) {
                                options.add(new MediaFormatOption(
                                        "direct_url:" + childImg,
                                        "Kaydırma #" + idx + " • HD Fotoğraf (JPG)",
                                        uploader + " • " + idx + ". Kaydırma Görseli",
                                        "jpg",
                                        0,
                                        false,
                                        true,
                                        childImg
                                ));
                            }
                            idx++;
                        }
                    }
                }

                // If not a carousel (or carousel had no options)
                if (options.isEmpty()) {
                    if (videoUrl != null && !videoUrl.isEmpty()) {
                        options.add(new MediaFormatOption(
                                "direct_url:" + videoUrl,
                                "Full HD Reel / Video (MP4)",
                                "MP4 • Orijinal Instagram Akışı",
                                "mp4",
                                0,
                                false,
                                true
                        ));
                        options.add(new MediaFormatOption(
                                "ffmpeg_mp3:" + videoUrl,
                                appContext.getString(R.string.format_best_audio),
                                "MP3 • Reel Ses Dosyası (FFmpeg)",
                                "mp3",
                                0,
                                true,
                                false
                        ));
                    } else if (thumbUrl != null && !thumbUrl.isEmpty()) {
                        // Single Photo Post!
                        options.add(new MediaFormatOption(
                                "direct_url:" + thumbUrl,
                                "HD Fotoğraf (Orijinal)",
                                "JPG • Yüksek Kalite Instagram Görseli",
                                "jpg",
                                0,
                                false,
                                true
                        ));
                    }
                }

                if (!options.isEmpty()) {
                    return new AnalyzedMedia(sourceUrl, title, uploader, duration, thumbUrl, options, options.size() > 1);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Instagram GraphQL failed: " + e.getMessage());
        }
        return null;
    }

    private AnalyzedMedia extractInstagramEmbed(String sourceUrl, String shortcode) {
        try {
            String embedUrl = "https://www.instagram.com/p/" + shortcode + "/embed/captioned/";
            Request.Builder reqBuilder = new Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9");

            String cookie = CookieBridge.getCookieHeaderForUrl("https://www.instagram.com");
            if (cookie != null && !cookie.isEmpty()) {
                reqBuilder.header("Cookie", cookie);
            }

            try (Response resp = httpClient.newCall(reqBuilder.build()).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                String html = resp.body().string();

                String videoUrl = null;
                // Match escaped video_url inside embed script JSON
                Matcher mJson = Pattern.compile("\\\\\"video_url\\\\\":\\\\\"([^\\\\\"]+)\\\\\"").matcher(html);
                if (mJson.find()) {
                    videoUrl = unescapeInstagramUrl(mJson.group(1));
                }
                if (videoUrl == null) {
                    Matcher mDirect = Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
                    if (mDirect.find()) {
                        videoUrl = unescapeInstagramUrl(mDirect.group(1));
                    }
                }
                if (videoUrl == null) {
                    Matcher mTag = Pattern.compile("class=\"EmbeddedMediaVideo\"[^>]*src=\"([^\"]+)\"").matcher(html);
                    if (mTag.find()) {
                        videoUrl = mTag.group(1).replace("&amp;", "&");
                    }
                }

                String username = "Instagram";
                Matcher mUser = Pattern.compile("class=\"UsernameText\"[^>]*>([^<]+)<").matcher(html);
                if (mUser.find()) {
                    username = "@" + mUser.group(1).trim();
                }

                String thumb = null;
                Matcher mThumb = Pattern.compile("class=\"EmbeddedMediaImage\"[^>]*src=\"([^\"]+)\"").matcher(html);
                if (mThumb.find()) {
                    thumb = mThumb.group(1).replace("&amp;", "&");
                }
                if (thumb == null) {
                    Matcher mDisplay = Pattern.compile("\\\\\"display_url\\\\\":\\\\\"([^\\\\\"]+)\\\\\"").matcher(html);
                    if (mDisplay.find()) {
                        thumb = unescapeInstagramUrl(mDisplay.group(1));
                    }
                }

                List<MediaFormatOption> options = new ArrayList<>();
                if (videoUrl != null && !videoUrl.isEmpty()) {
                    options.add(new MediaFormatOption(
                            "direct_url:" + videoUrl,
                            "HD Reel / Video (MP4)",
                            "MP4 • Instagram Embed Akışı",
                            "mp4",
                            0,
                            false,
                            true
                    ));
                    options.add(new MediaFormatOption(
                            "ffmpeg_mp3:" + videoUrl,
                            appContext.getString(R.string.format_best_audio),
                            "MP3 • Reel Ses Dosyası",
                            "mp3",
                            0,
                            true,
                            false
                    ));
                    return new AnalyzedMedia(sourceUrl, "Instagram_" + shortcode, username, 0, thumb, options);
                } else if (thumb != null && !thumb.isEmpty()) {
                    // Photo post from embed
                    options.add(new MediaFormatOption(
                            "direct_url:" + thumb,
                            "HD Fotoğraf (Orijinal)",
                            "JPG • Instagram Post Görseli",
                            "jpg",
                            0,
                            false,
                            true
                    ));
                    return new AnalyzedMedia(sourceUrl, "Instagram_Post_" + shortcode, username, 0, thumb, options);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Instagram Embed failed: " + e.getMessage());
        }
        return null;
    }

    private AnalyzedMedia extractInstagramViaProxyMeta(String sourceUrl, String shortcode) {
        String[] proxyHosts = {"https://www.ddinstagram.com/p/", "https://www.kkinstagram.com/p/"};
        for (String prefix : proxyHosts) {
            try {
                Request req = new Request.Builder()
                        .url(prefix + shortcode)
                        .header("User-Agent", "TelegramBot (like TwitterBot)")
                        .build();
                try (Response resp = httpClient.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) continue;
                    String html = resp.body().string();

                    String thumb = null;
                    Matcher mImg = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']").matcher(html);
                    if (!mImg.find()) {
                        mImg = Pattern.compile("<meta[^>]+name=[\"']twitter:image[\"'][^>]+content=[\"']([^\"']+)[\"']").matcher(html);
                    }
                    if (mImg.find()) {
                        thumb = mImg.group(1).replace("&amp;", "&");
                        if (!thumb.startsWith("http")) {
                            thumb = "https://www.ddinstagram.com" + thumb;
                        }
                    }

                    String desc = "Instagram_" + shortcode;
                    Matcher mDesc = Pattern.compile("<meta[^>]+property=[\"']og:description[\"'][^>]+content=[\"']([^\"']+)[\"']").matcher(html);
                    if (mDesc.find() && !mDesc.group(1).trim().isEmpty()) {
                        desc = mDesc.group(1).trim();
                        if (desc.length() > 80) desc = desc.substring(0, 80);
                    }

                    Matcher mVid = Pattern.compile("<meta[^>]+property=[\"']og:video(?::secure_url)?[\"'][^>]+content=[\"']([^\"']+)[\"']").matcher(html);
                    if (mVid.find()) {
                        String videoUrl = mVid.group(1).replace("&amp;", "&");
                        if (!videoUrl.startsWith("http")) {
                            videoUrl = "https://www.ddinstagram.com" + videoUrl;
                        }

                        List<MediaFormatOption> options = new ArrayList<>();
                        options.add(new MediaFormatOption(
                                "direct_url:" + videoUrl,
                                "HD Reel / Video (MP4)",
                                "MP4 • Doğrudan Instagram Akışı",
                                "mp4",
                                0,
                                false,
                                true
                        ));
                        options.add(new MediaFormatOption(
                                "ffmpeg_mp3:" + videoUrl,
                                appContext.getString(R.string.format_best_audio),
                                "MP3 • Ses Dosyası",
                                "mp3",
                                0,
                                true,
                                false
                        ));
                        return new AnalyzedMedia(sourceUrl, desc, "Instagram", 0, thumb, options);
                    } else if (thumb != null && !thumb.isEmpty()) {
                        // Photo post from proxy meta
                        List<MediaFormatOption> options = new ArrayList<>();
                        options.add(new MediaFormatOption(
                                "direct_url:" + thumb,
                                "HD Fotoğraf (Orijinal)",
                                "JPG • Instagram Görseli",
                                "jpg",
                                0,
                                false,
                                true
                        ));
                        return new AnalyzedMedia(sourceUrl, desc, "Instagram", 0, thumb, options);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String unescapeInstagramUrl(String raw) {
        if (raw == null) return null;
        return raw.replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u0025", "%")
                .replace("&amp;", "&");
    }

    // =========================================================================================
    // 4. TIKTOK EXTRACTOR (No-Watermark HD MP4 + Watermarked MP4 + MP3 Audio)
    // =========================================================================================

    public AnalyzedMedia extractTikTok(String rawUrl) {
        try {
            String apiUrl = "https://www.tikwm.com/api/?hd=1&url=" + Uri.encode(rawUrl);
            Request req = new Request.Builder()
                    .url(apiUrl)
                    .header("User-Agent", MOBILE_UA)
                    .header("Accept", "application/json")
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonObject root = JsonParser.parseString(resp.body().string()).getAsJsonObject();
                if (!root.has("data") || !root.get("data").isJsonObject()) return null;

                JsonObject data = root.getAsJsonObject("data");
                String title = data.has("title") && !data.get("title").isJsonNull()
                        ? data.get("title").getAsString() : "TikTok_Video_" + System.currentTimeMillis();
                if (title.isEmpty()) title = "TikTok_Video_" + System.currentTimeMillis();

                String uploader = "TikTok";
                if (data.has("author") && data.get("author").isJsonObject()) {
                    JsonObject author = data.getAsJsonObject("author");
                    if (author.has("nickname") && !author.get("nickname").isJsonNull()) {
                        uploader = author.get("nickname").getAsString();
                    }
                }

                long duration = data.has("duration") && !data.get("duration").isJsonNull()
                        ? data.get("duration").getAsLong() : 0;
                String thumb = data.has("cover") && !data.get("cover").isJsonNull()
                        ? data.get("cover").getAsString() : null;

                List<MediaFormatOption> options = new ArrayList<>();

                if (data.has("hdplay") && !data.get("hdplay").isJsonNull()) {
                    String hdUrl = data.get("hdplay").getAsString();
                    long hdSize = data.has("hd_size") && !data.get("hd_size").isJsonNull()
                            ? data.get("hd_size").getAsLong() : 0;
                    if (!hdUrl.isEmpty()) {
                        options.add(new MediaFormatOption(
                                "direct_url:" + hdUrl,
                                "Full HD (Filigransız / No Watermark)",
                                "MP4 • Orijinal Kalite",
                                "mp4",
                                hdSize,
                                false,
                                true
                        ));
                    }
                }

                if (data.has("play") && !data.get("play").isJsonNull()) {
                    String playUrl = data.get("play").getAsString();
                    long size = data.has("size") && !data.get("size").isJsonNull()
                            ? data.get("size").getAsLong() : 0;
                    if (!playUrl.isEmpty()) {
                        options.add(new MediaFormatOption(
                                "direct_url:" + playUrl,
                                "HD Video (Filigransız / Standart)",
                                "MP4 • Hızlı Akış",
                                "mp4",
                                size,
                                false,
                                true
                        ));
                    }
                }

                if (data.has("wmplay") && !data.get("wmplay").isJsonNull()) {
                    String wmUrl = data.get("wmplay").getAsString();
                    long wmSize = data.has("wm_size") && !data.get("wm_size").isJsonNull()
                            ? data.get("wm_size").getAsLong() : 0;
                    if (!wmUrl.isEmpty()) {
                        options.add(new MediaFormatOption(
                                "direct_url:" + wmUrl,
                                "Filigranlı Video (Watermarked)",
                                "MP4 • TikTok Logolu",
                                "mp4",
                                wmSize,
                                false,
                                true
                        ));
                    }
                }

                if (data.has("music") && !data.get("music").isJsonNull()) {
                    String musicUrl = data.get("music").getAsString();
                    if (!musicUrl.isEmpty()) {
                        options.add(new MediaFormatOption(
                                "direct_url:" + musicUrl,
                                appContext.getString(R.string.format_best_audio),
                                "MP3 • Orijinal TikTok Sesi",
                                "mp3",
                                0,
                                true,
                                true
                        ));
                    }
                }

                if (!options.isEmpty()) {
                    return new AnalyzedMedia(rawUrl, title, uploader, duration, thumb, options);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "TikTok native extraction failed: " + e.getMessage());
        }
        return null;
    }

    // =========================================================================================
    // 5. REDDIT EXTRACTOR (Video + Audio DASH Muxing)
    // =========================================================================================

    public AnalyzedMedia extractReddit(String rawUrl) {
        try {
            String resolved = resolveRedirectUrl(rawUrl);
            String clean = resolved.split("\\?")[0];
            if (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
            String jsonUrl = clean + ".json";

            Request req = new Request.Builder()
                    .url(jsonUrl)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "application/json")
                    .build();

            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                JsonArray rootArr = JsonParser.parseString(resp.body().string()).getAsJsonArray();
                JsonObject postData = rootArr.get(0).getAsJsonObject()
                        .getAsJsonObject("data")
                        .getAsJsonArray("children").get(0).getAsJsonObject()
                        .getAsJsonObject("data");

                String title = postData.has("title") ? postData.get("title").getAsString() : "Reddit_Video";
                String author = postData.has("subreddit_name_prefixed")
                        ? postData.get("subreddit_name_prefixed").getAsString() : "Reddit";
                String thumb = postData.has("thumbnail") && postData.get("thumbnail").getAsString().startsWith("http")
                        ? postData.get("thumbnail").getAsString() : null;

                JsonObject redditVideo = null;
                if (postData.has("secure_media") && postData.get("secure_media").isJsonObject()) {
                    JsonObject sm = postData.getAsJsonObject("secure_media");
                    if (sm.has("reddit_video")) redditVideo = sm.getAsJsonObject("reddit_video");
                }
                if (redditVideo == null && postData.has("crosspost_parent_list") && postData.get("crosspost_parent_list").isJsonArray()) {
                    JsonArray cp = postData.getAsJsonArray("crosspost_parent_list");
                    if (!cp.isEmpty()) {
                        JsonObject firstCp = cp.get(0).getAsJsonObject();
                        if (firstCp.has("secure_media") && firstCp.get("secure_media").isJsonObject()) {
                            redditVideo = firstCp.getAsJsonObject("secure_media").getAsJsonObject("reddit_video");
                        }
                    }
                }

                if (redditVideo != null && redditVideo.has("fallback_url")) {
                    String videoUrl = redditVideo.get("fallback_url").getAsString().split("\\?")[0];
                    int height = redditVideo.has("height") ? redditVideo.get("height").getAsInt() : 720;
                    long duration = redditVideo.has("duration") ? redditVideo.get("duration").getAsLong() : 0;
                    String baseUrl = videoUrl.substring(0, videoUrl.lastIndexOf('/'));
                    String audioUrl = baseUrl + "/DASH_AUDIO_128.mp4";

                    List<MediaFormatOption> options = new ArrayList<>();
                    options.add(new MediaFormatOption(
                            "newpipe_mux:" + videoUrl + "|" + audioUrl,
                            height + "p HD Video + Ses",
                            "MP4 • Reddit DASH Birleştirme (FFmpeg)",
                            "mp4",
                            0,
                            false,
                            false
                    ));
                    options.add(new MediaFormatOption(
                            "direct_url:" + videoUrl,
                            height + "p Video (Sessiz / Doğrudan)",
                            "MP4 • Doğrudan Akış",
                            "mp4",
                            0,
                            false,
                            true
                    ));
                    return new AnalyzedMedia(rawUrl, title, author, duration, thumb, options);
                }

                // 2. Reddit Multi-Slide Gallery (Kaydırmalı Gönderi)
                boolean isGallery = (postData.has("is_gallery") && postData.get("is_gallery").getAsBoolean())
                        || postData.has("gallery_data");
                if (isGallery && postData.has("gallery_data") && postData.get("gallery_data").isJsonObject()
                        && postData.has("media_metadata") && postData.get("media_metadata").isJsonObject()) {
                    JsonObject galleryData = postData.getAsJsonObject("gallery_data");
                    JsonObject mediaMetadata = postData.getAsJsonObject("media_metadata");
                    if (galleryData.has("items") && galleryData.get("items").isJsonArray()) {
                        JsonArray items = galleryData.getAsJsonArray("items");
                        List<MediaFormatOption> options = new ArrayList<>();
                        int slideIdx = 1;
                        String firstThumb = null;
                        for (JsonElement itemEl : items) {
                            if (!itemEl.isJsonObject()) continue;
                            JsonObject gItem = itemEl.getAsJsonObject();
                            if (!gItem.has("media_id")) continue;
                            String mediaId = gItem.get("media_id").getAsString();
                            if (!mediaMetadata.has(mediaId) || !mediaMetadata.get(mediaId).isJsonObject()) continue;
                            JsonObject meta = mediaMetadata.getAsJsonObject(mediaId);

                            if (meta.has("status") && !"valid".equalsIgnoreCase(meta.get("status").getAsString())) continue;

                            String mediaType = meta.has("e") ? meta.get("e").getAsString() : "Image";
                            String mime = meta.has("m") ? meta.get("m").getAsString() : "image/jpg";
                            String ext = mime.contains("png") ? "png" : (mime.contains("gif") ? "gif" : "jpg");

                            String directMediaUrl = null;
                            if (meta.has("s") && meta.get("s").isJsonObject()) {
                                JsonObject s = meta.getAsJsonObject("s");
                                if ("AnimatedImage".equalsIgnoreCase(mediaType) && s.has("mp4") && !s.get("mp4").isJsonNull()) {
                                    directMediaUrl = s.get("mp4").getAsString().replace("&amp;", "&");
                                    ext = "mp4";
                                } else if (s.has("u") && !s.get("u").isJsonNull()) {
                                    directMediaUrl = s.get("u").getAsString().replace("&amp;", "&");
                                } else if (s.has("gif") && !s.get("gif").isJsonNull()) {
                                    directMediaUrl = s.get("gif").getAsString().replace("&amp;", "&");
                                    ext = "gif";
                                }
                            }
                            if (directMediaUrl == null) {
                                directMediaUrl = "https://i.redd.it/" + mediaId + "." + ext;
                            }
                            String itemThumb = directMediaUrl;
                            if (firstThumb == null) firstThumb = itemThumb;

                            boolean isVid = "mp4".equalsIgnoreCase(ext);
                            options.add(new MediaFormatOption(
                                    "direct_url:" + directMediaUrl,
                                    "Kaydırma #" + slideIdx + " • " + (isVid ? "Video (MP4)" : ("HD Fotoğraf (" + ext.toUpperCase(Locale.US) + ")")),
                                    author + " • " + slideIdx + ". Galeri Medyası",
                                    ext,
                                    0,
                                    false,
                                    true,
                                    itemThumb
                            ));
                            slideIdx++;
                        }
                        if (!options.isEmpty()) {
                            return new AnalyzedMedia(rawUrl, title, author, 0, firstThumb != null ? firstThumb : thumb, options, true);
                        }
                    }
                }

                // 3. Reddit Single Image / Direct Media
                if (postData.has("url") && !postData.get("url").isJsonNull()) {
                    String postUrl = postData.get("url").getAsString();
                    String lower = postUrl.toLowerCase(Locale.US);
                    if (lower.contains("i.redd.it") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                            || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif")) {
                        String ext = lower.contains(".png") ? "png" : (lower.contains(".gif") ? "gif" : "jpg");
                        List<MediaFormatOption> options = new ArrayList<>();
                        options.add(new MediaFormatOption(
                                "direct_url:" + postUrl,
                                "HD Görsel (Orijinal)",
                                author + " • Orijinal Reddit Görseli",
                                ext,
                                0,
                                false,
                                true,
                                postUrl
                        ));
                        return new AnalyzedMedia(rawUrl, title, author, 0, postUrl, options, false);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Reddit extraction failed: " + e.getMessage());
        }
        return null;
    }

    // =========================================================================================
    // Helper Methods
    // =========================================================================================

    private String resolveRedirectUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.contains("/stories/")) {
            return url;
        }
        try {
            String cookie = CookieBridge.getCookieHeaderForUrl(url);
            Request.Builder req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", MOBILE_UA)
                    .get();
            if (cookie != null && !cookie.isEmpty()) {
                req.header("Cookie", cookie);
            }
            try (Response response = httpClient.newCall(req.build()).execute()) {
                String finalUrl = response.request().url().toString();
                if (finalUrl.contains("/accounts/login")) {
                    return url;
                }
                return finalUrl;
            }
        } catch (Exception e) {
            return url;
        }
    }

    private int parseResolutionHeight(String res) {
        if (res == null) return 0;
        Matcher m = Pattern.compile("(\\d{3,4})p").matcher(res);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private String getQualityBadge(int height) {
        if (height >= 2160) return "4K Ultra HD";
        if (height >= 1440) return "2K QHD";
        if (height >= 1080) return "Full HD";
        if (height >= 720) return "HD";
        return "SD";
    }

    private static class Mp4Variant {
        final String url;
        final int bitrate;

        Mp4Variant(String url, int bitrate) {
            this.url = url;
            this.bitrate = bitrate;
        }
    }
}
