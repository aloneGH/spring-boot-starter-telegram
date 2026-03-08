package dev.voroby.telegram.music.repository;

import dev.voroby.telegram.music.model.SyncMusicMessage;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SyncMusicMessageRepository extends JpaRepository<SyncMusicMessage, Long> {
    boolean existsByOriginChatIdAndOriginMessageId(Long originChatId, Long originMessageId);

    boolean existsByChatIdAndMessageId(Long chatId, Long messageId);

    SyncMusicMessage findTopByChatIdOrderByMessageIdDesc(Long chatId);

    @Modifying
    @Transactional
    void deleteByChatIdNotIn(Collection<Long> chatIds);

    Collection<SyncMusicMessage> findAllByChatId(long chatId);

    List<SyncMusicMessage> findByChatIdAndMessageId(long chatId, long msgId);

    @Transactional
    @Modifying
    @Query("update SyncMusicMessage s set s.messageId = ?1 where s.messageIdTemp = ?2 and s.chatId = ?3")
    int updateMessageIdByMessageIdTemp(Long messageId, Long messageIdTemp, Long chatId);

    List<SyncMusicMessage> findByFixDurationIsNullOrFixDurationLessThanAndDurationSeconds(Integer fixDuration
            , Integer durationSeconds, Limit limit);

    List<SyncMusicMessage> findByMessageId(Long messageId);

    List<SyncMusicMessage> findByFixDurationIsNullOrFixDurationLessThan(Integer fixDurationIsLessThan,
                                                                        Limit limit);
}

