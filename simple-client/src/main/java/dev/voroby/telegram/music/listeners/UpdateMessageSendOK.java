package dev.voroby.telegram.music.listeners;

import dev.voroby.springframework.telegram.client.updates.UpdateNotificationListener;
import dev.voroby.telegram.music.tdlib.TdGatewayService;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 接收 Telegram 上传正式确认，彻底移除原 while(retry-- < retryMax) 死循环。
 */
@Component
public class UpdateMessageSendOK implements UpdateNotificationListener<TdApi.UpdateMessageSendSucceeded> {
    private static final Logger log = LoggerFactory.getLogger(UpdateMessageSendOK.class);

    private final TdGatewayService tdGatewayService;

    public UpdateMessageSendOK(@Lazy TdGatewayService tdGatewayService) {
        this.tdGatewayService = tdGatewayService;
    }

    @Override
    public void handleNotification(TdApi.UpdateMessageSendSucceeded notification) {
        if (notification == null || notification.message == null) {
            return;
        }
        log.info("Message upload confirmed: chatId={}, tempId={} -> realId={}",
                notification.message.chatId, notification.oldMessageId, notification.message.id);
        tdGatewayService.onMessageSendSucceeded(notification);
    }

    @Override
    public Class<TdApi.UpdateMessageSendSucceeded> notificationType() {
        return TdApi.UpdateMessageSendSucceeded.class;
    }
}
