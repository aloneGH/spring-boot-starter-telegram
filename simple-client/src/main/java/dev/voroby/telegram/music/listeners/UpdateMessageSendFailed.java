package dev.voroby.telegram.music.listeners;

import dev.voroby.springframework.telegram.client.updates.UpdateNotificationListener;
import dev.voroby.telegram.music.tdlib.TdGatewayService;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class UpdateMessageSendFailed implements UpdateNotificationListener<TdApi.UpdateMessageSendFailed> {
    private static final Logger log = LoggerFactory.getLogger(UpdateMessageSendFailed.class);

    private final TdGatewayService tdGatewayService;

    public UpdateMessageSendFailed(@Lazy TdGatewayService tdGatewayService) {
        this.tdGatewayService = tdGatewayService;
    }

    @Override
    public void handleNotification(TdApi.UpdateMessageSendFailed notification) {
        if (notification == null) {
            return;
        }
        log.error("Message send failed: oldId={}, error={}", notification.oldMessageId, notification.error);
        tdGatewayService.onMessageSendFailed(notification);
    }

    @Override
    public Class<TdApi.UpdateMessageSendFailed> notificationType() {
        return TdApi.UpdateMessageSendFailed.class;
    }
}
