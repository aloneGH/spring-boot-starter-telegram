package dev.voroby.telegram.music.model;

import jakarta.persistence.*;

/**
 * 本地 SQLite 中存储的 Telegram 频道 / 聊天基础信息。
 * <p>
 * 目前仅同步指定文件夹下的频道，用于后续做本地展示或统计。
 */
@Entity
@Table(name = "channel_info")
public class ChannelInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Telegram chat id，频道的唯一标识。
     */
    @Column(name = "chat_id", unique = true, nullable = false)
    private Long chatId;

    /**
     * 频道/聊天标题。
     */
    @Column(name = "title")
    private String title;

    /**
     * @username，如果存在的话。
     */
    @Column(name = "username")
    private String username;

    /**
     * 频道类型（例如 supergroup / basic_group / private / secret），
     * 这里只是保存一个简化后的字符串，便于后续筛选或调试。
     */
    @Column(name = "chat_type")
    private String chatType;

    /**
     * 所属的 Telegram 聊天文件夹名称（例如 "Music"），
     * 便于将来支持多个文件夹的独立同步。
     */
    @Column(name = "folder_name")
    private String folderName;

    public ChannelInfo() {
    }

    public ChannelInfo(Long chatId,
                       String title,
                       String username,
                       String chatType,
                       String folderName) {
        this.chatId = chatId;
        this.title = title;
        this.username = username;
        this.chatType = chatType;
        this.folderName = folderName;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getChatType() {
        return chatType;
    }

    public void setChatType(String chatType) {
        this.chatType = chatType;
    }

    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }
}

