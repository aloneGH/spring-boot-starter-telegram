package dev.voroby.telegram.music.repository;

import dev.voroby.telegram.music.model.*;
import dev.voroby.telegram.music.service.TrackNormalizationService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

@Repository
public class MusicCatalogRepository {

    private final JdbcTemplate jdbc;
    private final TrackNormalizationService normService;

    public MusicCatalogRepository(JdbcTemplate jdbc, TrackNormalizationService normService) {
        this.jdbc = jdbc;
        this.normService = normService;
    }

    private static final RowMapper<Playlist> PLAYLIST_MAPPER = (rs, rowNum) -> new Playlist(
            rs.getLong("id"),
            getNullableLong(rs, "source_chat_id"),
            getNullableLong(rs, "legacy_chat_id"),
            rs.getString("title"),
            rs.getString("folder_name"),
            rs.getLong("created_at"),
            rs.getLong("updated_at")
    );

    private static final RowMapper<SyncCursor> CURSOR_MAPPER = (rs, rowNum) -> new SyncCursor(
            rs.getLong("chat_id"),
            rs.getString("title"),
            rs.getLong("newest_msg_id"),
            rs.getLong("oldest_msg_id"),
            rs.getInt("backfill_done") == 1,
            getNullableLong(rs, "last_sync_at")
    );

    private static final RowMapper<SourceTrack> SOURCE_TRACK_MAPPER = (rs, rowNum) -> {
        SourceTrack s = new SourceTrack();
        s.setId(rs.getLong("id"));
        s.setSourceChatId(rs.getLong("source_chat_id"));
        s.setSourceMsgId(rs.getLong("source_msg_id"));
        s.setSentAt(rs.getLong("sent_at"));
        s.setRawTitle(rs.getString("raw_title"));
        s.setRawArtist(rs.getString("raw_artist"));
        s.setRawAlbum(rs.getString("raw_album"));
        s.setRawFilename(rs.getString("raw_filename"));
        s.setRawCaption(rs.getString("raw_caption"));
        s.setMimeType(rs.getString("mime_type"));
        s.setFileSize(rs.getLong("file_size"));
        s.setDurationSec(rs.getInt("duration_sec"));
        s.setEstQualityScore(rs.getInt("est_quality_score"));
        s.setNormKey(rs.getString("norm_key"));
        s.setStatus(rs.getString("status"));
        s.setRetryCount(rs.getInt("retry_count"));
        s.setNextRetryAt(getNullableLong(rs, "next_retry_at"));
        s.setLastError(rs.getString("last_error"));
        s.setTrackId(getNullableLong(rs, "track_id"));
        s.setCreatedAt(rs.getLong("created_at"));
        s.setUpdatedAt(rs.getLong("updated_at"));
        return s;
    };

    private static final RowMapper<Track> TRACK_MAPPER = (rs, rowNum) -> {
        Track t = new Track();
        t.setId(rs.getLong("id"));
        t.setNormKey(rs.getString("norm_key"));
        t.setTitle(rs.getString("title"));
        t.setArtist(rs.getString("artist"));
        t.setAlbum(rs.getString("album"));
        t.setVersionTag(rs.getString("version_tag"));
        t.setDurationSec(rs.getInt("duration_sec"));
        t.setQualityScore(rs.getInt("quality_score"));
        t.setSrcCodec(rs.getString("src_codec"));
        t.setSrcBitrate(rs.getInt("src_bitrate"));
        t.setSrcSampleRate(rs.getInt("src_sample_rate"));
        t.setSrcBitDepth(rs.getInt("src_bit_depth"));
        t.setStoredChatId(getNullableLong(rs, "stored_chat_id"));
        t.setStoredMsgId(getNullableLong(rs, "stored_msg_id"));
        t.setStoredFileUid(rs.getString("stored_file_uid"));
        t.setStoredCodec(rs.getString("stored_codec"));
        t.setStoredBitrate(rs.getInt("stored_bitrate"));
        t.setStoredSize(rs.getLong("stored_size"));
        t.setMimeType(rs.getString("mime_type"));
        t.setFileName(rs.getString("file_name"));
        t.setCoverPath(rs.getString("cover_path"));
        t.setBestSourceId(getNullableLong(rs, "best_source_id"));
        t.setMetadataEnriched(rs.getInt("metadata_enriched") == 1);
        double lufs = rs.getDouble("loudness_lufs");
        t.setLoudnessLufs(rs.wasNull() ? null : lufs);
        t.setLoudnessNormalized(rs.getInt("loudness_normalized") == 1);
        t.setStatus(rs.getString("status"));
        t.setCreatedAt(rs.getLong("created_at"));
        t.setUpdatedAt(rs.getLong("updated_at"));
        return t;
    };

