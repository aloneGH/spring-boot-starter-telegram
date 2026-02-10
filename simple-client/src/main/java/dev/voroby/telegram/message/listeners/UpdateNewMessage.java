package dev.voroby.telegram.message.listeners;

import dev.voroby.springframework.telegram.client.updates.UpdateNotificationListener;
import dev.voroby.telegram.message.common.MessageCache;
import lombok.extern.slf4j.Slf4j;
import org.drinkless.tdlib.TdApi;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class UpdateNewMessage implements UpdateNotificationListener<TdApi.UpdateNewMessage> {

    @Override
    public void handleNotification(TdApi.UpdateNewMessage notification) {
        MessageCache.newMessagesQueue.add(notification.message);
    }

    @Override
    public Class<TdApi.UpdateNewMessage> notificationType() {
        return TdApi.UpdateNewMessage.class;
    }

}
