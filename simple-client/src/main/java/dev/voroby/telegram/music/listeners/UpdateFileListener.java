package dev.voroby.telegram.music.listeners;

import dev.voroby.springframework.telegram.client.updates.UpdateNotificationListener;
import dev.voroby.telegram.music.tdlib.TdGatewayService;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class UpdateFileListener implements UpdateNotificationListener<TdApi.UpdateFile> {
    private static final Logger log = LoggerFactory.getLogger(UpdateFileListener.class);

    private final TdGatewayService tdGatewayService;

    public UpdateFileListener(@Lazy TdGatewayService tdGatewayService) {
        this.tdGatewayService = tdGatewayService;
    }

    @Override
    public void handleNotification(TdApi.UpdateFile notification) {
        if (notification == null || notification.file == null) {
            return;
        }
        TdApi.File file = notification.file;
        if (file.local != null && file.local.isDownloadingCompleted) {
            log.debug("File download completed: id={}, path={}, size={}", file.id, file.local.path, file.expectedSize);
            tdGatewayService.onFileUpdated(file);
        }
    }

    @Override
    public Class<TdApi.UpdateFile> notificationType() {
        return TdApi.UpdateFile.class;
    }
}
