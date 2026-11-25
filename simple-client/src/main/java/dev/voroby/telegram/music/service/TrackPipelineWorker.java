package dev.voroby.telegram.music.service;

import com.google.gson.Gson;
import dev.voroby.telegram.Constant;
import dev.voroby.telegram.music.config.MusicProperties;
import dev.voroby.telegram.music.model.PendingUpload;
import dev.voroby.telegram.music.model.SourceTrack;
import dev.voroby.telegram.music.model.Track;
import dev.voroby.telegram.music.model.TrackLyric;
import dev.voroby.telegram.music.repository.MusicCatalogRepository;
import dev.voroby.telegram.music.repository.TrackLyricRepository;
import dev.voroby.telegram.music.tdlib.TdGatewayService;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单向音质选优 + 转码 + 上传确认流水线（严格 1 线程串行，适配 1GB RAM / 8GB Disk）：
 * 1. 事前品质初筛（避免下载明显不如库内现有音质的低码重复源）；
 * 2. 下载源文件 -> jaudiotagger + ffprobe 全量探测（标题/歌手/专辑/精确品质分/封面/内嵌歌词）；
 * 3. 精确品质复核（仅当新歌 / 品质更高 / 原曲 BROKEN 时才转码上传）；
 * 4. 无损转 192k VBR Opus，普通有损 <=15MB 原样直通；
 * 5. InputMessageAudio 上传至固定单一私有仓库频道，等待 UpdateMessageSendSucceeded 正式 ID；
 * 6. 原子写入/升级 tracks + tracks_fts + playlist_tracks + 内嵌歌词；
 * 7. finally 块立刻删除源下载文件与临时转码文件。
 */
@Service
public class TrackPipelineWorker {
    private static final Logger log = LoggerFactory.getLogger(TrackPipelineWorker.class);
    private static final int BATCH_SIZE = 5;
    private static final long[] RETRY_BACKOFF_MS = {
            5 * 60_000L, 30 * 60_000L, 2 * 3600_000L, 12 * 3600_000L, 24 * 3600_000L
    };

    private final MusicProperties properties;
    private final MusicCatalogRepository catalogRepository;
    private final TrackLyricRepository lyricRepository;
    private final TdGatewayService tdGateway;
    private final TrackNormalizationService normService;
    private final AudioMetadataService audioMetadataService;
    @Nullable
    private final CacheManager cacheManager;
    private final Gson gson = new Gson();

    private final AtomicBoolean running = new AtomicBoolean(false);

    public TrackPipelineWorker(MusicProperties properties,
                               MusicCatalogRepository catalogRepository,
                               TrackLyricRepository lyricRepository,
                               TdGatewayService tdGateway,
                               TrackNormalizationService normService,
                               AudioMetadataService audioMetadataService,
                               @Nullable CacheManager cacheManager) {
        this.properties = properties;
        this.catalogRepository = catalogRepository;
        this.lyricRepository = lyricRepository;
        this.tdGateway = tdGateway;
        this.normService = normService;
        this.audioMetadataService = audioMetadataService;
        this.cacheManager = cacheManager;
    }

    /**
     * 启动时恢复因进程异常重启而卡在 PROCESSING 的源任务，并清理残留 pending_uploads。
     */
    @PostConstruct
    public void recoverStaleTasksOnStartup() {
        try {
            int reset = catalogRepository.resetStaleProcessingSources(System.currentTimeMillis());
            int cleaned = cleanupStalePendingUploads(System.currentTimeMillis() - 5 * 60_000L);
            if (reset > 0 || cleaned > 0) {
                log.info("Recovered stale tasks on startup: resetProcessing={}, cleanedPendingUploads={}", reset, cleaned);
            }
        } catch (Exception e) {
            log.warn("recoverStaleTasksOnStartup warning: {}", e.getMessage());
        }
    }

