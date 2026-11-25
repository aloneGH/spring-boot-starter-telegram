package dev.voroby.telegram.music.model;

public record SyncCursor(
        long chatId,
        String title,
        long newestMsgId,
        long oldestMsgId,
        boolean backfillDone,
        Long lastSyncAt
) {
}
