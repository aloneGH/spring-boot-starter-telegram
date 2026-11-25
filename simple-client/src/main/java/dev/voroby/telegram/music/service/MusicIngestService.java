package dev.voroby.telegram.music.service;

import dev.voroby.springframework.telegram.client.TelegramClient;
import dev.voroby.telegram.Constant;
import dev.voroby.telegram.message.common.MessageCache;
import dev.voroby.telegram.music.cache.ChatFolderCache;
import dev.voroby.telegram.music.config.MusicProperties;
import dev.voroby.telegram.music.model.SourceTrack;
import dev.voroby.telegram.music.model.SyncCursor;
import dev.voroby.telegram.music.repository.MusicCatalogRepository;
import dev.voroby.telegram.music.tdlib.TdGatewayService;
import org.apache.commons.lang3.StringUtils;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 源频道采集服务（双游标设计，彻底解决断点漏歌）：
 * - 向后回填 (Backfill)：从 oldest_msg_id 不断往前翻历史，直到历史尽头才标记 backfill_done = 1；
 * - 向前增量 (Incremental)：定期从最新消息拉到 newest_msg_id，兜底实时推送；
 * - 实时消费：消费 UpdateNewMessage 过滤后的音乐频道音频消息；
 * - 音频识别：支持 MessageAudio + MessageDocument（MIME 或 扩展名，绝不漏掉 application/octet-stream 的 FLAC/WAV/APE）。
 */
@Service
public class MusicIngestService {
    private static final Logger log = LoggerFactory.getLogger(MusicIngestService.class);
    private static final int PAGE_LIMIT = 50;
    private static final int MAX_BACKFILL_PAGES_PER_ROUND = 20;

    private final TelegramClient telegramClient;
    private final TdGatewayService tdGateway;
    private final MusicProperties properties;
    private final MusicCatalogRepository catalogRepository;
    private final TrackNormalizationService normService;

    private final AtomicBoolean syncRunning = new AtomicBoolean(false);

    public MusicIngestService(TelegramClient telegramClient,
                              TdGatewayService tdGateway,
                              MusicProperties properties,
                              MusicCatalogRepository catalogRepository,
                              TrackNormalizationService normService) {
        this.telegramClient = telegramClient;
        this.tdGateway = tdGateway;
        this.properties = properties;
        this.catalogRepository = catalogRepository;
        this.normService = normService;
    }