    /**
     * 每 30 分钟巡检一次卡死超过 20 分钟的 PROCESSING 任务与超过 30 分钟的 pending_uploads。
     */
    @Scheduled(initialDelay = 30 * 60_000L, fixedDelay = 30 * 60_000L)
    public void recoverStaleTasksPeriodically() {
        if (Constant.LOCAL_DEBUG || !properties.isSyncEnabled()) {
            return;
        }
        try {
            int reset = catalogRepository.resetStaleProcessingSources(System.currentTimeMillis() - 20 * 60_000L);
            int cleaned = cleanupStalePendingUploads(System.currentTimeMillis() - 30 * 60_000L);
            if (reset > 0 || cleaned > 0) {
                log.info("Periodic stale task recovery: resetProcessing={}, cleanedPendingUploads={}", reset, cleaned);
            }
        } catch (Exception e) {
            log.warn("recoverStaleTasksPeriodically warning: {}", e.getMessage());
        }
    }

    private int cleanupStalePendingUploads(long olderThanMs) {
        List<PendingUpload> stale = catalogRepository.findStalePendingUploads(olderThanMs);
        int count = 0;
        for (PendingUpload pu : stale) {
            if (StringUtils.isNotBlank(pu.localFilePath())) {
                File f = new File(pu.localFilePath());
                if (f.exists() && f.getAbsolutePath().startsWith(new File(properties.getTempDir()).getAbsolutePath())) {
                    FileUtils.deleteQuietly(f);
                }
            }
            catalogRepository.deletePendingUpload(pu.chatId(), pu.tempMsgId());
            count++;
        }
        return count;
    }

    private void evictMusicListCache() {
        if (cacheManager != null) {
            Cache cache = cacheManager.getCache("music_list");
            if (cache != null) {
                cache.clear();
            }
        }
    }

    /**
     * 统一单线程转码与响度归一化主循环（严格 1 线程、同一时刻最多 1 个 ffmpeg 进程）：
     * 每轮先处理最多 BATCH_SIZE (5) 条新源/高品升级任务，再交错处理 legacyBatchSize (默认 2) 条存量老歌响度拉平，
     * 彻底避免新源积压时老歌拉平任务被饿死。
     */
    @Async("transcodeExecutor")
    @Scheduled(initialDelay = 45_000, fixedDelay = 60_000)
    public void processBatch() {
        if (Constant.LOCAL_DEBUG || !properties.isSyncEnabled()) {
            return;
        }
        if (tdGateway.isInFloodWait()) {
            log.info("TrackPipelineWorker paused for TDLib FloodWait ({}s remaining)", tdGateway.getFloodWaitRemainingSeconds());
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<SourceTrack> pending = catalogRepository.findPendingSourceTracks(BATCH_SIZE);
            boolean shouldNormalizeLegacy = properties.isLoudnessEnabled() && properties.isLoudnessLegacyBackfillEnabled();
            List<Track> legacyTracks = shouldNormalizeLegacy
                    ? catalogRepository.findTracksNeedingLoudnessNormalization(Math.max(1, properties.getLoudnessLegacyBatchSize()))
                    : List.of();

            if (pending.isEmpty() && legacyTracks.isEmpty()) {
                return;
            }

            long storageChatId = tdGateway.getOrCreateStorageChat();

            if (!pending.isEmpty()) {
                log.info("TrackPipelineWorker processing {} source tracks -> storageChatId={}", pending.size(), storageChatId);
                for (SourceTrack st : pending) {
                    if (tdGateway.isInFloodWait()) {
                        log.info("Stopping source batch early due to TDLib FloodWait ({}s remaining)", tdGateway.getFloodWaitRemainingSeconds());
                        break;
                    }
                    processSingleSource(st, storageChatId);
                }
                logSourcePipelineProgress();
            }

            if (!legacyTracks.isEmpty() && !tdGateway.isInFloodWait()) {
                runLegacyLoudnessRound(legacyTracks, storageChatId);
            }
        } catch (Exception e) {
            log.error("TrackPipelineWorker batch error", e);
        } finally {
            running.set(false);
        }
    }

