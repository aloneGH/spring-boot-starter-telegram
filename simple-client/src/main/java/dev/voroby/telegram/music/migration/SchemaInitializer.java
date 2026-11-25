package dev.voroby.telegram.music.migration;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 幂等初始化新曲库表结构、索引与 FTS5 全文搜索虚拟表。
 * 绝不修改或删除旧表，保证老数据完整安全。
 */
@Component
public class SchemaInitializer {
    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void initialize() {
        log.info("Initializing SQLite v2 music schema...");
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("PRAGMA synchronous=NORMAL");
        jdbc.execute("PRAGMA busy_timeout=15000");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS playlists (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_chat_id  INTEGER UNIQUE,
                    legacy_chat_id  INTEGER,
                    title           TEXT NOT NULL,
                    folder_name     TEXT,
                    created_at      INTEGER NOT NULL,
                    updated_at      INTEGER NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_playlists_legacy_chat ON playlists(legacy_chat_id)");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS sync_cursors (
                    chat_id         INTEGER PRIMARY KEY,
                    title           TEXT,
                    newest_msg_id   INTEGER NOT NULL DEFAULT 0,
                    oldest_msg_id   INTEGER NOT NULL DEFAULT 0,
                    backfill_done   INTEGER NOT NULL DEFAULT 0,
                    last_sync_at    INTEGER
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS source_tracks (
                    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                    source_chat_id      INTEGER NOT NULL,
                    source_msg_id       INTEGER NOT NULL,
                    sent_at             INTEGER NOT NULL,
                    raw_title           TEXT,
                    raw_artist          TEXT,
                    raw_album           TEXT,
                    raw_filename        TEXT,
                    raw_caption         TEXT,
                    mime_type           TEXT,
                    file_size           INTEGER DEFAULT 0,
                    duration_sec        INTEGER DEFAULT 0,
                    est_quality_score   INTEGER NOT NULL DEFAULT 0,
                    norm_key            TEXT,
                    status              TEXT NOT NULL DEFAULT 'NEW',
                    retry_count         INTEGER NOT NULL DEFAULT 0,
                    next_retry_at       INTEGER,
                    last_error          TEXT,
                    track_id            INTEGER,
                    created_at          INTEGER NOT NULL,
                    updated_at          INTEGER NOT NULL,
                    UNIQUE(source_chat_id, source_msg_id)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_source_tracks_status ON source_tracks(status, next_retry_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_source_tracks_norm_key ON source_tracks(norm_key)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_source_tracks_track_id ON source_tracks(track_id)");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tracks (
                    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
                    norm_key            TEXT NOT NULL UNIQUE,
                    title               TEXT NOT NULL,
                    artist              TEXT NOT NULL DEFAULT '',
                    album               TEXT NOT NULL DEFAULT '',
                    version_tag         TEXT NOT NULL DEFAULT '',
                    duration_sec        INTEGER NOT NULL DEFAULT 0,
                    quality_score       INTEGER NOT NULL DEFAULT 0,
                    src_codec           TEXT,
                    src_bitrate         INTEGER DEFAULT 0,
                    src_sample_rate     INTEGER DEFAULT 0,
                    src_bit_depth       INTEGER DEFAULT 0,
                    stored_chat_id      INTEGER,
                    stored_msg_id       INTEGER,
                    stored_file_uid     TEXT,
                    stored_codec        TEXT,
                    stored_bitrate      INTEGER DEFAULT 0,
                    stored_size         INTEGER DEFAULT 0,
                    mime_type           TEXT NOT NULL DEFAULT 'audio/ogg',
                    file_name           TEXT NOT NULL DEFAULT '',
                    cover_path          TEXT,
                    best_source_id      INTEGER,
                    metadata_enriched   INTEGER NOT NULL DEFAULT 0,
                    loudness_lufs       REAL,
                    loudness_normalized INTEGER NOT NULL DEFAULT 0,
                    status              TEXT NOT NULL DEFAULT 'READY',
                    created_at          INTEGER NOT NULL,
                    updated_at          INTEGER NOT NULL
                )
                """);
        ensureColumnExists("tracks", "loudness_lufs", "REAL");
        ensureColumnExists("tracks", "loudness_normalized", "INTEGER NOT NULL DEFAULT 0");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tracks_stored ON tracks(stored_chat_id, stored_msg_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tracks_artist ON tracks(artist)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tracks_status ON tracks(status)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tracks_loudness ON tracks(status, loudness_normalized)");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS playlist_tracks (
                    playlist_id     INTEGER NOT NULL,
                    track_id        INTEGER NOT NULL,
                    added_at        INTEGER NOT NULL,
                    PRIMARY KEY (playlist_id, track_id)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_playlist_tracks_track ON playlist_tracks(track_id)");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS track_lyrics (
                    track_id            INTEGER PRIMARY KEY,
                    source              TEXT,
                    lrc                 TEXT,
                    tlrc                TEXT,
                    matched_duration_ms INTEGER,
                    match_score         REAL,
                    status              TEXT NOT NULL DEFAULT 'PENDING',
                    retry_count         INTEGER NOT NULL DEFAULT 0,
                    next_retry_at       INTEGER,
                    updated_at          INTEGER NOT NULL
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_track_lyrics_status ON track_lyrics(status, next_retry_at)");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS pending_uploads (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id         INTEGER NOT NULL,
                    temp_msg_id     INTEGER NOT NULL,
                    source_track_id INTEGER NOT NULL,
                    target_track_id INTEGER,
                    local_file_path TEXT,
                    payload_json    TEXT NOT NULL,
                    created_at      INTEGER NOT NULL,
                    UNIQUE(chat_id, temp_msg_id)
                )
                """);

        jdbc.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS tracks_fts USING fts5(
                    title_norm,
                    artist_norm,
                    album_norm,
                    filename_norm,
                    tokenize='unicode61 remove_diacritics 2'
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS schema_migration (
                    name        TEXT PRIMARY KEY,
                    applied_at  INTEGER NOT NULL,
                    summary     TEXT
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS kv_config (
                    k           TEXT PRIMARY KEY,
                    v           TEXT NOT NULL,
                    updated_at  INTEGER NOT NULL
                )
                """);

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS legacy_message_aliases (
                    chat_id     INTEGER NOT NULL,
                    message_id  INTEGER NOT NULL,
                    track_id    INTEGER NOT NULL,
                    PRIMARY KEY (chat_id, message_id)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_legacy_aliases_track ON legacy_message_aliases(track_id)");

        log.info("SQLite v2 music schema ready.");
    }

    private void ensureColumnExists(String table, String column, String columnDef) {
        var cols = jdbc.queryForList("PRAGMA table_info(" + table + ")");
        for (var row : cols) {
            if (column.equalsIgnoreCase(String.valueOf(row.get("name")))) {
                return;
            }
        }
        log.info("Adding column {}.{} ({})", table, column, columnDef);
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + columnDef);
    }
}
