package dev.voroby.telegram.music.migration;

import dev.voroby.telegram.music.model.SourceTrack;
import dev.voroby.telegram.music.model.Track;
import dev.voroby.telegram.music.model.TrackLyric;
import dev.voroby.telegram.music.repository.MusicCatalogRepository;
import dev.voroby.telegram.music.repository.TrackLyricRepository;
import dev.voroby.telegram.music.service.TrackNormalizationService;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 幂等、低内存（每批 200 条分页流式）存量数据迁移服务：
 * - 在 @PostConstruct（依赖 SchemaInitializer 完成、早于 Tomcat 开放端口与 @Scheduled 任务启动）同步执行，
 *   彻底杜绝迁移期间并发写入导致的 SQLITE_BUSY_SNAPSHOT 以及客户端半迁移 404；
 * - 一次性预读 sync_music_message 到内存索引，彻底消除无索引下的 O(N²) 全表扫描；
 * - 将所有旧 src_music_message 的 (chat_id, message_id) 写入 legacy_message_aliases，
 *   即使去重合并后，旧 MusicFree 收藏夹仍能 100% 命中对应的高品质主曲目；
 * - 绝不修改或删除旧表，确保随时可回滚。
 */
@Service
public class LegacyDataMigrationService {
    private static final Logger log = LoggerFactory.getLogger(LegacyDataMigrationService.class);
    private static final String MIGRATION_NAME = "v2_initial_migration";
    private static final String ALIAS_BACKFILL_NAME = "v2_legacy_aliases_backfill";
    private static final int BATCH_SIZE = 200;
    private static final Pattern LRC_TIMESTAMP = Pattern.compile("\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?]");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final MusicCatalogRepository catalogRepository;
    private final TrackLyricRepository lyricRepository;
    private final TrackNormalizationService normService;

