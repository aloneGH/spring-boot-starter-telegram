package dev.voroby.telegram.music.tdlib;

import dev.voroby.springframework.telegram.client.TelegramClient;
import dev.voroby.springframework.telegram.client.templates.response.Response;
import dev.voroby.telegram.music.config.MusicProperties;
import dev.voroby.telegram.music.repository.MusicCatalogRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一封装全部 TDLib 交互与磁盘缓存守卫：
 * 1. ensureChatLoaded：自动唤醒频道，解决 use-message-database=false 重启后 400 Chat not found；
 * 2. downloadFileAwait：先注册 Future 再发 DownloadFile，多并发共享同一 Future，彻底消除竞态；
 * 3. uploadAudioAndConfirm：使用 InputMessageAudio 上传至固定私有仓库频道，等待 UpdateMessageSendSucceeded 正式 ID；
 * 4. 8GB 磁盘护栏：周期性执行 OptimizeStorage（1GB/24h/120文件）、清理 tmp/ 与 300MB 封面 LRU。
 */
@Service
public class TdGatewayService {
    private static final Logger log = LoggerFactory.getLogger(TdGatewayService.class);
    private static final String KV_STORAGE_CHAT_ID = "storage_chat_id";
    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile("retry after (\\d+)", Pattern.CASE_INSENSITIVE);

    private final TelegramClient telegramClient;
    private final MusicProperties properties;
    private final MusicCatalogRepository catalogRepository;

    // fileId -> 并发共享下载 Future
    private final ConcurrentHashMap<Integer, CompletableFuture<TdApi.File>> downloadFutures = new ConcurrentHashMap<>();
    // "chatId:tempMsgId" -> 上传正式确认 Future
    private final ConcurrentHashMap<String, CompletableFuture<TdApi.Message>> uploadFutures = new ConcurrentHashMap<>();
    // 秒传竞态保护：若 UpdateMessageSendSucceeded 先于 uploadFutures.put 到达，暂存 60 秒
    private final ConcurrentHashMap<String, EarlyUploadConfirmation> earlyConfirmedUploads = new ConcurrentHashMap<>();

    private volatile Long resolvedStorageChatId;
    private volatile long floodWaitUntilMs = 0L;

    public TdGatewayService(TelegramClient telegramClient,
                            MusicProperties properties,
                            MusicCatalogRepository catalogRepository) {
        this.telegramClient = telegramClient;
        this.properties = properties;
        this.catalogRepository = catalogRepository;
    }

    @PostConstruct
    public void cleanupTempOnStartup() {
        try {
            File tmpDir = new File(properties.getTempDir());
            if (tmpDir.exists()) {
                FileUtils.cleanDirectory(tmpDir);
                log.info("Cleaned temp directory on startup: {}", tmpDir.getAbsolutePath());
            } else {
                Files.createDirectories(tmpDir.toPath());
            }
            Files.createDirectories(new File(properties.getCoverDir()).toPath());
        } catch (Exception e) {
            log.warn("Failed to initialize temp/cover dirs: {}", e.getMessage());
        }
    }

