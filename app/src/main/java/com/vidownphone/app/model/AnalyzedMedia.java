package com.vidownphone.app.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class AnalyzedMedia implements Serializable {

    private final String sourceUrl;
    private final String title;
    private final String uploader;
    private final long durationSeconds;
    private final String thumbnailUrl;
    private final List<MediaFormatOption> formatOptions;
    private final boolean isMultiMedia;

    public AnalyzedMedia(String sourceUrl, String title, String uploader,
                         long durationSeconds, String thumbnailUrl,
                         List<MediaFormatOption> formatOptions) {
        this(sourceUrl, title, uploader, durationSeconds, thumbnailUrl, formatOptions, false);
    }

    public AnalyzedMedia(String sourceUrl, String title, String uploader,
                         long durationSeconds, String thumbnailUrl,
                         List<MediaFormatOption> formatOptions, boolean isMultiMedia) {
        this.sourceUrl = sourceUrl;
        this.title = title;
        this.uploader = uploader;
        this.durationSeconds = durationSeconds;
        this.thumbnailUrl = thumbnailUrl;
        this.formatOptions = formatOptions != null ? formatOptions : new ArrayList<>();
        this.isMultiMedia = isMultiMedia || calculateIsMultiMedia(this.formatOptions);
    }

    private static boolean calculateIsMultiMedia(List<MediaFormatOption> options) {
        if (options == null || options.size() <= 1) return false;
        int slideCount = 0;
        for (MediaFormatOption opt : options) {
            if (opt.getLabel() != null) {
                String l = opt.getLabel();
                if (l.contains("Kaydırma #") || l.contains("Slayt #") || l.contains("Hikaye #")
                        || l.contains("Galeri #") || l.contains("Parça #")) {
                    slideCount++;
                }
            }
        }
        return slideCount > 1;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getUploader() {
        return uploader;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public List<MediaFormatOption> getFormatOptions() {
        return formatOptions;
    }

    public boolean isMultiMedia() {
        return isMultiMedia;
    }
}