    @Autowired
    public LegacyDataMigrationService(JdbcTemplate jdbc,
                                      SchemaInitializer schemaInitializer,
                                      PlatformTransactionManager txManager,
                                      MusicCatalogRepository catalogRepository,
                                      TrackLyricRepository lyricRepository,
                                      TrackNormalizationService normService) {
        this.jdbc = jdbc;
        this.txTemplate = txManager != null
                ? new TransactionTemplate(txManager)
                : (jdbc.getDataSource() != null ? new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())) : null);
        this.catalogRepository = catalogRepository;
        this.lyricRepository = lyricRepository;
        this.normService = normService;
    }

    /** 供单元测试直接构造使用 */
    public LegacyDataMigrationService(JdbcTemplate jdbc,
                                      MusicCatalogRepository catalogRepository,
                                      TrackLyricRepository lyricRepository,
                                      TrackNormalizationService normService) {
        this(jdbc, null,
                jdbc.getDataSource() != null ? new DataSourceTransactionManager(jdbc.getDataSource()) : null,
                catalogRepository, lyricRepository, normService);
    }

    private void runInBatchTx(Runnable action) {
        if (txTemplate != null) {
            txTemplate.executeWithoutResult(status -> action.run());
        } else {
            action.run();
        }
    }

    /**
     * 在 Bean 初始化阶段（SchemaInitializer 之后、Tomcat 与定时任务启动之前）完成存量迁移，
     * 避免并发写锁争用与半迁移状态对外暴露。
     */
    @PostConstruct
    public void runMigrationOnStartup() {
        migrateIfNeeded();
    }

    public synchronized void migrateIfNeeded() {
        boolean initialApplied = isMigrationApplied(MIGRATION_NAME);
        if (!initialApplied) {
            runInitialMigration();
        }
        if (!isMigrationApplied(ALIAS_BACKFILL_NAME)) {
            backfillLegacyAliasesIfNeeded();
        }
    }

    private void runInitialMigration() {
        boolean hasSrcTable = tableExists("src_music_message");
        boolean hasMusicTable = tableExists("music_message");
        boolean hasChannelTable = tableExists("channel_info");
        boolean hasSyncChannelTable = tableExists("sync_channel_info");
        boolean hasSyncMusicTable = tableExists("sync_music_message");
        boolean hasLyricTable = tableExists("music_lyric");

        if (!hasSrcTable && !hasMusicTable && !hasChannelTable && !hasSyncChannelTable) {
            recordMigration(MIGRATION_NAME, "fresh_install_no_legacy_tables");
            recordMigration(ALIAS_BACKFILL_NAME, "fresh_install_no_legacy_tables");
            return;
        }

        log.info("Starting streaming legacy migration (batchSize={})...", BATCH_SIZE);
        long t0 = System.currentTimeMillis();

        Map<Long, Long> legacyChatToPlaylistId = new HashMap<>();
        Map<Long, Long> sourceChatToPlaylistId = new HashMap<>();
        int migratedPlaylists = migratePlaylists(hasChannelTable, hasSyncChannelTable, sourceChatToPlaylistId, legacyChatToPlaylistId);

        int migratedTracks = 0;
        int mergedDuplicates = 0;
        if (hasSrcTable) {
            Map<String, SyncMeta> syncLookup = hasSyncMusicTable ? loadSyncMusicLookup() : Collections.emptyMap();
            int[] stats = migrateSrcMusicMessages(syncLookup, legacyChatToPlaylistId, sourceChatToPlaylistId);
            migratedTracks = stats[0];
            mergedDuplicates = stats[1];
        }

        int migratedSources = 0;
        if (hasMusicTable) {
            migratedSources = migrateLegacySourceMessages();
        }

        int migratedLyrics = 0;
        if (hasLyricTable) {
            migratedLyrics = migrateLegacyLyrics();
        }

        String summary = String.format("playlists=%d, tracks=%d, mergedDup=%d, sources=%d, lyrics=%d, costMs=%d",
                migratedPlaylists, migratedTracks, mergedDuplicates, migratedSources, migratedLyrics,
                System.currentTimeMillis() - t0);
        recordMigration(MIGRATION_NAME, summary);
        recordMigration(ALIAS_BACKFILL_NAME, "populated_during_initial_migration");
        log.info("Legacy migration completed: {}", summary);
    }

    /**
     * 针对已跑过旧版 v2_initial_migration 的库，补建 legacy_message_aliases 映射，
     * 保证去重合并后的旧 MusicFree 收藏夹也能 100% 播放。
     */
    private void backfillLegacyAliasesIfNeeded() {
        if (!tableExists("src_music_message")) {
            recordMigration(ALIAS_BACKFILL_NAME, "no_src_table");
            return;
        }
        long t0 = System.currentTimeMillis();
        Map<String, SyncMeta> syncLookup = tableExists("sync_music_message") ? loadSyncMusicLookup() : Collections.emptyMap();
        int[] aliased = {0};
        long[] lastId = {0L};
        while (true) {
            List<Map<String, Object>> batch = jdbc.queryForList("""
                    SELECT id, chat_id, message_id, file_name, title, performer
                    FROM src_music_message
                    WHERE id > ?
                    ORDER BY id ASC
                    LIMIT ?
                    """, lastId[0], BATCH_SIZE);
            if (batch.isEmpty()) break;

            runInBatchTx(() -> {
                for (Map<String, Object> row : batch) {
                    lastId[0] = toLong(row.get("id"));
                    long chatId = toLong(row.get("chat_id"));
                    long msgId = toLong(row.get("message_id"));
                    if (chatId == -1 || msgId == -1 || chatId == 0 || msgId == 0) continue;

                    String fileName = str(row.get("file_name"));
                    String title = normService.cleanDisplayText(str(row.get("title")));
                    String artist = normService.cleanDisplayText(str(row.get("performer")));
                    SyncMeta sr = syncLookup.get(chatId + ":" + msgId);
                    if (sr != null) {
                        if (StringUtils.isBlank(fileName)) fileName = sr.fileName();
                        if (StringUtils.isBlank(title)) title = normService.cleanDisplayText(sr.title());
                        if (StringUtils.isBlank(artist)) artist = normService.cleanDisplayText(sr.performer());
                    }
                    if (StringUtils.isBlank(title) || StringUtils.isBlank(artist)) {
                        TrackNormalizationService.ParsedName parsed = normService.parseFromFilenameOrCaption(fileName, null);
                        if (StringUtils.isBlank(title) && StringUtils.isNotBlank(parsed.title())) title = parsed.title();
                        if (StringUtils.isBlank(artist) && StringUtils.isNotBlank(parsed.artist())) artist = parsed.artist();
                    }
                    if (StringUtils.isBlank(title)) continue;

                    String versionTag = normService.extractVersionTag(title, fileName);
                    String normKey = normService.buildNormKey(title, artist, fileName, versionTag);
                    Optional<Track> trackOpt = catalogRepository.findTrackByNormKey(normKey);
                    if (trackOpt.isPresent()) {
                        long trackId = trackOpt.get().getId();
                        catalogRepository.upsertLegacyAlias(chatId, msgId, trackId);
                        if (sr != null && sr.originChatId() != 0 && sr.originMsgId() != 0) {
                            catalogRepository.upsertLegacyAlias(sr.originChatId(), sr.originMsgId(), trackId);
                        }
                        aliased[0]++;
                    }
                }
            });
        }
        String summary = String.format("aliased=%d, costMs=%d", aliased[0], System.currentTimeMillis() - t0);
        recordMigration(ALIAS_BACKFILL_NAME, summary);
        log.info("Legacy aliases backfill completed: {}", summary);
    }

    private Map<String, SyncMeta> loadSyncMusicLookup() {
        Map<String, SyncMeta> map = new HashMap<>();
        long lastId = 0L;
        while (true) {
            List<Map<String, Object>> batch = jdbc.queryForList("""
                    SELECT id, chat_id, message_id, origin_chat_id, origin_message_id,
                           file_name, mime_type, title, performer, duration_seconds, audio_file_size
                    FROM sync_music_message
                    WHERE id > ?
                    ORDER BY id ASC
                    LIMIT 500
                    """, lastId);
            if (batch.isEmpty()) break;
            for (Map<String, Object> r : batch) {
                lastId = toLong(r.get("id"));
                long chatId = toLong(r.get("chat_id"));
                long msgId = toLong(r.get("message_id"));
                if (chatId == 0 || msgId == 0) continue;
                map.putIfAbsent(chatId + ":" + msgId, new SyncMeta(
                        toLong(r.get("origin_chat_id")),
                        toLong(r.get("origin_message_id")),
                        str(r.get("file_name")),
                        str(r.get("mime_type")),
                        str(r.get("title")),
                        str(r.get("performer")),
                        toInt(r.get("duration_seconds")),
                        toLong(r.get("audio_file_size"))
                ));
            }
        }
        log.info("Loaded {} sync_music_message entries for fast O(1) migration lookup", map.size());
        return map;
    }

    private int migratePlaylists(boolean hasChannel, boolean hasSyncChannel,
                                 Map<Long, Long> sourceChatToPlaylistId,
                                 Map<Long, Long> legacyChatToPlaylistId) {
        Map<String, Long> titleToSourceChat = new HashMap<>();
        int[] count = {0};
        runInBatchTx(() -> {
            if (hasChannel) {
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT chat_id, title, folder_name FROM channel_info");
                for (Map<String, Object> r : rows) {
                    long chatId = toLong(r.get("chat_id"));
                    String title = Objects.toString(r.get("title"), "Unknown").trim();
                    String folderName = Objects.toString(r.get("folder_name"), "Music");
                    long pid = catalogRepository.upsertPlaylist(chatId, null, title, folderName);
                    sourceChatToPlaylistId.put(chatId, pid);
                    titleToSourceChat.put(title, chatId);
                    count[0]++;
                }
            }
            if (hasSyncChannel) {
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT chat_id, title, folder_name FROM sync_channel_info");
                for (Map<String, Object> r : rows) {
                    long legacyChatId = toLong(r.get("chat_id"));
                    String rawTitle = Objects.toString(r.get("title"), "Unknown").trim();
                    String cleanTitle = rawTitle.startsWith("CW-") ? rawTitle.substring(3) : rawTitle;
                    Long matchedSourceChat = titleToSourceChat.get(cleanTitle);
                    long pid = catalogRepository.upsertPlaylist(matchedSourceChat, legacyChatId, cleanTitle, "Music");
                    legacyChatToPlaylistId.put(legacyChatId, pid);
                    if (matchedSourceChat != null) {
                        sourceChatToPlaylistId.put(matchedSourceChat, pid);
                    }
                    count[0]++;
                }
            }
        });
        return count[0];
    }

    private int[] migrateSrcMusicMessages(Map<String, SyncMeta> syncLookup,
                                          Map<Long, Long> legacyChatToPlaylistId,
                                          Map<Long, Long> sourceChatToPlaylistId) {
        int[] stats = {0, 0};
        long[] lastId = {0L};

        while (true) {
            List<Map<String, Object>> batch = jdbc.queryForList("""
                    SELECT id, chat_id, message_id, sent_at, file_name, mime_type, title, performer,
                           duration_seconds, audio_file_size
                    FROM src_music_message
                    WHERE id > ?
                    ORDER BY id ASC
                    LIMIT ?
                    """, lastId[0], BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }

            runInBatchTx(() -> {
                for (Map<String, Object> row : batch) {
                    lastId[0] = toLong(row.get("id"));
                    long chatId = toLong(row.get("chat_id"));
                    long msgId = toLong(row.get("message_id"));
                    if (chatId == -1 || msgId == -1 || chatId == 0 || msgId == 0) {
                        continue;
                    }

                    String fileName = str(row.get("file_name"));
                    String mimeType = str(row.get("mime_type"));
                    String title = normService.cleanDisplayText(str(row.get("title")));
                    String artist = normService.cleanDisplayText(str(row.get("performer")));
                    int duration = toInt(row.get("duration_seconds"));
                    long fileSize = toLong(row.get("audio_file_size"));

                    Long originChatId = null;
                    Long originMsgId = null;
                    SyncMeta sr = syncLookup.get(chatId + ":" + msgId);
                    if (sr != null) {
                        originChatId = sr.originChatId() != 0 ? sr.originChatId() : null;
                        originMsgId = sr.originMsgId() != 0 ? sr.originMsgId() : null;
                        if (StringUtils.isBlank(fileName)) fileName = sr.fileName();
                        if (StringUtils.isBlank(title)) title = normService.cleanDisplayText(sr.title());
                        if (StringUtils.isBlank(artist)) artist = normService.cleanDisplayText(sr.performer());
                        if (duration <= 0) duration = sr.durationSec();
                        if (fileSize <= 0) fileSize = sr.fileSize();
                        if (StringUtils.isBlank(mimeType)) mimeType = sr.mimeType();
                    }

                    if (StringUtils.isBlank(title) || StringUtils.isBlank(artist)) {
                        TrackNormalizationService.ParsedName parsed = normService.parseFromFilenameOrCaption(fileName, null);
                        if (StringUtils.isBlank(title) && StringUtils.isNotBlank(parsed.title())) {
                            title = parsed.title();
                        }
                        if (StringUtils.isBlank(artist) && StringUtils.isNotBlank(parsed.artist())) {
                            artist = parsed.artist();
                        }
                    }
                    if (StringUtils.isBlank(title)) {
                        continue;
                    }

                    String versionTag = normService.extractVersionTag(title, fileName);
                    String normKey = normService.buildNormKey(title, artist, fileName, versionTag);
                    int estScore = normService.estimateQualityScore(mimeType, fileName, fileSize, duration);
                    if (StringUtils.containsIgnoreCase(mimeType, "ogg") || StringUtils.endsWithIgnoreCase(fileName, ".ogg")) {
                        estScore = Math.min(estScore, 660);
                    }

                    Optional<Track> existingOpt = catalogRepository.findTrackByNormKey(normKey);
                    long trackId;
                    if (existingOpt.isPresent()) {
                        Track existing = existingOpt.get();
                        stats[1]++;
                        boolean shouldReplace = estScore > existing.getQualityScore()
                                || (estScore == existing.getQualityScore()
                                && StringUtils.isBlank(existing.getFileName()) && StringUtils.isNotBlank(fileName));
                        if (shouldReplace) {
                            Track updated = buildMigratedTrack(normKey, title, artist, versionTag, duration, estScore,
                                    chatId, msgId, mimeType, fileName, fileSize);
                            trackId = catalogRepository.upsertTrackAndFts(updated);
                        } else {
                            trackId = existing.getId();
                        }
                    } else {
                        Track track = buildMigratedTrack(normKey, title, artist, versionTag, duration, estScore,
                                chatId, msgId, mimeType, fileName, fileSize);
                        trackId = catalogRepository.upsertTrackAndFts(track);
                        stats[0]++;
                    }

                    // 记录旧 (chat_id, message_id) 别名映射，确保去重合并后的旧收藏夹仍可无感播放
                    catalogRepository.upsertLegacyAlias(chatId, msgId, trackId);
                    if (originChatId != null && originMsgId != null) {
                        catalogRepository.upsertLegacyAlias(originChatId, originMsgId, trackId);
                    }

                    Long playlistId = legacyChatToPlaylistId.get(chatId);
                    if (playlistId == null && originChatId != null) {
                        playlistId = sourceChatToPlaylistId.get(originChatId);
                    }
                    if (playlistId != null) {
                        catalogRepository.linkTrackToPlaylist(playlistId, trackId);
                    }
                }
            });
        }
        return stats;
    }

    private Track buildMigratedTrack(String normKey, String title, String artist, String versionTag,
                                     int duration, int estScore, long chatId, long msgId,
                                     String mimeType, String fileName, long fileSize) {
        Track t = new Track();
        t.setNormKey(normKey);
        t.setTitle(title);
        t.setArtist(StringUtils.defaultString(artist));
        t.setAlbum("");
        t.setVersionTag(versionTag);
        t.setDurationSec(Math.max(0, duration));
        t.setQualityScore(estScore);
        String resolvedMime = StringUtils.defaultIfBlank(mimeType, "audio/mpeg");
        t.setMimeType(resolvedMime);
        String codec = resolvedMime.contains("ogg") ? "opus" : (resolvedMime.contains("mp4") ? "aac" : "mp3");
        t.setSrcCodec(codec);
        t.setStoredCodec(codec);
        t.setStoredChatId(chatId);
        t.setStoredMsgId(msgId);
        t.setStoredSize(Math.max(0, fileSize));
        String safeName = StringUtils.isNotBlank(fileName) ? fileName
                : (title + (StringUtils.isNotBlank(artist) ? " - " + artist : "") + (codec.equals("opus") ? ".ogg" : ".mp3"));
        t.setFileName(safeName);
        t.setMetadataEnriched(false);
        t.setStatus(Track.STATUS_READY);
        return t;
    }

    private int migrateLegacySourceMessages() {
        int[] count = {0};
        long[] lastId = {0L};
        while (true) {
            List<Map<String, Object>> batch = jdbc.queryForList("""
                    SELECT id, chat_id, message_id, sent_at, file_name, mime_type, title, performer,
                           duration_seconds, audio_file_size, sync
                    FROM music_message
                    WHERE id > ?
                    ORDER BY id ASC
                    LIMIT ?
                    """, lastId[0], BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            runInBatchTx(() -> {
                for (Map<String, Object> row : batch) {
                    lastId[0] = toLong(row.get("id"));
                    long chatId = toLong(row.get("chat_id"));
                    long msgId = toLong(row.get("message_id"));
                    if (chatId == 0 || msgId == 0) continue;

                    String fileName = str(row.get("file_name"));
                    String mimeType = str(row.get("mime_type"));
                    String title = normService.cleanDisplayText(str(row.get("title")));
                    String artist = normService.cleanDisplayText(str(row.get("performer")));
                    int duration = toInt(row.get("duration_seconds"));
                    long fileSize = toLong(row.get("audio_file_size"));
                    int oldSync = toInt(row.get("sync"));

                    if (StringUtils.isBlank(title)) {
                        TrackNormalizationService.ParsedName parsed = normService.parseFromFilenameOrCaption(fileName, null);
                        title = parsed.title();
                        if (StringUtils.isBlank(artist)) artist = parsed.artist();
                    }

                    String ver = normService.extractVersionTag(title, fileName);
                    String normKey = normService.buildNormKey(title, artist, fileName, ver);
                    int estScore = normService.estimateQualityScore(mimeType, fileName, fileSize, duration);

                    String status = SourceTrack.STATUS_NEW;
                    Long linkedTrackId = null;
                    Optional<Track> existingTrack = catalogRepository.findTrackByNormKey(normKey);
                    if (existingTrack.isPresent()) {
                        linkedTrackId = existingTrack.get().getId();
                        if (oldSync == 1 && existingTrack.get().getQualityScore() >= estScore) {
                            status = SourceTrack.STATUS_DONE;
                        }
                    }

                    SourceTrack st = new SourceTrack();
                    st.setSourceChatId(chatId);
                    st.setSourceMsgId(msgId);
                    st.setSentAt(toEpochSeconds(row.get("sent_at")));
                    st.setRawTitle(title);
                    st.setRawArtist(artist);
                    st.setRawFilename(fileName);
                    st.setMimeType(mimeType);
                    st.setFileSize(fileSize);
                    st.setDurationSec(duration);
                    st.setEstQualityScore(estScore);
                    st.setNormKey(normKey);
                    st.setStatus(status);
                    st.setTrackId(linkedTrackId);
                    if (catalogRepository.insertSourceTrackIgnore(st)) {
                        count[0]++;
                    }
                }
            });
        }
        return count[0];
    }

    private int migrateLegacyLyrics() {
        int[] count = {0};
        long[] lastId = {0L};
        while (true) {
            List<Map<String, Object>> batch = jdbc.queryForList("""
                    SELECT id, chat_id, message_id, lyric
                    FROM music_lyric
                    WHERE id > ?
                    ORDER BY id ASC
                    LIMIT ?
                    """, lastId[0], BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            runInBatchTx(() -> {
                for (Map<String, Object> row : batch) {
                    lastId[0] = toLong(row.get("id"));
                    long chatId = toLong(row.get("chat_id"));
                    long msgId = toLong(row.get("message_id"));
                    String lrc = str(row.get("lyric"));
                    if (StringUtils.isBlank(lrc) || !LRC_TIMESTAMP.matcher(lrc).find()) {
                        continue;
                    }
                    Optional<Track> trackOpt = catalogRepository.resolveTrackForApi(msgId, chatId);
                    if (trackOpt.isEmpty()) {
                        continue;
                    }
                    TrackLyric tl = new TrackLyric();
                    tl.setTrackId(trackOpt.get().getId());
                    tl.setSource(TrackLyric.SOURCE_LEGACY);
                    tl.setLrc(lrc);
                    tl.setStatus(TrackLyric.STATUS_FOUND);
                    tl.setRetryCount(0);
                    lyricRepository.upsert(tl);
                    count[0]++;
                }
            });
        }
        return count[0];
    }

    private boolean isMigrationApplied(String name) {
        Integer applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE name = ?", Integer.class, name);
        return applied != null && applied > 0;
    }

    private boolean tableExists(String name) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, name);
        return cnt != null && cnt > 0;
    }

    private void recordMigration(String name, String summary) {
        jdbc.update("INSERT OR IGNORE INTO schema_migration(name, applied_at, summary) VALUES(?,?,?)",
                name, System.currentTimeMillis(), summary);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString().trim()); } catch (Exception e) { return 0L; }
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return 0; }
    }

    private static long toEpochSeconds(Object o) {
        long v = toLong(o);
        return v > 10_000_000_000L ? v / 1000L : v;
    }

    private record SyncMeta(long originChatId, long originMsgId, String fileName, String mimeType,
                            String title, String performer, int durationSec, long fileSize) {
    }
}
