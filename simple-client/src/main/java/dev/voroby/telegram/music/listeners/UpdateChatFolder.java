package dev.voroby.telegram.music.listeners;

import dev.voroby.springframework.telegram.client.updates.UpdateNotificationListener;
import dev.voroby.telegram.music.cache.Cache;
import org.drinkless.tdlib.TdApi;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class UpdateChatFolder implements UpdateNotificationListener<TdApi.UpdateChatFolders> {
    @Override
    public void handleNotification(TdApi.UpdateChatFolders notification) {
        Cache.chatFolders.clear();
        if (notification == null || notification.chatFolders == null) {
            return;
        }
        Cache.chatFolders.addAll(Arrays.asList(notification.chatFolders));
    }

    @Override
    public Class<TdApi.UpdateChatFolders> notificationType() {
        return TdApi.UpdateChatFolders.class;
    }
}