    /**
     * 每 6 小时自动执行 TDLib 磁盘缓存裁剪（1GB / 24h / 120 文件）及封面目录 300MB LRU 清理。
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 6 * 3600_000L)
    public void periodicStorageMaintenance() {
        try {
            TdApi.OptimizeStorage req = new TdApi.OptimizeStorage(
                    properties.getCacheMaxBytes(),
                    properties.getCacheTtlSeconds(),
                    properties.getCacheMaxFiles(),
                    30 * 60, // 30 分钟内刚下载的不清理
                    null, null, null, false, 0
            );
            telegramClient.sendWithCallback(req, (obj, error) -> {
                if (error != null || obj == null) {
                    log.warn("OptimizeStorage skipped/failed: {}", error != null ? error.message : "null");
                    return;
                }
                log.info("TDLib OptimizeStorage done: current file cache size = {} MB, count = {}",
                        obj.size / (1024 * 1024), obj.count);
            });
        } catch (Exception e) {
            log.warn("OptimizeStorage invocation error: {}", e.getMessage());
        }

        enforceCoverDirQuota();
        try {
            catalogRepository.runPeriodicSqliteMaintenance();
            log.info("SQLite periodic WAL checkpoint & PRAGMA optimize completed.");
        } catch (Exception e) {
            log.warn("SQLite maintenance skipped/failed: {}", e.getMessage());
        }
    }

    public boolean isInFloodWait() {
        return System.currentTimeMillis() < floodWaitUntilMs;
    }

    public long getFloodWaitRemainingSeconds() {
        long rem = floodWaitUntilMs - System.currentTimeMillis();
        return rem > 0 ? (rem + 999) / 1000 : 0;
    }

    private void recordFloodWaitIfNeeded(@Nullable TdApi.Error err) {
        if (err == null || err.code != 429) {
            return;
        }
        int retrySec = 60;
        if (StringUtils.isNotBlank(err.message)) {
            Matcher m = RETRY_AFTER_PATTERN.matcher(err.message);
            if (m.find()) {
                try {
                    retrySec = Math.max(5, Math.min(3600, Integer.parseInt(m.group(1))));
                } catch (NumberFormatException ignored) {}
            }
        }
        long until = System.currentTimeMillis() + (retrySec + 2L) * 1000L;
        if (until > floodWaitUntilMs) {
            floodWaitUntilMs = until;
        }
        log.warn("TDLib 429 FloodWait detected ({}): pausing background tasks for {}s", err.message, retrySec + 2);
    }

    /**
     * 优先通知 TDLib 删除本地缓存文件（保持内部 file_database 一致），再兜底删除残留物理文件。
     */
    public void deleteTdFileQuietly(@Nullable Integer tdFileId, @Nullable File localFile) {
        if (tdFileId != null && tdFileId > 0) {
            try {
                telegramClient.send(new TdApi.DeleteFile(tdFileId));
            } catch (Exception e) {
                log.debug("DeleteFile({}) warning: {}", tdFileId, e.getMessage());
            }
        }
        if (localFile != null && localFile.exists()) {
            FileUtils.deleteQuietly(localFile);
        }
    }

    public void enforceCoverDirQuota() {
        File coverDir = new File(properties.getCoverDir());
        if (!coverDir.isDirectory()) return;
        File[] files = coverDir.listFiles();
        if (files == null || files.length == 0) return;

        long total = 0;
        for (File f : files) {
            if (f.isFile()) total += f.length();
        }
        long maxBytes = properties.getCoverMaxBytes();
        if (total <= maxBytes) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long target = (long) (maxBytes * 0.85);
        int removed = 0;
        for (File f : files) {
            if (total <= target) break;
            long len = f.length();
            if (f.delete()) {
                total -= len;
                removed++;
            }
        }
        log.info("Cover LRU cleanup removed {} files, remaining {} MB", removed, total / (1024 * 1024));
    }

    /**
     * 确保指定 chatId 在 TDLib 内存中已注册（解决 use-message-database=false 重启后 Chat not found）。
     */
    public boolean ensureChatLoaded(long chatId) {
        if (chatId == 0) return false;
        TdApi.Function<TdApi.Chat> fn;
        if (chatId <= -1_000_000_000_000L) {
            long supergroupId = -chatId - 1_000_000_000_000L;
            fn = new TdApi.CreateSupergroupChat(supergroupId, false);
        } else if (chatId < 0) {
            fn = new TdApi.CreateBasicGroupChat(-chatId, false);
        } else {
            fn = new TdApi.CreatePrivateChat(chatId, false);
        }
        Response<TdApi.Chat> rsp = telegramClient.send(fn);
        return rsp.getError().isEmpty() && rsp.getObject().isPresent();
    }

