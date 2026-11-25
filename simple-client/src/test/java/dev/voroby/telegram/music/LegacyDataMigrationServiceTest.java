package dev.voroby.telegram.music;

import dev.voroby.telegram.music.migration.LegacyDataMigrationService;
import dev.voroby.telegram.music.migration.SchemaInitializer;
import dev.voroby.telegram.music.model.Track;
import dev.voroby.telegram.music.model.TrackLyric;
import dev.voroby.telegram.music.repository.MusicCatalogRepository;
import dev.voroby.telegram.music.repository.TrackLyricRepository;
import dev.voroby.telegram.music.service.TrackNormalizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LegacyDataMigrationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void testIdempotentMigrationDeduplicationAndFtsSearch() {
        String url = "jdbc:sqlite:" + tempDir.resolve("legacy.db") + "?journal_mode=WAL";
        DriverManagerDataSource ds = new DriverManagerDataSource(url);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 构造旧 6 张表及典型脏数据
        jdbc.execute("CREATE TABLE channel_info (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER UNIQUE NOT NULL, title TEXT, username TEXT, chat_type TEXT, folder_name TEXT)");
        jdbc.execute("CREATE TABLE sync_channel_info (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER UNIQUE NOT NULL, title TEXT, username TEXT, chat_type TEXT, folder_name TEXT)");
        jdbc.execute("CREATE TABLE src_music_message (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL, sent_at INTEGER NOT NULL, file_name TEXT, mime_type TEXT, title TEXT, performer TEXT, duration_seconds INTEGER, cover_file_id INTEGER, cover_width INTEGER, cover_height INTEGER, audio_file_id INTEGER, audio_file_size INTEGER, sync INTEGER)");
        jdbc.execute("CREATE TABLE sync_music_message (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, origin_chat_id INTEGER NOT NULL, message_id INTEGER, message_id_temp INTEGER NOT NULL, origin_message_id INTEGER NOT NULL, sent_at INTEGER NOT NULL, file_name TEXT, mime_type TEXT, title TEXT, performer TEXT, duration_seconds INTEGER, cover_file_id INTEGER, cover_width INTEGER, cover_height INTEGER, audio_file_size INTEGER, fix_duration INTEGER)");
        jdbc.execute("CREATE TABLE music_message (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL, sent_at INTEGER NOT NULL, file_name TEXT, mime_type TEXT, title TEXT, performer TEXT, duration_seconds INTEGER, cover_file_id INTEGER, cover_width INTEGER, cover_height INTEGER, audio_file_id INTEGER, audio_file_size INTEGER, sync INTEGER)");
        jdbc.execute("CREATE TABLE music_lyric (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, message_id INTEGER NOT NULL, lyric TEXT, sync INTEGER)");

        jdbc.update("INSERT INTO channel_info(chat_id, title, chat_type, folder_name) VALUES(-1001001, '音乐曲库', 'channel', 'Music')");
        jdbc.update("INSERT INTO sync_channel_info(chat_id, title, chat_type, folder_name) VALUES(-1002001, 'CW-音乐曲库', 'channel', 'Music-Source')");

        // 1. 重复歌曲：一条 128k MP3 和一条 320k MP3（简繁不同），迁移后应合并并保留 320k
        jdbc.update("INSERT INTO src_music_message(chat_id, message_id, sent_at, file_name, mime_type, title, performer, duration_seconds, audio_file_size, sync) VALUES(-1002001, 1001, 1700000000, 'qingtian_128.mp3', 'audio/mpeg', '晴天', '周杰伦', 269, 4300000, 1)");
        jdbc.update("INSERT INTO src_music_message(chat_id, message_id, sent_at, file_name, mime_type, title, performer, duration_seconds, audio_file_size, sync) VALUES(-1002001, 1002, 1700000010, 'qingtian_320.mp3', 'audio/mpeg', '晴天', '周杰倫', 269, 10800000, 1)");
        // 2. VoiceNote 缺失 file_name 且标题含 "null-" 脏串，靠 sync_music_message 补齐
        jdbc.update("INSERT INTO src_music_message(chat_id, message_id, sent_at, file_name, mime_type, title, performer, duration_seconds, audio_file_size, sync) VALUES(-1002001, 1003, 1700000020, NULL, 'audio/ogg', 'null-稻香', '周杰伦', 0, 5000000, 1)");
        jdbc.update("INSERT INTO sync_music_message(chat_id, origin_chat_id, message_id, message_id_temp, origin_message_id, sent_at, file_name, mime_type, title, performer, duration_seconds, audio_file_size, fix_duration) VALUES(-1002001, -1001001, 1003, 999, 5003, 1700000020, '稻香.ogg', 'audio/ogg', '稻香', '周杰伦', 223, 5000000, 1)");
        // 3. 无效 -1 幽灵记录，应被忽略
        jdbc.update("INSERT INTO src_music_message(chat_id, message_id, sent_at, title, performer) VALUES(-1, -1, 0, '幽灵歌', '无名')");
        // 4. 歌词：一条有效 LRC，一条空串
        jdbc.update("INSERT INTO music_lyric(chat_id, message_id, lyric, sync) VALUES(-1002001, 1002, '[00:01.00]故事的小黄花', 1)");
        jdbc.update("INSERT INTO music_lyric(chat_id, message_id, lyric, sync) VALUES(-1002001, 1003, '', 1)");
        // 5. 原始采集表：一条曾 sync=2 失败的无损歌，应保持 NEW 重新进入新流水线
        jdbc.update("INSERT INTO music_message(chat_id, message_id, sent_at, file_name, mime_type, title, performer, duration_seconds, audio_file_size, sync) VALUES(-1001001, 5009, 1700000030, '周杰伦 - 稻香.flac', 'audio/flac', '稻香', '周杰伦', 223, 30000000, 2)");

        TrackNormalizationService norm = new TrackNormalizationService();
        new SchemaInitializer(jdbc).initialize();
        MusicCatalogRepository catalogRepo = new MusicCatalogRepository(jdbc, norm);
        TrackLyricRepository lyricRepo = new TrackLyricRepository(jdbc);
        LegacyDataMigrationService migration = new LegacyDataMigrationService(jdbc, catalogRepo, lyricRepo, norm);

        migration.migrateIfNeeded();
        // 再次调用验证幂等性
        migration.migrateIfNeeded();

        // 验证旧表完全未受破坏
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM src_music_message", Integer.class));

        // 验证合并去重后仅保留 2 首歌（晴天 + 稻香），且晴天保留了 320k 版本 (msgId=1002)
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tracks", Integer.class));
        MusicCatalogRepository.PageResult<Track> qingtianSearch = catalogRepo.searchReadyTracks("周杰伦 晴天", 0, 10);
        assertEquals(1, qingtianSearch.items().size());
        Track qingtian = qingtianSearch.items().get(0);
        assertEquals(1002L, qingtian.getStoredMsgId());
        assertTrue(qingtian.getQualityScore() >= 750);

        // 验证被去重合并掉的旧 128k 收藏夹 ID (msgId=1001, fid=-1002001) 仍能通过别名表命中升级后的 320k 晴天
        Optional<Track> legacyDupResolved = catalogRepo.resolveTrackForApi(1001L, -1002001L);
        assertTrue(legacyDupResolved.isPresent());
        assertEquals(qingtian.getId(), legacyDupResolved.get().getId());
        assertEquals(1002L, legacyDupResolved.get().getStoredMsgId());

        // 验证稻香已清洗 "null-" 并从 sync_music_message 恢复 fileName 和时长
        MusicCatalogRepository.PageResult<Track> daoxiangSearch = catalogRepo.searchReadyTracks("稻香", 0, 10);
        assertEquals(1, daoxiangSearch.items().size());
        Track daoxiang = daoxiangSearch.items().get(0);
        assertEquals("稻香", daoxiang.getTitle());
        assertEquals("稻香.ogg", daoxiang.getFileName());
        assertEquals(223, daoxiang.getDurationSec());

        // 验证歌词：晴天保留有效 LRC；稻香空串被忽略（待新引擎重抓）
        Optional<TrackLyric> l1 = lyricRepo.findByTrackId(qingtian.getId());
        assertTrue(l1.isPresent() && TrackLyric.STATUS_FOUND.equals(l1.get().getStatus()));
        assertTrue(lyricRepo.findByTrackId(daoxiang.getId()).isEmpty());

        // 验证原 sync=2 的高品 FLAC 源被迁移为 NEW，待流水线自动升级覆盖旧 128k 稻香
        List<dev.voroby.telegram.music.model.SourceTrack> pending = catalogRepo.findPendingSourceTracks(10);
        assertEquals(1, pending.size());
        assertEquals(5009L, pending.get(0).getSourceMsgId());
    }

    @Test
    void testDryRunMigrationOnRealLocalSqliteCopy() throws Exception {
        Path realDb = Path.of("database/sqlite.db");
        if (!java.nio.file.Files.exists(realDb)) {
            return;
        }
        Path copyDb = tempDir.resolve("real_copy.db");
        java.nio.file.Files.copy(realDb, copyDb);

        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + copyDb + "?journal_mode=WAL");
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        TrackNormalizationService norm = new TrackNormalizationService();
        new SchemaInitializer(jdbc).initialize();
        MusicCatalogRepository catalogRepo = new MusicCatalogRepository(jdbc, norm);
        TrackLyricRepository lyricRepo = new TrackLyricRepository(jdbc);
        LegacyDataMigrationService migration = new LegacyDataMigrationService(jdbc, catalogRepo, lyricRepo, norm);

        migration.migrateIfNeeded();

        int trackCount = jdbc.queryForObject("SELECT COUNT(*) FROM tracks", Integer.class);
        int playlistCount = jdbc.queryForObject("SELECT COUNT(*) FROM playlists", Integer.class);
        int ftsCount = jdbc.queryForObject("SELECT COUNT(*) FROM tracks_fts", Integer.class);
        String summary = jdbc.queryForObject("SELECT summary FROM schema_migration WHERE name='v2_initial_migration'", String.class);
        System.out.println("REAL LOCAL SQLITE DRY-RUN SUMMARY: " + summary + " | tracks=" + trackCount + ", playlists=" + playlistCount + ", fts=" + ftsCount);
        assertEquals(trackCount, ftsCount, "FTS5 row count must match tracks count");
        assertTrue(trackCount >= 0);
    }

    @Test
    void testConcurrentHikariWritesWithImmediateTransactionsNoBusySnapshot() throws Exception {
        com.zaxxer.hikari.HikariConfig cfg = new com.zaxxer.hikari.HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + tempDir.resolve("concurrent.db")
                + "?journal_mode=WAL&busy_timeout=15000&synchronous=NORMAL&foreign_keys=ON&transaction_mode=IMMEDIATE");
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(2);

        try (com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource(cfg)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            TrackNormalizationService norm = new TrackNormalizationService();
            new SchemaInitializer(jdbc).initialize();
            MusicCatalogRepository repo = new MusicCatalogRepository(jdbc, norm);
            org.springframework.transaction.support.TransactionTemplate tx =
                    new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));

            java.util.concurrent.atomic.AtomicReference<Throwable> err = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);

            Thread t1 = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 60; i++) {
                        final int idx = i;
                        tx.executeWithoutResult(status -> {
                            // 先 SELECT 再 INSERT/UPDATE：若非 BEGIN IMMEDIATE，在并发写入下极易触发 SQLITE_BUSY_SNAPSHOT
                            repo.findTrackByNormKey("song-" + idx);
                            Track t = new Track();
                            t.setNormKey("song-" + idx);
                            t.setTitle("Song " + idx);
                            t.setArtist("Artist " + idx);
                            t.setAlbum("");
                            t.setVersionTag("");
                            t.setDurationSec(200);
                            t.setQualityScore(700);
                            t.setStoredChatId(-1001L);
                            t.setStoredMsgId(1000L + idx);
                            t.setMimeType("audio/ogg");
                            t.setFileName("song-" + idx + ".ogg");
                            t.setStatus(Track.STATUS_READY);
                            repo.upsertTrackAndFts(t);
                        });
                    }
                } catch (Throwable ex) {
                    err.compareAndSet(null, ex);
                }
            });

            Thread t2 = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 120; i++) {
                        dev.voroby.telegram.music.model.SourceTrack st = new dev.voroby.telegram.music.model.SourceTrack();
                        st.setSourceChatId(-2001L);
                        st.setSourceMsgId(5000L + i);
                        st.setSentAt(1700000000L + i);
                        st.setRawTitle("Src " + i);
                        st.setRawArtist("Singer");
                        st.setNormKey("src-" + i);
                        st.setStatus(dev.voroby.telegram.music.model.SourceTrack.STATUS_NEW);
                        repo.insertSourceTrackIgnore(st);
                    }
                } catch (Throwable ex) {
                    err.compareAndSet(null, ex);
                }
            });

            t1.start();
            t2.start();
            start.countDown();
            t1.join(30_000);
            t2.join(30_000);

            if (err.get() != null) {
                fail("Concurrent SQLite writes failed with: " + err.get(), err.get());
            }
            assertEquals(60, jdbc.queryForObject("SELECT COUNT(*) FROM tracks", Integer.class));
            assertEquals(120, jdbc.queryForObject("SELECT COUNT(*) FROM source_tracks", Integer.class));
        }
    }
}