    private static final RowMapper<PendingUpload> PENDING_UPLOAD_MAPPER = (rs, rowNum) -> new PendingUpload(
            rs.getLong("id"),
            rs.getLong("chat_id"),
            rs.getLong("temp_msg_id"),
            rs.getLong("source_track_id"),
            getNullableLong(rs, "target_track_id"),
            rs.getString("local_file_path"),
            rs.getString("payload_json"),
            rs.getLong("created_at")
    );

    private static Long getNullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    // ======================== KV Config ========================

    public Optional<String> getConfig(String key) {
        List<String> rows = jdbc.query("SELECT v FROM kv_config WHERE k = ?", (rs, i) -> rs.getString(1), key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void putConfig(String key, String value) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO kv_config(k, v, updated_at) VALUES(?, ?, ?)
                ON CONFLICT(k) DO UPDATE SET v = excluded.v, updated_at = excluded.updated_at
                """, key, value, now);
    }

    // ======================== Playlists ========================

    public List<Playlist> findAllPlaylists() {
        return jdbc.query("SELECT * FROM playlists ORDER BY id ASC", PLAYLIST_MAPPER);
    }

    public Optional<Playlist> findPlaylistByAnyId(long fid) {
        List<Playlist> list = jdbc.query(
                "SELECT * FROM playlists WHERE id = ? OR source_chat_id = ? OR legacy_chat_id = ? LIMIT 1",
                PLAYLIST_MAPPER, fid, fid, fid);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public long upsertPlaylist(Long sourceChatId, Long legacyChatId, String title, String folderName) {
        long now = System.currentTimeMillis();
        if (sourceChatId != null) {
            List<Long> existing = jdbc.query("SELECT id FROM playlists WHERE source_chat_id = ?", (rs, i) -> rs.getLong(1), sourceChatId);
            if (!existing.isEmpty()) {
                long id = existing.get(0);
                jdbc.update("""
                        UPDATE playlists
                        SET title = ?, folder_name = COALESCE(?, folder_name),
                            legacy_chat_id = COALESCE(?, legacy_chat_id), updated_at = ?
                        WHERE id = ?
                        """, title, folderName, legacyChatId, now, id);
                return id;
            }
        }
        if (legacyChatId != null) {
            List<Long> existing = jdbc.query("SELECT id FROM playlists WHERE legacy_chat_id = ?", (rs, i) -> rs.getLong(1), legacyChatId);
            if (!existing.isEmpty()) {
                long id = existing.get(0);
                jdbc.update("""
                        UPDATE playlists
                        SET source_chat_id = COALESCE(?, source_chat_id),
                            title = ?, folder_name = COALESCE(?, folder_name), updated_at = ?
                        WHERE id = ?
                        """, sourceChatId, title, folderName, now, id);
                return id;
            }
        }
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO playlists(source_chat_id, legacy_chat_id, title, folder_name, created_at, updated_at) VALUES(?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            if (sourceChatId != null) ps.setLong(1, sourceChatId); else ps.setNull(1, java.sql.Types.BIGINT);
            if (legacyChatId != null) ps.setLong(2, legacyChatId); else ps.setNull(2, java.sql.Types.BIGINT);
            ps.setString(3, title);
            ps.setString(4, folderName);
            ps.setLong(5, now);
            ps.setLong(6, now);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key != null ? key.longValue() : 0L;
    }

    public void linkTrackToPlaylist(long playlistId, long trackId) {
        jdbc.update("INSERT OR IGNORE INTO playlist_tracks(playlist_id, track_id, added_at) VALUES(?, ?, ?)",
                playlistId, trackId, System.currentTimeMillis());
    }

    public void linkTrackToPlaylistBySourceChat(long sourceChatId, long trackId) {
        List<Long> ids = jdbc.query("SELECT id FROM playlists WHERE source_chat_id = ? LIMIT 1",
                (rs, i) -> rs.getLong(1), sourceChatId);
        if (!ids.isEmpty()) {
            linkTrackToPlaylist(ids.get(0), trackId);
        }
    }

    // ======================== Sync Cursors ========================

    public SyncCursor getOrCreateCursor(long chatId, String title) {
        List<SyncCursor> list = jdbc.query("SELECT * FROM sync_cursors WHERE chat_id = ?", CURSOR_MAPPER, chatId);
        if (!list.isEmpty()) {
            return list.get(0);
        }
        jdbc.update("INSERT OR IGNORE INTO sync_cursors(chat_id, title, newest_msg_id, oldest_msg_id, backfill_done) VALUES(?, ?, 0, 0, 0)",
                chatId, title);
        return new SyncCursor(chatId, title, 0L, 0L, false, null);
    }

    public void updateCursor(long chatId, String title, long newestMsgId, long oldestMsgId, boolean backfillDone) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO sync_cursors(chat_id, title, newest_msg_id, oldest_msg_id, backfill_done, last_sync_at)
                VALUES(?, ?, ?, ?, ?, ?)
                ON CONFLICT(chat_id) DO UPDATE SET
                    title = excluded.title,
                    newest_msg_id = CASE WHEN excluded.newest_msg_id > sync_cursors.newest_msg_id THEN excluded.newest_msg_id ELSE sync_cursors.newest_msg_id END,
                    oldest_msg_id = CASE
                        WHEN sync_cursors.oldest_msg_id = 0 THEN excluded.oldest_msg_id
                        WHEN excluded.oldest_msg_id > 0 AND excluded.oldest_msg_id < sync_cursors.oldest_msg_id THEN excluded.oldest_msg_id
                        ELSE sync_cursors.oldest_msg_id
                    END,
                    backfill_done = CASE WHEN excluded.backfill_done = 1 THEN 1 ELSE sync_cursors.backfill_done END,
                    last_sync_at = excluded.last_sync_at
                """, chatId, title, newestMsgId, oldestMsgId, backfillDone ? 1 : 0, now);
    }

