package dev.voroby.telegram.music.service;

import dev.voroby.telegram.music.config.MusicProperties;
import dev.voroby.telegram.music.dto.FolderItem;
import dev.voroby.telegram.music.dto.MediaResponse;
import dev.voroby.telegram.music.dto.MusicItem;
import dev.voroby.telegram.music.model.Playlist;
import dev.voroby.telegram.music.model.Track;
import dev.voroby.telegram.music.model.TrackLyric;
import dev.voroby.telegram.music.repository.MusicCatalogRepository;
import dev.voroby.telegram.music.repository.TrackLyricRepository;
import dev.voroby.telegram.music.tdlib.TdGatewayService;
import jakarta.annotation.Nullable;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RestController("musicStreamService")
@RequestMapping("/music")
public class MusicStreamService {
    private static final Logger log = LoggerFactory.getLogger(MusicStreamService.class);
    private static final int PAGE_SIZE = 100;
    private static final long MAX_RANGE_CHUNK = 1024L * 1024L; // 1MB 分片，适配 1GB 内存平滑流式传输
    public static final long VIRTUAL_FOLDER_LATEST = -1L;
    public static final long VIRTUAL_FOLDER_HIQ = -2L;

    private final MusicCatalogRepository catalogRepository;
    private final TrackLyricRepository lyricRepository;
    private final TdGatewayService tdGateway;
    private final LyricSyncService lyricSyncService;
    private final AudioMetadataService audioMetadataService;
    private final MusicProperties properties;
    private final Executor lyricExecutor;
    @Nullable
    private final CacheManager cacheManager;

    public MusicStreamService(MusicCatalogRepository catalogRepository,
                              TrackLyricRepository lyricRepository,
                              TdGatewayService tdGateway,
                              LyricSyncService lyricSyncService,
                              AudioMetadataService audioMetadataService,
                              MusicProperties properties,
                              @org.springframework.beans.factory.annotation.Qualifier("lyricExecutor") Executor lyricExecutor,
                              @Nullable CacheManager cacheManager) {
        this.catalogRepository = catalogRepository;
        this.lyricRepository = lyricRepository;
        this.tdGateway = tdGateway;
        this.lyricSyncService = lyricSyncService;
        this.audioMetadataService = audioMetadataService;
        this.properties = properties;
        this.lyricExecutor = lyricExecutor;
        this.cacheManager = cacheManager;
    }

    @GetMapping("/folders")
    public List<FolderItem> folders() {
        List<FolderItem> result = new ArrayList<>();
        result.add(new FolderItem(VIRTUAL_FOLDER_LATEST, "最新入库"));
        result.add(new FolderItem(VIRTUAL_FOLDER_HIQ, "高品精选"));
        for (Playlist p : catalogRepository.findAllPlaylists()) {
            long fid = p.sourceChatId() != null ? p.sourceChatId()
                    : (p.legacyChatId() != null ? p.legacyChatId() : p.id());
            result.add(new FolderItem(fid, p.title()));
        }
        return result;
    }

    @Cacheable(value = "music_list")
    @GetMapping("/folder/{fid}")
    public MediaResponse<MusicItem> musicList(@PathVariable(name = "fid") long fid,
                                              @RequestParam(name = "pn", defaultValue = "1") int pn) {
        int pageZero = Math.max(pn - 1, 0);
        if (fid == VIRTUAL_FOLDER_LATEST) {
            MusicCatalogRepository.PageResult<Track> page =
                    catalogRepository.findReadyTracksLatest(pageZero, PAGE_SIZE);
            List<MusicItem> items = page.items().stream().map(t -> toMusicItem(fid, t)).toList();
            return new MediaResponse<>(page.hasMore(), items);
        }
        if (fid == VIRTUAL_FOLDER_HIQ) {
            MusicCatalogRepository.PageResult<Track> page =
                    catalogRepository.findReadyTracksHighQuality(pageZero, PAGE_SIZE);
            List<MusicItem> items = page.items().stream().map(t -> toMusicItem(fid, t)).toList();
            return new MediaResponse<>(page.hasMore(), items);
        }
        Optional<Playlist> plOpt = catalogRepository.findPlaylistByAnyId(fid);
        if (plOpt.isEmpty()) {
            return new MediaResponse<>(false, List.of());
        }
        MusicCatalogRepository.PageResult<Track> page =
                catalogRepository.findReadyTracksByPlaylist(plOpt.get().id(), pageZero, PAGE_SIZE);
        List<MusicItem> items = page.items().stream().map(t -> toMusicItem(fid, t)).toList();
        return new MediaResponse<>(page.hasMore(), items);
    }

    @GetMapping("/performer/{performer}")
    public MediaResponse<MusicItem> performers(@PathVariable(name = "performer") String performer,
                                               @RequestParam(name = "pn", defaultValue = "1") int pn) {
        int pageZero = Math.max(pn - 1, 0);
        MusicCatalogRepository.PageResult<Track> page =
                catalogRepository.findReadyTracksByArtist(performer, pageZero, PAGE_SIZE);
        List<MusicItem> items = page.items().stream()
                .map(t -> toMusicItem(t.getStoredChatId() != null ? t.getStoredChatId() : 0L, t))
                .toList();
        return new MediaResponse<>(page.hasMore(), items);
    }

