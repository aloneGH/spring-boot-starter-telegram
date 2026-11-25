package dev.voroby.telegram.music.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.voroby.telegram.Constant;
import dev.voroby.telegram.music.config.MusicProperties;
import dev.voroby.telegram.music.model.Track;
import dev.voroby.telegram.music.model.TrackLyric;
import dev.voroby.telegram.music.repository.MusicCatalogRepository;
import dev.voroby.telegram.music.repository.TrackLyricRepository;
import dev.voroby.telegram.music.service.netease.LyricResponse;
import dev.voroby.telegram.music.service.netease.SearchMusicResult;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向海外美国 VPS 优化的多源高精度歌词匹配引擎：
 * 1. 内嵌歌词（由 TrackPipelineWorker 直接入库，时间轴 100% 精准）；
 * 2. LRCLIB（海外公益源，美国直连极速、零封锁、原生精确时长匹配 syncedLyrics）；
 * 3. 网易云音乐（多候选严格打分：时长差 <= 5s 硬约束 + 歌名/歌手相似度 + 版本一致性，绝不盲取 songs[0]；
 *    支持配置代理或国内反代，正确合并原文 lrc 与翻译 tlyric，遇限流/超时指数退避重试）。
 */
@Service
public class LyricSyncService {
    private static final Logger log = LoggerFactory.getLogger(LyricSyncService.class);
    private static final String USER_AGENT = "CWMusicServer/2.0 (https://github.com/spring-boot-starter-telegram)";
    private static final String NETEASE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern LRC_LINE_PATTERN = Pattern.compile("^((?:\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?])+)(.*)$");
    private static final long[] RETRY_BACKOFF_MS = {
            10 * 60_000L, 60 * 60_000L, 6 * 3600_000L, 24 * 3600_000L, 72 * 3600_000L, 7 * 24 * 3600_000L
    };
    private static final long NOT_FOUND_RECHECK_MS = 30L * 24 * 3600_000L;

    private final MusicProperties properties;
    private final MusicCatalogRepository catalogRepository;
    private final TrackLyricRepository lyricRepository;
    private final TrackNormalizationService normService;
    private final Gson gson = new Gson();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public LyricSyncService(MusicProperties properties,
                            MusicCatalogRepository catalogRepository,
                            TrackLyricRepository lyricRepository,
                            TrackNormalizationService normService) {
        this.properties = properties;
        this.catalogRepository = catalogRepository;
        this.lyricRepository = lyricRepository;
        this.normService = normService;
    }

