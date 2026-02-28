package dev.voroby.telegram.message.listeners;

import dev.voroby.springframework.telegram.client.updates.UpdateNotificationListener;
import dev.voroby.telegram.message.common.MessageCache;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UpdateNewMessage implements UpdateNotificationListener<TdApi.UpdateNewMessage> {
    private static final Logger log = LoggerFactory.getLogger(UpdateNewMessage.class);

    @Override
    public void handleNotification(TdApi.UpdateNewMessage notification) {
        MessageCache.newMessagesQueue.add(notification.message);
    }

    @Override
    public Class<TdApi.UpdateNewMessage> notificationType() {
        return TdApi.UpdateNewMessage.class;
    }

}
