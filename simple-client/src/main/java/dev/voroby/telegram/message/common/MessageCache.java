package dev.voroby.telegram.message.common;

import org.drinkless.tdlib.TdApi;

import java.util.concurrent.ArrayBlockingQueue;

public final class MessageCache {

    public static final ArrayBlockingQueue<TdApi.Message> newMessagesQueue = new ArrayBlockingQueue<>(512);
}
