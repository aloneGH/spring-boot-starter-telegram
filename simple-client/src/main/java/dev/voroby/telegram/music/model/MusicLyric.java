package dev.voroby.telegram.music.model;

import jakarta.persistence.*;

@Entity
@Table(name = "music_lyric", indexes = {
        @Index(name = "chatid_msgid", columnList = "chat_id, message_id")
})
public class MusicLyric {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "lyric")
    private String lyric;

    @Column(name = "sync")
    private Integer sync;

    public MusicLyric() {
    }

    public MusicLyric(Long chatId, Long messageId, String lyric, Integer sync) {
        this.chatId = chatId;
        this.messageId = messageId;
        this.lyric = lyric;
        this.sync = sync;
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

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getLyric() {
        return lyric;
    }

    public void setLyric(String lyric) {
        this.lyric = lyric;
    }

    public Integer getSync() {
        return sync;
    }

    public void setSync(Integer sync) {
        this.sync = sync;
    }
}
