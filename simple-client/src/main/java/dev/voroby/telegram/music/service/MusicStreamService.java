package dev.voroby.telegram.music.service;

import dev.voroby.telegram.music.dto.FolderItem;
import dev.voroby.telegram.music.dto.MediaResponse;
import dev.voroby.telegram.music.dto.MusicItem;
import dev.voroby.telegram.music.model.MusicLyric;
import dev.voroby.telegram.music.model.SrcMusicMessage;
import dev.voroby.telegram.music.repository.MusicLyricRepository;
import dev.voroby.telegram.music.repository.SrcMusicMessageRepository;
import dev.voroby.telegram.music.repository.SyncChannelInfoRepository;
import jakarta.annotation.Nullable;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static dev.voroby.telegram.music.service.MusicSyncService.downloadFutures;

@RestController("musicStreamService")
@RequestMapping("/music")
public class MusicStreamService {
    private static final Logger log = LoggerFactory.getLogger(MusicStreamService.class);

    private final SrcMusicMessageRepository srcMusicMessageRepository;

    private final SyncChannelInfoRepository syncChannelInfoRepository;

    private final MusicSyncService musicSyncService;
    private final MusicLyricRepository musicLyricRepository;
    private final LyricSyncService lyricSyncService;

    public MusicStreamService(SrcMusicMessageRepository srcMusicMessageRepository,
                              SyncChannelInfoRepository syncChannelInfoRepository, MusicSyncService musicSyncService, MusicLyricRepository musicLyricRepository, LyricSyncService lyricSyncService) {
        // 假设这是你封装的 TDLib 客户端
        this.srcMusicMessageRepository = srcMusicMessageRepository;
        this.syncChannelInfoRepository = syncChannelInfoRepository;
        this.musicSyncService = musicSyncService;
        this.musicLyricRepository = musicLyricRepository;
        this.lyricSyncService = lyricSyncService;
    }

    @GetMapping("/folders")
    public List<FolderItem> folders() {
        return syncChannelInfoRepository.findAll().stream()
                .map(it -> new FolderItem(it.getChatId(), ChannelSyncService.restoreChatTitleForMusicSource(it.getTitle())))
                .collect(Collectors.toList());
    }

    @Cacheable(value = "music_list")
    @GetMapping("/folder/{fid}")
    public MediaResponse<MusicItem> musicList(@PathVariable(name = "fid") long chatId,
                                              @RequestParam(name = "pn", defaultValue = "1") int pn) {
        pn = Math.max(pn - 1, 0);
        int ps = 100;
        PageRequest pageRequest = PageRequest.of(pn, ps);
        Page<SrcMusicMessage> data = srcMusicMessageRepository.findByChatId(chatId, pageRequest);

        List<MusicItem> items = data.stream()
                .filter(it -> it.getChatId() != -1 && it.getMessageId() != -1
                        && StringUtils.isNotBlank(it.getTitle()))
                .map(it -> {
                    String title = StringUtils.isNotBlank(it.getTitle()) ? it.getTitle()
                            : FilenameUtils.getBaseName(it.getFileName()).trim();
                    return new MusicItem(it.getChatId(), it.getMessageId(), it.getFileName(), it.getMimeType(),
                            title, it.getPerformer(), it.getDurationSeconds(), it.getAudioFileSize());
                }).toList();

        return new MediaResponse<>(data.hasNext(), items);
    }

    @GetMapping("/performer/{performer}")
    public MediaResponse<MusicItem> performers(@PathVariable(name = "performer") String performer,
                                               @RequestParam(name = "pn", defaultValue = "1") int pn) {
        pn = Math.max(pn - 1, 0);
        int ps = 100;
        PageRequest pageRequest = PageRequest.of(pn, ps);
        Page<SrcMusicMessage> data = srcMusicMessageRepository.findByPerformer(performer, pageRequest);

        List<MusicItem> items = data.stream()
                .filter(it -> it.getChatId() != -1 && it.getMessageId() != -1
                        && StringUtils.isNotBlank(it.getTitle()))
                .map(it -> {
                    String title = StringUtils.isNotBlank(it.getTitle()) ? it.getTitle()
                            : FilenameUtils.getBaseName(it.getFileName()).trim();
                    return new MusicItem(it.getChatId(), it.getMessageId(), it.getFileName(), it.getMimeType(),
                            title, it.getPerformer(), it.getDurationSeconds(), it.getAudioFileSize());
                }).toList();

        return new MediaResponse<>(data.hasNext(), items);
    }

    @GetMapping("/stream/{msgId}")
    public ResponseEntity<Resource> streamMusic(
            @PathVariable long msgId,
            @RequestParam(name = "fid") long chatId) {

        List<SrcMusicMessage> result = srcMusicMessageRepository.findByChatIdAndMessageId(chatId, msgId);
        SrcMusicMessage musicMessage = result == null || result.isEmpty() ? null : result.get(0);
        if (musicMessage == null) {
            log.warn("no music message found for {}", msgId);
            return ResponseEntity.notFound().build();
        }

        TdApi.File tdFile = null;
        try {
            tdFile = musicSyncService.downloadFile(chatId, msgId);
        } catch (Exception e) {
            log.warn("could not download music file {}", msgId, e);
        }

        if (tdFile == null) {
            return ResponseEntity.notFound().build();
        }

        if (!tdFile.local.isDownloadingCompleted || !new File(tdFile.local.path).exists()) {
            CompletableFuture<TdApi.File> future = new CompletableFuture<>();
            downloadFutures.put(tdFile.id, future);

            try {
                tdFile = future.get(30L, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("couldn't download music file -> {}", tdFile.local.path, e);
                return ResponseEntity.notFound().build();
            }
        }

        String filePath = tdFile.local.path;
        String encodedFileName = UriUtils.encode(musicMessage.getFileName(), StandardCharsets.UTF_8.toString());
        try {
            FileSystemResource resource = new FileSystemResource(filePath);
            if (resource.exists()) {
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(musicMessage.getMimeType()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + encodedFileName
                                + "\"; filename*=UTF-8''" + encodedFileName)
                        .body(resource);
            }
        } catch (Exception e) {
            log.warn("couldn't stream music file -> {}", filePath, e);
        }

        return ResponseEntity.notFound().build();
    }

    @GetMapping("/lyric/{msgId}")
    public ResponseEntity<String> getLyric(
            @PathVariable long msgId,
            @RequestParam(name = "fid") long chatId) {
        List<MusicLyric> data = musicLyricRepository.findByChatIdAndMessageId(chatId, msgId);
        MusicLyric item = data.isEmpty() ? null : data.get(0);
        String lyric;

        if (item == null) {
            lyric = searchLyric(chatId, msgId);
            if (StringUtils.isBlank(lyric)) {
                log.warn("no music lyric found for {}", msgId);
                return ResponseEntity.notFound().build();
            } else {
                MusicLyric musicLyric = new MusicLyric(chatId, msgId, lyric, 1);
                musicLyricRepository.save(musicLyric);
            }
        } else {
            lyric = item.getLyric();
        }

        return ResponseEntity.ok().body(lyric);
    }

    @Nullable
    private String searchLyric(long chatId, long messageId) {
        List<SrcMusicMessage> data = srcMusicMessageRepository.findByChatIdAndMessageId(chatId, messageId);
        if (data == null || data.isEmpty()) {
            return null;
        }

        SrcMusicMessage item = data.get(0);
        String query = item.getTitle();
        String performer = item.getPerformer();
        if (StringUtils.isNotBlank(performer)) {
            query += "-" + performer;
        }

        return lyricSyncService.getLyric(query);
    }
}
