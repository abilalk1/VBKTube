package com.vidownphone.app.util;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FormatUtils {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern WWW_PATTERN = Pattern.compile(
            "(?<![\\w./@-])(www\\.[\\w\\-]+(?:\\.[\\w\\-]+)+(?:[/?#][\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]*)?)",
            Pattern.CASE_INSENSITIVE
    );

    private static final String TRAILING_CHARS = ".,;:!?)'\"]>}";

    private static final Pattern MEDIA_EXT_PATTERN = Pattern.compile(
            "(?i)\\.(mp4|mkv|webm|mov|m3u8|mp3|m4a|wav|ogg|jpg|jpeg|png|webp|gif|avif|bmp|heic|heif)$");

    private FormatUtils() {
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.US, "%.1f MB", mb);
        }
        double gb = mb / 1024.0;
        return String.format(Locale.US, "%.2f GB", gb);
    }

    public static String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, secs);
    }

    public static String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "vidown_" + System.currentTimeMillis();
        }
        String clean = name
                .replaceAll("[^a-zA-Z0-9ğüşıöçĞÜŞİÖÇ._\\- ]", "_")
                .replaceAll("\\s+", " ")
                .replaceAll("_+", "_")
                .trim();
        while (clean.startsWith(".") || clean.startsWith("_") || clean.startsWith("-")) {
            clean = clean.substring(1).trim();
        }
        if (clean.length() > 60) {
            clean = clean.substring(0, 60).trim();
        }
        while (clean.endsWith(".") || clean.endsWith("_") || clean.endsWith("-")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        return clean.isEmpty() ? "vidown_" + System.currentTimeMillis() : clean;
    }

    /**
     * Extracts the first http(s) URL from shared text. Trailing punctuation is trimmed and
     * scheme-less "www.example.com/..." links get "https://" prepended.
     */
    public static String extractFirstUrl(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String trimmed = text.trim();
        String found = null;
        Matcher matcher = URL_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            found = matcher.group(1);
        } else {
            Matcher w = WWW_PATTERN.matcher(trimmed);
            if (w.find()) {
                found = "https://" + w.group(1);
            }
        }
        if (found == null) {
            return null;
        }
        found = stripTrailingPunctuation(found);
        String lower = found.toLowerCase(Locale.US);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        return found.length() > "https://".length() ? found : null;
    }

    private static String stripTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && TRAILING_CHARS.indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    private static String pathOnly(String url) {
        String s = url.toLowerCase(Locale.US);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        return s;
    }

    public static boolean hasImageExtension(String url) {
        if (url == null) return false;
        String lower = pathOnly(url);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".avif")
                || lower.endsWith(".bmp") || lower.endsWith(".heic") || lower.endsWith(".heif");
    }

    public static boolean isDirectMediaUrl(String url) {
        if (url == null) return false;
        String lower = pathOnly(url);
        return lower.endsWith(".mp4")
                || lower.endsWith(".mkv")
                || lower.endsWith(".webm")
                || lower.endsWith(".mov")
                || lower.endsWith(".m3u8")
                || lower.endsWith(".mp3")
                || lower.endsWith(".m4a")
                || lower.endsWith(".wav")
                || lower.endsWith(".ogg")
                || hasImageExtension(url);
    }

    private static String hostOf(String url) {
        try {
            String h = new URI(url.trim()).getHost();
            return h != null ? h.toLowerCase(Locale.US) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hostIs(String host, String domain) {
        return host != null && (host.equals(domain) || host.endsWith("." + domain));
    }

    /**
     * Google Images wrapper links (google.* /imgres?imgurl=...): returns the decoded real image URL,
     * or null if the URL is not such a wrapper (or has no http(s) imgurl parameter).
     */
    public static String extractWrappedImageUrl(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url.trim());
            String host = hostOf(url);
            String query = uri.getRawQuery();
            if (host == null || query == null) return null;
            boolean google = host.startsWith("google.") || host.contains(".google.");
            String path = uri.getPath() != null ? uri.getPath() : "";
            if (!google && !path.contains("imgres")) return null;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                if ("imgurl".equals(pair.substring(0, eq))) {
                    String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    String lv = v.toLowerCase(Locale.US);
                    if (lv.startsWith("http://") || lv.startsWith("https://")) {
                        return v;
                    }
                }
            }
        } catch (Exception e) {
            // not a parseable wrapper URL
        }
        return null;
    }

    /** True for hosts that only serve images (Google image thumbnails / user content CDN). */
    public static boolean isKnownImageHostUrl(String url) {
        if (url == null) return false;
        String host = hostOf(url);
        if (host == null) return false;
        if (host.startsWith("encrypted-tbn") && hostIs(host, "gstatic.com")) return true;
        return hostIs(host, "googleusercontent.com");
    }

    private static String bareContentType(String contentType) {
        String ct = contentType.toLowerCase(Locale.US).trim();
        int semi = ct.indexOf(';');
        return semi >= 0 ? ct.substring(0, semi).trim() : ct;
    }

    /** Maps an image/* content type to a file extension (jpg for unknown image subtypes); null if not image/*. */
    public static String imageExtFromContentType(String contentType) {
        if (contentType == null) return null;
        String ct = bareContentType(contentType);
        if (!ct.startsWith("image/")) return null;
        switch (ct) {
            case "image/png":
                return "png";
            case "image/webp":
                return "webp";
            case "image/gif":
                return "gif";
            case "image/avif":
                return "avif";
            case "image/bmp":
                return "bmp";
            case "image/heic":
                return "heic";
            case "image/heif":
                return "heif";
            default:
                return "jpg";
        }
    }

    /** Maps a video/audio content type to an extension; null if not a known video/audio type. */
    public static String mediaExtFromContentType(String contentType) {
        if (contentType == null) return null;
        switch (bareContentType(contentType)) {
            case "video/mp4": return "mp4";
            case "video/webm": return "webm";
            case "video/x-matroska": return "mkv";
            case "video/quicktime": return "mov";
            case "video/3gpp": return "3gp";
            case "audio/mpeg":
            case "audio/mp3": return "mp3";
            case "audio/mp4":
            case "audio/x-m4a":
            case "audio/aac": return "m4a";
            case "audio/ogg": return "ogg";
            case "audio/wav":
            case "audio/x-wav": return "wav";
            case "audio/webm": return "webm";
            case "application/vnd.apple.mpegurl":
            case "application/x-mpegurl": return "m3u8";
            default: return null;
        }
    }

    /** Removes trailing known media extension(s), e.g. "flower.mp4" -> "flower". */
    public static String stripMediaExtension(String name) {
        if (name == null) return null;
        String s = name.trim();
        while (true) {
            String next = MEDIA_EXT_PATTERN.matcher(s).replaceFirst("");
            if (next.equals(s)) break;
            s = next;
        }
        return s;
    }

    /**
     * Derives a readable title from the last path segment of a URL: URL-decoded, extension stripped.
     * Returns null when there is no meaningful name.
     */
    public static String titleFromUrlPath(String url) {
        if (url == null) return null;
        try {
            String path = new URI(url.trim()).getRawPath();
            if (path == null) return null;
            int slash = path.lastIndexOf('/');
            String seg = slash >= 0 ? path.substring(slash + 1) : path;
            if (seg.isEmpty()) return null;
            String decoded = URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8");
            decoded = stripMediaExtension(decoded).trim();
            return decoded.isEmpty() ? null : decoded;
        } catch (Exception e) {
            return null;
        }
    }
}
