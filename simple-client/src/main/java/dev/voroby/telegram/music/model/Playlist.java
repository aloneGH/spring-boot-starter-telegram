package dev.voroby.telegram.music.model;

public record Playlist(
        Long id,
        Long sourceChatId,
        Long legacyChatId,
        String title,
        String folderName,
        long createdAt,
        long updatedAt
) {
}
