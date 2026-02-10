package dev.voroby.telegram.music.service;

import dev.voroby.springframework.telegram.client.TelegramClient;
import dev.voroby.telegram.music.cache.ChatFolderCache;
import dev.voroby.telegram.music.model.ChannelInfo;
import dev.voroby.telegram.music.repository.ChannelInfoRepository;
import lombok.extern.slf4j.Slf4j;
import org.drinkless.tdlib.TdApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 将指定文件夹下的频道基础信息同步到本地 SQLite。
 * <p>
 * - 应用启动时做一次全量同步；
 * - 定时任务周期性对比当前 Telegram 文件夹中的频道列表，更新/删除本地数据，保持一致性。
 */
@Service
@Slf4j
public class ChannelSyncService {

    private final TelegramClient telegramClient;
    private final ChannelInfoRepository channelInfoRepository;
    private final MusicSyncService musicSyncService;

    /**
     * 需要同步的聊天文件夹名称。
     * 为了和音乐消息保持一致，这里直接复用 music.sync.folder-name 配置。
     */
    @Value("${music.sync.folder-name:Music}")
    private String folderName;

    public ChannelSyncService(TelegramClient telegramClient,
                              ChannelInfoRepository channelInfoRepository, MusicSyncService musicSyncService) {
        this.telegramClient = telegramClient;
        this.channelInfoRepository = channelInfoRepository;
        this.musicSyncService = musicSyncService;
    }

    /**
     * 应用启动完成后，先做一次全量同步。
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        try {
            log.info("开始执行频道信息启动同步，目标文件夹名称: {}", folderName);
            syncFolderChannels();
            log.info("频道信息启动同步完成");
        } catch (Exception e) {
            log.error("频道信息启动同步失败", e);
        }
    }

    /**
     * 周期性同步频道信息，确保：
     * - 新增的频道会被插入到本地；
     * - 标题 / 用户名 等基础信息发生变化会被更新；
     * - 在 Telegram 中被删除或移出该文件夹的频道，会从本地删除。
     */
    @Scheduled(fixedDelay = 60_000)
    public void syncFolderChannels() {
        // 如果文件夹信息还没准备好，直接跳过
        if (ChatFolderCache.chatFolders.isEmpty()) {
            return;
        }

        List<TdApi.Chat> chats = ChatFolderCache.queryChats(log, telegramClient, folderName);
        if (chats == null || chats.isEmpty()) {
            // 为了避免误删（例如文件夹信息暂时拿不到），这里不做删除，只记录日志。
            log.warn("目标文件夹 '{}' 中未找到任何频道，本次频道同步跳过删除逻辑", folderName);
            return;
        }

        Set<Long> currentChatIds = new HashSet<>();

        for (TdApi.Chat chat : chats) {
            currentChatIds.add(chat.id);
            if (upsertChannel(chat, folderName)) {
                musicSyncService.syncHistoryForChat(chat);
            }
        }

        // 删除本地中已经不在该文件夹中的频道数据
        try {
            channelInfoRepository.deleteByFolderNameAndChatIdNotIn(folderName, currentChatIds);
        } catch (Exception e) {
            log.error("删除本地已不存在的频道记录失败, folderName={}, remainIds={}", folderName, currentChatIds, e);
        }
    }

    private boolean upsertChannel(TdApi.Chat chat, String folderName) {
        Long chatId = chat.id;
        ChannelInfo existing = channelInfoRepository.findByChatId(chatId);

        String title = chat.title;
        String username = null;
        String chatType = simplifyChatType(chat.type);

        if (existing == null) {
            ChannelInfo channelInfo = new ChannelInfo(chatId, title, username, chatType, folderName);
            channelInfoRepository.save(channelInfo);
            return true;
        }

        boolean changed = false;
        if (notEquals(existing.getTitle(), title)) {
            existing.setTitle(title);
            changed = true;
        }
        if (notEquals(existing.getUsername(), username)) {
            existing.setUsername(username);
            changed = true;
        }
        if (notEquals(existing.getChatType(), chatType)) {
            existing.setChatType(chatType);
            changed = true;
        }
        if (notEquals(existing.getFolderName(), folderName)) {
            existing.setFolderName(folderName);
            changed = true;
        }

        if (changed) {
            channelInfoRepository.save(existing);
        }

        return false;
    }

    private boolean notEquals(Object a, Object b) {
        if (a == b) {
            return false;
        }
        if (a == null || b == null) {
            return true;
        }
        return !a.equals(b);
    }

    private String simplifyChatType(TdApi.ChatType type) {
        if (type == null) {
            return null;
        }
        if (type instanceof TdApi.ChatTypePrivate) {
            return "private";
        }
        if (type instanceof TdApi.ChatTypeSecret) {
            return "secret";
        }
        if (type instanceof TdApi.ChatTypeBasicGroup) {
            return "basic_group";
        }
        if (type instanceof TdApi.ChatTypeSupergroup supergroup) {
            return supergroup.isChannel ? "channel" : "supergroup";
        }
        return type.getClass().getSimpleName();
    }
}