    // ======================== Source Tracks ========================

    public boolean insertSourceTrackIgnore(SourceTrack st) {
        long now = System.currentTimeMillis();
        int rows = jdbc.update("""
                INSERT OR IGNORE INTO source_tracks(
                    source_chat_id, source_msg_id, sent_at, raw_title, raw_artist, raw_album,
                    raw_filename, raw_caption, mime_type, file_size, duration_sec,
                    est_quality_score, norm_key, status, retry_count, next_retry_at, last_error, track_id,
                    created_at, updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                st.getSourceChatId(), st.getSourceMsgId(), st.getSentAt(),
                st.getRawTitle(), st.getRawArtist(), st.getRawAlbum(),
                st.getRawFilename(), st.getRawCaption(), st.getMimeType(),
                st.getFileSize(), st.getDurationSec(), st.getEstQualityScore(),
                st.getNormKey(),
                st.getStatus() != null ? st.getStatus() : SourceTrack.STATUS_NEW,
                st.getRetryCount(), st.getNextRetryAt(), st.getLastError(), st.getTrackId(),
                now, now);
        return rows > 0;
    }

    public List<SourceTrack> findPendingSourceTracks(int limit) {
        long now = System.currentTimeMillis();
        return jdbc.query("""
                SELECT * FROM source_tracks
                WHERE status = 'NEW'
                   OR (status = 'FAILED' AND retry_count < 5 AND (next_retry_at IS NULL OR next_retry_at <= ?))
                ORDER BY est_quality_score DESC, id ASC
                LIMIT ?
                """, SOURCE_TRACK_MAPPER, now, limit);
    }

    public Optional<SourceTrack> findSourceTrackById(long id) {
        List<SourceTrack> list = jdbc.query("SELECT * FROM source_tracks WHERE id = ?", SOURCE_TRACK_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void updateSourceTrackStatus(long id, String status, Long trackId, int retryCount, Long nextRetryAt, String lastError) {
        jdbc.update("""
                UPDATE source_tracks
                SET status = ?, track_id = COALESCE(?, track_id), retry_count = ?, next_retry_at = ?, last_error = ?, updated_at = ?
                WHERE id = ?
                """, status, trackId, retryCount, nextRetryAt, lastError, System.currentTimeMillis(), id);
    }

    public int reactivateAlternateSourcesForNormKey(String normKey, Long excludeSourceId) {
        long now = System.currentTimeMillis();
        if (excludeSourceId != null) {
            return jdbc.update("""
                    UPDATE source_tracks
                    SET status = 'NEW', retry_count = 0, next_retry_at = NULL, updated_at = ?
                    WHERE norm_key = ? AND id != ? AND status IN ('SKIPPED_DUP', 'FAILED')
                    """, now, normKey, excludeSourceId);
        }
        return jdbc.update("""
                UPDATE source_tracks
                SET status = 'NEW', retry_count = 0, next_retry_at = NULL, updated_at = ?
                WHERE norm_key = ? AND status IN ('SKIPPED_DUP', 'FAILED')
                """, now, normKey);
    }

    // ======================== Tracks & FTS5 ========================

    public Optional<Track> findTrackById(long id) {
        List<Track> list = jdbc.query("SELECT * FROM tracks WHERE id = ?", TRACK_MAPPER, id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<Track> findTrackByNormKey(String normKey) {
        List<Track> list = jdbc.query("SELECT * FROM tracks WHERE norm_key = ?", TRACK_MAPPER, normKey);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public Optional<Track> findTrackByStoredMessage(long chatId, long msgId) {
        List<Track> list = jdbc.query("SELECT * FROM tracks WHERE stored_chat_id = ? AND stored_msg_id = ? LIMIT 1",
                TRACK_MAPPER, chatId, msgId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void upsertLegacyAlias(long chatId, long msgId, long trackId) {
        if (chatId == 0 || msgId == 0 || trackId <= 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO legacy_message_aliases(chat_id, message_id, track_id) VALUES(?, ?, ?)
                ON CONFLICT(chat_id, message_id) DO UPDATE SET track_id = excluded.track_id
                """, chatId, msgId, trackId);
    }

