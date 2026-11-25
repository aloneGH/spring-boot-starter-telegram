package dev.voroby.telegram.message.listeners;

import dev.voroby.springframework.telegram.client.updates.UpdateNotificationListener;
import dev.voroby.telegram.message.common.MessageCache;
import dev.voroby.telegram.music.cache.ChatFolderCache;
import dev.voroby.telegram.music.config.MusicProperties;
import org.drinkless.tdlib.TdApi;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * 实时消息监听器：
 * 1. 只接收目标 Music 文件夹中源频道的音频/文档消息（且过滤掉尚未发送确认的临时消息），
 *    彻底杜绝其他无关群刷屏挤爆队列或把临时 ID 写入曲库。
 */
@Component
public class UpdateNewMessage implements UpdateNotificationListener<TdApi.UpdateNewMessage> {

    private final MusicProperties properties;

    public UpdateNewMessage(MusicProperties properties) {
        this.properties = properties;
    }

    @Override
    public void handleNotification(TdApi.UpdateNewMessage notification) {
        if (notification == null || notification.message == null) {
            return;
        }
        TdApi.Message msg = notification.message;
        // 过滤本地未发送确认的临时消息
        if (msg.sendingState != null || msg.isOutgoing) {
            return;
        }
        // 只保留音频/文档类型，快速丢弃纯文本/图片/视频等噪声
        if (!(msg.content instanceof TdApi.MessageAudio || msg.content instanceof TdApi.MessageDocument)) {
            return;
        }
        // 仅入队属于目标 Music 源文件夹的消息
        Set<Long> targetChats = ChatFolderCache.getCachedChatIds(properties.getSourceFolderName());
        if (!targetChats.isEmpty() && !targetChats.contains(msg.chatId)) {
            return;
        }

        ArrayBlockingQueue<TdApi.Message> queue = MessageCache.newMessagesQueue;
        if (queue.remainingCapacity() == 0) {
            queue.poll();
        }
        queue.offer(msg);
    }

    @Override
    public Class<TdApi.UpdateNewMessage> notificationType() {
        return TdApi.UpdateNewMessage.class;
    }
}
