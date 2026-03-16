package dev.voroby.telegram.music.service;

import dev.voroby.springframework.telegram.client.TelegramClient;
import dev.voroby.springframework.telegram.client.templates.response.Response;
import dev.voroby.telegram.message.common.MessageCache;
import dev.voroby.telegram.music.cache.ChatFolderCache;
import dev.voroby.telegram.music.model.MusicMessage;
import dev.voroby.telegram.music.model.SrcMusicMessage;
import dev.voroby.telegram.music.model.SyncMusicMessage;
import dev.voroby.telegram.music.repository.MusicMessageRepository;
import dev.voroby.telegram.music.repository.SrcMusicMessageRepository;
import dev.voroby.telegram.music.repository.SyncMusicMessageRepository;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.commons.io.FileUtils;
import org.drinkless.tdlib.TdApi;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 将指定文件夹中的频道消息（主要是歌曲文件）同步到本地 SQLite。
 * <p>
 * - 应用启动时执行一次历史消息增量同步（基于本地已保存的最大 messageId）；
 * - 运行期间基于 UpdateNewMessage 实时同步新消息；
 * - 通过 (chatId, messageId) 唯一约束保证本地数据无重复。
 */
@Service
public class MusicSyncService {
    private static final Logger log = LoggerFactory.getLogger(MusicSyncService.class);

    private static final int HISTORY_PAGE_LIMIT = 100;

    private final TelegramClient telegramClient;
    private final MusicMessageRepository musicMessageRepository;
    private final SrcMusicMessageRepository srcMusicMessageRepository;
    private final SyncMusicMessageRepository syncMusicMessageRepository;
    private final AudioConvertService audioConvertService;

    public static final Map<Integer, CompletableFuture<TdApi.File>> downloadFutures = new ConcurrentHashMap<>();

    /**
     * 需要同步的聊天文件夹名称（人工筛选好的“音乐频道文件夹”）
     */
    @Value("${music.sync.folder-name:Music}")
    private String folderName;

    @Value("${music.sync.MusicSource:Music-Source}")
    private String musicSourceFolderName;

