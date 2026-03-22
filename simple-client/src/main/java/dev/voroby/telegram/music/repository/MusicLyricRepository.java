package dev.voroby.telegram.music.repository;

import dev.voroby.telegram.music.model.MusicLyric;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MusicLyricRepository extends JpaRepository<MusicLyric, Long> {
    List<MusicLyric> findByChatIdAndMessageIdAndSync(Long chatId, Long messageId, Integer sync);

    boolean existsByChatIdAndMessageIdAndSync(Long chatId, Long messageId, Integer sync);

    List<MusicLyric> findByChatIdAndMessageId(Long chatId, Long messageId);
}
