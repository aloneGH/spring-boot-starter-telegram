package dev.voroby.telegram.music.service;

import dev.voroby.telegram.music.dto.FolderItem;
import dev.voroby.telegram.music.dto.MusicItem;
import dev.voroby.telegram.music.model.MusicLyric;
import dev.voroby.telegram.music.model.SrcMusicMessage;
import dev.voroby.telegram.music.repository.MusicLyricRepository;
import dev.voroby.telegram.music.repository.SrcMusicMessageRepository;
import dev.voroby.telegram.music.repository.SyncChannelInfoRepository;
import org.apache.commons.io.FilenameUtils;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
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

    public MusicStreamService(SrcMusicMessageRepository srcMusicMessageRepository,
                              SyncChannelInfoRepository syncChannelInfoRepository, MusicSyncService musicSyncService, MusicLyricRepository musicLyricRepository) {
        // 假设这是你封装的 TDLib 客户端
        this.srcMusicMessageRepository = srcMusicMessageRepository;
        this.syncChannelInfoRepository = syncChannelInfoRepository;
        this.musicSyncService = musicSyncService;
        this.musicLyricRepository = musicLyricRepository;
    }

    @GetMapping("/folders")
    public List<FolderItem> folders() {
        return syncChannelInfoRepository.findAll().stream()
                .map(it -> new FolderItem(it.getChatId(), ChannelSyncService.restoreChatTitleForMusicSource(it.getTitle())))
                .collect(Collectors.toList());
    }

    @Cacheable(key = "#chatId", value = "music_list")
    @GetMapping("/folder/{fid}")
    public List<MusicItem> musicList(@PathVariable(name = "fid") long chatId) {
        return srcMusicMessageRepository.findAllByChatId(chatId).stream()
                .filter(it -> it.getChatId() != -1 && it.getMessageId() != -1
                        && StringUtils.hasText(it.getTitle()))
                .map(it -> {
                    String title = StringUtils.hasText(it.getTitle()) ? it.getTitle()
                            : FilenameUtils.getBaseName(it.getFileName()).trim();
                    return new MusicItem(it.getChatId(), it.getMessageId(), it.getFileName(), it.getMimeType(),
                            title, it.getPerformer(), it.getDurationSeconds(), it.getAudioFileSize());
                }).collect(Collectors.toList());
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

        TdApi.File tdFile = musicSyncService.downloadFile(chatId, msgId);
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
        if (item == null) {
            log.warn("no music lyric found for {}", msgId);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok().body(item.getLyric());
    }
}