    /**
     * 同时兼容新接口 (trackId)、旧存量主消息 (storedChatId, storedMsgId) 以及被去重合并的旧收藏夹别名 (legacy_message_aliases)。
     */
    public Optional<Track> resolveTrackForApi(long idOrMsgId, Long fidOrChatId) {
        Optional<Track> byId = findTrackById(idOrMsgId);
        if (byId.isPresent()) {
            return byId;
        }
        if (fidOrChatId != null && fidOrChatId != 0) {
            Optional<Track> byStored = findTrackByStoredMessage(fidOrChatId, idOrMsgId);
            if (byStored.isPresent()) {
                return byStored;
            }
            List<Long> aliasTrackIds = jdbc.query(
                    "SELECT track_id FROM legacy_message_aliases WHERE chat_id = ? AND message_id = ? LIMIT 1",
                    (rs, i) -> rs.getLong(1), fidOrChatId, idOrMsgId);
            if (!aliasTrackIds.isEmpty()) {
                return findTrackById(aliasTrackIds.get(0));
            }
        }
        return Optional.empty();
    }

    @Transactional
    public long upsertTrackAndFts(Track track) {
        long now = System.currentTimeMillis();
        Optional<Track> existingOpt = findTrackByNormKey(track.getNormKey());
        long trackId;
        if (existingOpt.isPresent()) {
            trackId = existingOpt.get().getId();
            track.setId(trackId);
            jdbc.update("""
                    UPDATE tracks SET
                        title = ?, artist = ?, album = ?, version_tag = ?, duration_sec = ?,
                        quality_score = ?, src_codec = ?, src_bitrate = ?, src_sample_rate = ?, src_bit_depth = ?,
                        stored_chat_id = ?, stored_msg_id = ?, stored_file_uid = ?,
                        stored_codec = ?, stored_bitrate = ?, stored_size = ?,
                        mime_type = ?, file_name = ?, cover_path = COALESCE(?, cover_path),
                        best_source_id = COALESCE(?, best_source_id),
                        metadata_enriched = ?, loudness_lufs = COALESCE(?, loudness_lufs),
                        loudness_normalized = ?, status = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    track.getTitle(), StringUtils.defaultString(track.getArtist()), StringUtils.defaultString(track.getAlbum()),
                    StringUtils.defaultString(track.getVersionTag()), track.getDurationSec(),
                    track.getQualityScore(), track.getSrcCodec(), track.getSrcBitrate(),
                    track.getSrcSampleRate(), track.getSrcBitDepth(),
                    track.getStoredChatId(), track.getStoredMsgId(), track.getStoredFileUid(),
                    track.getStoredCodec(), track.getStoredBitrate(), track.getStoredSize(),
                    track.getMimeType(), StringUtils.defaultString(track.getFileName()), track.getCoverPath(),
                    track.getBestSourceId(), track.isMetadataEnriched() ? 1 : 0,
                    track.getLoudnessLufs(), track.isLoudnessNormalized() ? 1 : 0,
                    track.getStatus(), now, trackId);
        } else {
            KeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO tracks(
                            norm_key, title, artist, album, version_tag, duration_sec,
                            quality_score, src_codec, src_bitrate, src_sample_rate, src_bit_depth,
                            stored_chat_id, stored_msg_id, stored_file_uid,
                            stored_codec, stored_bitrate, stored_size,
                            mime_type, file_name, cover_path, best_source_id,
                            metadata_enriched, loudness_lufs, loudness_normalized, status, created_at, updated_at
                        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, track.getNormKey());
                ps.setString(2, track.getTitle());
                ps.setString(3, StringUtils.defaultString(track.getArtist()));
                ps.setString(4, StringUtils.defaultString(track.getAlbum()));
                ps.setString(5, StringUtils.defaultString(track.getVersionTag()));
                ps.setInt(6, track.getDurationSec());
                ps.setInt(7, track.getQualityScore());
                ps.setString(8, track.getSrcCodec());
                ps.setInt(9, track.getSrcBitrate());
                ps.setInt(10, track.getSrcSampleRate());
                ps.setInt(11, track.getSrcBitDepth());
                if (track.getStoredChatId() != null) ps.setLong(12, track.getStoredChatId()); else ps.setNull(12, java.sql.Types.BIGINT);
                if (track.getStoredMsgId() != null) ps.setLong(13, track.getStoredMsgId()); else ps.setNull(13, java.sql.Types.BIGINT);
                ps.setString(14, track.getStoredFileUid());
                ps.setString(15, track.getStoredCodec());
                ps.setInt(16, track.getStoredBitrate());
                ps.setLong(17, track.getStoredSize());
                ps.setString(18, StringUtils.defaultIfBlank(track.getMimeType(), "audio/ogg"));
                ps.setString(19, StringUtils.defaultString(track.getFileName()));
                ps.setString(20, track.getCoverPath());
                if (track.getBestSourceId() != null) ps.setLong(21, track.getBestSourceId()); else ps.setNull(21, java.sql.Types.BIGINT);
                ps.setInt(22, track.isMetadataEnriched() ? 1 : 0);
                if (track.getLoudnessLufs() != null) ps.setDouble(23, track.getLoudnessLufs()); else ps.setNull(23, java.sql.Types.REAL);
                ps.setInt(24, track.isLoudnessNormalized() ? 1 : 0);
                ps.setString(25, StringUtils.defaultIfBlank(track.getStatus(), Track.STATUS_READY));
                ps.setLong(26, now);
                ps.setLong(27, now);
                return ps;
            }, kh);
            Number key = kh.getKey();
            trackId = key != null ? key.longValue() : 0L;
            track.setId(trackId);
        }
        syncFtsRow(trackId, track.getTitle(), track.getArtist(), track.getAlbum(), track.getFileName());
        return trackId;
    }

    public void syncFtsRow(long trackId, String title, String artist, String album, String fileName) {
        jdbc.update("DELETE FROM tracks_fts WHERE rowid = ?", trackId);
        jdbc.update("INSERT INTO tracks_fts(rowid, title_norm, artist_norm, album_norm, filename_norm) VALUES(?, ?, ?, ?, ?)",
                trackId,
                normService.normalizeForIndex(title),
                normService.normalizeForIndex(artist),
                normService.normalizeForIndex(album),
                normService.normalizeForIndex(fileName));
    }

    public void markTrackStatus(long trackId, String status) {
        jdbc.update("UPDATE tracks SET status = ?, updated_at = ? WHERE id = ?",
                status, System.currentTimeMillis(), trackId);
    }

    public void updateTrackEnrichedMetadata(long trackId, String title, String artist, String album,
                                            int durationSec, String coverPath, String codec, int bitrate,
                                            int sampleRate, int bitDepth, int qualityScore) {
        jdbc.update("""
                UPDATE tracks SET
                    title = CASE WHEN ? != '' THEN ? ELSE title END,
                    artist = CASE WHEN ? != '' THEN ? ELSE artist END,
                    album = CASE WHEN ? != '' THEN ? ELSE album END,
                    duration_sec = CASE WHEN ? > 0 THEN ? ELSE duration_sec END,
                    cover_path = COALESCE(?, cover_path),
                    src_codec = COALESCE(?, src_codec),
                    src_bitrate = CASE WHEN ? > 0 THEN ? ELSE src_bitrate END,
                    src_sample_rate = CASE WHEN ? > 0 THEN ? ELSE src_sample_rate END,
                    src_bit_depth = CASE WHEN ? > 0 THEN ? ELSE src_bit_depth END,
                    quality_score = CASE WHEN ? > quality_score THEN ? ELSE quality_score END,
                    metadata_enriched = 1,
                    updated_at = ?
                WHERE id = ?
                """,
                StringUtils.defaultString(title), StringUtils.defaultString(title),
                StringUtils.defaultString(artist), StringUtils.defaultString(artist),
                StringUtils.defaultString(album), StringUtils.defaultString(album),
                durationSec, durationSec,
                coverPath, codec,
                bitrate, bitrate,
                sampleRate, sampleRate,
                bitDepth, bitDepth,
                qualityScore, qualityScore,
                System.currentTimeMillis(), trackId);
        findTrackById(trackId).ifPresent(t -> syncFtsRow(trackId, t.getTitle(), t.getArtist(), t.getAlbum(), t.getFileName()));
    }

    // ======================== Paginated Queries for MusicFree API ========================

    public PageResult<Track> findReadyTracksByPlaylist(long playlistId, int pageZeroBased, int pageSize) {
        int offset = pageZeroBased * pageSize;
        List<Track> rows = jdbc.query("""
                SELECT t.* FROM tracks t
                INNER JOIN playlist_tracks pt ON pt.track_id = t.id
                WHERE pt.playlist_id = ? AND t.status = 'READY'
                ORDER BY pt.added_at DESC, t.id DESC
                LIMIT ? OFFSET ?
                """, TRACK_MAPPER, playlistId, pageSize + 1, offset);
        return toPageResult(rows, pageSize);
    }

    public PageResult<Track> findReadyTracksByArtist(String artist, int pageZeroBased, int pageSize) {
        int offset = pageZeroBased * pageSize;
        String simp = normService.normalizeForIndex(artist);
        List<Track> rows = jdbc.query("""
                SELECT t.* FROM tracks t
                WHERE t.status = 'READY' AND (LOWER(t.artist) = LOWER(?) OR t.artist LIKE ?)
                ORDER BY t.quality_score DESC, t.id DESC
                LIMIT ? OFFSET ?
                """, TRACK_MAPPER, artist.trim(), "%" + (simp.isEmpty() ? artist.trim() : simp) + "%", pageSize + 1, offset);
        return toPageResult(rows, pageSize);
    }

    /**
     * FTS5 多词+繁简混合搜索（歌名/歌手/专辑/文件名），无命中时自动降级 LIKE 搜索。
     */
    public PageResult<Track> searchReadyTracks(String rawQuery, int pageZeroBased, int pageSize) {
        int offset = pageZeroBased * pageSize;
        String normQ = normService.normalizeForIndex(rawQuery);
        if (normQ.isEmpty()) {
            return new PageResult<>(false, List.of());
        }
        String[] tokens = normQ.split("\\s+");
        StringBuilder ftsExpr = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            String tok = tokens[i].replace("\"", "");
            if (tok.isEmpty()) continue;
            if (!ftsExpr.isEmpty()) ftsExpr.append(" AND ");
            ftsExpr.append('"').append(tok).append('"').append('*');
        }

        List<Track> rows = List.of();
        if (!ftsExpr.isEmpty()) {
            try {
                rows = jdbc.query("""
                        SELECT t.* FROM tracks_fts f
                        INNER JOIN tracks t ON t.id = f.rowid
                        WHERE tracks_fts MATCH ? AND t.status = 'READY'
                        ORDER BY bm25(tracks_fts, 4.0, 2.5, 1.0, 0.5), t.quality_score DESC
                        LIMIT ? OFFSET ?
                        """, TRACK_MAPPER, ftsExpr.toString(), pageSize + 1, offset);
            } catch (Exception ignored) {
                // FTS 语法异常时安全降级到 LIKE
            }
        }
        if (rows.isEmpty()) {
            String like = "%" + normQ + "%";
            String rawLike = "%" + rawQuery.trim() + "%";
            rows = jdbc.query("""
                    SELECT t.* FROM tracks t
                    LEFT JOIN tracks_fts f ON f.rowid = t.id
                    WHERE t.status = 'READY'
                      AND (f.title_norm LIKE ? OR f.artist_norm LIKE ? OR f.album_norm LIKE ? OR f.filename_norm LIKE ?
                           OR t.title LIKE ? OR t.artist LIKE ?)
                    ORDER BY t.quality_score DESC, t.id DESC
                    LIMIT ? OFFSET ?
                    """, TRACK_MAPPER, like, like, like, like, rawLike, rawLike, pageSize + 1, offset);
        }
        return toPageResult(rows, pageSize);
    }

    public PageResult<String> searchArtists(String rawQuery, int pageZeroBased, int pageSize) {
        int offset = pageZeroBased * pageSize;
        String normQ = normService.normalizeForIndex(rawQuery);
        String like = "%" + (normQ.isEmpty() ? rawQuery.trim() : normQ) + "%";
        String rawLike = "%" + rawQuery.trim() + "%";
        List<String> rows = jdbc.query("""
                SELECT DISTINCT t.artist FROM tracks t
                LEFT JOIN tracks_fts f ON f.rowid = t.id
                WHERE t.status = 'READY' AND t.artist != ''
                  AND (f.artist_norm LIKE ? OR t.artist LIKE ?)
                ORDER BY t.artist ASC
                LIMIT ? OFFSET ?
                """, (rs, i) -> rs.getString(1), like, rawLike, pageSize + 1, offset);
        return toPageResult(rows, pageSize);
    }

    // ======================== Pending Uploads ========================

    public void savePendingUpload(long chatId, long tempMsgId, long sourceTrackId, Long targetTrackId,
                                  String localFilePath, String payloadJson) {
        jdbc.update("""
                INSERT INTO pending_uploads(chat_id, temp_msg_id, source_track_id, target_track_id, local_file_path, payload_json, created_at)
                VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(chat_id, temp_msg_id) DO UPDATE SET
                    local_file_path = excluded.local_file_path,
                    payload_json = excluded.payload_json
                """, chatId, tempMsgId, sourceTrackId, targetTrackId, localFilePath, payloadJson, System.currentTimeMillis());
    }

    public Optional<PendingUpload> findPendingUpload(long chatId, long tempMsgId) {
        List<PendingUpload> list = jdbc.query(
                "SELECT * FROM pending_uploads WHERE chat_id = ? AND temp_msg_id = ?",
                PENDING_UPLOAD_MAPPER, chatId, tempMsgId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void deletePendingUpload(long chatId, long tempMsgId) {
        jdbc.update("DELETE FROM pending_uploads WHERE chat_id = ? AND temp_msg_id = ?", chatId, tempMsgId);
    }

    public List<PendingUpload> findStalePendingUploads(long olderThanEpochMs) {
        return jdbc.query("SELECT * FROM pending_uploads WHERE created_at < ? ORDER BY id ASC LIMIT 50",
                PENDING_UPLOAD_MAPPER, olderThanEpochMs);
    }

    public int resetStaleProcessingSources(long olderThanEpochMs) {
        long now = System.currentTimeMillis();
        return jdbc.update("""
                UPDATE source_tracks
                SET status = 'NEW', updated_at = ?
                WHERE status = 'PROCESSING' AND updated_at <= ?
                """, now, olderThanEpochMs);
    }

    public PageResult<Track> findReadyTracksLatest(int pageZeroBased, int pageSize) {
        int offset = pageZeroBased * pageSize;
        List<Track> rows = jdbc.query("""
                SELECT * FROM tracks
                WHERE status = 'READY'
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, TRACK_MAPPER, pageSize + 1, offset);
        return toPageResult(rows, pageSize);
    }

    public PageResult<Track> findReadyTracksHighQuality(int pageZeroBased, int pageSize) {
        int offset = pageZeroBased * pageSize;
        List<Track> rows = jdbc.query("""
                SELECT * FROM tracks
                WHERE status = 'READY' AND quality_score >= 900
                ORDER BY quality_score DESC, id DESC
                LIMIT ? OFFSET ?
                """, TRACK_MAPPER, pageSize + 1, offset);
        return toPageResult(rows, pageSize);
    }

    public List<Track> findTracksNeedingLoudnessNormalization(int limit) {
        return jdbc.query("""
                SELECT * FROM tracks
                WHERE status = 'READY'
                  AND loudness_normalized = 0
                  AND stored_chat_id IS NOT NULL
                  AND stored_msg_id IS NOT NULL
                ORDER BY id DESC
                LIMIT ?
                """, TRACK_MAPPER, limit);
    }

    public void markTrackLoudnessDone(long trackId, Double loudnessLufs) {
        jdbc.update("""
                UPDATE tracks
                SET loudness_lufs = COALESCE(?, loudness_lufs),
                    loudness_normalized = 1,
                    updated_at = ?
                WHERE id = ?
                """, loudnessLufs, System.currentTimeMillis(), trackId);
    }

    public void updateTrackAfterLoudnessReupload(long trackId, long newChatId, long newMsgId, String newFileUid,
                                                 String storedCodec, int storedBitrate, long storedSize,
                                                 String mimeType, String fileName, Double loudnessLufs) {
        jdbc.update("""
                UPDATE tracks
                SET stored_chat_id = ?, stored_msg_id = ?, stored_file_uid = ?,
                    stored_codec = ?, stored_bitrate = ?, stored_size = ?,
                    mime_type = ?, file_name = CASE WHEN ? != '' THEN ? ELSE file_name END,
                    loudness_lufs = ?, loudness_normalized = 1, updated_at = ?
                WHERE id = ?
                """,
                newChatId, newMsgId, newFileUid,
                storedCodec, storedBitrate, storedSize,
                mimeType, StringUtils.defaultString(fileName), StringUtils.defaultString(fileName),
                loudnessLufs, System.currentTimeMillis(), trackId);
    }

    public long[] countLoudnessProgress() {
        long total = queryCount("SELECT COUNT(*) FROM tracks WHERE status = 'READY' AND stored_chat_id IS NOT NULL AND stored_msg_id IS NOT NULL");
        long done = queryCount("SELECT COUNT(*) FROM tracks WHERE status = 'READY' AND loudness_normalized = 1 AND stored_chat_id IS NOT NULL AND stored_msg_id IS NOT NULL");
        return new long[]{done, total};
    }

    public Map<String, Long> countSourceTracksByStatus() {
        return countGroupBy("SELECT status, COUNT(*) FROM source_tracks GROUP BY status");
    }

    public long[] countLyricProgress() {
        long total = queryCount("SELECT COUNT(*) FROM tracks WHERE status = 'READY' AND title != ''");
        long found = queryCount("""
                SELECT COUNT(*) FROM tracks t
                INNER JOIN track_lyrics l ON l.track_id = t.id
                WHERE t.status = 'READY' AND l.status = 'FOUND'
                """);
        long notFound = queryCount("""
                SELECT COUNT(*) FROM tracks t
                INNER JOIN track_lyrics l ON l.track_id = t.id
                WHERE t.status = 'READY' AND l.status = 'NOT_FOUND'
                """);
        long retry = queryCount("""
                SELECT COUNT(*) FROM tracks t
                INNER JOIN track_lyrics l ON l.track_id = t.id
                WHERE t.status = 'READY' AND l.status = 'RETRY'
                """);
        return new long[]{found, notFound, retry, total};
    }

    public long[] countBackfillProgress() {
        long total = queryCount("SELECT COUNT(*) FROM sync_cursors");
        long done = queryCount("SELECT COUNT(*) FROM sync_cursors WHERE backfill_done = 1");
        long sources = queryCount("SELECT COUNT(*) FROM source_tracks");
        return new long[]{done, total, sources};
    }

    /**
     * 定期执行轻量 WAL 检查点与查询计划优化，防止长期后台跑批导致 sqlite.db-wal 膨胀。
     */
    public void runPeriodicSqliteMaintenance() {
        jdbc.query("PRAGMA wal_checkpoint(PASSIVE)", rs -> {
            // 消费结果集确保执行完成
        });
        jdbc.execute("PRAGMA optimize");
    }

    public Map<String, Object> queryCatalogStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("tracksByStatus", countGroupBy("SELECT status, COUNT(*) FROM tracks GROUP BY status"));
        stats.put("tracksEnriched", queryCount("SELECT COUNT(*) FROM tracks WHERE status = 'READY' AND metadata_enriched = 1"));
        stats.put("tracksWithCover", queryCount("SELECT COUNT(*) FROM tracks WHERE status = 'READY' AND cover_path IS NOT NULL AND cover_path != ''"));
        stats.put("tracksHighQuality", queryCount("SELECT COUNT(*) FROM tracks WHERE status = 'READY' AND quality_score >= 900"));
        stats.put("tracksLoudnessNormalized", queryCount("SELECT COUNT(*) FROM tracks WHERE status = 'READY' AND loudness_normalized = 1"));
        stats.put("playlists", queryCount("SELECT COUNT(*) FROM playlists"));
        stats.put("sourceTracksByStatus", countGroupBy("SELECT status, COUNT(*) FROM source_tracks GROUP BY status"));
        stats.put("lyricsByStatus", countGroupBy("SELECT status, COUNT(*) FROM track_lyrics GROUP BY status"));
        stats.put("pendingUploads", queryCount("SELECT COUNT(*) FROM pending_uploads"));
        stats.put("legacyAliases", queryCount("SELECT COUNT(*) FROM legacy_message_aliases"));
        return stats;
    }

