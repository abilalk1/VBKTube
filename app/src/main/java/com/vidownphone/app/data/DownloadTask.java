package com.vidownphone.app.data;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "download_tasks")
public class DownloadTask {

    public static final int STATUS_QUEUED = 0;
    public static final int STATUS_DOWNLOADING = 1;
    public static final int STATUS_COMPLETED = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELED = 4;

    @PrimaryKey(autoGenerate = true)
    private long id;

    private String url;
    private String title;
    private String thumbnailUrl;
    private String formatId;
    private String formatLabel;
    private String extension;
    private boolean audioOnly;
    private boolean directHttp;

    private int status;
    private int progress;
    private String speedAndEta;
    private String filePath;
    private long fileSize;
    private String errorMessage;
    private long createdAt;

    public DownloadTask() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public String getFormatId() {
        return formatId;
    }

    public void setFormatId(String formatId) {
        this.formatId = formatId;
    }

    public String getFormatLabel() {
        return formatLabel;
    }

    public void setFormatLabel(String formatLabel) {
        this.formatLabel = formatLabel;
    }

    public String getExtension() {
        return extension;
    }

    public void setExtension(String extension) {
        this.extension = extension;
    }

    public boolean isAudioOnly() {
        return audioOnly;
    }

    public void setAudioOnly(boolean audioOnly) {
        this.audioOnly = audioOnly;
    }

    public boolean isDirectHttp() {
        return directHttp;
    }

    public void setDirectHttp(boolean directHttp) {
        this.directHttp = directHttp;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public String getSpeedAndEta() {
        return speedAndEta;
    }

    public void setSpeedAndEta(String speedAndEta) {
        this.speedAndEta = speedAndEta;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
