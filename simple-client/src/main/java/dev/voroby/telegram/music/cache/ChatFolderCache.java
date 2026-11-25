package dev.voroby.telegram.music.cache;

import dev.voroby.springframework.telegram.client.TelegramClient;
import dev.voroby.springframework.telegram.client.templates.response.Response;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telegram 聊天文件夹内存缓存：
 * - 缓存 folderName -> includedChatIds（5 分钟 TTL），避免每次收到消息都发 GetChatFolder 触发 429；
 * - 移除危险的超时自杀 System.exit(-1) 逻辑。
 */
public final class ChatFolderCache {
    public static final List<TdApi.ChatFolderInfo> chatFolders = Collections.synchronizedList(new ArrayList<>());

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final Map<String, CachedFolderIds> folderIdsCache = new ConcurrentHashMap<>();

    private record CachedFolderIds(Set<Long> chatIds, long fetchedAtMs) {
        boolean isFresh() {
            return System.currentTimeMillis() - fetchedAtMs < CACHE_TTL_MS;
        }
    }

    public static void invalidateAll() {
        folderIdsCache.clear();
    }

    @Nullable
    public static TdApi.ChatFolderInfo queryChatFolderInfo(@Nonnull String folderName) {
        synchronized (chatFolders) {
            return chatFolders.stream()
                    .filter(it -> it != null && it.name != null && it.name.text != null
                            && folderName.equals(it.name.text.text))
                    .findFirst().orElse(null);
        }
    }

    /**
     * 快速查询内存中缓存的文件夹内 chatId 集合（无网络开销，供实时监听器过滤使用）。
     */
    @Nonnull
    public static Set<Long> getCachedChatIds(@Nonnull String folderName) {
        CachedFolderIds cached = folderIdsCache.get(folderName);
        return cached != null ? cached.chatIds() : Collections.emptySet();
    }

    /**
     * 获取文件夹包含的 chatId 集合（优先命中 5 分钟缓存，过期时再请求 TDLib）。
     */
    @Nonnull
    public static Set<Long> queryChatIds(@Nonnull Logger log, @Nonnull TelegramClient telegramClient,
                                         @Nonnull String folderName, boolean forceRefresh) {
        CachedFolderIds cached = folderIdsCache.get(folderName);
        if (!forceRefresh && cached != null && cached.isFresh()) {
            return cached.chatIds();
        }
        TdApi.ChatFolder folder = queryChatFolder(telegramClient, folderName, log);
        if (folder == null || folder.includedChatIds == null) {
            return cached != null ? cached.chatIds() : Collections.emptySet();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (long id : folder.includedChatIds) {
            ids.add(id);
        }
        Set<Long> unmodifiable = Collections.unmodifiableSet(ids);
        folderIdsCache.put(folderName, new CachedFolderIds(unmodifiable, System.currentTimeMillis()));
        return unmodifiable;
    }

    @Nullable
    public static TdApi.ChatFolder queryChatFolder(@Nonnull TelegramClient telegramClient,
                                                   @Nonnull String folderName,
                                                   @Nonnull Logger log) {
        TdApi.ChatFolderInfo folderInfo = queryChatFolderInfo(folderName);
        if (folderInfo == null) {
            return null;
        }
        Response<TdApi.ChatFolder> response = telegramClient.send(new TdApi.GetChatFolder(folderInfo.id));
        TdApi.Error error = response.getError().orElse(null);
        if (error != null) {
            log.warn("GetChatFolder failed for '{}' (id={}): {}", folderName, folderInfo.id, error.message);
            return null;
        }
        return response.getObject().orElse(null);
    }
}
