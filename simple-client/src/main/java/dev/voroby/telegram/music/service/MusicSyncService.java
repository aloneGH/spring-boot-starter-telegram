package dev.voroby.telegram.music.service;

import dev.voroby.springframework.telegram.client.TelegramClient;
import dev.voroby.springframework.telegram.client.templates.response.Response;
import dev.voroby.telegram.message.common.MessageCache;
import dev.voroby.telegram.music.cache.ChatFolderCache;
import dev.voroby.telegram.music.model.MusicMessage;
import dev.voroby.telegram.music.repository.MusicMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.drinkless.tdlib.TdApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 将指定文件夹中的频道消息（主要是歌曲文件）同步到本地 SQLite。
 * <p>
 * - 应用启动时执行一次历史消息增量同步（基于本地已保存的最大 messageId）；
 * - 运行期间基于 UpdateNewMessage 实时同步新消息；
 * - 通过 (chatId, messageId) 唯一约束保证本地数据无重复。
 */
@Service
@Slf4j
public class MusicSyncService {

    private static final int HISTORY_PAGE_LIMIT = 100;

    private final TelegramClient telegramClient;
    private final MusicMessageRepository musicMessageRepository;

    /**
     * 需要同步的聊天文件夹名称（人工筛选好的“音乐频道文件夹”）
     */
    @Value("${music.sync.folder-name:Music}")
    private String folderName;

    public MusicSyncService(TelegramClient telegramClient,
                            MusicMessageRepository musicMessageRepository) {
        this.telegramClient = telegramClient;
        this.musicMessageRepository = musicMessageRepository;
    }

    /**
     * 应用启动完成后做一次历史消息同步。
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void syncHistoryOnStartup() {
        try {
            log.info("开始执行音乐频道历史消息同步，目标文件夹名称: {}", folderName);
            List<TdApi.Chat> chats = ChatFolderCache.queryChats(log, telegramClient, folderName);
            if (chats == null || chats.isEmpty()) {
                log.warn("目标文件夹 '{}' 中未找到任何频道，历史同步跳过", folderName);
                return;
            }

            for (TdApi.Chat chat : chats) {
                syncHistoryForChat(chat);
            }
            log.info("音乐频道历史消息同步完成");
        } catch (Exception e) {
            log.error("音乐频道历史消息同步失败", e);
        }
    }

    public void syncHistoryForChat(TdApi.Chat chat) {
        long chatId = chat.id;
        log.info("开始同步频道 [{}] (id={}) 的历史消息", chat.title, chatId);

        // 先查一下本地该频道已保存的最新一条消息，用于增量同步
        MusicMessage lastSaved = musicMessageRepository.findTopByChatIdOrderByMessageIdDesc(chatId);
        Long lastSavedMessageId = lastSaved != null ? lastSaved.getMessageId() : null;

        long fromMessageId = 0; // 0 表示从最新消息开始
        int totalSaved = 0;
        boolean reachedExisting = false;

        while (true) {
            TdApi.GetChatHistory request = new TdApi.GetChatHistory(chatId, fromMessageId, 0, HISTORY_PAGE_LIMIT, false);
            Response<TdApi.Messages> response = telegramClient.send(request);
            TdApi.Messages messages = response.getObject().orElse(null);
            if (messages == null || messages.messages == null || messages.totalCount == 0 || messages.messages.length == 0) {
                break;
            }

            List<MusicMessage> toSave = new ArrayList<>();

            for (TdApi.Message message : messages.messages) {
                // TDLib 返回是按 messageId 递减（从新到旧），一旦遇到 <= 已存在的最大 ID，
                // 说明后面的都是更旧的历史，可以直接结束该频道的历史同步。
                if (lastSavedMessageId != null && message.id <= lastSavedMessageId) {
                    reachedExisting = true;
                    break;
                }
                if (notMusicMessage(message)) {
                    continue;
                }
                if (musicMessageRepository.existsByChatIdAndMessageId(chatId, message.id)) {
                    continue;
                }
                MusicMessage entity = convertToEntity(chatId, message);
                toSave.add(entity);
            }

            if (!toSave.isEmpty()) {
                musicMessageRepository.saveAll(toSave);
                totalSaved += toSave.size();
            }

            if (reachedExisting) {
                break;
            }

            // 下一轮从当前批次中最旧的那条消息 id 再往前翻
            long oldestMessageId = messages.messages[messages.messages.length - 1].id;
            if (oldestMessageId == 0 || oldestMessageId == fromMessageId) {
                break;
            }
            fromMessageId = oldestMessageId;
        }

        log.info("频道 [{}] (id={}) 历史消息同步完成，本次新增 {} 条音乐消息", chat.title, chatId, totalSaved);
    }

    /**
     * 定时消费实时新消息队列并写入 SQLite。
     * 与 message.service.print.scheduler.Scheduler 的机制类似。
     */
    @Scheduled(fixedDelay = 1000)
    public void syncRealtimeMessages() {
        // 如果文件夹信息还没准备好，直接跳过
        if (ChatFolderCache.chatFolders.isEmpty()) {
            return;
        }

        for (int i = 0; i < 100; i++) {
            TdApi.Message message = MessageCache.newMessagesQueue.pollFirst();
            if (message == null) {
                break;
            }

            try {
                if (!isFromTargetFolder(message)) {
                    continue;
                }
                if (notMusicMessage(message)) {
                    continue;
                }
                if (musicMessageRepository.existsByChatIdAndMessageId(message.chatId, message.id)) {
                    continue;
                }

                MusicMessage entity = convertToEntity(message.chatId, message);
                musicMessageRepository.save(entity);
            } catch (Exception e) {
                log.error("实时同步音乐消息失败, chatId={}, messageId={}", message.chatId, message.id, e);
            }
        }
    }

