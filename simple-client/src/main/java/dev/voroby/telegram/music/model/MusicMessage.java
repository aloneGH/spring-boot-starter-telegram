package dev.voroby.telegram.music.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 本地 SQLite 中存储的音乐消息实体。
 * 通过 (title, performer) 保证去重。
 */
@Entity
@Table(name = "music_message",
        uniqueConstraints = @UniqueConstraint(name = "uk_title_performer", columnNames = {"title", "performer"}))
public class MusicMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Telegram chat id
     */
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    /**
     * Telegram message id
     */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /**
     * 消息发送时间
     */
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    /**
     * 文件名（如果存在）
     */
    @Column(name = "file_name")
    private String fileName;

    /**
     * MIME 类型
     */
    @Column(name = "mime_type")
    private String mimeType;

    /**
     * 歌曲标题
     */
    @Column(name = "title")
    private String title;

    /**
     * 演唱者 / 艺术家
     */
    @Column(name = "performer")
    private String performer;

    /**
     * 时长（秒）
     */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    /**
     * 封面图对应的 Telegram File id（缩略图）
     */
    @Column(name = "cover_file_id")
    private Integer coverFileId;

    /**
     * 封面图宽度
     */
    @Column(name = "cover_width")
    private Integer coverWidth;

    /**
     * 封面图高度
     */
    @Column(name = "cover_height")
    private Integer coverHeight;

    /**
     * 音频文件在 Telegram 中对应的 File id。
     * 后续读取音频二进制数据时，可通过该 id 下载文件。
     */
    @Column(name = "audio_file_id")
    private Integer audioFileId;

    /**
     * 音频文件大小（字节），便于后续做统计或校验。
     */
    @Column(name = "audio_file_size")
    private Long audioFileSize;

    public MusicMessage() {
    }

    public MusicMessage(Long chatId,
                        Long messageId,
                        Instant sentAt,
                        String fileName,
                        String mimeType,
                        String title,
                        String performer,
                        Integer durationSeconds,
                        Integer coverFileId,
                        Integer coverWidth,
                        Integer coverHeight,
                        Integer audioFileId,
                        Long audioFileSize) {
        this.chatId = chatId;
        this.messageId = messageId;
        this.sentAt = sentAt;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.title = title;
        this.performer = performer;
        this.durationSeconds = durationSeconds;
        this.coverFileId = coverFileId;
        this.coverWidth = coverWidth;
        this.coverHeight = coverHeight;
        this.audioFileId = audioFileId;
        this.audioFileSize = audioFileSize;
    }

    public Long getId() {
        return id;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
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

    public String getPerformer() {
        return performer;
    }

    public void setPerformer(String performer) {
        this.performer = performer;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Integer getCoverFileId() {
        return coverFileId;
    }

    public void setCoverFileId(Integer coverFileId) {
        this.coverFileId = coverFileId;
    }

    public Integer getCoverWidth() {
        return coverWidth;
    }

    public void setCoverWidth(Integer coverWidth) {
        this.coverWidth = coverWidth;
    }

    public Integer getCoverHeight() {
        return coverHeight;
    }

    public void setCoverHeight(Integer coverHeight) {
        this.coverHeight = coverHeight;
    }

    public Integer getAudioFileId() {
        return audioFileId;
    }

    public void setAudioFileId(Integer audioFileId) {
        this.audioFileId = audioFileId;
    }

    public Long getAudioFileSize() {
        return audioFileSize;
    }

    public void setAudioFileSize(Long audioFileSize) {
        this.audioFileSize = audioFileSize;
    }
}