    @Nullable
    public TdApi.Chat getChatWithRetry(long chatId) {
        Response<TdApi.Chat> rsp = telegramClient.send(new TdApi.GetChat(chatId));
        if (rsp.getObject().isPresent()) {
            return rsp.getObject().get();
        }
        TdApi.Error err = rsp.getError().orElse(null);
        recordFloodWaitIfNeeded(err);
        if (err != null && err.code == 400 && StringUtils.containsIgnoreCase(err.message, "Chat not found")) {
            if (ensureChatLoaded(chatId)) {
                return telegramClient.send(new TdApi.GetChat(chatId)).getObject().orElse(null);
            }
        }
        return null;
    }

    /**
     * 查询单条消息，自动处理 Chat not found 唤醒重试，并明确区分 404（消息已删）与其他错误。
     */
    public MessageLookupResult getMessageWithRetry(long chatId, long messageId) {
        Response<TdApi.Message> rsp = telegramClient.send(new TdApi.GetMessage(chatId, messageId));
        if (rsp.getObject().isPresent()) {
            return new MessageLookupResult(rsp.getObject().get(), false, null);
        }
        TdApi.Error err = rsp.getError().orElse(null);
        recordFloodWaitIfNeeded(err);
        if (err != null && err.code == 400 && StringUtils.containsIgnoreCase(err.message, "Chat not found")) {
            if (ensureChatLoaded(chatId)) {
                rsp = telegramClient.send(new TdApi.GetMessage(chatId, messageId));
                if (rsp.getObject().isPresent()) {
                    return new MessageLookupResult(rsp.getObject().get(), false, null);
                }
                err = rsp.getError().orElse(null);
                recordFloodWaitIfNeeded(err);
            }
        }
        boolean notFound = err != null && err.code == 404;
        return new MessageLookupResult(null, notFound, err != null ? err.code + ":" + err.message : "unknown");
    }

