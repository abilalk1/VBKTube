package com.vidownphone.app.model;

import com.vidownphone.app.util.FormatUtils;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

public class DetectedStream implements Serializable {

    private final String streamUrl;
    private final String title;
    private final String mimeType;
    private final boolean audioOnly;
    private final String thumbnailUrl;
    private String audioStreamUrl;

    public DetectedStream(String streamUrl, String title, String mimeType, boolean audioOnly) {
        this(streamUrl, title, mimeType, audioOnly, null, null);
    }

    public DetectedStream(String streamUrl, String title, String mimeType, boolean audioOnly, String thumbnailUrl) {
        this(streamUrl, title, mimeType, audioOnly, thumbnailUrl, null);
    }

    public DetectedStream(String streamUrl, String title, String mimeType, boolean audioOnly, String thumbnailUrl, String audioStreamUrl) {
        this.streamUrl = streamUrl;
        this.title = title;
        this.mimeType = mimeType;
        this.audioOnly = audioOnly;
        this.thumbnailUrl = thumbnailUrl;
        this.audioStreamUrl = audioStreamUrl;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getMimeType() {
        return mimeType;
    }

    public boolean isAudioOnly() {
        return audioOnly;
    }

    public String getThumbnailUrl() {
        if (thumbnailUrl != null && !thumbnailUrl.isEmpty()) {
            return thumbnailUrl;
        }
        return isImage() ? streamUrl : null;
    }

    public String getAudioStreamUrl() {
        return audioStreamUrl;
    }

    public void setAudioStreamUrl(String audioStreamUrl) {
        this.audioStreamUrl = audioStreamUrl;
    }

    public boolean hasCompanionAudio() {
        return audioStreamUrl != null && !audioStreamUrl.isEmpty();
    }

    public boolean isImageWithAudioMux() {
        if (!hasCompanionAudio() || audioOnly) return false;
        String m = mimeType != null ? mimeType.toLowerCase(Locale.US) : "";
        return m.contains("fotoğraf + ses") || m.contains("müzikli")
                || FormatUtils.hasImageExtension(streamUrl);
    }

    public boolean isImage() {
        if (audioOnly || hasCompanionAudio()) return false;
        String m = mimeType != null ? mimeType.toLowerCase(Locale.US) : "";
        if (m.startsWith("video/") || m.startsWith("audio/")) return false;
        return m.contains("görsel") || m.contains("image") || m.contains("foto")
                || FormatUtils.hasImageExtension(streamUrl)
                || FormatUtils.extractWrappedImageUrl(streamUrl) != null
                || FormatUtils.isKnownImageHostUrl(streamUrl);
    }

    private static String normalizeUrlKey(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        return q > 0 ? url.substring(0, q) : url;
    }

    public String getMapKey() {
        if (isImageWithAudioMux()) {
            return "mux_img:" + normalizeUrlKey(streamUrl);
        }
        return (audioOnly ? "aud:" : "raw:") + normalizeUrlKey(streamUrl);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DetectedStream)) return false;
        DetectedStream that = (DetectedStream) o;
        return Objects.equals(getMapKey(), that.getMapKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getMapKey());
    }
}
