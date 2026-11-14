package com.vidownphone.app.model;

import java.io.Serializable;

public class MediaFormatOption implements Serializable {

    private final String formatId;
    private final String label;
    private final String details;
    private final String extension;
    private final long fileSize;
    private final boolean audioOnly;
    private final boolean directHttp;
    private final String thumbnailUrl;

    public MediaFormatOption(String formatId, String label, String details,
                             String extension, long fileSize, boolean audioOnly, boolean directHttp) {
        this(formatId, label, details, extension, fileSize, audioOnly, directHttp, null);
    }

    public MediaFormatOption(String formatId, String label, String details,
                             String extension, long fileSize, boolean audioOnly, boolean directHttp,
                             String thumbnailUrl) {
        this.formatId = formatId;
        this.label = label;
        this.details = details;
        this.extension = extension;
        this.fileSize = fileSize;
        this.audioOnly = audioOnly;
        this.directHttp = directHttp;
        this.thumbnailUrl = thumbnailUrl;
    }

    public String getFormatId() {
        return formatId;
    }

    public String getLabel() {
        return label;
    }

    public String getDetails() {
        return details;
    }

    public String getExtension() {
        return extension;
    }

    public long getFileSize() {
        return fileSize;
    }

    public boolean isAudioOnly() {
        return audioOnly;
    }

    public boolean isDirectHttp() {
        return directHttp;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }
}