    /**
     * 是否来自目标文件夹中的频道。
     */
    private boolean isFromTargetFolder(TdApi.Message message) {
        List<TdApi.Chat> chats = ChatFolderCache.queryChats(log, telegramClient, folderName);
        if (chats == null || chats.isEmpty()) {
            return false;
        }
        for (TdApi.Chat chat : chats) {
            if (chat.id == message.chatId) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否为我们关心的“歌曲消息”，这里简单认为：
     * - 语音/音乐音频 TdApi.MessageAudio
     * - 或带文档的 TdApi.MessageDocument，且 MIME 类型为音频相关
     */
    private boolean notMusicMessage(TdApi.Message message) {
        TdApi.MessageContent content = message.content;
        if (content instanceof TdApi.MessageAudio) {
            return false;
        }
        if (content instanceof TdApi.MessageDocument md && md.document != null) {
            String mimeType = md.document.mimeType;
            if (mimeType == null) {
                return true;
            }
            return !mimeType.startsWith("audio/") && !mimeType.contains("mpeg") && !mimeType.contains("ogg");
        }
        return true;
    }

    private MusicMessage convertToEntity(long chatId, TdApi.Message message) {
        TdApi.MessageContent content = message.content;

        String fileName = null;
        String mimeType = null;
        String title = null;
        String performer = null;
        Integer durationSeconds = null;
        Integer coverFileId = null;
        Integer coverWidth = null;
        Integer coverHeight = null;
        Integer audioFileId = null;
        Long audioFileSize = null;

        if (content instanceof TdApi.MessageAudio ma && ma.audio != null) {
            TdApi.Audio audio = ma.audio;
            mimeType = audio.mimeType;
            durationSeconds = audio.duration;
            title = audio.title;
            performer = audio.performer;
            if (audio.fileName != null && !audio.fileName.isEmpty()) {
                fileName = audio.fileName;
            }
            if (audio.audio != null) {
                audioFileId = audio.audio.id;
                audioFileSize = audio.audio.size;
            }
            TdApi.Thumbnail thumb = audio.albumCoverThumbnail;
            if (thumb == null && audio.externalAlbumCovers != null && audio.externalAlbumCovers.length > 0) {
                thumb = audio.externalAlbumCovers[0];
            }
            if (thumb != null && thumb.file != null) {
                coverFileId = thumb.file.id;
                coverWidth = thumb.width;
                coverHeight = thumb.height;
            }
        } else if (content instanceof TdApi.MessageDocument md && md.document != null) {
            TdApi.Document doc = md.document;
            mimeType = doc.mimeType;
            if (doc.fileName != null && !doc.fileName.isEmpty()) {
                fileName = doc.fileName;
            }
            if (doc.document != null) {
                audioFileId = doc.document.id;
                audioFileSize = doc.document.size;
            }
            TdApi.Thumbnail thumb = doc.thumbnail;
            if (thumb != null && thumb.file != null) {
                coverFileId = thumb.file.id;
                coverWidth = thumb.width;
                coverHeight = thumb.height;
            }
        }

        Instant sentAt = Instant.ofEpochSecond(message.date);

        return new MusicMessage(
                chatId,
                message.id,
                sentAt,
                fileName,
                mimeType,
                title,
                performer,
                durationSeconds,
                coverFileId,
                coverWidth,
                coverHeight,
                audioFileId,
                audioFileSize
        );
    }
}

