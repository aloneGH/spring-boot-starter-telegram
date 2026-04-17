package dev.voroby.telegram.music.cache;

import dev.voroby.springframework.telegram.client.TelegramClient;
import dev.voroby.springframework.telegram.client.templates.response.Response;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.drinkless.tdlib.TdApi;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public final class ChatFolderCache {
    public static final List<TdApi.ChatFolderInfo> chatFolders = Collections.synchronizedList(new ArrayList<>());

    private static int sTdLibTimeoutCount = 0;

    @Nullable
    public static List<TdApi.Chat> queryChats(@NonNull Logger log, @NonNull TelegramClient telegramClient,
                                              @NonNull String folderName, ApplicationContext applicationContext) throws Exception {
        TdApi.ChatFolder chatFolder = queryChatFolder(telegramClient, folderName, applicationContext, log);
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

    @Nullable
    public static TdApi.ChatFolderInfo queryChatFolderInfo(@Nonnull String folderName) {
        return chatFolders.stream()
                .filter(it -> folderName.equals(it.name.text.text))
                .findFirst().orElse(null);
    }

    @Nullable
    public static TdApi.ChatFolder queryChatFolder(@NonNull TelegramClient telegramClient, @Nonnull String folderName,
                                                   ApplicationContext applicationContext, @NonNull Logger log)
            throws Exception {
        TdApi.ChatFolderInfo folderInfo = queryChatFolderInfo(folderName);

        if (folderInfo == null) {
            return null;
        }

        Response<TdApi.ChatFolder> response = telegramClient.send(new TdApi.GetChatFolder(folderInfo.id));
        TdApi.Error error = response.getError().orElse(null);
        if (error != null) {
            sTdLibTimeoutCount += error.message.contains("TDLib request timeout") ? 1 : 0;
            if (sTdLibTimeoutCount > 5) {
                log.error("TDLib request timeout exceeded, exit application");
                SpringApplication.exit(applicationContext, () -> -1);
                System.exit(-1);
            }
            throw new Exception("Error while fetching chat folder: " + error);
        }
        return response.getObject().orElseThrow();
    }
}
