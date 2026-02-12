package dev.voroby.telegram.music.service;

import dev.voroby.springframework.telegram.client.TelegramClient;
import dev.voroby.springframework.telegram.client.templates.response.Response;
import dev.voroby.telegram.music.dto.FolderItem;
import dev.voroby.telegram.music.dto.MusicItem;
import dev.voroby.telegram.music.model.MusicMessage;
import dev.voroby.telegram.music.repository.ChannelInfoRepository;
import dev.voroby.telegram.music.repository.MusicMessageRepository;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.drinkless.tdlib.TdApi;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ObjectUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriUtils;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

@RestController("musicStreamService")
@Slf4j
@RequestMapping("/music")
public class MusicStreamService {
    private final TelegramClient telegramClient; // 假设这是你封装的 TDLib 客户端

    private final MusicMessageRepository musicMessageRepository;

    private final ChannelInfoRepository channelInfoRepository;

    public MusicStreamService(TelegramClient telegramClient, MusicMessageRepository musicMessageRepository,
                              ChannelInfoRepository channelInfoRepository) {
        this.telegramClient = telegramClient;
        this.musicMessageRepository = musicMessageRepository;
        this.channelInfoRepository = channelInfoRepository;
    }

    @GetMapping("/folders")
    public List<FolderItem> folders() {
        return channelInfoRepository.findAll().stream()
                .map(it -> new FolderItem(it.getChatId(), it.getTitle()))
                .collect(Collectors.toList());
    }

    @GetMapping("/folder/{fid}")
    public List<MusicItem> musicList(@PathVariable(name = "fid") long chatId) {
        return musicMessageRepository.findAllByChatId(chatId).stream()
                .map(it -> new MusicItem(it.getChatId(), it.getMessageId(), it.getFileName(),
                        it.getMimeType(), it.getTitle(), it.getPerformer(), it.getDurationSeconds(), it.getAudioFileSize())
                ).collect(Collectors.toList());
    }

    @Nullable
    private TdApi.File downloadFile(long chatId, long messageId) {
        log.info("downloadFile: {}", messageId);
        Response<TdApi.Message> rspMsg = telegramClient.send(new TdApi.GetMessage(chatId, messageId));
        TdApi.Message message = rspMsg.getObject().orElse(null);
        if (message == null) {
            log.error("downloadFile: failed to find msg -> {}", messageId);
            return null;
        }

        TdApi.MessageContent content = message.content;
        int fileId = 0;
        if (content instanceof TdApi.MessageAudio ma && ma.audio != null) {
            TdApi.Audio audio = ma.audio;
            fileId = audio.audio.id;
        } else if (content instanceof TdApi.MessageDocument md && md.document != null) {
            fileId = md.document.document.id;
        }
        if (fileId == 0) {
            log.error("downloadFile: failed to find fileId -> {}", messageId);
            return null;
        }

        Response<TdApi.File> response = telegramClient.send(new TdApi.GetFile(fileId));
        TdApi.Error error = response.getError().orElse(null);
        if (error != null) {
            log.error("downloadFile error: {}", error);
            return null;
        }

        TdApi.File file = response.getObject().orElseThrow();
        String path = file.local.path;
        log.info("local path {}", path);
        if (file.local.isDownloadingCompleted) {
            log.info("file is downloaded");
            return file;
        }

        if (!file.local.canBeDownloaded) {
            log.info("local can't be downloaded");
            return null;
        }

        log.info("request to download");
        telegramClient.send(new TdApi.DownloadFile(fileId, 1, 0, 0, false));
        return file;
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

        List<MusicMessage> result = musicMessageRepository.findByChatIdAndMessageId(chatId, msgId);
        MusicMessage musicMessage = result == null || result.isEmpty() ? null : result.get(0);
        if (musicMessage == null) {
            log.warn("no music message found for {}", msgId);
            return ResponseEntity.notFound().build();
        }

        TdApi.File tdFile = downloadFile(chatId, msgId);
        if (tdFile == null) {
            return ResponseEntity.notFound().build();
        }

        long itemSize = tdFile.size == 0 ? musicMessage.getAudioFileSize() : tdFile.size;
        final long reqSize = size < 0 ? itemSize : size;
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

                            TdApi.File fileTd = telegramClient.send(new TdApi.GetFile(tdFile.id)).getObjectOrThrow();
                            filePath = fileTd.local.path;
                            continue;
                        }

                        File file = new File(filePath);
                        if (!file.exists()) {
                            try {
                                Thread.sleep(retryInterval);
                            } catch (InterruptedException e) {
                                log.warn("interrupted while waiting for {}", filePath, e);
                            }

                            TdApi.File fileTd = telegramClient.send(new TdApi.GetFile(tdFile.id)).getObjectOrThrow();
                            filePath = fileTd.local.path;
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

}