    public void processSingleSource(SourceTrack st, long storageChatId) {
        // 1. 事前品质初筛：若库内已有明显更高品质的 READY 曲目且已完成元数据补全，直接跳过下载与转码
        if (StringUtils.isNotBlank(st.getNormKey()) && !st.getNormKey().startsWith("unknown::")) {
            Optional<Track> existingOpt = catalogRepository.findTrackByNormKey(st.getNormKey());
            if (existingOpt.isPresent()) {
                Track existing = existingOpt.get();
                if (Track.STATUS_READY.equals(existing.getStatus())
                        && existing.isMetadataEnriched()
                        && existing.getQualityScore() >= st.getEstQualityScore() + 40) {
                    catalogRepository.linkTrackToPlaylistBySourceChat(st.getSourceChatId(), existing.getId());
                    catalogRepository.updateSourceTrackStatus(st.getId(), SourceTrack.STATUS_SKIPPED_DUP,
                            existing.getId(), st.getRetryCount(), null, null);
                    log.debug("Pre-skipped lower quality duplicate: src={} ({}) < track={} ({})",
                            st.getId(), st.getEstQualityScore(), existing.getId(), existing.getQualityScore());
                    return;
                }
            }
        }

        File downloadedFile = null;
        Integer downloadedTdFileId = null;
        AudioMetadataService.TranscodeOutput transcodeOutput = null;
        try {
            catalogRepository.updateSourceTrackStatus(st.getId(), SourceTrack.STATUS_PROCESSING,
                    st.getTrackId(), st.getRetryCount(), null, null);

            // 2. 下载源文件
            TdGatewayService.DownloadResult dl = tdGateway.downloadFileAwait(
                    st.getSourceChatId(), st.getSourceMsgId(), Duration.ofSeconds(120));
            if (dl.file() == null) {
                if (dl.notFound()) {
                    catalogRepository.updateSourceTrackStatus(st.getId(), SourceTrack.STATUS_DELETED,
                            st.getTrackId(), st.getRetryCount(), null, dl.error());
                } else {
                    markRetryableFailure(st, "download failed: " + dl.error());
                }
                return;
            }
            downloadedTdFileId = dl.file().id;
            downloadedFile = new File(dl.file().local.path);

            // 3. 全量探测（标题/歌手/专辑/精确品质分/封面/内嵌歌词）
            AudioMetadataService.AudioProbeResult probe = audioMetadataService.probe(
                    downloadedFile, st.getRawTitle(), st.getRawArtist(),
                    st.getRawFilename(), st.getRawCaption(), st.getMimeType());
            if (StringUtils.isBlank(probe.title())) {
                catalogRepository.updateSourceTrackStatus(st.getId(), SourceTrack.STATUS_FAILED,
                        st.getTrackId(), 5, null, "empty title after probe");
                return;
            }

            String exactNormKey = normService.buildNormKey(probe.title(), probe.artist(), st.getRawFilename(), probe.versionTag());
            Optional<Track> existingOpt = catalogRepository.findTrackByNormKey(exactNormKey);

            // 4. 精确品质复核
            if (existingOpt.isPresent()) {
                Track existing = existingOpt.get();
                boolean needUpgrade = Track.STATUS_BROKEN.equals(existing.getStatus())
                        || probe.qualityScore() > existing.getQualityScore();
                if (!needUpgrade) {
                    // 顺手补齐存量缺失的专辑/封面/内嵌歌词，不重复上传音频
                    if (!existing.isMetadataEnriched()) {
                        catalogRepository.updateTrackEnrichedMetadata(
                                existing.getId(), probe.title(), probe.artist(), probe.album(),
                                probe.durationSec(), probe.coverRelPath(), probe.codec(), probe.bitrateKbps(),
                                probe.sampleRate(), probe.bitDepth(), probe.qualityScore());
                        saveEmbeddedLyricIfPresent(existing.getId(), probe.embeddedLyrics(), probe.durationSec());
                        evictMusicListCache();
                    }
                    catalogRepository.linkTrackToPlaylistBySourceChat(st.getSourceChatId(), existing.getId());
                    catalogRepository.updateSourceTrackStatus(st.getId(), SourceTrack.STATUS_SKIPPED_DUP,
                            existing.getId(), st.getRetryCount(), null, null);
                    return;
                }
                log.info("Upgrading track '{}' ({}) quality: {} -> {}",
                        probe.title(), existing.getId(), existing.getQualityScore(), probe.qualityScore());
            }

            // 5. 智能转码（无损 -> 192k VBR Opus；<=15MB 有损原样直通）
            transcodeOutput = audioMetadataService.prepareForUpload(downloadedFile, probe, st.getRawFilename());

            // 6. 使用 InputMessageAudio 上传至固定单一私有仓库频道，并等待正式 messageId
            File coverFile = StringUtils.isNotBlank(probe.coverRelPath())
                    ? new File(properties.getCoverDir(), probe.coverRelPath()) : null;
            String caption = buildStructuredCaption(probe, transcodeOutput);
            String payloadJson = gson.toJson(Map.of(
                    "normKey", exactNormKey,
                    "title", probe.title(),
                    "artist", probe.artist(),
                    "album", probe.album(),
                    "qualityScore", probe.qualityScore()
            ));

            TdApi.Message confirmedMsg = tdGateway.uploadAudioAndConfirm(
                    storageChatId,
                    transcodeOutput.file(),
                    coverFile,
                    probe.durationSec(),
                    probe.title(),
                    probe.artist(),
                    caption,
                    st.getId(),
                    existingOpt.map(Track::getId).orElse(null),
                    payloadJson,
                    Duration.ofSeconds(180)
            );

            TdApi.File uploadedTdFile = TdGatewayService.extractPrimaryAudioFile(confirmedMsg);
            String remoteUid = (uploadedTdFile != null && uploadedTdFile.remote != null)
                    ? uploadedTdFile.remote.uniqueId : null;

            // 7. 仅在拿到正式 confirmedMsg.id 后才落库/升级 tracks 并置 READY
            Track track = new Track();
            track.setNormKey(exactNormKey);
            track.setTitle(probe.title());
            track.setArtist(probe.artist());
            track.setAlbum(probe.album());
            track.setVersionTag(probe.versionTag());
            track.setDurationSec(probe.durationSec());
            track.setQualityScore(probe.qualityScore());
            track.setSrcCodec(probe.codec());
            track.setSrcBitrate(probe.bitrateKbps());
            track.setSrcSampleRate(probe.sampleRate());
            track.setSrcBitDepth(probe.bitDepth());
            track.setStoredChatId(confirmedMsg.chatId);
            track.setStoredMsgId(confirmedMsg.id);
            track.setStoredFileUid(remoteUid);
            track.setStoredCodec(transcodeOutput.storedCodec());
            track.setStoredBitrate(transcodeOutput.storedBitrateKbps());
            track.setStoredSize(transcodeOutput.fileSize());
            track.setMimeType(transcodeOutput.mimeType());
            track.setFileName(transcodeOutput.uploadFileName());
            track.setCoverPath(probe.coverRelPath());
            track.setBestSourceId(st.getId());
            track.setMetadataEnriched(true);
            track.setLoudnessLufs(transcodeOutput.loudnessLufs());
            track.setLoudnessNormalized(transcodeOutput.loudnessNormalized());
            track.setStatus(Track.STATUS_READY);

            long trackId = catalogRepository.upsertTrackAndFts(track);
            catalogRepository.linkTrackToPlaylistBySourceChat(st.getSourceChatId(), trackId);
            catalogRepository.updateSourceTrackStatus(st.getId(), SourceTrack.STATUS_DONE,
                    trackId, st.getRetryCount(), null, null);

            saveEmbeddedLyricIfPresent(trackId, probe.embeddedLyrics(), probe.durationSec());
            evictMusicListCache();
            log.info("Track synced READY: id={}, title='{}', artist='{}', album='{}', quality={}, stored={}:{}",
                    trackId, probe.title(), probe.artist(), probe.album(), probe.qualityScore(),
                    confirmedMsg.chatId, confirmedMsg.id);
        } catch (Exception e) {
            log.error("Failed to process sourceTrack id={} ({}:{}): {}",
                    st.getId(), st.getSourceChatId(), st.getSourceMsgId(), e.getMessage());
            markRetryableFailure(st, e.getMessage());
        } finally {
            // 8GB 磁盘严控：无论成功失败立刻清理转码临时文件与源下载缓存文件
            if (transcodeOutput != null && transcodeOutput.isTempFile()) {
                FileUtils.deleteQuietly(transcodeOutput.file());
            }
            if (downloadedFile != null || downloadedTdFileId != null) {
                tdGateway.deleteTdFileQuietly(downloadedTdFileId, downloadedFile);
            }
        }
    }