    /**
     * 音频流式播放接口：
     * - 同时兼容新 trackId 和旧 MusicFree 收藏夹 (chatId, messageId)；
     * - 原生支持 HTTP Range (206 Partial Content) 拖动进度条；
     * - 若遇 TG 404，自动标 BROKEN 并唤醒备用源重新补录；
     * - 对存量未补全元数据的曲目，借点播缓存文件顺手异步补齐 album/封面/内嵌歌词。
     */
    @GetMapping("/stream/{msgId}")
    public ResponseEntity<ResourceRegion> streamMusic(
            @PathVariable long msgId,
            @RequestParam(name = "fid", required = false) Long chatId,
            @RequestHeader HttpHeaders headers) {

        Optional<Track> trackOpt = catalogRepository.resolveTrackForApi(msgId, chatId);
        if (trackOpt.isEmpty()) {
            log.warn("streamMusic: track not found for id={}, fid={}", msgId, chatId);
            return ResponseEntity.notFound().build();
        }
        Track track = trackOpt.get();
        if (track.getStoredChatId() == null || track.getStoredMsgId() == null) {
            return ResponseEntity.notFound().build();
        }

        TdGatewayService.DownloadResult dl = tdGateway.downloadFileAwait(
                track.getStoredChatId(), track.getStoredMsgId(), Duration.ofSeconds(45));
        if (dl.file() == null) {
            log.warn("streamMusic: download failed for track={} ({}:{}), notFound={}, err={}",
                    track.getId(), track.getStoredChatId(), track.getStoredMsgId(), dl.notFound(), dl.error());
            if (dl.notFound()) {
                catalogRepository.markTrackStatus(track.getId(), Track.STATUS_BROKEN);
                int reactivated = catalogRepository.reactivateAlternateSourcesForNormKey(
                        track.getNormKey(), track.getBestSourceId());
                log.info("Marked track {} BROKEN and reactivated {} alternate sources", track.getId(), reactivated);
            }
            return ResponseEntity.notFound().build();
        }

        File localFile = new File(dl.file().local.path);
        if (!localFile.exists() || localFile.length() == 0) {
            return ResponseEntity.notFound().build();
        }

        // 存量未做过深度探测的曲目，趁文件已在本地缓存异步补全 album / 封面 / 内嵌歌词
        if (!track.isMetadataEnriched()) {
            enrichLegacyTrackAsync(track, localFile);
        }

        try {
            FileSystemResource resource = new FileSystemResource(localFile);
            long contentLength = resource.contentLength();
            ResourceRegion region = buildResourceRegion(resource, contentLength, headers.getRange());

            String safeFileName = StringUtils.isNotBlank(track.getFileName())
                    ? track.getFileName() : localFile.getName();
            String encodedFileName = UriUtils.encode(safeFileName, StandardCharsets.UTF_8);
            MediaType mediaType = parseSafeMediaType(track.getMimeType());

            HttpStatus status = headers.getRange().isEmpty() ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT;
            String etag = "\"" + track.getId() + "-" + contentLength + "\"";
            return ResponseEntity.status(status)
                    .contentType(mediaType)
                    .eTag(etag)
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePrivate())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + encodedFileName
                            + "\"; filename*=UTF-8''" + encodedFileName)
                    .body(region);
        } catch (IOException e) {
            log.warn("streamMusic: failed to stream {}", localFile.getAbsolutePath(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/lyric/{msgId}")
    public ResponseEntity<String> getLyric(
            @PathVariable long msgId,
            @RequestParam(name = "fid", required = false) Long chatId) {

        Optional<Track> trackOpt = catalogRepository.resolveTrackForApi(msgId, chatId);
        if (trackOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Track track = trackOpt.get();
        Optional<TrackLyric> existing = lyricRepository.findByTrackId(track.getId());
        TrackLyric lyricObj = existing.orElse(null);

        if (lyricObj == null || TrackLyric.STATUS_PENDING.equals(lyricObj.getStatus())
                || TrackLyric.STATUS_RETRY.equals(lyricObj.getStatus())) {
            try {
                lyricObj = CompletableFuture.supplyAsync(() -> lyricSyncService.fetchAndPersistLyric(track), lyricExecutor)
                        .get(6, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("Online lyric fetch timed out for track {}: {}", track.getId(), e.getMessage());
            }
        }

        String formatted = lyricSyncService.formatLyricForClient(lyricObj);
        if (StringUtils.isBlank(formatted)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(formatted);
    }

    @RequestMapping(value = "/prefetch/{msgId}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Void> prefetchMusic(
            @PathVariable long msgId,
            @RequestParam(name = "fid", required = false) Long chatId) {
        Optional<Track> trackOpt = catalogRepository.resolveTrackForApi(msgId, chatId);
        if (trackOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Track track = trackOpt.get();
        if (track.getStoredChatId() == null || track.getStoredMsgId() == null) {
            return ResponseEntity.notFound().build();
        }
        CompletableFuture.runAsync(
                () -> tdGateway.prefetchFileAsync(track.getStoredChatId(), track.getStoredMsgId()),
                lyricExecutor);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>(catalogRepository.queryCatalogStats());
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("coverDirMb", dirSizeMb(new File(properties.getCoverDir())));
        storage.put("tempDirMb", dirSizeMb(new File(properties.getTempDir())));
        result.put("storage", storage);
        return result;
    }

    @GetMapping("/info/{msgId}")
    public ResponseEntity<MusicItem> getMusicInfo(
            @PathVariable long msgId,
            @RequestParam(name = "fid", required = false) Long chatId) {
        Optional<Track> trackOpt = catalogRepository.resolveTrackForApi(msgId, chatId);
        if (trackOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Track track = trackOpt.get();
        long folderId = chatId != null ? chatId : (track.getStoredChatId() != null ? track.getStoredChatId() : 0L);
        return ResponseEntity.ok(toMusicItem(folderId, track));
    }

    @GetMapping("/cover/{trackId}")
    public ResponseEntity<Resource> getCover(@PathVariable long trackId) {
        Optional<Track> trackOpt = catalogRepository.findTrackById(trackId);
        if (trackOpt.isEmpty() || StringUtils.isBlank(trackOpt.get().getCoverPath())) {
            return ResponseEntity.notFound().build();
        }
        File coverFile = new File(properties.getCoverDir(), trackOpt.get().getCoverPath());
        if (!coverFile.exists() || !coverFile.isFile()) {
            return ResponseEntity.notFound().build();
        }
        coverFile.setLastModified(System.currentTimeMillis());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .body(new FileSystemResource(coverFile));
    }

    public static MusicItem toMusicItem(long folderId, Track t) {
        String title = StringUtils.isNotBlank(t.getTitle())
                ? t.getTitle()
                : FilenameUtils.getBaseName(StringUtils.defaultString(t.getFileName())).trim();
        String coverUrl = StringUtils.isNotBlank(t.getCoverPath()) ? "/music/cover/" + t.getId() : null;
        return new MusicItem(
                folderId,
                t.getId(),
                t.getFileName(),
                t.getMimeType(),
                title,
                t.getArtist(),
                StringUtils.trimToNull(t.getAlbum()),
                t.getDurationSec(),
                t.getStoredSize(),
                coverUrl,
                t.buildQualityTag()
        );
    }

    private void enrichLegacyTrackAsync(Track track, File localFile) {
        CompletableFuture.runAsync(() -> {
            try {
                if (!localFile.exists()) return;
                AudioMetadataService.AudioProbeResult probe = audioMetadataService.probe(
                        localFile, track.getTitle(), track.getArtist(), track.getFileName(), null, track.getMimeType());
                catalogRepository.updateTrackEnrichedMetadata(
                        track.getId(), probe.title(), probe.artist(), probe.album(),
                        probe.durationSec(), probe.coverRelPath(), probe.codec(),
                        probe.bitrateKbps(), probe.sampleRate(), probe.bitDepth(), probe.qualityScore());
                if (StringUtils.isNotBlank(probe.embeddedLyrics())) {
                    TrackLyric tl = new TrackLyric();
                    tl.setTrackId(track.getId());
                    tl.setSource(TrackLyric.SOURCE_EMBEDDED);
                    tl.setLrc(probe.embeddedLyrics());
                    tl.setMatchedDurationMs(probe.durationSec() > 0 ? probe.durationSec() * 1000 : null);
                    tl.setMatchScore(1.0);
                    tl.setStatus(TrackLyric.STATUS_FOUND);
                    lyricRepository.upsert(tl);
                } else {
                    lyricRepository.resetNotFoundToPending(track.getId());
                }
                if (cacheManager != null) {
                    Cache cache = cacheManager.getCache("music_list");
                    if (cache != null) {
                        cache.clear();
                    }
                }
            } catch (Exception e) {
                log.debug("Background legacy metadata enrich skipped for track {}: {}", track.getId(), e.getMessage());
            }
        }, lyricExecutor);
    }

    private static double dirSizeMb(File dir) {
        if (!dir.isDirectory()) return 0.0;
        File[] files = dir.listFiles();
        if (files == null) return 0.0;
        long total = 0;
        for (File f : files) {
            if (f.isFile()) total += f.length();
        }
        return Math.round(total * 100.0 / (1024.0 * 1024.0)) / 100.0;
    }

    private static ResourceRegion buildResourceRegion(Resource resource, long contentLength, List<HttpRange> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return new ResourceRegion(resource, 0, contentLength);
        }
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(contentLength);
        long end = range.getRangeEnd(contentLength);
        long length = Math.min(MAX_RANGE_CHUNK, end - start + 1);
        return new ResourceRegion(resource, start, length);
    }

    private static MediaType parseSafeMediaType(String mime) {
        try {
            if (StringUtils.isNotBlank(mime)) {
                return MediaType.parseMediaType(mime);
            }
        } catch (Exception ignored) {}
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
