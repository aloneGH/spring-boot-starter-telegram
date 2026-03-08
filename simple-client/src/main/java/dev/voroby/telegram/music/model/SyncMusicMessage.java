package dev.voroby.telegram.music.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "sync_music_message", indexes = {
        @Index(name = "message_id_temp", columnList = "message_id_temp"),
        @Index(name = "fix_duration", columnList = "duration_seconds, fixDuration")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_title_performer", columnNames = {"title", "performer"})
})
public class SyncMusicMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Telegram chat id
     */
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "origin_chat_id", nullable = false)
    private Long originChatId;

    /**
     * Telegram message id
     */
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "message_id_temp", nullable = false)
    private Long messageIdTemp;

    @Column(name = "origin_message_id", nullable = false)
    private Long originMessageId;

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
     * 音频文件大小（字节），便于后续做统计或校验。
     */
    @Column(name = "audio_file_size")
    private Long audioFileSize;

    @Column(name = "fix_duration")
    private Integer fixDuration;

    public SyncMusicMessage() {
    }

    public SyncMusicMessage(Long chatId, Long originChatId, Long messageId, Long messageIdTemp, Long originMessageId,
                            Instant sentAt, String fileName, String mimeType, String title, String performer,
                            Integer durationSeconds, Integer coverFileId, Integer coverWidth, Integer coverHeight,
                            Long audioFileSize, Integer fixDuration) {
        this.chatId = chatId;
        this.originChatId = originChatId;
        this.messageId = messageId;
        this.messageIdTemp = messageIdTemp;
        this.originMessageId = originMessageId;
        this.sentAt = sentAt;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.title = title;
        this.performer = performer;
        this.durationSeconds = durationSeconds;
        this.coverFileId = coverFileId;
        this.coverWidth = coverWidth;
        this.coverHeight = coverHeight;
        this.audioFileSize = audioFileSize;
        this.fixDuration = fixDuration;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public Long getOriginChatId() {
        return originChatId;
    }

    public void setOriginChatId(Long originChatId) {
        this.originChatId = originChatId;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public Long getMessageIdTemp() {
        return messageIdTemp;
    }

    public void setMessageIdTemp(Long messageIdTemp) {
        this.messageIdTemp = messageIdTemp;
    }

    public Long getOriginMessageId() {
        return originMessageId;
    }

    public void setOriginMessageId(Long originMessageId) {
        this.originMessageId = originMessageId;
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

    public Long getAudioFileSize() {
        return audioFileSize;
    }

    public void setAudioFileSize(Long audioFileSize) {
        this.audioFileSize = audioFileSize;
    }

    public Integer getFixDuration() {
        return fixDuration;
    }

    public void setFixDuration(Integer fixDuration) {
        this.fixDuration = fixDuration;
    }
}
