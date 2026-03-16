package dev.voroby.telegram.music.service;

import dev.voroby.telegram.music.dto.FolderItem;
import dev.voroby.telegram.music.dto.MusicItem;
import dev.voroby.telegram.music.model.SrcMusicMessage;
import dev.voroby.telegram.music.repository.SrcMusicMessageRepository;
import dev.voroby.telegram.music.repository.SyncChannelInfoRepository;
import org.apache.commons.io.FilenameUtils;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriUtils;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@RestController("musicStreamService")
@RequestMapping("/music")
public class MusicStreamService {
    private static final Logger log = LoggerFactory.getLogger(MusicStreamService.class);

    private final SrcMusicMessageRepository srcMusicMessageRepository;

    private final SyncChannelInfoRepository syncChannelInfoRepository;

    private final MusicSyncService musicSyncService;

    public MusicStreamService(SrcMusicMessageRepository srcMusicMessageRepository,
                              SyncChannelInfoRepository syncChannelInfoRepository, MusicSyncService musicSyncService) {
        // 假设这是你封装的 TDLib 客户端
        this.srcMusicMessageRepository = srcMusicMessageRepository;
        this.syncChannelInfoRepository = syncChannelInfoRepository;
        this.musicSyncService = musicSyncService;
    }

    @GetMapping("/folders")
    public List<FolderItem> folders() {
        return syncChannelInfoRepository.findAll().stream()
                .map(it -> new FolderItem(it.getChatId(), ChannelSyncService.restoreChatTitleForMusicSource(it.getTitle())))
                .collect(Collectors.toList());
    }

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
    public ResponseEntity<StreamingResponseBody> streamMusic(
            @RequestHeader(name = "Range", required = false) String range,
            @PathVariable long msgId,
            @RequestParam(name = "fid") long chatId,
            @RequestParam(name = "size", defaultValue = "-1") long size) {

        long start = 0;
        if (range != null) {
            // 简单解析 Range: bytes=1000-
            start = Long.parseLong(range.replace("bytes=", "").split("-")[0]);
        }
        final long finalStart = start;

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

        long itemSize = tdFile.size == 0 ? musicMessage.getAudioFileSize() : tdFile.size;
        final long reqSize = size < 0 ? itemSize - start : size;
        if (start < 0 || reqSize > itemSize || start >= itemSize) {
            log.warn("invalid size: {} - {} -> {}", start, reqSize, itemSize);
            return ResponseEntity.badRequest().build();
        }

        if (reqSize > (itemSize - start)) {
            log.warn("invalid range: {} - {} -> {}", start, reqSize, itemSize);
            return ResponseEntity.badRequest().build();
        }

        String encodedFileName = UriUtils.encode(musicMessage.getFileName(), StandardCharsets.UTF_8.toString());
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName)
                .header(HttpHeaders.CONTENT_TYPE, musicMessage.getMimeType()) // 明确指定视频格式
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(reqSize))
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + finalStart + "-" + (finalStart + (reqSize - 1)) + "/" + itemSize)
                .body(outputStream -> {
                    String filePath = tdFile.local.path;
                    int maxRetries = 60;
                    int retryInterval = 1000;
                    int cnt = 0;

                    long offset = finalStart;
                    long remaining = reqSize - offset;

                    byte[] buffer = new byte[4096];
                    int bytesRead;

                    while (cnt++ < maxRetries) {
                        if (ObjectUtils.isEmpty(filePath)) {
                            try {
                                Thread.sleep(retryInterval);
                            } catch (InterruptedException e) {
                                log.warn("interrupted while waiting for file to be downloaded");
                            }

                            TdApi.File fileTd = musicSyncService.queryFile(tdFile.id);
                            filePath = fileTd == null ? null : fileTd.local.path;
                            continue;
                        }

                        File file = new File(filePath);
                        if (!file.exists()) {
                            try {
                                Thread.sleep(retryInterval);
                            } catch (InterruptedException e) {
                                log.warn("interrupted while waiting for {}", filePath, e);
                            }

                            TdApi.File fileTd = musicSyncService.queryFile(tdFile.id);
                            filePath = fileTd == null ? null : fileTd.local.path;
                            continue;
                        }

                        try (RandomAccessFile fp = new RandomAccessFile(filePath, "r")) {
                            if (offset + remaining > fp.length()) {
                                Thread.sleep(retryInterval);
                                continue;
                            }

                            fp.seek(offset);
                            while ((bytesRead = fp.read(buffer, 0, buffer.length)) > 0) {
                                outputStream.write(buffer, 0, bytesRead);
                                offset += bytesRead;
                                remaining -= bytesRead;
                            }

                            if (remaining <= 0) {
                                break;
                            }
                        } catch (Exception e) {
                            log.error("read file failed: {}", filePath, e);
                            break;
                        }
                    }

                    outputStream.flush();
                });
    }

    @GetMapping("/sync")
    public ResponseEntity<Object> syncMusicMessage(@RequestParam(name = "cnt", defaultValue = "10") int count) {
        musicSyncService.syncMusicMessages(count);
        return ResponseEntity.ok().build();
    }
}
