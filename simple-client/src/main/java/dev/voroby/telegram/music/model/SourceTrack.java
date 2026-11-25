package dev.voroby.telegram.music.model;

public class SourceTrack {
    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_SKIPPED_DUP = "SKIPPED_DUP";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DELETED = "DELETED";

    private Long id;
    private long sourceChatId;
    private long sourceMsgId;
    private long sentAt;
    private String rawTitle;
    private String rawArtist;
    private String rawAlbum;
    private String rawFilename;
    private String rawCaption;
    private String mimeType;
    private long fileSize;
    private int durationSec;
    private int estQualityScore;
    private String normKey;
    private String status;
    private int retryCount;
    private Long nextRetryAt;
    private String lastError;
    private Long trackId;
    private long createdAt;
    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public long getSourceChatId() { return sourceChatId; }
    public void setSourceChatId(long sourceChatId) { this.sourceChatId = sourceChatId; }

    public long getSourceMsgId() { return sourceMsgId; }
    public void setSourceMsgId(long sourceMsgId) { this.sourceMsgId = sourceMsgId; }

    public long getSentAt() { return sentAt; }
    public void setSentAt(long sentAt) { this.sentAt = sentAt; }

    public String getRawTitle() { return rawTitle; }
    public void setRawTitle(String rawTitle) { this.rawTitle = rawTitle; }

    public String getRawArtist() { return rawArtist; }
    public void setRawArtist(String rawArtist) { this.rawArtist = rawArtist; }

    public String getRawAlbum() { return rawAlbum; }
    public void setRawAlbum(String rawAlbum) { this.rawAlbum = rawAlbum; }

    public String getRawFilename() { return rawFilename; }
    public void setRawFilename(String rawFilename) { this.rawFilename = rawFilename; }

    public String getRawCaption() { return rawCaption; }
    public void setRawCaption(String rawCaption) { this.rawCaption = rawCaption; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public int getDurationSec() { return durationSec; }
    public void setDurationSec(int durationSec) { this.durationSec = durationSec; }

    public int getEstQualityScore() { return estQualityScore; }
    public void setEstQualityScore(int estQualityScore) { this.estQualityScore = estQualityScore; }

    public String getNormKey() { return normKey; }
    public void setNormKey(String normKey) { this.normKey = normKey; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public Long getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Long nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Long getTrackId() { return trackId; }
    public void setTrackId(Long trackId) { this.trackId = trackId; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
