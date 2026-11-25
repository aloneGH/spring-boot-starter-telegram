package dev.voroby.telegram.music.repository;

import dev.voroby.telegram.music.model.TrackLyric;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class TrackLyricRepository {

    private final JdbcTemplate jdbc;

    public TrackLyricRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TrackLyric> MAPPER = (rs, rowNum) -> {
        TrackLyric l = new TrackLyric();
        l.setTrackId(rs.getLong("track_id"));
        l.setSource(rs.getString("source"));
        l.setLrc(rs.getString("lrc"));
        l.setTlrc(rs.getString("tlrc"));
        int dur = rs.getInt("matched_duration_ms");
        l.setMatchedDurationMs(rs.wasNull() ? null : dur);
        double score = rs.getDouble("match_score");
        l.setMatchScore(rs.wasNull() ? null : score);
        l.setStatus(rs.getString("status"));
        l.setRetryCount(rs.getInt("retry_count"));
        long nextRetry = rs.getLong("next_retry_at");
        l.setNextRetryAt(rs.wasNull() ? null : nextRetry);
        l.setUpdatedAt(rs.getLong("updated_at"));
        return l;
    };

    public Optional<TrackLyric> findByTrackId(long trackId) {
        List<TrackLyric> list = jdbc.query("SELECT * FROM track_lyrics WHERE track_id = ?", MAPPER, trackId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public void upsert(TrackLyric lyric) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO track_lyrics(track_id, source, lrc, tlrc, matched_duration_ms, match_score, status, retry_count, next_retry_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(track_id) DO UPDATE SET
                    source = excluded.source,
                    lrc = excluded.lrc,
                    tlrc = excluded.tlrc,
                    matched_duration_ms = excluded.matched_duration_ms,
                    match_score = excluded.match_score,
                    status = excluded.status,
                    retry_count = excluded.retry_count,
                    next_retry_at = excluded.next_retry_at,
                    updated_at = excluded.updated_at
                """,
                lyric.getTrackId(), lyric.getSource(), lyric.getLrc(), lyric.getTlrc(),
                lyric.getMatchedDurationMs(), lyric.getMatchScore(),
                lyric.getStatus(), lyric.getRetryCount(), lyric.getNextRetryAt(), now);
    }

    public void resetNotFoundToPending(long trackId) {
        jdbc.update("""
                UPDATE track_lyrics
                SET status = 'PENDING', retry_count = 0, next_retry_at = NULL, updated_at = ?
                WHERE track_id = ? AND status IN ('NOT_FOUND', 'RETRY')
                """, System.currentTimeMillis(), trackId);
    }
}
