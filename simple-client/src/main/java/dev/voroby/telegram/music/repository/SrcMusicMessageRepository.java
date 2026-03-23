package dev.voroby.telegram.music.repository;

import dev.voroby.telegram.music.model.SrcMusicMessage;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface SrcMusicMessageRepository extends JpaRepository<SrcMusicMessage, Long> {

    boolean existsByChatIdAndMessageId(Long chatId, Long messageId);

    /**
     * 查询某个频道本地已保存的最新一条消息（按 messageId 倒序）。
     */
    SrcMusicMessage findTopByChatIdOrderByMessageIdDesc(Long chatId);

    List<SrcMusicMessage> findByAudioFileId(Integer audioFileId);

    List<SrcMusicMessage> findByChatId(Long chatId);

    List<SrcMusicMessage> findByMessageId(Long messageId);

    List<SrcMusicMessage> findByChatIdAndMessageId(Long chatId, Long messageId);

    void deleteByChatIdIn(Collection<Long> chatIds);

    @Modifying
    @Transactional
    void deleteByChatIdNotIn(Collection<Long> chatIds);

    Page<SrcMusicMessage> findAllByChatId(Long chatId, Pageable pageable);

    Page<SrcMusicMessage> findByChatId(Long chatId, Pageable pageable);

    @Transactional
    @Modifying
    @Query("update SrcMusicMessage m set m.sync = ?1 where m.id = ?2")
    int updateSyncById(Integer sync, Long id);

    Page<SrcMusicMessage> findByChatIdAndSyncLessThan(long chatId, int sync, Pageable pageable);

    Page<SrcMusicMessage> findByChatIdAndSyncIsNullOrSyncLessThan(Long chatId, Integer sync, Pageable pageable);

    Page<SrcMusicMessage> findByTitleContainsIgnoreCase(String title, Pageable pageable);

    @Query("SELECT DISTINCT m FROM SrcMusicMessage m WHERE m.performer LIKE %:kw% group by m.performer")
    Page<SrcMusicMessage> findPerformers(@Param("kw") String query, Pageable pageable);

    Page<SrcMusicMessage> findByPerformerContainingIgnoreCase(String performer, Pageable pageable);

    Page<SrcMusicMessage> findByPerformer(String performer, Pageable pageable);
}