    @Async("lyricExecutor")
    @Scheduled(initialDelay = 90_000, fixedDelay = 180_000)
    public void syncPendingLyrics() {
        if (Constant.LOCAL_DEBUG || !properties.isSyncEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            List<Track> tracks = catalogRepository.findTracksNeedingLyrics(30);
            if (tracks.isEmpty()) {
                return;
            }
            log.info("LyricSyncService processing {} tracks", tracks.size());
            int roundFound = 0;
            int roundNotFound = 0;
            int roundRetry = 0;
            for (Track track : tracks) {
                TrackLyric res = fetchAndPersistLyric(track);
                if (res != null && TrackLyric.STATUS_FOUND.equals(res.getStatus())) {
                    roundFound++;
                } else if (res != null && TrackLyric.STATUS_RETRY.equals(res.getStatus())) {
                    roundRetry++;
                } else {
                    roundNotFound++;
                }
                sleepQuietly(1500);
            }
            long[] lp = catalogRepository.countLyricProgress();
            long found = lp[0];
            long notFound = lp[1];
            long retry = lp[2];
            long total = lp[3];
            long checked = found + notFound;
            double pct = total > 0 ? (checked * 100.0 / total) : 100.0;
            log.info("Lyric sync progress: checked {}/{} ({}%) [found={}, notFound={}, retry={}, untouched={} | this round: +found={}, notFound={}, retry={}]",
                    checked, total, String.format("%.2f", pct), found, notFound, retry,
                    Math.max(0, total - checked - retry), roundFound, roundNotFound, roundRetry);
        } catch (Exception e) {
            log.error("syncPendingLyrics failed", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 为单首曲目按优先级抓取并落库歌词（也可供 /music/lyric 在线兜底触发）。
     */
    @Nullable
    public TrackLyric fetchAndPersistLyric(@Nonnull Track track) {
        Optional<TrackLyric> existingOpt = lyricRepository.findByTrackId(track.getId());
        if (existingOpt.isPresent() && TrackLyric.STATUS_FOUND.equals(existingOpt.get().getStatus())
                && StringUtils.isNotBlank(existingOpt.get().getLrc())) {
            return existingOpt.get();
        }
        int prevRetry = existingOpt.map(TrackLyric::getRetryCount).orElse(0);

        String cleanTitle = normService.stripVersionAnnotations(track.getTitle());
        if (StringUtils.isBlank(cleanTitle)) {
            cleanTitle = normService.cleanDisplayText(track.getTitle());
        }
        String cleanArtist = normService.cleanDisplayText(track.getArtist());
        String cleanAlbum = normService.cleanDisplayText(track.getAlbum());
        if (StringUtils.isBlank(cleanTitle)) {
            return saveNotFound(track.getId(), prevRetry);
        }

        boolean hadTransientError = false;

        // 1. 优先查 LRCLIB（美国 VPS 极速，原生按 duration 精准对轴）
        try {
            LyricCandidate lrclibHit = queryLrclib(cleanTitle, cleanArtist, cleanAlbum, track.getDurationSec(), track.getVersionTag());
            if (lrclibHit != null && StringUtils.isNotBlank(lrclibHit.lrc())) {
                return saveFound(track.getId(), TrackLyric.SOURCE_LRCLIB, lrclibHit);
            }
        } catch (TransientLyricException te) {
            hadTransientError = true;
            log.debug("LRCLIB transient error for '{}': {}", cleanTitle, te.getMessage());
        }

        // 2. 兜底查网易云多候选严格打分（含时长差 + 版本一致 + 翻译歌词）
        try {
            LyricCandidate neteaseHit = queryNetease(cleanTitle, cleanArtist, track.getDurationSec(), track.getVersionTag());
            if (neteaseHit != null && StringUtils.isNotBlank(neteaseHit.lrc())) {
                return saveFound(track.getId(), TrackLyric.SOURCE_NETEASE, neteaseHit);
            }
        } catch (TransientLyricException te) {
            hadTransientError = true;
            log.debug("Netease transient error for '{}': {}", cleanTitle, te.getMessage());
        }

        if (hadTransientError) {
            return saveRetry(track.getId(), prevRetry);
        }
        return saveNotFound(track.getId(), prevRetry);
    }

    /**
     * 合并原文 LRC 与翻译 LRC（按时间戳对齐，MusicFree 原生支持同时间戳双行展示）。
     */
    @Nullable
    public String formatLyricForClient(@Nullable TrackLyric lyric) {
        if (lyric == null || StringUtils.isBlank(lyric.getLrc())) {
            return null;
        }
        if (StringUtils.isBlank(lyric.getTlrc())) {
            return lyric.getLrc();
        }
        return mergeLrcWithTranslation(lyric.getLrc(), lyric.getTlrc());
    }

    // ======================== LRCLIB ========================

    @Nullable
    LyricCandidate queryLrclib(String title, String artist, String album, int durationSec, String versionTag) {
        RestClient client = buildRestClient(properties.getLrclibBaseUrl(), USER_AGENT, false);
        // 1a. 先尝试 /api/get 精确匹配（要求有 artist 与 duration）
        if (StringUtils.isNotBlank(artist) && durationSec > 0 && StringUtils.isBlank(versionTag)) {
            try {
                String body = client.get()
                        .uri(u -> {
                            u.path("/api/get")
                                    .queryParam("track_name", title)
                                    .queryParam("artist_name", artist)
                                    .queryParam("duration", durationSec);
                            if (StringUtils.isNotBlank(album)) {
                                u.queryParam("album_name", album);
                            }
                            return u.build();
                        })
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .body(String.class);
                if (StringUtils.isNotBlank(body)) {
                    JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
                    String synced = getJsonStr(obj, "syncedLyrics");
                    if (StringUtils.isNotBlank(synced)) {
                        int durMs = (int) (getJsonDouble(obj, "duration") * 1000.0);
                        return new LyricCandidate(synced, null, durMs, 0.95);
                    }
                }
            } catch (Exception e) {
                if (isTransientHttpError(e)) {
                    throw new TransientLyricException("LRCLIB /api/get transient: " + e.getMessage());
                }
                // 404 时继续走 /api/search
            }
        }

        // 1b. 再走 /api/search 多候选打分
        try {
            String body = client.get()
                    .uri(u -> {
                        u.path("/api/search").queryParam("track_name", title);
                        if (StringUtils.isNotBlank(artist)) {
                            u.queryParam("artist_name", artist);
                        }
                        return u.build();
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            if (StringUtils.isBlank(body)) {
                return null;
            }
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonArray()) return null;
            JsonArray arr = el.getAsJsonArray();
            LyricCandidate best = null;
            for (JsonElement item : arr) {
                if (!item.isJsonObject()) continue;
                JsonObject o = item.getAsJsonObject();
                String synced = getJsonStr(o, "syncedLyrics");
                if (StringUtils.isBlank(synced)) continue;
                String candTitle = getJsonStr(o, "trackName");
                String candArtist = getJsonStr(o, "artistName");
                int candDurMs = (int) Math.round(getJsonDouble(o, "duration") * 1000.0);
                double score = scoreCandidate(title, artist, durationSec, versionTag, candTitle, candArtist, candDurMs);
                if (score >= 0.65 && (best == null || score > best.score())) {
                    best = new LyricCandidate(synced, null, candDurMs, score);
                }
            }
            return best;
        } catch (Exception e) {
            if (isTransientHttpError(e)) {
                throw new TransientLyricException("LRCLIB /api/search transient: " + e.getMessage());
            }
            return null;
        }
    }

    // ======================== 网易云多候选严格打分 ========================

    @Nullable
    LyricCandidate queryNetease(String title, String artist, int durationSec, String versionTag) {
        RestClient client = buildRestClient(properties.getNeteaseBaseUrl(), NETEASE_UA, true);
        String keyword = StringUtils.isNotBlank(artist) ? title + " " + artist : title;
        SearchMusicResult.Song bestSong = null;
        double bestScore = 0.0;

        try {
            String rspText = client.get()
                    .uri(u -> u.path("/api/search/get")
                            .queryParam("type", 1)
                            .queryParam("limit", 10)
                            .queryParam("s", keyword)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            if (StringUtils.isBlank(rspText)) {
                return null;
            }
            SearchMusicResult result = gson.fromJson(rspText, SearchMusicResult.class);
            if (result == null || result.getCode() == null) {
                return null;
            }
            if (result.getCode() == 460 || result.getCode() == 406 || result.getCode() == 429) {
                throw new TransientLyricException("Netease rate-limited/blocked code=" + result.getCode());
            }
            if (result.getCode() != 200 || result.getResult() == null || result.getResult().getSongs() == null) {
                return null;
            }
            for (SearchMusicResult.Song song : result.getResult().getSongs()) {
                if (song == null || song.getId() == null) continue;
                String candArtist = joinNeteaseArtists(song.getArtists());
                int candDurMs = song.getDuration() != null ? song.getDuration().intValue() : 0;
                double score = scoreCandidate(title, artist, durationSec, versionTag, song.getName(), candArtist, candDurMs);
                if (score >= 0.65 && score > bestScore) {
                    bestScore = score;
                    bestSong = song;
                }
            }
        } catch (TransientLyricException te) {
            throw te;
        } catch (Exception e) {
            if (isTransientHttpError(e)) {
                throw new TransientLyricException("Netease search transient: " + e.getMessage());
            }
            log.debug("Netease search error for '{}': {}", keyword, e.getMessage());
            return null;
        }

        if (bestSong == null) {
            return null;
        }

        try {
            long songId = bestSong.getId();
            String rspText = client.get()
                    .uri(u -> u.path("/api/song/lyric")
                            .queryParam("os", "pc")
                            .queryParam("lv", -1)
                            .queryParam("tv", -1)
                            .queryParam("id", songId)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            if (StringUtils.isBlank(rspText)) return null;
            LyricResponse rsp = gson.fromJson(rspText, LyricResponse.class);
            if (rsp == null || rsp.getCode() == null) return null;
            if (rsp.getCode() == 460 || rsp.getCode() == 406 || rsp.getCode() == 429) {
                throw new TransientLyricException("Netease lyric blocked code=" + rsp.getCode());
            }
            if (rsp.getCode() != 200) return null;
            String lrc = rsp.getLrc() != null ? rsp.getLrc().getLyric() : null;
            String tlrc = rsp.getTlyric() != null ? rsp.getTlyric().getLyric() : null;
            if (StringUtils.isBlank(lrc)) {
                return null;
            }
            int durMs = bestSong.getDuration() != null ? bestSong.getDuration().intValue() : 0;
            return new LyricCandidate(lrc, StringUtils.trimToNull(tlrc), durMs, bestScore);
        } catch (TransientLyricException te) {
            throw te;
        } catch (Exception e) {
            if (isTransientHttpError(e)) {
                throw new TransientLyricException("Netease lyric transient: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * 核心多维打分函数（满分 1.0，阈值 >= 0.65）：
     * - 时长差 (0.50)：|Δt| <= 1.5s 得 0.50；<= 3s 得 0.35；<= 5s 得 0.15；> 5s 直接淘汰返回 0.0
     * - 歌名相似度 (0.25) + 歌手相似度 (0.15)
     * - 版本一致性 (0.10)：原版对 Live/DJ/伴奏直接淘汰返回 0.0
     */
    public double scoreCandidate(String targetTitle, String targetArtist, int targetDurationSec, String targetVersionTag,
                                 String candTitle, String candArtist, int candDurationMs) {
        String candVersion = normService.extractVersionTag(candTitle, "");
        String targetVer = StringUtils.defaultString(targetVersionTag);
        if (!targetVer.equals(candVersion)) {
            return 0.0;
        }

        double durationScore;
        if (targetDurationSec > 0 && candDurationMs > 0) {
            double diffSec = Math.abs(targetDurationSec - (candDurationMs / 1000.0));
            if (diffSec > 5.0) {
                return 0.0; // 时长差超过 5 秒直接淘汰，杜绝时间轴错位
            } else if (diffSec <= 1.5) {
                durationScore = 0.50;
            } else if (diffSec <= 3.0) {
                durationScore = 0.35;
            } else {
                durationScore = 0.15;
            }
        } else {
            // 无时长信息时给保守基础分，需要歌名歌手极高匹配才能过阈值
            durationScore = 0.25;
        }

        double titleSim = normService.textSimilarity(
                normService.stripVersionAnnotations(targetTitle),
                normService.stripVersionAnnotations(candTitle));
        if (titleSim < 0.5) {
            return 0.0;
        }

        double artistSim;
        if (StringUtils.isBlank(targetArtist)) {
            artistSim = 0.7;
        } else {
            artistSim = normService.textSimilarity(targetArtist, candArtist);
            if (artistSim < 0.35) {
                return 0.0;
            }
        }

        return durationScore + titleSim * 0.25 + artistSim * 0.15 + 0.10;
    }

    static String mergeLrcWithTranslation(String lrc, String tlrc) {
        Map<String, String> transByTs = new HashMap<>();
        for (String line : tlrc.split("\\R")) {
            Matcher m = LRC_LINE_PATTERN.matcher(line.trim());
            if (m.matches()) {
                String text = m.group(2).trim();
                if (!text.isEmpty()) {
                    for (String ts : extractTimestamps(m.group(1))) {
                        transByTs.putIfAbsent(ts, text);
                    }
                }
            }
        }
        if (transByTs.isEmpty()) {
            return lrc;
        }
        StringBuilder out = new StringBuilder(lrc.length() + tlrc.length());
        for (String line : lrc.split("\\R")) {
            String trimmed = line.trim();
            out.append(trimmed).append('\n');
            Matcher m = LRC_LINE_PATTERN.matcher(trimmed);
            if (m.matches()) {
                for (String ts : extractTimestamps(m.group(1))) {
                    String tr = transByTs.get(ts);
                    if (tr != null) {
                        out.append(ts).append(tr).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    private static List<String> extractTimestamps(String group) {
        List<String> list = new ArrayList<>();
        Matcher m = Pattern.compile("\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?]").matcher(group);
        while (m.find()) {
            list.add(m.group());
        }
        return list;
    }

    private TrackLyric saveFound(long trackId, String source, LyricCandidate hit) {
        TrackLyric tl = new TrackLyric();
        tl.setTrackId(trackId);
        tl.setSource(source);
        tl.setLrc(hit.lrc());
        tl.setTlrc(hit.tlrc());
        tl.setMatchedDurationMs(hit.durationMs() > 0 ? hit.durationMs() : null);
        tl.setMatchScore(hit.score());
        tl.setStatus(TrackLyric.STATUS_FOUND);
        tl.setRetryCount(0);
        tl.setNextRetryAt(null);
        lyricRepository.upsert(tl);
        return tl;
    }

    private TrackLyric saveRetry(long trackId, int prevRetry) {
        int next = prevRetry + 1;
        long delay = RETRY_BACKOFF_MS[Math.min(next - 1, RETRY_BACKOFF_MS.length - 1)];
        TrackLyric tl = new TrackLyric();
        tl.setTrackId(trackId);
        tl.setStatus(TrackLyric.STATUS_RETRY);
        tl.setRetryCount(next);
        tl.setNextRetryAt(System.currentTimeMillis() + delay);
        lyricRepository.upsert(tl);
        return tl;
    }

    private TrackLyric saveNotFound(long trackId, int prevRetry) {
        TrackLyric tl = new TrackLyric();
        tl.setTrackId(trackId);
        tl.setStatus(TrackLyric.STATUS_NOT_FOUND);
        tl.setRetryCount(prevRetry);
        tl.setNextRetryAt(System.currentTimeMillis() + NOT_FOUND_RECHECK_MS);
        lyricRepository.upsert(tl);
        return tl;
    }

    private RestClient buildRestClient(String baseUrl, String ua, boolean allowProxy) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        if (allowProxy && StringUtils.isNotBlank(properties.getLyricProxyUrl())) {
            try {
                URI uri = URI.create(properties.getLyricProxyUrl().trim());
                Proxy.Type type = "socks5".equalsIgnoreCase(uri.getScheme()) || "socks".equalsIgnoreCase(uri.getScheme())
                        ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
                factory.setProxy(new Proxy(type, new InetSocketAddress(uri.getHost(), uri.getPort())));
            } catch (Exception e) {
                log.warn("Invalid lyric proxy url '{}': {}", properties.getLyricProxyUrl(), e.getMessage());
            }
        }
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("User-Agent", ua)
                .build();
    }

    private static String joinNeteaseArtists(@Nullable List<SearchMusicResult.Artist> artists) {
        if (artists == null || artists.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (SearchMusicResult.Artist a : artists) {
            if (a != null && StringUtils.isNotBlank(a.getName())) {
                if (!sb.isEmpty()) sb.append('/');
                sb.append(a.getName().trim());
            }
        }
        return sb.toString();
    }

    private static String getJsonStr(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        return o.get(key).getAsString();
    }

    private static double getJsonDouble(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return 0.0;
        try { return o.get(key).getAsDouble(); } catch (Exception e) { return 0.0; }
    }

    private static boolean isTransientHttpError(Throwable e) {
        String msg = StringUtils.defaultString(e.getMessage()).toLowerCase(Locale.ROOT);
        return msg.contains("timeout") || msg.contains("timed out") || msg.contains("429")
                || msg.contains("502") || msg.contains("503") || msg.contains("504")
                || msg.contains("connection reset") || msg.contains("refused");
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    record LyricCandidate(String lrc, @Nullable String tlrc, int durationMs, double score) {}
    static class TransientLyricException extends RuntimeException {
        TransientLyricException(String message) { super(message); }
    }
}