    private void logSourcePipelineProgress() {
        Map<String, Long> byStatus = catalogRepository.countSourceTracksByStatus();
        long done = byStatus.getOrDefault(SourceTrack.STATUS_DONE, 0L);
        long skipped = byStatus.getOrDefault(SourceTrack.STATUS_SKIPPED_DUP, 0L);
        long deleted = byStatus.getOrDefault(SourceTrack.STATUS_DELETED, 0L);
        long failed = byStatus.getOrDefault(SourceTrack.STATUS_FAILED, 0L);
        long pendingNew = byStatus.getOrDefault(SourceTrack.STATUS_NEW, 0L);
        long processing = byStatus.getOrDefault(SourceTrack.STATUS_PROCESSING, 0L);
        long total = done + skipped + deleted + failed + pendingNew + processing;
        long finished = done + skipped + deleted;
        double pct = total > 0 ? (finished * 100.0 / total) : 100.0;
        log.info("Source pipeline progress: {}/{} ({}%) [done={}, skippedDup={}, deleted={}, remainingNew={}, failed={}]",
                finished, total, String.format("%.2f", pct), done, skipped, deleted, pendingNew, failed);
    }

    /**
     * 执行一轮存量曲目响度拉平并打印总体进度（由 processBatch 单线程交错调度）。
     */
    private void runLegacyLoudnessRound(List<Track> tracks, long storageChatId) {
        int reencoded = 0;
        int alreadyOk = 0;
        int failed = 0;
        for (Track track : tracks) {
            if (tdGateway.isInFloodWait()) {
                log.info("Stopping legacy loudness batch early due to TDLib FloodWait ({}s remaining)", tdGateway.getFloodWaitRemainingSeconds());
                break;
            }
            int outcome = normalizeSingleLegacyTrack(track, storageChatId);
            if (outcome == 1) reencoded++;
            else if (outcome == 0) alreadyOk++;
            else failed++;
        }
        long[] progress = catalogRepository.countLoudnessProgress();
        long done = progress[0];
        long total = progress[1];
        double pct = total > 0 ? (done * 100.0 / total) : 100.0;
        log.info("Legacy loudness progress: {}/{} ({}%) [this round: reencoded={}, ok={}, failed={}]",
                done, total, String.format("%.2f", pct), reencoded, alreadyOk, failed);
    }

