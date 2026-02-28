package dev.voroby.telegram.music.repository;

import dev.voroby.telegram.music.model.SyncMusicMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SyncMusicMessageRepository extends JpaRepository<SyncMusicMessage, Long> {
    boolean existsByOriginChatIdAndOriginMessageId(Long originChatId, Long originMessageId);
}

