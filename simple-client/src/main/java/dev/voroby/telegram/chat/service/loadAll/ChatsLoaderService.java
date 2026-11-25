package dev.voroby.telegram.chat.service.loadAll;

import dev.voroby.springframework.telegram.TelegramRunner;
import dev.voroby.springframework.telegram.client.TelegramClient;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 低速限流加载会话列表（兼顾主列表 ChatListMain 与归档列表 ChatListArchive），
 * 每次分批间隔 500ms，避免启动瞬间与 updates.getDifference 争抢配额触发 Telegram 429 限流。
 */
@Component
public class ChatsLoaderService implements TelegramRunner {
    private static final Logger log = LoggerFactory.getLogger(ChatsLoaderService.class);
    private static final int BATCH_SIZE = 100;
    private static final long DELAY_MS = 500L;

    private final TelegramClient telegramClient;

    public ChatsLoaderService(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        loadListAsync(new TdApi.ChatListMain(), "Main");
    }

    private void loadListAsync(TdApi.ChatList chatList, String label) {
        telegramClient.sendWithCallback(new TdApi.LoadChats(chatList, BATCH_SIZE), (ok, error) -> {
            if (error == null) {
                CompletableFuture.delayedExecutor(DELAY_MS, TimeUnit.MILLISECONDS)
                        .execute(() -> loadListAsync(chatList, label));
            } else if (error.code == 404) {
                log.info("Chats loaded ({}).", label);
                if (chatList instanceof TdApi.ChatListMain) {
                    CompletableFuture.delayedExecutor(DELAY_MS, TimeUnit.MILLISECONDS)
                            .execute(() -> loadListAsync(new TdApi.ChatListArchive(), "Archive"));
                }
            } else {
                log.warn("LoadChats ({}) stopped with error: {} {}", label, error.code, error.message);
            }
        });
    }
}