    /**
     * @return 1=重编上传成功, 0=已在容差内跳过/标记完成, -1=失败或本次跳过重试
     */
    private int normalizeSingleLegacyTrack(Track track, long storageChatId) {
        File downloadedFile = null;
        Integer downloadedTdFileId = null;
        AudioMetadataService.TranscodeOutput out = null;
        try {
            TdGatewayService.DownloadResult dl = tdGateway.downloadFileAwait(
                    track.getStoredChatId(), track.getStoredMsgId(), Duration.ofSeconds(90));
            if (dl.file() == null) {
                if (dl.notFound()) {
                    catalogRepository.markTrackStatus(track.getId(), Track.STATUS_BROKEN);
                    catalogRepository.reactivateAlternateSourcesForNormKey(track.getNormKey(), track.getBestSourceId());
                    log.warn("Legacy loudness check: track {} ({}:{}) not found, marked BROKEN",
                            track.getId(), track.getStoredChatId(), track.getStoredMsgId());
                }
                return -1;
            }
            downloadedTdFileId = dl.file().id;
            downloadedFile = new File(dl.file().local.path);

            // 趁文件已在本地，若老歌尚未补全元数据则顺手探测补齐
            AudioMetadataService.AudioProbeResult probe = null;
            if (!track.isMetadataEnriched()) {
                probe = audioMetadataService.probe(
                        downloadedFile, track.getTitle(), track.getArtist(), track.getFileName(), null, track.getMimeType());
                catalogRepository.updateTrackEnrichedMetadata(
                        track.getId(), probe.title(), probe.artist(), probe.album(),
                        probe.durationSec(), probe.coverRelPath(), probe.codec(), probe.bitrateKbps(),
                        probe.sampleRate(), probe.bitDepth(), probe.qualityScore());
                saveEmbeddedLyricIfPresent(track.getId(), probe.embeddedLyrics(), probe.durationSec());
            }

            AudioMetadataService.LoudnessMeasurement lm = audioMetadataService.measureLoudness(downloadedFile);
            if (lm == null) {
                catalogRepository.markTrackLoudnessDone(track.getId(), null);
                return 0;
            }

            double targetLufs = properties.getLoudnessTargetLufs();
            double diffDb = Math.abs(lm.inputI() - targetLufs);
            if (diffDb <= properties.getLoudnessLegacyToleranceDb()) {
                catalogRepository.markTrackLoudnessDone(track.getId(), Math.round(lm.inputI() * 10.0) / 10.0);
                log.info("Legacy track {} '{}' loudness OK ({} LUFS, diff={} dB), no re-upload",
                        track.getId(), track.getTitle(), String.format("%.1f", lm.inputI()), String.format("%.1f", diffDb));
                return 0;
            }

            String title = probe != null && StringUtils.isNotBlank(probe.title()) ? probe.title() : track.getTitle();
            String artist = probe != null && StringUtils.isNotBlank(probe.artist()) ? probe.artist() : track.getArtist();
            String album = probe != null && StringUtils.isNotBlank(probe.album()) ? probe.album() : track.getAlbum();
            int durationSec = probe != null && probe.durationSec() > 0 ? probe.durationSec() : track.getDurationSec();
            String coverRel = probe != null && StringUtils.isNotBlank(probe.coverRelPath())
                    ? probe.coverRelPath() : track.getCoverPath();

            out = audioMetadataService.transcodeToNormalizedOpus(
                    downloadedFile, title, artist, album, null, lm);

            File coverFile = StringUtils.isNotBlank(coverRel)
                    ? new File(properties.getCoverDir(), coverRel) : null;
            String caption = String.format("Loudnorm: %.1f -> %.1f LUFS | Stored: OPUS %dk",
                    lm.inputI(), targetLufs, out.storedBitrateKbps());
            String payloadJson = gson.toJson(Map.of(
                    "type", "legacy_loudnorm",
                    "trackId", track.getId(),
                    "fromLufs", lm.inputI()
            ));

            TdApi.Message confirmedMsg = tdGateway.uploadAudioAndConfirm(
                    storageChatId,
                    out.file(),
                    coverFile,
                    durationSec,
                    title,
                    artist,
                    caption,
                    0L,
                    track.getId(),
                    payloadJson,
                    Duration.ofSeconds(180)
            );

            TdApi.File uploadedTdFile = TdGatewayService.extractPrimaryAudioFile(confirmedMsg);
            String remoteUid = (uploadedTdFile != null && uploadedTdFile.remote != null)
                    ? uploadedTdFile.remote.uniqueId : null;

            // 先把旧消息坐标写入别名表，确保客户端旧收藏夹无缝切到归一化后的新消息
            catalogRepository.upsertLegacyAlias(track.getStoredChatId(), track.getStoredMsgId(), track.getId());
            catalogRepository.updateTrackAfterLoudnessReupload(
                    track.getId(), confirmedMsg.chatId, confirmedMsg.id, remoteUid,
                    out.storedCodec(), out.storedBitrateKbps(), out.fileSize(),
                    out.mimeType(), out.uploadFileName(), out.loudnessLufs());
            evictMusicListCache();

            log.info("Legacy track {} '{}' loudness normalized: {} -> {} LUFS (stored={}:{})",
                    track.getId(), title, String.format("%.1f", lm.inputI()), String.format("%.1f", targetLufs),
                    confirmedMsg.chatId, confirmedMsg.id);
            return 1;
        } catch (Exception e) {
            log.warn("Failed legacy loudness normalization for track {}: {}", track.getId(), e.getMessage());
            return -1;
        } finally {
            if (out != null && out.isTempFile()) {
                FileUtils.deleteQuietly(out.file());
            }
            if (downloadedFile != null || downloadedTdFileId != null) {
                tdGateway.deleteTdFileQuietly(downloadedTdFileId, downloadedFile);
            }
        }
    }

