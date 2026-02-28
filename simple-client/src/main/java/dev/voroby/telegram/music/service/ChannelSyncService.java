package dev.voroby.telegram.music.service;

import dev.voroby.springframework.telegram.client.TelegramClient;
import dev.voroby.springframework.telegram.client.templates.response.Response;
import dev.voroby.telegram.music.cache.ChatFolderCache;
import dev.voroby.telegram.music.model.ChannelInfo;
import dev.voroby.telegram.music.repository.ChannelInfoRepository;
import dev.voroby.telegram.music.repository.MusicMessageRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
public class ChannelSyncService {
    private static final Logger log = LoggerFactory.getLogger(ChannelSyncService.class);

    private final TelegramClient telegramClient;
    private final ChannelInfoRepository channelInfoRepository;
    private final MusicMessageRepository musicMessageRepository;
    private final MusicSyncService musicSyncService;

    /**
     * 需要同步的聊天文件夹名称。
     * 为了和音乐消息保持一致，这里直接复用 music.sync.folder-name 配置。
     */
    @Value("${music.sync.folder-name:Music}")
    private String musicFolderName;

    @Value("${music.sync.MusicSource:Music-Source}")
    private String musicSourceFolderName;

    public ChannelSyncService(TelegramClient telegramClient,
                              ChannelInfoRepository channelInfoRepository, MusicMessageRepository musicMessageRepository,
                              MusicSyncService musicSyncService) {
        this.telegramClient = telegramClient;
        this.channelInfoRepository = channelInfoRepository;
        this.musicMessageRepository = musicMessageRepository;
        this.musicSyncService = musicSyncService;
    }

    /**
     * 周期性同步频道信息，确保：
     * - 新增的频道会被插入到本地；
     * - 标题 / 用户名 等基础信息发生变化会被更新；
     * - 在 Telegram 中被删除或移出该文件夹的频道，会从本地删除。
     */
    @Scheduled(fixedDelay = 300_000)
    public synchronized void syncFolderChannels() {
        // 如果文件夹信息还没准备好，直接跳过
        if (ChatFolderCache.chatFolders.isEmpty()) {
            return;
        }

        List<TdApi.Chat> chats = ChatFolderCache.queryChats(log, telegramClient, musicFolderName);
        if (chats == null || chats.isEmpty()) {
            // 为了避免误删（例如文件夹信息暂时拿不到），这里不做删除，只记录日志。
            log.warn("目标文件夹 '{}' 中未找到任何频道，本次频道同步跳过删除逻辑", musicFolderName);
            return;
        }

        Set<Long> currentChatIds = new HashSet<>();

        for (TdApi.Chat chat : chats) {
            currentChatIds.add(chat.id);
            if (upsertChannel(chat, musicFolderName)) {
                musicSyncService.syncHistoryForChat(chat);
            }
        }

        // 删除本地中已经不在该文件夹中的频道数据
        try {
            channelInfoRepository.deleteByFolderNameAndChatIdNotIn(musicFolderName, currentChatIds);
        } catch (Exception e) {
            log.error("删除本地已不存在的频道记录失败, folderName={}, remainIds={}", musicFolderName, currentChatIds, e);
        }

        // 删除本地中已经不存在的消息
        try {
            musicMessageRepository.deleteByChatIdNotIn(currentChatIds);
        } catch (Exception e) {
            log.error("删除本地中已经不存在的消息失败, remainIds={}", currentChatIds, e);
        }

        syncMusicSourceChats(chats);
    }

    @Nonnull
    public static String getChatTitleForMusicSource(@Nonnull String title) {
        return "CW-" + title;
    }

    private synchronized void syncMusicSourceChats(@Nonnull List<TdApi.Chat> srcChats) {
        List<TdApi.Chat> currentChats = ChatFolderCache.queryChats(log, telegramClient, musicSourceFolderName);
        if (currentChats == null) {
            log.error("no chats found for musicSourceFolderName={}", musicSourceFolderName);
            return;
        }

        List<String> currentChatTitles = currentChats.stream().map(it -> it.title).toList();
        List<Long> newChatIds = new ArrayList<>();
        for (TdApi.Chat chat : srcChats) {
            String chatTitle = getChatTitleForMusicSource(chat.title);
            if (currentChatTitles.contains(chatTitle)) {
                continue;
            }

            TdApi.Chat newChannel = createChannel(chatTitle, chatTitle + " for music source");
            if (newChannel == null) {
                continue;
            }

            newChatIds.add(newChannel.id);
        }

        if (!newChatIds.isEmpty()) {
            addChatToFolder(newChatIds, musicSourceFolderName);
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

    @Nullable
    public TdApi.Chat createChannel(@Nonnull String title, @Nonnull String desc) {
        TdApi.CreateNewSupergroupChat request = new TdApi.CreateNewSupergroupChat(title, false, true,
                desc, null, 0, false);
        Response<TdApi.Chat> response = telegramClient.send(request);
        TdApi.Error error = response.getError().orElse(null);
        if (error != null) {
            log.error("createChannel: error={}, title = {}", error, title);
            return null;
        }

        TdApi.Chat chat = response.getObject().orElse(null);
        if (chat == null) {
            log.error("createChannel: failed to create chat: {}", title);
        } else {
            log.info("createChannel: ok chat={}", chat.title);
        }
        return chat;
    }

    public boolean addChatToFolder(@Nonnull List<Long> chatIds, @Nonnull String folderName) {
        TdApi.ChatFolderInfo folderInfo = ChatFolderCache.queryChatFolderInfo(folderName);
        if (folderInfo == null) {
            log.error("addChatToFolder: folderInfo not found -> {}", folderName);
            return false;
        }

        TdApi.ChatFolder folder = ChatFolderCache.queryChatFolder(telegramClient, folderName);
        if (folder == null) {
            log.error("addChatToFolder: folderName={} not found", folderName);
            return false;
        }

        List<Long> includedChatIds = new ArrayList<>(folder.includedChatIds.length + chatIds.size());
        for (long chatId : folder.includedChatIds) {
            includedChatIds.add(chatId);
        }
        includedChatIds.addAll(chatIds);
        folder.includedChatIds = includedChatIds.stream().mapToLong(Long::longValue).toArray();

        TdApi.EditChatFolder request = new TdApi.EditChatFolder(folderInfo.id, folder);
        Response<TdApi.ChatFolderInfo> response = telegramClient.send(request);
        TdApi.Error error = response.getError().orElse(null);
        if (error != null) {
            log.error("addChatToFolder: error={}", error);
            return false;
        }

        log.info("addChatToFolder: chatIds={}, folderName={} ok", chatIds, folderName);
        return true;
    }
}

