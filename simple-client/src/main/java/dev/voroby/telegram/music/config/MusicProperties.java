package dev.voroby.telegram.music.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MusicProperties {

    @Value("${music.sync:true}")
    private boolean syncEnabled;

    @Value("${music.sync.folder-name:Music}")
    private String sourceFolderName;

    @Value("${music.sync.MusicSource:Music-Source}")
    private String legacySourceFolderName;

    @Value("${music.storage.chat-id:0}")
    private long storageChatId;

    @Value("${music.storage.channel-title:CW-Music-Vault}")
    private String storageChannelTitle;

    @Value("${music.transcode.opus-bitrate:192k}")
    private String opusBitrate;

    @Value("${music.transcode.lossy-reencode-threshold-mb:15}")
    private int lossyReencodeThresholdMb = 15;

    @Value("${music.loudness.enabled:true}")
    private boolean loudnessEnabled = true;

    @Value("${music.loudness.target-lufs:-14.0}")
    private double loudnessTargetLufs = -14.0;

    @Value("${music.loudness.true-peak:-1.5}")
    private double loudnessTruePeak = -1.5;

    @Value("${music.loudness.lra:11.0}")
    private double loudnessLra = 11.0;

    @Value("${music.loudness.lossy-tolerance-db:2.5}")
    private double loudnessLossyToleranceDb = 2.5;

    @Value("${music.loudness.legacy-backfill-enabled:true}")
    private boolean loudnessLegacyBackfillEnabled = true;

    @Value("${music.loudness.legacy-tolerance-db:3.0}")
    private double loudnessLegacyToleranceDb = 3.0;

    @Value("${music.loudness.legacy-batch-size:2}")
    private int loudnessLegacyBatchSize = 2;

    @Value("${music.cache.max-bytes:1073741824}")
    private long cacheMaxBytes;

    @Value("${music.cache.max-files:120}")
    private int cacheMaxFiles;

    @Value("${music.cache.ttl-seconds:86400}")
    private int cacheTtlSeconds;

    @Value("${music.cover.dir:files/covers}")
    private String coverDir;

    @Value("${music.cover.max-bytes:314572800}")
    private long coverMaxBytes;

    @Value("${music.temp.dir:files/tmp}")
    private String tempDir;

    @Value("${music.lyric.lrclib-base-url:https://lrclib.net}")
    private String lrclibBaseUrl;

    @Value("${music.lyric.netease-base-url:https://music.163.com}")
    private String neteaseBaseUrl;

    @Value("${music.lyric.proxy-url:}")
    private String lyricProxyUrl;

    public boolean isSyncEnabled() { return syncEnabled; }
    public void setSyncEnabled(boolean syncEnabled) { this.syncEnabled = syncEnabled; }

    public String getSourceFolderName() { return sourceFolderName; }
    public void setSourceFolderName(String sourceFolderName) { this.sourceFolderName = sourceFolderName; }

    public String getLegacySourceFolderName() { return legacySourceFolderName; }
    public void setLegacySourceFolderName(String legacySourceFolderName) { this.legacySourceFolderName = legacySourceFolderName; }

    public long getStorageChatId() { return storageChatId; }
    public void setStorageChatId(long storageChatId) { this.storageChatId = storageChatId; }

    public String getStorageChannelTitle() { return storageChannelTitle; }
    public void setStorageChannelTitle(String storageChannelTitle) { this.storageChannelTitle = storageChannelTitle; }

    public String getOpusBitrate() { return opusBitrate; }
    public void setOpusBitrate(String opusBitrate) { this.opusBitrate = opusBitrate; }

    public int getLossyReencodeThresholdMb() { return lossyReencodeThresholdMb; }
    public void setLossyReencodeThresholdMb(int lossyReencodeThresholdMb) { this.lossyReencodeThresholdMb = lossyReencodeThresholdMb; }

    public long getCacheMaxBytes() { return cacheMaxBytes; }
    public void setCacheMaxBytes(long cacheMaxBytes) { this.cacheMaxBytes = cacheMaxBytes; }

    public int getCacheMaxFiles() { return cacheMaxFiles; }
    public void setCacheMaxFiles(int cacheMaxFiles) { this.cacheMaxFiles = cacheMaxFiles; }

    public int getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }

    public String getCoverDir() { return coverDir; }
    public void setCoverDir(String coverDir) { this.coverDir = coverDir; }

    public long getCoverMaxBytes() { return coverMaxBytes; }
    public void setCoverMaxBytes(long coverMaxBytes) { this.coverMaxBytes = coverMaxBytes; }

    public String getTempDir() { return tempDir; }
    public void setTempDir(String tempDir) { this.tempDir = tempDir; }

    public String getLrclibBaseUrl() { return lrclibBaseUrl; }
    public void setLrclibBaseUrl(String lrclibBaseUrl) { this.lrclibBaseUrl = lrclibBaseUrl; }

    public String getNeteaseBaseUrl() { return neteaseBaseUrl; }
    public void setNeteaseBaseUrl(String neteaseBaseUrl) { this.neteaseBaseUrl = neteaseBaseUrl; }

    public String getLyricProxyUrl() { return lyricProxyUrl; }
    public void setLyricProxyUrl(String lyricProxyUrl) { this.lyricProxyUrl = lyricProxyUrl; }

    public boolean isLoudnessEnabled() { return loudnessEnabled; }
    public void setLoudnessEnabled(boolean loudnessEnabled) { this.loudnessEnabled = loudnessEnabled; }

    public double getLoudnessTargetLufs() { return loudnessTargetLufs; }
    public void setLoudnessTargetLufs(double loudnessTargetLufs) { this.loudnessTargetLufs = loudnessTargetLufs; }

    public double getLoudnessTruePeak() { return loudnessTruePeak; }
    public void setLoudnessTruePeak(double loudnessTruePeak) { this.loudnessTruePeak = loudnessTruePeak; }

    public double getLoudnessLra() { return loudnessLra; }
    public void setLoudnessLra(double loudnessLra) { this.loudnessLra = loudnessLra; }

    public double getLoudnessLossyToleranceDb() { return loudnessLossyToleranceDb; }
    public void setLoudnessLossyToleranceDb(double loudnessLossyToleranceDb) { this.loudnessLossyToleranceDb = loudnessLossyToleranceDb; }

    public boolean isLoudnessLegacyBackfillEnabled() { return loudnessLegacyBackfillEnabled; }
    public void setLoudnessLegacyBackfillEnabled(boolean loudnessLegacyBackfillEnabled) { this.loudnessLegacyBackfillEnabled = loudnessLegacyBackfillEnabled; }

    public double getLoudnessLegacyToleranceDb() { return loudnessLegacyToleranceDb; }
    public void setLoudnessLegacyToleranceDb(double loudnessLegacyToleranceDb) { this.loudnessLegacyToleranceDb = loudnessLegacyToleranceDb; }

    public int getLoudnessLegacyBatchSize() { return loudnessLegacyBatchSize; }
    public void setLoudnessLegacyBatchSize(int loudnessLegacyBatchSize) { this.loudnessLegacyBatchSize = loudnessLegacyBatchSize; }
}
