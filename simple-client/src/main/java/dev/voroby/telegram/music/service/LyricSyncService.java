package dev.voroby.telegram.music.service;

import com.google.gson.Gson;
import dev.voroby.telegram.Constant;
import dev.voroby.telegram.music.model.MusicLyric;
import dev.voroby.telegram.music.model.SyncMusicMessage;
import dev.voroby.telegram.music.repository.MusicLyricRepository;
import dev.voroby.telegram.music.repository.SyncMusicMessageRepository;
import dev.voroby.telegram.music.service.netease.LyricResponse;
import dev.voroby.telegram.music.service.netease.SearchMusicResult;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class LyricSyncService {
    private static final Logger log = LoggerFactory.getLogger(LyricSyncService.class);

    private static final String NETEASE_BASE_URL = "https://music.163.com";
    private static final String NETEASE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36";

    private final MusicLyricRepository musicLyricRepository;
    private final SyncMusicMessageRepository syncMusicMessageRepository;

    public LyricSyncService(MusicLyricRepository musicLyricRepository, SyncMusicMessageRepository syncMusicMessageRepository) {
        this.musicLyricRepository = musicLyricRepository;
        this.syncMusicMessageRepository = syncMusicMessageRepository;
    }

    @Scheduled(fixedDelay = 30_000)
    public void syncMusicLyrics() {
        syncMusicLyrics(12);
    }

    public void syncMusicLyrics(int count) {
        log.info("Syncing Music Lyrics: count={}", count);
        if (Constant.LOCAL_DEBUG) {
            log.warn("ignored in debug");
            return;
        }

        List<SyncMusicMessage> data = getMusicMessagesWithoutLyrics(count);
        if (data.isEmpty()) {
            log.info("no music need to sync lyrics");
            return;
        }

        AtomicInteger idx = new AtomicInteger(0);
        data.forEach(it -> {
            log.info("Syncing Music Lyrics: {}/{}", idx.getAndIncrement(), data.size());

            String keyword = it.getTitle();
            String lyric = null;

            if (!StringUtils.isBlank(keyword)) {
                String performer = it.getPerformer();
                if (!StringUtils.isBlank(performer)) {
                    keyword += "-" + performer;
                }

                lyric = getLyric(keyword);
            }

            if (lyric == null) {
                lyric = "";
            }

            MusicLyric item = new MusicLyric(it.getChatId(), it.getMessageId(), lyric, 1);
            musicLyricRepository.save(item);

            ThreadUtils.sleepQuietly(Duration.ofSeconds(10));
        });

        log.info("Finished syncing Music Lyrics");
    }

    @Nonnull
    private List<SyncMusicMessage> getMusicMessagesWithoutLyrics(int count) {
        List<SyncMusicMessage> messages = new ArrayList<>();

        int pageIdx = 0;
        Page<SyncMusicMessage> page;
        do {
            PageRequest pageRequest = PageRequest.of(pageIdx, count);
            page = syncMusicMessageRepository.findAll(pageRequest);
            page.forEach(it -> {
                boolean exists = musicLyricRepository.existsByChatIdAndMessageIdAndSync(it.getChatId(), it.getMessageId(), 1);
                if (!exists) {
                    messages.add(it);
                }
            });

            if (messages.size() >= count) {
                break;
            }

            pageIdx += 1;
        } while (page.hasNext());

        return messages;
    }

    private RestClient getRestClient() {
        return RestClient.builder().baseUrl(NETEASE_BASE_URL)
                .defaultHeader("User-Agent", NETEASE_USER_AGENT)
                .build();
    }

    @Nullable
    private String getLyric(@Nonnull String keyword) {
        log.info("Searching for music Lyric with keyword={}", keyword);
        String lyric = null;

        long songId = getSongId(keyword);
        if (songId > 0) {
            lyric = getLyrics(songId, false);
            if (StringUtils.isEmpty(lyric)) {
                ThreadUtils.sleepQuietly(Duration.ofSeconds(10));
                lyric = getLyrics(songId, true);
            }
        }

        return lyric;
    }

    private long getSongId(@Nonnull String keyword) {
        long songId = 0;
        try {
            String rspText = getRestClient()
                    .get()
                    .uri(it -> it
                            .path("/api/search/get")
                            .query("type=1&limit=10")
                            .queryParam("s", keyword)
                            .build()
                    )
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);

            SearchMusicResult result = new Gson().fromJson(rspText, SearchMusicResult.class);
            List<SearchMusicResult.Song> songs = result == null || result.getCode() != 200 ? null
                    : result.getResult().getSongs();
            songId = songs == null ? 0 : songs.get(0).getId();
        } catch (Exception e) {
            log.error("Error while searching for music lyrics: {}", keyword, e);
        }

        return songId;
    }

    @Nullable
    private String getLyrics(long songId, boolean translatedVersion) {
        String lyrics = null;
        try {
            String rspText = getRestClient()
                    .get()
                    .uri(it -> it
                            .path("/api/song/lyric")
                            .query("os=pc")
                            .queryParam(translatedVersion ? "tv" : "lv", -1)
                            .queryParam("id", songId)
                            .build()
                    )
                    .retrieve()
                    .body(String.class);

            LyricResponse rsp = new Gson().fromJson(rspText, LyricResponse.class);
            LyricResponse.Lrc lrc = rsp == null || rsp.getCode() != 200 ? null : rsp.getLrc();
            lyrics = lrc == null ? null : lrc.getLyric();
        } catch (Exception e) {
            log.error("Error while searching for music lyrics: {}", songId, e);
        }

        return lyrics;
    }
}
