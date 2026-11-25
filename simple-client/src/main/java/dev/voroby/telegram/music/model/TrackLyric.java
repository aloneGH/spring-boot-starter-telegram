package dev.voroby.telegram.music.model;

public class TrackLyric {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_FOUND = "FOUND";
    public static final String STATUS_NOT_FOUND = "NOT_FOUND";
    public static final String STATUS_RETRY = "RETRY";

    public static final String SOURCE_EMBEDDED = "EMBEDDED";
    public static final String SOURCE_LRCLIB = "LRCLIB";
    public static final String SOURCE_NETEASE = "NETEASE";
    public static final String SOURCE_LEGACY = "LEGACY";

    private long trackId;
    private String source;
    private String lrc;
    private String tlrc;
    private Integer matchedDurationMs;
    private Double matchScore;
    private String status;
    private int retryCount;
    private Long nextRetryAt;
    private long updatedAt;

    public long getTrackId() { return trackId; }
    public void setTrackId(long trackId) { this.trackId = trackId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getLrc() { return lrc; }
    public void setLrc(String lrc) { this.lrc = lrc; }

    public String getTlrc() { return tlrc; }
    public void setTlrc(String tlrc) { this.tlrc = tlrc; }

    public Integer getMatchedDurationMs() { return matchedDurationMs; }
    public void setMatchedDurationMs(Integer matchedDurationMs) { this.matchedDurationMs = matchedDurationMs; }

    public Double getMatchScore() { return matchScore; }
    public void setMatchScore(Double matchScore) { this.matchScore = matchScore; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public Long getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Long nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
