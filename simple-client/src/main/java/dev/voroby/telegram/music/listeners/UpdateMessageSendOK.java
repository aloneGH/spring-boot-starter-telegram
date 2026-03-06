package dev.voroby.telegram.music.listeners;

import dev.voroby.springframework.telegram.client.updates.UpdateNotificationListener;
import dev.voroby.telegram.music.repository.SyncMusicMessageRepository;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.ThreadUtils;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Component
public class UpdateMessageSendOK implements UpdateNotificationListener<TdApi.UpdateMessageSendSucceeded> {
    private static final Logger log = LoggerFactory.getLogger(UpdateMessageSendOK.class);
    private final SyncMusicMessageRepository syncMusicMessageRepository;

    public UpdateMessageSendOK(SyncMusicMessageRepository syncMusicMessageRepository) {
        this.syncMusicMessageRepository = syncMusicMessageRepository;
    }

    @Override
    public void handleNotification(TdApi.UpdateMessageSendSucceeded notification) {
        log.info("message send successful: {} -> {}", notification.oldMessageId, notification.message.id);
        CompletableFuture.runAsync(() -> {
            int retry = 0;
            int retryMax = 4;
            while (retry-- < retryMax) {
                int rows = syncMusicMessageRepository.updateMessageIdByMessageIdTemp(notification.message.id,
                        notification.oldMessageId, notification.message.chatId);
                if (rows > 0) {
                    break;
                }

                ThreadUtils.sleepQuietly(Duration.ofMillis(500));
            }

            String filePath = getFilePath(notification.message);
            if (filePath != null) {
                FileUtils.deleteQuietly(new File(filePath));
            }
        });
    }

    public static String getFilePath(TdApi.Message message) {
        TdApi.MessageContent content = message.content;

        String filePath = null;
        if (content instanceof TdApi.MessageAudio ma && ma.audio != null) {
            filePath = ma.audio.audio.local.path;
        } else if (content instanceof TdApi.MessageDocument md && md.document != null) {
            filePath = md.document.document.local.path;
        } else if (content instanceof TdApi.MessageVoiceNote mv && mv.voiceNote != null) {
            filePath = mv.voiceNote.voice.local.path;
        }
        return filePath;
    }

    @Override
    public Class<TdApi.UpdateMessageSendSucceeded> notificationType() {
        return TdApi.UpdateMessageSendSucceeded.class;
    }
}