    public MusicSyncService(TelegramClient telegramClient,
                            MusicMessageRepository musicMessageRepository, SrcMusicMessageRepository srcMusicMessageRepository,
                            SyncMusicMessageRepository syncMusicMessageRepository,
                            AudioConvertService audioConvertService) {
        this.telegramClient = telegramClient;
        this.musicMessageRepository = musicMessageRepository;
        this.srcMusicMessageRepository = srcMusicMessageRepository;
        this.syncMusicMessageRepository = syncMusicMessageRepository;
        this.audioConvertService = audioConvertService;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("Starting Music Sync Service");
        TdApi.OptimizeStorage optimizeRequest = new TdApi.OptimizeStorage(
                2L * 1024 * 1024 * 1024, // 2GB
                2 * 24 * 60 * 60,         // 7 天 (秒)
                100,                     // 最多保留 1000 个文件
                30 * 60,              // 刚下载 1 小时内的文件不参与自动清理
                null,
                null,
                null,
                false,
                0
        );
        telegramClient.sendWithCallback(optimizeRequest, (obj, error) -> {
            // 打印当前总字节数
            System.out.println("当前 TDLib 占用空间: " + obj.size / (1024 * 1024) + " MB");
        });
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

        try {
            log.info("开始执行音乐频道历史消息同步，目标文件夹名称: {}", musicSourceFolderName);
            List<TdApi.Chat> chats = ChatFolderCache.queryChats(log, telegramClient, musicSourceFolderName);
            if (chats == null || chats.isEmpty()) {
                log.warn("目标文件夹 '{}' 中未找到任何频道，历史同步跳过", musicSourceFolderName);
                return;
            }

            for (TdApi.Chat chat : chats) {
                syncSrcHistoryForChat(chat);
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

    public void syncSrcHistoryForChat(TdApi.Chat chat) {
        long chatId = chat.id;
        log.info("开始同步频道 [{}] (id={}) 的历史消息", chat.title, chatId);

        // 先查一下本地该频道已保存的最新一条消息，用于增量同步
        SrcMusicMessage lastSaved = srcMusicMessageRepository.findTopByChatIdOrderByMessageIdDesc(chatId);
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

            List<SrcMusicMessage> toSave = new ArrayList<>();

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
                if (srcMusicMessageRepository.existsByChatIdAndMessageId(chatId, message.id)) {
                    continue;
                }
                SrcMusicMessage entity = convertToSrcMusicMessage(chatId, message);
                toSave.add(entity);
            }

            if (!toSave.isEmpty()) {
                srcMusicMessageRepository.saveAll(toSave);
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
    @Scheduled(fixedDelay = 60_000)
    public void syncRealtimeMessages() {
        // 如果文件夹信息还没准备好，直接跳过
        if (ChatFolderCache.chatFolders.isEmpty()) {
            return;
        }

        for (int i = 0; i < 6000; i++) {
            TdApi.Message message = MessageCache.newMessagesQueue.pollFirst();
            if (message == null) {
                break;
            }

            try {
                if (notMusicMessage(message)) {
                    continue;
                }

                if (isFromTargetFolder(message, folderName)
                        && !musicMessageRepository.existsByChatIdAndMessageId(message.chatId, message.id)) {
                    MusicMessage entity = convertToEntity(message.chatId, message);
                    musicMessageRepository.save(entity);
                }

                if (isFromTargetFolder(message, musicSourceFolderName)
                        && !srcMusicMessageRepository.existsByChatIdAndMessageId(message.chatId, message.id)) {
                    SrcMusicMessage item = convertToSrcMusicMessage(message.chatId, message);
                    srcMusicMessageRepository.save(item);
                }
            } catch (Exception e) {
                log.error("实时同步音乐消息失败, chatId={}, messageId={}", message.chatId, message.id, e);
            }
        }
    }

    /**
     * 是否来自目标文件夹中的频道。
     */
    private boolean isFromTargetFolder(TdApi.Message message, @Nonnull String targetFolder) {
        try {
            List<TdApi.Chat> chats = ChatFolderCache.queryChats(log, telegramClient, targetFolder);
            if (chats == null || chats.isEmpty()) {
                return false;
            }
            for (TdApi.Chat chat : chats) {
                if (chat.id == message.chatId) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("isFromTargetFolder: failed", e);
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

        if (content instanceof TdApi.MessageVoiceNote) {
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
                audioFileSize,
                0
        );
    }

    private SrcMusicMessage convertToSrcMusicMessage(long chatId, TdApi.Message message) {
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
        } else if (content instanceof TdApi.MessageVoiceNote mv && mv.voiceNote != null) {
            TdApi.VoiceNote note = mv.voiceNote;
            mimeType = note.mimeType;
            durationSeconds = note.duration;
            if (note.voice != null) {
                audioFileSize = note.voice.size;
            }

            String[] split = mv.caption.text.split("-");
            if (split.length == 2) {
                title = split[0];
                performer = split[1];
            } else if (split.length == 1) {
                title = split[0];
            }
        }

        Instant sentAt = Instant.ofEpochSecond(message.date);

        return new SrcMusicMessage(chatId,
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
                audioFileSize,
                1);
    }

    @Nullable
    public TdApi.File queryFile(int fileId) {
        log.info("queryFile: {}", fileId);
        Response<TdApi.File> response = telegramClient.send(new TdApi.GetFile(fileId));
        TdApi.Error error = response.getError().orElse(null);
        if (error != null) {
            log.error("queryFile error: {}", error);
            return null;
        }

        TdApi.File file = response.getObject().orElse(null);
        if (file == null) {
            log.error("queryFile file is null: {}", fileId);
            return null;
        }

        log.info("queryFile: {} -> {}, size = {}", fileId, file.local.path, file.expectedSize);
        return file;
    }

    @Nullable
    public TdApi.Message queryMessage(long chatId, long messageId) {
        Response<TdApi.Message> response = telegramClient.send(new TdApi.GetMessage(chatId, messageId));
        TdApi.Error error = response.getError().orElse(null);
        if (error != null) {
            log.error("queryMessage: failed to find msg, {} -> {}", messageId, error);
            return null;
        }

        return response.getObject().orElse(null);
    }

    @Nullable
    public TdApi.File downloadFile(long chatId, long messageId) {
        log.info("downloadFile: {}", messageId);
        TdApi.Message message = queryMessage(chatId, messageId);
        if (message == null) {
            log.error("downloadFile: failed to find msg -> {} -> {}", chatId, messageId);
            return null;
        }

        int fileId = getFileId(message);
        if (fileId == 0) {
            log.error("downloadFile: failed to find fileId -> {} -> {}", chatId, messageId);
            return null;
        }

        TdApi.File file = queryFile(fileId);
        if (file == null) {
            log.error("downloadFile: failed, file is null");
            return null;
        }

        String path = file.local.path;
        log.info("local path {}", path);
        if (file.local.isDownloadingCompleted && new File(path).exists()) {
            log.info("file is downloaded");
            return file;
        }

        if (!file.local.canBeDownloaded) {
            log.info("local can't be downloaded");
            return null;
        }

        log.info("request to download");
        Response<TdApi.File> fileResponse = telegramClient.send(new TdApi.DownloadFile(fileId, 1, 0, 0, false));
        TdApi.Error error = fileResponse.getError().orElse(null);
        if (error != null) {
            log.error("downloadFile error: {}", error);
            return null;
        }

        file = fileResponse.getObject().orElse(file);
        return file;
    }

    private static int getFileId(TdApi.Message message) {
        TdApi.MessageContent content = message.content;
        int fileId = 0;
        if (content instanceof TdApi.MessageAudio ma && ma.audio != null) {
            TdApi.Audio audio = ma.audio;
            fileId = audio.audio.id;
        } else if (content instanceof TdApi.MessageDocument md && md.document != null) {
            fileId = md.document.document.id;
        } else if (content instanceof TdApi.MessageVoiceNote mv && mv.voiceNote != null) {
            fileId = mv.voiceNote.voice.id;
        }
        return fileId;
    }

    @Scheduled(fixedDelay = 120_000)
    public void syncMusic() {
        syncMusicMessages(100);
    }

    public void syncMusicMessages(int count) {
        log.info("syncMusicMessages count={}", count);
        List<TdApi.Chat> chats;
        try {
            chats = ChatFolderCache.queryChats(log, telegramClient, folderName);
            if (chats == null || chats.isEmpty()) {
                log.error("syncMusicMessages chats is null or empty");
                return;
            }
        } catch (Exception e) {
            log.error("syncMusicMessages error", e);
            return;
        }

        List<MusicMessage> unsyncMusicMessage = new ArrayList<>();
        for (TdApi.Chat chat : chats) {
            long chatId = chat.id;
            List<MusicMessage> musicMessages = findUnsyncMusicMessage(chatId, count);
            unsyncMusicMessage.addAll(musicMessages);
            if (unsyncMusicMessage.size() >= count) {
                break;
            }
        }

        if (unsyncMusicMessage.isEmpty()) {
            log.warn("syncMusicMessages is empty");
            return;
        }

        int success = 0;
        for (MusicMessage musicMessage : unsyncMusicMessage) {
            long originChatId = musicMessage.getChatId();
            long messageId = musicMessage.getMessageId();

            TdApi.File tdFile = downloadFile(originChatId, messageId);
            if (tdFile == null) {
                log.error("downloadFile: failed to download file -> {} -> {}", originChatId, messageId);
                continue;
            }

            CompletableFuture<TdApi.File> future = new CompletableFuture<>();
            downloadFutures.put(tdFile.id, future);

            TdApi.Chat newChat = queryNewChat(telegramClient, originChatId);
            if (newChat == null) {
                future.completeExceptionally(new RuntimeException("failed to query new chat " + originChatId));
                continue;
            }

            CompletableFuture<Void> wholeChain = future
                    .thenCompose(file -> convertMusicFormat(file, musicMessage))
                    .thenCompose(file -> sendAudioMessage(newChat.id, file, musicMessage))
                    .thenAccept(message -> {
                        SyncMusicMessage syncMusicMessage = new SyncMusicMessage(message.chatId, originChatId, -1L, message.id,
                                musicMessage.getMessageId(), Instant.ofEpochSecond(message.date),
                                musicMessage.getFileName(), musicMessage.getMimeType(), musicMessage.getTitle(),
                                musicMessage.getPerformer(), musicMessage.getDurationSeconds(),
                                musicMessage.getCoverFileId(), musicMessage.getCoverWidth(), musicMessage.getCoverHeight(),
                                musicMessage.getAudioFileSize(), 0);
                        syncMusicMessageRepository.save(syncMusicMessage);
                        musicMessageRepository.updateSyncById(1, musicMessage.getId());
                        downloadFutures.remove(tdFile.id);
                    }).exceptionally(ex -> {
                        log.error("syncMusicMessages error: {}", ex.getMessage());
                        musicMessageRepository.updateSyncById(2, musicMessage.getId());
                        downloadFutures.remove(tdFile.id);
                        return null;
                    });

            if (tdFile.local.isDownloadingCompleted && new File(tdFile.local.path).exists()) {
                future.complete(tdFile);
            }

            try {
                wholeChain.get(180L, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("syncMusicMessages: timeout -> {}", musicMessage, e);
            }
        }

        log.info("syncMusicMessages: done, success count={}", success);
    }

    @Nonnull
    public CompletableFuture<File> convertMusicFormat(@Nonnull TdApi.File file,
                                                      @Nonnull MusicMessage musicMessage) {
        CompletableFuture<File> future = new CompletableFuture<>();

        File srcFile = new File(file.local.path);
        int small_file_size = 10 * 1024 * 1024;
        if (file.size <= small_file_size) {
            future.complete(srcFile);
        } else {
            File srcDir = srcFile.getParentFile().getParentFile();
            File destDir = new File(srcDir, "converted");

            try {
                Files.createDirectories(destDir.toPath());
            } catch (Exception e) {
                log.error("convertMusicFormat: failed to create directories", e);
                future.completeExceptionally(e);
                return future;
            }

            String srcFilename = musicMessage.getFileName();
            String dstFilename = srcFilename.substring(0, srcFilename.lastIndexOf('.')) + ".ogg";
            String dstFileNameWithoutPath = dstFilename;
            srcFilename = srcFile.getAbsolutePath();

            File dstFile = new File(destDir, dstFilename);
            dstFilename = dstFile.getAbsolutePath();
            if (audioConvertService.convertToOpus(srcFilename, dstFilename)) {
                if (musicMessage.getDurationSeconds() == 0) {
                    musicMessage.setDurationSeconds(audioConvertService.queryDuration(dstFilename));
                }

                if (!StringUtils.hasText(musicMessage.getTitle())) {
                    Map<String, String> id3Info = audioConvertService.queryID3Info(srcFilename);
                    musicMessage.setTitle(id3Info.getOrDefault(AudioConvertService.ID3_TITLE, ""));
                    musicMessage.setPerformer(id3Info.getOrDefault(AudioConvertService.ID3_ARTIST, ""));
                }

                musicMessage.setFileName(dstFileNameWithoutPath);
                musicMessage.setMimeType("audio/ogg");
                musicMessage.setAudioFileSize(dstFile.length());
                future.complete(dstFile);
            } else {
                future.completeExceptionally(new RuntimeException("failed to convert audio file: " + srcFilename));
            }
            FileUtils.deleteQuietly(srcFile);
        }

        return future;
    }

    @Nonnull
    public CompletableFuture<TdApi.Message> sendAudioMessage(long chatId, @Nonnull File file,
                                                             @Nonnull MusicMessage musicMessage) {
        CompletableFuture<TdApi.Message> future = new CompletableFuture<>();

        boolean isOpusFile = file.getName().endsWith(".ogg");
        TdApi.FormattedText caption = new TdApi.FormattedText(String.join("-", musicMessage.getTitle(),
                musicMessage.getPerformer()), null);
        TdApi.InputFileLocal inputFile = new TdApi.InputFileLocal(file.getAbsolutePath());
        TdApi.InputMessageContent content = isOpusFile
                ? new TdApi.InputMessageVoiceNote(inputFile, musicMessage.getDurationSeconds(), null, caption,
                null)
                : new TdApi.InputMessageAudio(inputFile, null, musicMessage.getDurationSeconds(),
                musicMessage.getTitle(), musicMessage.getPerformer(),
                caption);

        telegramClient.sendWithCallback(new TdApi.SendMessage(chatId, null, null, null, null, content),
                (obj, error) -> {
                    if (error == null) {
                        future.complete(obj);
                    } else {
                        future.completeExceptionally(new RuntimeException("sendAudioMessage: failed -> " + error));
                    }
                });

        return future;
    }

    @Nonnull
    private List<MusicMessage> findUnsyncMusicMessage(long chatId, int count) {
        List<MusicMessage> results = new ArrayList<>();
        int pageIdx = 0;

        Page<MusicMessage> page;
        do {
            PageRequest pageRequest = PageRequest.of(pageIdx, count);
            page = musicMessageRepository.findByChatIdAndSyncIsNullOrSyncLessThan(chatId, 1, pageRequest);
            page.forEach(it -> {
                boolean exists = syncMusicMessageRepository.existsByOriginChatIdAndOriginMessageId(chatId, it.getMessageId());
                if (!exists) {
                    results.add(it);
                }
            });

            if (results.size() >= count) {
                break;
            }

            pageIdx += 1;
        } while (page.hasNext());

        return results;
    }

    @Nullable
    public TdApi.Chat queryNewChat(@NonNull TelegramClient telegramClient, long chatId) {
        Response<TdApi.Chat> response = telegramClient.send(new TdApi.GetChat(chatId));
        TdApi.Chat chat = response.getObject().orElse(null);
        if (chat == null) {
            return null;
        }

        String newChatTitle = ChannelSyncService.getChatTitleForMusicSource(chat.title);
        List<TdApi.Chat> chats;
        try {
            chats = ChatFolderCache.queryChats(log, telegramClient, musicSourceFolderName);
            if (chats == null || chats.isEmpty()) {
                return null;
            }
        } catch (Exception e) {
            log.error("queryNewChat: failed", e);
            return null;
        }

        for (TdApi.Chat t : chats) {
            if (t.title.equals(newChatTitle)) {
                return t;
            }
        }

        return null;
    }

    @Scheduled(fixedDelay = 180_000)
    public void fixMusicDuration() {
        fixMusicDuration(100);
    }

    public void fixMusicDuration(int count) {
        log.info("fixMusicDuration: {}", count);
        List<SyncMusicMessage> data = syncMusicMessageRepository.findByFixDurationIsNullOrFixDurationLessThan(
                0, Limit.of(count)
        );

        if (data == null || data.isEmpty()) {
            log.info("fixMusicDuration: empty data");
            return;
        }

        int[] success = {0};
        int idx = 0;
        for (SyncMusicMessage message : data) {
            log.info("fixMusicDuration: idx = {}", idx++);
            message.setFixDuration(1);

            if (message.getDurationSeconds() != 0) {
                syncMusicMessageRepository.save(message);
                continue;
            }

            if (message.getMessageId() == -1) {
                syncMusicMessageRepository.save(message);
                continue;
            }

            TdApi.File tdFile = downloadFile(message.getChatId(), message.getMessageId());
            if (tdFile == null) {
                log.error("fixMusicDuration: failed to download file, {} -> {}", message.getChatId(),
                        message.getMessageId());
                syncMusicMessageRepository.save(message);
                continue;
            }

            CompletableFuture<TdApi.File> future = new CompletableFuture<>();
            downloadFutures.put(tdFile.id, future);

            CompletableFuture<Void> wholeChain = future
                    .thenApply(file -> {
                        String path = file.local.path;
                        int duration = audioConvertService.queryDuration(path);
                        log.info("fixMusicDuration: duration = {}", duration);
                        FileUtils.deleteQuietly(new File(path));
                        return duration;
                    })
                    .thenAccept(duration -> {
                        message.setDurationSeconds(duration);
                        log.info("fixMusicDuration: update to {}", message);
                        syncMusicMessageRepository.save(message);
                        success[0] += 1;
                        downloadFutures.remove(tdFile.id);
                    })
                    .exceptionally(ex -> {
                        log.error("fixMusicDuration: failed -> {}", message, ex);
                        downloadFutures.remove(tdFile.id);
                        return null;
                    });

            if (tdFile.local.isDownloadingCompleted && new File(tdFile.local.path).exists()) {
                future.complete(tdFile);
            }

            try {
                wholeChain.get(180L, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("fixMusicDuration: timeout -> {}", message, e);
            }
        }

        log.info("fixMusicDuration: cnt = {}", success[0]);
    }
}
