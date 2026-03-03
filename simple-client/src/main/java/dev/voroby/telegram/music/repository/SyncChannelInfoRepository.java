package dev.voroby.telegram.music.repository;

import dev.voroby.telegram.music.model.SyncChannelInfo;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.Collection;

@Repository
public interface SyncChannelInfoRepository extends JpaRepository<SyncChannelInfo, Long> {
    SyncChannelInfo findByChatId(Long chatId);

    @Modifying
    @Transactional
    void deleteByFolderNameAndChatIdNotIn(String folderName, Collection<Long> chatIds);
}
