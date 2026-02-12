package dev.voroby.telegram.music.repository;

import dev.voroby.telegram.music.model.MusicMessage;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface MusicMessageRepository extends JpaRepository<MusicMessage, Long> {

    boolean existsByChatIdAndMessageId(Long chatId, Long messageId);

    /**
     * 查询某个频道本地已保存的最新一条消息（按 messageId 倒序）。
     */
    MusicMessage findTopByChatIdOrderByMessageIdDesc(Long chatId);

    List<MusicMessage> findByAudioFileId(Integer audioFileId);

    List<MusicMessage> findByChatId(Long chatId);

    List<MusicMessage> findByMessageId(Long messageId);

    List<MusicMessage> findByChatIdAndMessageId(Long chatId, Long messageId);

    void deleteByChatIdIn(java.util.Collection<Long> chatIds);

    @Modifying
    @Transactional
    void deleteByChatIdNotIn(Collection<Long> chatIds);

    List<MusicMessage> findAllByChatId(Long chatId);
}