    @Nullable
    public TdApi.Messages getChatHistory(long chatId, long fromMessageId, int limit) {
        Response<TdApi.Messages> rsp = telegramClient.send(new TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false));
        if (rsp.getObject().isPresent()) {
            return rsp.getObject().get();
        }
        TdApi.Error err = rsp.getError().orElse(null);
        recordFloodWaitIfNeeded(err);
        if (err != null && err.code == 400 && StringUtils.containsIgnoreCase(err.message, "Chat not found")) {
            if (ensureChatLoaded(chatId)) {
                return telegramClient.send(new TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false))
                        .getObject().orElse(null);
            }
        }
        return null;
    }

    /**
     * 获取或自动创建固定单一私有仓库频道（优先环境变量 STORAGE_CHAT_ID -> kv_config -> 自动创建）。
     */
    public synchronized long getOrCreateStorageChat() {
        if (resolvedStorageChatId != null && resolvedStorageChatId != 0) {
            ensureChatLoaded(resolvedStorageChatId);
            return resolvedStorageChatId;
        }
        if (properties.getStorageChatId() != 0) {
            long configured = properties.getStorageChatId();
            if (getChatWithRetry(configured) != null) {
                resolvedStorageChatId = configured;
                catalogRepository.putConfig(KV_STORAGE_CHAT_ID, String.valueOf(configured));
                log.info("Using configured fixed storage channel: {}", configured);
                return configured;
            }
            log.warn("Configured storage chat {} not accessible yet, will fallback check", configured);
        }

        Optional<String> savedId = catalogRepository.getConfig(KV_STORAGE_CHAT_ID);
        if (savedId.isPresent()) {
            try {
                long id = Long.parseLong(savedId.get());
                if (getChatWithRetry(id) != null) {
                    resolvedStorageChatId = id;
                    log.info("Using persisted fixed storage channel: {}", id);
                    return id;
                }
            } catch (NumberFormatException ignored) {}
        }

        String title = StringUtils.defaultIfBlank(properties.getStorageChannelTitle(), "CW-Music-Vault");
        // 在已加载会话中按标题查找，避免重复创建
        Response<TdApi.Chats> searchRsp = telegramClient.send(new TdApi.SearchChatsOnServer(title, 20));
        if (searchRsp.getObject().isPresent() && searchRsp.getObject().get().chatIds != null) {
            for (long cid : searchRsp.getObject().get().chatIds) {
                TdApi.Chat c = getChatWithRetry(cid);
                if (c != null && title.equals(c.title) && c.type instanceof TdApi.ChatTypeSupergroup sg && sg.isChannel) {
                    resolvedStorageChatId = c.id;
                    catalogRepository.putConfig(KV_STORAGE_CHAT_ID, String.valueOf(c.id));
                    log.info("Found existing fixed storage channel '{}' (id={})", title, c.id);
                    return c.id;
                }
            }
        }

        Response<TdApi.Chat> createRsp = telegramClient.send(new TdApi.CreateNewSupergroupChat(
                title, false, true, "Unified private music vault for MusicFree", null, 0, false));
        TdApi.Chat created = createRsp.getObject().orElse(null);
        if (created == null) {
            throw new IllegalStateException("Failed to create fixed storage channel '" + title + "': "
                    + createRsp.getError().map(e -> e.message).orElse("unknown"));
        }
        resolvedStorageChatId = created.id;
        catalogRepository.putConfig(KV_STORAGE_CHAT_ID, String.valueOf(created.id));
        log.info("Created fixed private storage channel '{}' (id={})", title, created.id);
        return created.id;
    }

    /**
     * 并发安全的下载并等待完成：先注册 Future 再发 DownloadFile，多路同曲请求共享一次下载。
     */
    public DownloadResult downloadFileAwait(long chatId, long messageId, Duration timeout) {
        MessageLookupResult lookup = getMessageWithRetry(chatId, messageId);
        if (lookup.message == null) {
            return new DownloadResult(null, lookup.notFound, lookup.error);
        }
        TdApi.File file = extractPrimaryAudioFile(lookup.message);
        if (file == null) {
            return new DownloadResult(null, true, "message has no audio/document file");
        }
        // 已下载且磁盘文件存在直接返回
        if (isDownloadedAndExists(file)) {
            return new DownloadResult(file, false, null);
        }

        int fileId = file.id;
        boolean[] shouldTrigger = {false};
        CompletableFuture<TdApi.File> future = downloadFutures.computeIfAbsent(fileId, k -> {
            shouldTrigger[0] = true;
            return new CompletableFuture<>();
        });

        try {
            if (shouldTrigger[0]) {
                Response<TdApi.File> dlRsp = telegramClient.send(new TdApi.DownloadFile(fileId, 16, 0, 0, false));
                TdApi.Error err = dlRsp.getError().orElse(null);
                if (err != null) {
                    recordFloodWaitIfNeeded(err);
                    downloadFutures.remove(fileId, future);
                    future.completeExceptionally(new RuntimeException("DownloadFile error: " + err.message));
                    return new DownloadResult(null, err.code == 404, err.code + ":" + err.message);
                }
                TdApi.File immediate = dlRsp.getObject().orElse(null);
                if (isDownloadedAndExists(immediate)) {
                    downloadFutures.remove(fileId, future);
                    future.complete(immediate);
                    return new DownloadResult(immediate, false, null);
                }
            }
            TdApi.File completed = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new DownloadResult(completed, false, null);
        } catch (TimeoutException te) {
            downloadFutures.remove(fileId, future);
            return new DownloadResult(null, false, "download timeout after " + timeout.toSeconds() + "s");
        } catch (Exception e) {
            downloadFutures.remove(fileId, future);
            return new DownloadResult(null, false, e.getMessage());
        }
    }

    /**
     * 低优先级静默预热指定音频文件到本地 TDLib 缓存（用于下一首秒播，非阻塞）。
     */
    public void prefetchFileAsync(long chatId, long messageId) {
        try {
            MessageLookupResult lookup = getMessageWithRetry(chatId, messageId);
            if (lookup.message == null) return;
            TdApi.File file = extractPrimaryAudioFile(lookup.message);
            if (file == null || isDownloadedAndExists(file)) return;
            int fileId = file.id;
            boolean[] shouldTrigger = {false};
            CompletableFuture<TdApi.File> future = downloadFutures.computeIfAbsent(fileId, k -> {
                shouldTrigger[0] = true;
                return new CompletableFuture<>();
            });
            if (shouldTrigger[0]) {
                telegramClient.sendWithCallback(new TdApi.DownloadFile(fileId, 8, 0, 0, false), (obj, err) -> {
                    if (err != null) {
                        downloadFutures.remove(fileId, future);
                        future.completeExceptionally(new RuntimeException("Prefetch DownloadFile error: " + err.message));
                    } else if (isDownloadedAndExists(obj)) {
                        downloadFutures.remove(fileId, future);
                        future.complete(obj);
                    }
                });
            }
        } catch (Exception e) {
            log.debug("prefetchFileAsync skipped for {}:{}: {}", chatId, messageId, e.getMessage());
        }
    }

    /**
     * 供 UpdateFileListener 回调：下载完成立刻唤醒所有等待方（移除原 2 秒无意义延迟）。
     */
    public void onFileUpdated(@Nullable TdApi.File file) {
        if (!isDownloadedAndExists(file)) {
            return;
        }
        CompletableFuture<TdApi.File> future = downloadFutures.remove(file.id);
        if (future != null) {
            future.complete(file);
        }
    }

    /**
     * 使用 InputMessageAudio 上传音频到固定仓库频道，并同步等待 UpdateMessageSendSucceeded 拿到正式 messageId。
     */
    public TdApi.Message uploadAudioAndConfirm(long storageChatId,
                                               @Nonnull File audioFile,
                                               @Nullable File coverThumbFile,
                                               int durationSec,
                                               @Nonnull String title,
                                               @Nonnull String artist,
                                               @Nullable String captionText,
                                               long sourceTrackId,
                                               @Nullable Long targetTrackId,
                                               @Nonnull String pendingPayloadJson,
                                               Duration timeout) throws Exception {
        ensureChatLoaded(storageChatId);

        TdApi.InputThumbnail thumb = null;
        if (coverThumbFile != null && coverThumbFile.exists() && coverThumbFile.length() > 0 && coverThumbFile.length() <= 200 * 1024) {
            thumb = new TdApi.InputThumbnail(new TdApi.InputFileLocal(coverThumbFile.getAbsolutePath()), 240, 240);
        }
        TdApi.FormattedText caption = StringUtils.isNotBlank(captionText)
                ? new TdApi.FormattedText(captionText, null) : null;

        TdApi.InputMessageAudio content = new TdApi.InputMessageAudio(
                new TdApi.InputFileLocal(audioFile.getAbsolutePath()),
                thumb,
                Math.max(0, durationSec),
                StringUtils.defaultString(title),
                StringUtils.defaultString(artist),
                caption
        );

        Response<TdApi.Message> sendRsp = telegramClient.send(
                new TdApi.SendMessage(storageChatId, null, null, null, null, content));
        TdApi.Error err = sendRsp.getError().orElse(null);
        if (err != null || sendRsp.getObject().isEmpty()) {
            recordFloodWaitIfNeeded(err);
            throw new IllegalStateException("SendMessage failed: " + (err != null ? err.code + ":" + err.message : "null"));
        }

        TdApi.Message tempMsg = sendRsp.getObject().get();
        String key = uploadKey(storageChatId, tempMsg.id);
        CompletableFuture<TdApi.Message> confirmFuture = new CompletableFuture<>();
        uploadFutures.put(key, confirmFuture);

        // 先落盘 pending_uploads，即使进程重启也不丢上传上下文
        catalogRepository.savePendingUpload(storageChatId, tempMsg.id, sourceTrackId, targetTrackId,
                audioFile.getAbsolutePath(), pendingPayloadJson);

        // 检查是否命中秒传（UpdateMessageSendSucceeded 早于 put 到达）
        EarlyUploadConfirmation early = earlyConfirmedUploads.remove(key);
        if (early != null) {
            uploadFutures.remove(key, confirmFuture);
            catalogRepository.deletePendingUpload(storageChatId, tempMsg.id);
            return early.message;
        }

        // 若消息状态已经是已发送（无 sendingState），直接完成
        if (tempMsg.sendingState == null) {
            uploadFutures.remove(key);
            catalogRepository.deletePendingUpload(storageChatId, tempMsg.id);
            return tempMsg;
        }

        try {
            TdApi.Message confirmed = confirmFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            catalogRepository.deletePendingUpload(storageChatId, tempMsg.id);
            return confirmed;
        } catch (Exception e) {
            uploadFutures.remove(key);
            throw e;
        }
    }

    public void onMessageSendSucceeded(@Nonnull TdApi.UpdateMessageSendSucceeded update) {
        if (update.message == null) return;
        String key = uploadKey(update.message.chatId, update.oldMessageId);
        CompletableFuture<TdApi.Message> future = uploadFutures.remove(key);
        if (future != null) {
            future.complete(update.message);
        } else {
            pruneEarlyConfirmations();
            earlyConfirmedUploads.put(key, new EarlyUploadConfirmation(update.message, System.currentTimeMillis()));
        }
        catalogRepository.deletePendingUpload(update.message.chatId, update.oldMessageId);
    }

    private void pruneEarlyConfirmations() {
        long cutoff = System.currentTimeMillis() - 60_000L;
        earlyConfirmedUploads.entrySet().removeIf(e -> e.getValue().recordedAt < cutoff);
    }

    public void onMessageSendFailed(@Nonnull TdApi.UpdateMessageSendFailed update) {
        if (update.message == null) return;
        recordFloodWaitIfNeeded(update.error);
        String key = uploadKey(update.message.chatId, update.oldMessageId);
        CompletableFuture<TdApi.Message> future = uploadFutures.remove(key);
        String msg = update.error != null ? update.error.code + ":" + update.error.message : "send failed";
        if (future != null) {
            future.completeExceptionally(new RuntimeException("Upload send failed: " + msg));
        }
        catalogRepository.deletePendingUpload(update.message.chatId, update.oldMessageId);
    }

    @Nullable
    public static TdApi.File extractPrimaryAudioFile(@Nonnull TdApi.Message message) {
        TdApi.MessageContent c = message.content;
        if (c instanceof TdApi.MessageAudio ma && ma.audio != null) return ma.audio.audio;
        if (c instanceof TdApi.MessageDocument md && md.document != null) return md.document.document;
        if (c instanceof TdApi.MessageVoiceNote mv && mv.voiceNote != null) return mv.voiceNote.voice;
        return null;
    }

    private static boolean isDownloadedAndExists(@Nullable TdApi.File file) {
        return file != null && file.local != null && file.local.isDownloadingCompleted
                && StringUtils.isNotBlank(file.local.path) && new File(file.local.path).exists();
    }

    private static String uploadKey(long chatId, long msgId) {
        return chatId + ":" + msgId;
    }

    private record EarlyUploadConfirmation(TdApi.Message message, long recordedAt) {}
    public record MessageLookupResult(@Nullable TdApi.Message message, boolean notFound, @Nullable String error) {}
    public record DownloadResult(@Nullable TdApi.File file, boolean notFound, @Nullable String error) {}
}
