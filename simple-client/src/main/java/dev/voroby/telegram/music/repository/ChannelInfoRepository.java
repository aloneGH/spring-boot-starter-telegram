package dev.voroby.telegram.music.repository;

import dev.voroby.telegram.music.model.ChannelInfo;
import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ChannelInfoRepository extends JpaRepository<ChannelInfo, Long> {

    ChannelInfo findByChatId(Long chatId);

    List<ChannelInfo> findByFolderName(String folderName);

    @Modifying
    @Transactional
    void deleteByFolderNameAndChatIdNotIn(String folderName, Collection<Long> chatIds);

    @Modifying
    @Transactional
    void deleteByFolderName(String folderName);
}

