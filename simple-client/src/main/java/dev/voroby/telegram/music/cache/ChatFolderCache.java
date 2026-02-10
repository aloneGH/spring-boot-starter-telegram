package dev.voroby.telegram.music.cache;

import dev.voroby.springframework.telegram.client.TelegramClient;
import dev.voroby.springframework.telegram.client.templates.response.Response;
import jakarta.annotation.Nullable;
import org.drinkless.tdlib.TdApi;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public final class ChatFolderCache {
    public static final List<TdApi.ChatFolderInfo> chatFolders = Collections.synchronizedList(new ArrayList<>());

    @Nullable
    public static List<TdApi.Chat> queryChats(@NonNull Logger log, @NonNull TelegramClient telegramClient,
                                              @NonNull String folderName) {
        TdApi.ChatFolderInfo folderInfo = ChatFolderCache.chatFolders.stream()
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