    private void saveEmbeddedLyricIfPresent(long trackId, String embeddedLyrics, int durationSec) {
        if (StringUtils.isBlank(embeddedLyrics)) {
            return;
        }
        TrackLyric tl = new TrackLyric();
        tl.setTrackId(trackId);
        tl.setSource(TrackLyric.SOURCE_EMBEDDED);
        tl.setLrc(embeddedLyrics);
        tl.setMatchedDurationMs(durationSec > 0 ? durationSec * 1000 : null);
        tl.setMatchScore(1.0);
        tl.setStatus(TrackLyric.STATUS_FOUND);
        tl.setRetryCount(0);
        lyricRepository.upsert(tl);
    }

    private void markRetryableFailure(SourceTrack st, String error) {
        int nextRetry = st.getRetryCount() + 1;
        long delay = RETRY_BACKOFF_MS[Math.min(nextRetry - 1, RETRY_BACKOFF_MS.length - 1)];
        long nextAt = System.currentTimeMillis() + delay;
        String truncatedErr = error != null && error.length() > 300 ? error.substring(0, 300) : error;
        catalogRepository.updateSourceTrackStatus(st.getId(), SourceTrack.STATUS_FAILED,
                st.getTrackId(), nextRetry, nextAt, truncatedErr);
    }

    private static String buildStructuredCaption(AudioMetadataService.AudioProbeResult probe,
                                                 AudioMetadataService.TranscodeOutput out) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.isNotBlank(probe.album())) {
            sb.append("Album: ").append(probe.album());
        }
        if (StringUtils.isNotBlank(probe.codec())) {
            if (!sb.isEmpty()) sb.append(" | ");
            sb.append("Src: ").append(probe.codec().toUpperCase());
            if (probe.sampleRate() > 0) sb.append(' ').append(probe.sampleRate() / 1000).append("kHz");
            if (probe.bitDepth() > 0) sb.append('/').append(probe.bitDepth()).append("bit");
        }
        if (!sb.isEmpty()) sb.append(" | ");
        sb.append("Stored: ").append(out.storedCodec().toUpperCase());
        if (out.storedBitrateKbps() > 0) sb.append(' ').append(out.storedBitrateKbps()).append("k");
        return sb.toString();
    }
}
