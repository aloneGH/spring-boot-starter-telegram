package dev.voroby.telegram.message.service.print;

import dev.voroby.telegram.chat.common.Cache;
import org.drinkless.tdlib.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PrintService {
    private static final Logger log = LoggerFactory.getLogger(PrintService.class);

    public void print(TdApi.Message message) {
        TdApi.MessageContent content = message.content;
        if (content instanceof TdApi.MessageText mt) {
            if (Cache.idToMainListChat.containsKey(message.chatId)) {
                TdApi.Chat chat = Cache.idToMainListChat.get(message.chatId);
                log.info("Incoming text message:\n[\n\ttitle: {},\n\tmessage: {}\n]",
                        chat.title, mt.text.text);
            }
        }
    }
}