    private long queryCount(String sql) {
        Long cnt = jdbc.queryForObject(sql, Long.class);
        return cnt != null ? cnt : 0L;
    }

    private Map<String, Long> countGroupBy(String sql) {
        Map<String, Long> map = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            String key = rs.getString(1);
            map.put(key != null ? key : "UNKNOWN", rs.getLong(2));
        });
        return map;
    }

    public List<Track> findTracksNeedingLyrics(int limit) {
        long now = System.currentTimeMillis();
        return jdbc.query("""
                SELECT t.* FROM tracks t
                LEFT JOIN track_lyrics l ON l.track_id = t.id
                WHERE t.status = 'READY'
                  AND t.title != ''
                  AND (
                        l.track_id IS NULL
                     OR l.status = 'PENDING'
                     OR (l.status = 'RETRY' AND l.retry_count < 6 AND (l.next_retry_at IS NULL OR l.next_retry_at <= ?))
                     OR (l.status = 'NOT_FOUND' AND l.next_retry_at IS NOT NULL AND l.next_retry_at <= ?)
                  )
                ORDER BY CASE WHEN l.track_id IS NULL THEN 0 ELSE 1 END, t.id DESC
                LIMIT ?
                """, TRACK_MAPPER, now, now, limit);
    }

    private <T> PageResult<T> toPageResult(List<T> rows, int pageSize) {
        boolean hasMore = rows.size() > pageSize;
        List<T> slice = hasMore ? rows.subList(0, pageSize) : rows;
        return new PageResult<>(hasMore, slice);
    }

    public record PageResult<T>(boolean hasMore, List<T> items) {
    }
}