    @Order(2)
    @Async("ingestExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (Constant.LOCAL_DEBUG || !properties.isSyncEnabled()) {
            return;
        }
        // 给 TDLib 一点缓冲时间完成初始化和 ChatFolder 接收
        sleepQuietly(5000);
        syncAllSourceChannels();
    }

    /**
     * 每 5 分钟定期执行一次频道目录同步 + 增量拉取 + 未完成历史回填。
     */
    @Async("ingestExecutor")
    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void scheduledSync() {
        if (Constant.LOCAL_DEBUG || !properties.isSyncEnabled()) {
            return;
        }
        syncAllSourceChannels();
    }

    /**
     * 每 15 秒消费一次实时新消息队列。
     */
    @Async("ingestExecutor")
    @Scheduled(initialDelay = 30_000, fixedDelay = 15_000)
    public void drainRealtimeQueue() {
        if (Constant.LOCAL_DEBUG || !properties.isSyncEnabled()) {
            return;
        }
        Set<Long> targetChats = ChatFolderCache.getCachedChatIds(properties.getSourceFolderName());
        if (targetChats.isEmpty()) {
            targetChats = ChatFolderCache.queryChatIds(log, telegramClient, properties.getSourceFolderName(), false);
        }
        if (targetChats.isEmpty()) {
            return;
        }
        int saved = 0;
        for (int i = 0; i < 500; i++) {
            TdApi.Message msg = MessageCache.newMessagesQueue.poll();
            if (msg == null) break;
            if (!targetChats.contains(msg.chatId)) continue;
            if (ingestSingleMessage(msg)) {
                saved++;
            }
        }
        if (saved > 0) {
            log.info("Realtime ingest saved {} new source tracks", saved);
        }
    }

    public void syncAllSourceChannels() {
        if (tdGateway.isInFloodWait()) {
            log.info("MusicIngestService paused for TDLib FloodWait ({}s remaining)", tdGateway.getFloodWaitRemainingSeconds());
            return;
        }
        if (!syncRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            if (ChatFolderCache.chatFolders.isEmpty()) {
                log.debug("ChatFolders not ready yet, skipping ingest round");
                return;
            }
            Set<Long> chatIds = ChatFolderCache.queryChatIds(log, telegramClient, properties.getSourceFolderName(), true);
            if (chatIds.isEmpty()) {
                log.warn("No channels found in folder '{}'", properties.getSourceFolderName());
                return;
            }

            for (long chatId : chatIds) {
                if (tdGateway.isInFloodWait()) {
                    log.info("Stopping ingest sync early due to TDLib FloodWait ({}s remaining)", tdGateway.getFloodWaitRemainingSeconds());
                    break;
                }
                TdApi.Chat chat = tdGateway.getChatWithRetry(chatId);
                if (chat == null) {
                    log.warn("Skip inaccessible source channel {}", chatId);
                    continue;
                }
                catalogRepository.upsertPlaylist(chatId, null, chat.title, properties.getSourceFolderName());
                SyncCursor cursor = catalogRepository.getOrCreateCursor(chatId, chat.title);

                // 1. 向前增量：从最新拉到 newestMsgId
                syncIncremental(chat, cursor);
                // 重新读取更新后的游标
                cursor = catalogRepository.getOrCreateCursor(chatId, chat.title);
                // 2. 向后回填：若尚未到达历史尽头，每次推进最多 MAX_BACKFILL_PAGES_PER_ROUND 页，防 429 与长时间占线
                if (!cursor.backfillDone()) {
                    syncBackfill(chat, cursor);
                }
                sleepQuietly(300);
            }
            long[] bp = catalogRepository.countBackfillProgress();
            log.info("Ingest sync round done: channels={}, backfillDone={}/{}, totalSourceTracks={}",
                    chatIds.size(), bp[0], bp[1], bp[2]);
        } catch (Exception e) {
            log.error("syncAllSourceChannels failed", e);
        } finally {
            syncRunning.set(false);
        }
    }

    private void syncIncremental(TdApi.Chat chat, SyncCursor cursor) {
        long chatId = chat.id;
        long knownNewest = cursor.newestMsgId();
        long fromMsgId = 0;
        long maxSeen = knownNewest;
        long minSeen = cursor.oldestMsgId();
        int added = 0;
        boolean reachedKnown = false;

        for (int page = 0; page < 30; page++) {
            TdApi.Messages msgs = tdGateway.getChatHistory(chatId, fromMsgId, PAGE_LIMIT);
            if (msgs == null || msgs.messages == null || msgs.messages.length == 0) {
                // 如果从未同步过且已读完，标记回填完成
                if (knownNewest == 0) {
                    catalogRepository.updateCursor(chatId, chat.title, maxSeen, minSeen, true);
                }
                break;
            }

            for (TdApi.Message m : msgs.messages) {
                if (m.id > maxSeen) maxSeen = m.id;
                if (minSeen == 0 || m.id < minSeen) minSeen = m.id;
                if (knownNewest > 0 && m.id <= knownNewest) {
                    reachedKnown = true;
                    break;
                }
                if (ingestSingleMessage(m)) {
                    added++;
                }
            }

            long oldestInBatch = msgs.messages[msgs.messages.length - 1].id;
            if (reachedKnown || oldestInBatch == 0 || oldestInBatch == fromMsgId) {
                break;
            }
            fromMsgId = oldestInBatch;
            sleepQuietly(200);
        }

        if (maxSeen > 0 || minSeen > 0) {
            catalogRepository.updateCursor(chatId, chat.title, maxSeen, minSeen, false);
        }
        if (added > 0) {
            log.info("Incremental sync [{}]: added {} source tracks", chat.title, added);
        }
    }

    private void syncBackfill(TdApi.Chat chat, SyncCursor cursor) {
        long chatId = chat.id;
        long fromMsgId = cursor.oldestMsgId(); // 0 表示从头开始，否则从当前最旧记录继续往前翻
        long maxSeen = cursor.newestMsgId();
        long minSeen = cursor.oldestMsgId();
        int added = 0;
        boolean reachedEnd = false;

        for (int page = 0; page < MAX_BACKFILL_PAGES_PER_ROUND; page++) {
            TdApi.Messages msgs = tdGateway.getChatHistory(chatId, fromMsgId, PAGE_LIMIT);
            if (msgs == null || msgs.messages == null || msgs.messages.length == 0) {
                reachedEnd = true;
                break;
            }
            for (TdApi.Message m : msgs.messages) {
                if (m.id > maxSeen) maxSeen = m.id;
                if (minSeen == 0 || m.id < minSeen) minSeen = m.id;
                if (ingestSingleMessage(m)) {
                    added++;
                }
            }
            long oldestInBatch = msgs.messages[msgs.messages.length - 1].id;
            if (oldestInBatch == 0 || oldestInBatch == fromMsgId) {
                reachedEnd = true;
                break;
            }
            fromMsgId = oldestInBatch;
            sleepQuietly(250);
        }

        catalogRepository.updateCursor(chatId, chat.title, maxSeen, minSeen, reachedEnd);
        if (added > 0 || reachedEnd) {
            log.info("Backfill sync [{}]: added={} tracks, oldestMsgId={}, done={}",
                    chat.title, added, minSeen, reachedEnd);
        }
    }

    public boolean ingestSingleMessage(TdApi.Message message) {
        if (message == null || message.sendingState != null || !isMusicMessage(message)) {
            return false;
        }

        String fileName = null;
        String mimeType = null;
        String title = null;
        String artist = null;
        String caption = null;
        int durationSec = 0;
        long fileSize = 0;

        TdApi.MessageContent content = message.content;
        if (content instanceof TdApi.MessageAudio ma && ma.audio != null) {
            TdApi.Audio a = ma.audio;
            fileName = a.fileName;
            mimeType = a.mimeType;
            title = a.title;
            artist = a.performer;
            durationSec = a.duration;
            if (a.audio != null) fileSize = Math.max(a.audio.size, a.audio.expectedSize);
            if (ma.caption != null) caption = ma.caption.text;
        } else if (content instanceof TdApi.MessageDocument md && md.document != null) {
            TdApi.Document d = md.document;
            fileName = d.fileName;
            mimeType = d.mimeType;
            if (d.document != null) fileSize = Math.max(d.document.size, d.document.expectedSize);
            if (md.caption != null) caption = md.caption.text;
        } else {
            return false;
        }

        // 兜底解析用于事前去重键计算
        String cleanTitle = normService.cleanDisplayText(title);
        String cleanArtist = normService.cleanDisplayText(artist);
        if (StringUtils.isBlank(cleanTitle) || StringUtils.isBlank(cleanArtist)) {
            TrackNormalizationService.ParsedName parsed = normService.parseFromFilenameOrCaption(fileName, caption);
            if (StringUtils.isBlank(cleanTitle)) cleanTitle = parsed.title();
            if (StringUtils.isBlank(cleanArtist)) cleanArtist = parsed.artist();
        }

        String versionTag = normService.extractVersionTag(cleanTitle, fileName);
        String normKey = normService.buildNormKey(cleanTitle, cleanArtist, fileName, versionTag);
        int estScore = normService.estimateQualityScore(mimeType, fileName, fileSize, durationSec);

        SourceTrack st = new SourceTrack();
        st.setSourceChatId(message.chatId);
        st.setSourceMsgId(message.id);
        st.setSentAt(message.date);
        st.setRawTitle(cleanTitle);
        st.setRawArtist(cleanArtist);
        st.setRawFilename(fileName);
        st.setRawCaption(caption);
        st.setMimeType(mimeType);
        st.setFileSize(fileSize);
        st.setDurationSec(durationSec);
        st.setEstQualityScore(estScore);
        st.setNormKey(normKey);
        st.setStatus(SourceTrack.STATUS_NEW);

        return catalogRepository.insertSourceTrackIgnore(st);
    }

    public boolean isMusicMessage(TdApi.Message message) {
        if (message == null || message.content == null) return false;
        TdApi.MessageContent content = message.content;
        if (content instanceof TdApi.MessageAudio) {
            return true;
        }
        if (content instanceof TdApi.MessageDocument md && md.document != null) {
            // 同时校验 MIME 和文件扩展名，防止 application/octet-stream 的 FLAC/WAV/APE 被漏掉
            return normService.isAudioMimeType(md.document.mimeType)
                    || normService.isAudioFileName(md.document.fileName);
        }
        return false;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
