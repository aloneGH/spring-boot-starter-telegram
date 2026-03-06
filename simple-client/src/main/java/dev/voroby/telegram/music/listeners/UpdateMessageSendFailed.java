package dev.voroby.telegram.music.listeners;

import dev.voroby.springframework.telegram.client.updates.UpdateNotificationListener;
import org.apache.commons.io.FileUtils;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class UpdateMessageSendFailed implements UpdateNotificationListener<TdApi.UpdateMessageSendFailed> {
    private static final Logger log = LoggerFactory.getLogger(UpdateMessageSendFailed.class);

    @Override
    public void handleNotification(TdApi.UpdateMessageSendFailed notification) {
        log.error("message send failed: {} -> {}", notification.error, notification.message);
        String filePath = UpdateMessageSendOK.getFilePath(notification.message);
        if (filePath != null) {
            FileUtils.deleteQuietly(new File(filePath));
        }
    }

    @Override
    public Class<TdApi.UpdateMessageSendFailed> notificationType() {
        return TdApi.UpdateMessageSendFailed.class;
    }
}
