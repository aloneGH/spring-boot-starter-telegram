package dev.voroby.telegram.music.model;

public class Track {
    public static final String STATUS_READY = "READY";
    public static final String STATUS_PENDING_UPLOAD = "PENDING_UPLOAD";
    public static final String STATUS_BROKEN = "BROKEN";

    private Long id;
    private String normKey;
    private String title;
    private String artist;
    private String album;
    private String versionTag;
    private int durationSec;
    private int qualityScore;
    private String srcCodec;
    private int srcBitrate;
    private int srcSampleRate;
    private int srcBitDepth;
    private Long storedChatId;
    private Long storedMsgId;
    private String storedFileUid;
    private String storedCodec;
    private int storedBitrate;
    private long storedSize;
    private String mimeType;
    private String fileName;
    private String coverPath;
    private Long bestSourceId;
    private boolean metadataEnriched;
    private Double loudnessLufs;
    private boolean loudnessNormalized;
    private String status;
    private long createdAt;
    private long updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNormKey() { return normKey; }
    public void setNormKey(String normKey) { this.normKey = normKey; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }

    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album; }

    public String getVersionTag() { return versionTag; }
    public void setVersionTag(String versionTag) { this.versionTag = versionTag; }

    public int getDurationSec() { return durationSec; }
    public void setDurationSec(int durationSec) { this.durationSec = durationSec; }

    public int getQualityScore() { return qualityScore; }
    public void setQualityScore(int qualityScore) { this.qualityScore = qualityScore; }

    public String getSrcCodec() { return srcCodec; }
    public void setSrcCodec(String srcCodec) { this.srcCodec = srcCodec; }

    public int getSrcBitrate() { return srcBitrate; }
    public void setSrcBitrate(int srcBitrate) { this.srcBitrate = srcBitrate; }

    public int getSrcSampleRate() { return srcSampleRate; }
    public void setSrcSampleRate(int srcSampleRate) { this.srcSampleRate = srcSampleRate; }

    public int getSrcBitDepth() { return srcBitDepth; }
    public void setSrcBitDepth(int srcBitDepth) { this.srcBitDepth = srcBitDepth; }

    public Long getStoredChatId() { return storedChatId; }
    public void setStoredChatId(Long storedChatId) { this.storedChatId = storedChatId; }

    public Long getStoredMsgId() { return storedMsgId; }
    public void setStoredMsgId(Long storedMsgId) { this.storedMsgId = storedMsgId; }

    public String getStoredFileUid() { return storedFileUid; }
    public void setStoredFileUid(String storedFileUid) { this.storedFileUid = storedFileUid; }

    public String getStoredCodec() { return storedCodec; }
    public void setStoredCodec(String storedCodec) { this.storedCodec = storedCodec; }

    public int getStoredBitrate() { return storedBitrate; }
    public void setStoredBitrate(int storedBitrate) { this.storedBitrate = storedBitrate; }

    public long getStoredSize() { return storedSize; }
    public void setStoredSize(long storedSize) { this.storedSize = storedSize; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getCoverPath() { return coverPath; }
    public void setCoverPath(String coverPath) { this.coverPath = coverPath; }

    public Long getBestSourceId() { return bestSourceId; }
    public void setBestSourceId(Long bestSourceId) { this.bestSourceId = bestSourceId; }

    public boolean isMetadataEnriched() { return metadataEnriched; }
    public void setMetadataEnriched(boolean metadataEnriched) { this.metadataEnriched = metadataEnriched; }

    public Double getLoudnessLufs() { return loudnessLufs; }
    public void setLoudnessLufs(Double loudnessLufs) { this.loudnessLufs = loudnessLufs; }

    public boolean isLoudnessNormalized() { return loudnessNormalized; }
    public void setLoudnessNormalized(boolean loudnessNormalized) { this.loudnessNormalized = loudnessNormalized; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public String buildQualityTag() {
        if (storedCodec != null && !storedCodec.isBlank()) {
            String c = storedCodec.toUpperCase();
            if (storedBitrate > 0) {
                return c + "-" + storedBitrate + "K";
            }
            return c;
        }
        if (srcCodec != null && !srcCodec.isBlank()) {
            return srcCodec.toUpperCase();
        }
        return null;
    }
}
