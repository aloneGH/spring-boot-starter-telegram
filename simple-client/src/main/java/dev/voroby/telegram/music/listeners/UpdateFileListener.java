package dev.voroby.telegram.music.listeners;

import dev.voroby.springframework.telegram.client.updates.UpdateNotificationListener;
import dev.voroby.telegram.music.service.MusicSyncService;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class UpdateFileListener implements UpdateNotificationListener<TdApi.UpdateFile> {
    private static final Logger log = LoggerFactory.getLogger(UpdateFileListener.class);

    @Override
    public void handleNotification(TdApi.UpdateFile notification) {
        TdApi.File file = notification.file;
        if (file == null || !file.local.isDownloadingCompleted) {
            return;
        }

        log.info("handleNotification: {}, size = {}", file.local.path, file.expectedSize);
        CompletableFuture<TdApi.File> future = MusicSyncService.downloadFutures.get(file.id);
        if (future == null) {
            return;
        }
        future.complete(file);
    }

    @Override
    public Class<TdApi.UpdateFile> notificationType() {
        return TdApi.UpdateFile.class;
    }
}
