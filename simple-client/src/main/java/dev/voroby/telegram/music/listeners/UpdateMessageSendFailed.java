package dev.voroby.telegram.music.listeners;

import dev.voroby.springframework.telegram.client.updates.UpdateNotificationListener;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UpdateMessageSendFailed implements UpdateNotificationListener<TdApi.UpdateMessageSendFailed> {
    private static final Logger log = LoggerFactory.getLogger(UpdateMessageSendFailed.class);

    @Override
    public void handleNotification(TdApi.UpdateMessageSendFailed notification) {
        log.error("message send failed: {} -> {}", notification.error, notification.message);
    }

    @Override
    public Class<TdApi.UpdateMessageSendFailed> notificationType() {
        return TdApi.UpdateMessageSendFailed.class;
    }
}
