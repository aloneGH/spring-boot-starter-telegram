package dev.voroby.telegram.music.model;

public record PendingUpload(
        Long id,
        long chatId,
        long tempMsgId,
        long sourceTrackId,
        Long targetTrackId,
        String localFilePath,
        String payloadJson,
        long createdAt
) {
}
