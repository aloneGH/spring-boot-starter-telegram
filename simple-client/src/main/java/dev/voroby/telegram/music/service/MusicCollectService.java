package dev.voroby.telegram.music.service;

import dev.voroby.springframework.telegram.client.TelegramClient;
import dev.voroby.springframework.telegram.client.templates.response.Response;
import dev.voroby.telegram.music.cache.Cache;
import jakarta.annotation.Nullable;
import org.drinkless.tdlib.TdApi;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 音乐收集相关服务，例如获取 Channel 列表等。
 */
@Component
public class MusicCollectService {

    private static final Logger log = LoggerFactory.getLogger(MusicCollectService.class);
    private final TelegramClient telegramClient;

    /**
     * 单次获取聊天列表的最大数量
     */
    private static final int CHAT_LIST_LIMIT = 200;

    public MusicCollectService(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    /**
     * 获取当前用户所有 Channel 的名称。
     *
     * @param namePrefix 名称前缀过滤，只返回以此字符串开头的 Channel 名称；
     *                   传 {@code null} 或空字符串表示不过滤，返回全部
     * @return Channel 名称列表，未匹配到则返回空列表
     */
    public List<String> getAllChannelNames(String namePrefix) {
        Response<TdApi.Chats> chatsResponse = telegramClient.send(
                new TdApi.GetChats(new TdApi.ChatListMain(), CHAT_LIST_LIMIT));
        TdApi.Chats chats = chatsResponse.getObjectOrThrow();

        Stream<String> channelNames = Arrays.stream(chats.chatIds)
                .mapToObj(chatId -> {
                    Response<TdApi.Chat> chatResponse = telegramClient.send(new TdApi.GetChat(chatId));
                    return chatResponse.getObjectOrThrow();
                })
                .filter(MusicCollectService::isChannel)
                .map(chat -> chat.title);

        if (namePrefix != null && !namePrefix.isEmpty()) {
            channelNames = channelNames.filter(name -> name != null && name.startsWith(namePrefix));
        }

        return channelNames.toList();
    }

    private static boolean isChannel(TdApi.Chat chat) {
        if (chat == null || chat.type == null) {
            return false;
        }
        if (chat.type instanceof TdApi.ChatTypeSupergroup supergroup) {
            return supergroup.isChannel;
        }
        return false;
    }

    @Nullable
    public List<TdApi.Chat> queryChats(@NonNull String folderName) {
        TdApi.ChatFolderInfo folderInfo = Cache.chatFolders.stream()
                .filter(it -> folderName.equals(it.name.text.text))
                .findFirst().orElse(null);
        if (folderInfo == null) {
            log.warn("No folder info with name {} found", folderName);
            return null;
        }

        Response<TdApi.ChatFolder> rspChatFolder = telegramClient.send(new TdApi.GetChatFolder(folderInfo.id));
        TdApi.ChatFolder chatFolder = rspChatFolder.getObject().orElse(null);
        if (chatFolder == null) {
            log.warn("No folder with name {} found", folderName);
            return null;
        }

        List<TdApi.Chat> chats = new ArrayList<>();
        for (long chatId : chatFolder.includedChatIds) {
            Response<TdApi.Chat> rspChat = telegramClient.send(new TdApi.GetChat(chatId));
            TdApi.Chat chat = rspChat.getObject().orElse(null);
            if (chat == null) {
                log.warn("query chat with id {} not found", chatId);
                continue;
            }
            chats.add(chat);
        }

        return chats;
    }
}
