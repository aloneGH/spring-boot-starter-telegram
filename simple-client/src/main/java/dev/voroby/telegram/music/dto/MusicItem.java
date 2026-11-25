package dev.voroby.telegram.music.dto;

import java.io.Serializable;

public class MusicItem implements Serializable {
    private long folderId;
    private long musicId;
    private String fileName;
    private String mimeType;
    private String title;
    private String artist;
    private String album;
    private Integer durationSeconds;
    private Long audioFileSize;
    private String coverUrl;
    private String qualityTag;

    public MusicItem() {
    }

    public MusicItem(long folderId, long musicId, String fileName, String mimeType, String title, String artist,
                     Integer durationSeconds, Long audioFileSize) {
        this(folderId, musicId, fileName, mimeType, title, artist, null, durationSeconds, audioFileSize, null, null);
    }

    public MusicItem(long folderId, long musicId, String fileName, String mimeType, String title, String artist,
                     String album, Integer durationSeconds, Long audioFileSize, String coverUrl, String qualityTag) {
        this.folderId = folderId;
        this.musicId = musicId;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationSeconds = durationSeconds;
        this.audioFileSize = audioFileSize;
        this.coverUrl = coverUrl;
        this.qualityTag = qualityTag;
    }

    public long getFolderId() {
        return folderId;
    }

    public void setFolderId(long folderId) {
        this.folderId = folderId;
    }

    public long getMusicId() {
        return musicId;
    }

    public void setMusicId(long musicId) {
        this.musicId = musicId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Long getAudioFileSize() {
        return audioFileSize;
    }

    public void setAudioFileSize(Long audioFileSize) {
        this.audioFileSize = audioFileSize;
    }

    public String getCoverUrl() {
        return coverUrl;
    }

    public void setCoverUrl(String coverUrl) {
        this.coverUrl = coverUrl;
    }

    public String getQualityTag() {
        return qualityTag;
    }

    public void setQualityTag(String qualityTag) {
        this.qualityTag = qualityTag;
    }

    @Override
    public String toString() {
        return "MusicItem{" +
                "folderId=" + folderId +
                ", musicId=" + musicId +
                ", fileName='" + fileName + '\'' +
                ", mimeType='" + mimeType + '\'' +
                ", title='" + title + '\'' +
                ", artist='" + artist + '\'' +
                ", album='" + album + '\'' +
                ", durationSeconds=" + durationSeconds +
                ", audioFileSize=" + audioFileSize +
                ", coverUrl='" + coverUrl + '\'' +
                ", qualityTag='" + qualityTag + '\'' +
                '}';
    }
}
