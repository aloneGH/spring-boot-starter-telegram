package dev.voroby.telegram.music.service;

import dev.voroby.telegram.music.dto.ArtistItem;
import dev.voroby.telegram.music.dto.MediaResponse;
import dev.voroby.telegram.music.dto.MusicItem;
import dev.voroby.telegram.music.model.SrcMusicMessage;
import dev.voroby.telegram.music.repository.SrcMusicMessageRepository;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("MusicSearch")
@RequestMapping("/search")
public class MusicSearchService {
    private static final Logger log = LoggerFactory.getLogger(MusicSearchService.class);
    private final SrcMusicMessageRepository srcMusicMessageRepository;

    public MusicSearchService(SrcMusicMessageRepository srcMusicMessageRepository) {
        this.srcMusicMessageRepository = srcMusicMessageRepository;
    }

    @GetMapping("/music")
    public MediaResponse<MusicItem> searchLyrics(@RequestParam(name = "q") String query,
                                                 @RequestParam(name = "pn", defaultValue = "1") int pn) {
        MediaResponse<MusicItem> response = new MediaResponse<>();
        if (StringUtils.isBlank(query)) {
            return response;
        }

        pn = Math.max(pn - 1, 0);
        int ps = 10;
        PageRequest pageRequest = PageRequest.of(pn, ps);
        Page<SrcMusicMessage> data = srcMusicMessageRepository.findByTitleContainsIgnoreCase(query, pageRequest);

        boolean hasMore = data.hasNext();
        List<MusicItem> items = data.stream()
                .filter(it -> it.getChatId() != -1 && it.getMessageId() != -1
                        && StringUtils.isNotBlank(it.getTitle()))
                .map(it -> {
                    String title = StringUtils.isNotBlank(it.getTitle()) ? it.getTitle()
                            : FilenameUtils.getBaseName(it.getFileName()).trim();
                    return new MusicItem(it.getChatId(), it.getMessageId(), it.getFileName(), it.getMimeType(),
                            title, it.getPerformer(), it.getDurationSeconds(), it.getAudioFileSize());
                }).toList();

        response.setHasMore(hasMore);
        response.setData(items);
        return response;
    }

    @GetMapping("/artist")
    public MediaResponse<ArtistItem> searchArtist(@RequestParam(name = "q") String query,
                                                  @RequestParam(name = "pn", defaultValue = "1") int pn) {
        MediaResponse<ArtistItem> response = new MediaResponse<>();
        if (StringUtils.isBlank(query)) {
            return response;
        }

        pn = Math.max(pn - 1, 0);
        int ps = 10;
        PageRequest pageRequest = PageRequest.of(pn, ps);
        Page<SrcMusicMessage> data = srcMusicMessageRepository.findPerformers(query, pageRequest);

        boolean hasMore = data.hasNext();
        List<ArtistItem> artists = data.stream()
                .filter(it -> it.getChatId() != -1 && it.getMessageId() != -1
                        && StringUtils.isNotBlank(it.getTitle()))
                .map(it -> new ArtistItem(it.getId(), it.getPerformer()))
                .toList();

        response.setHasMore(hasMore);
        response.setData(artists);
        return response;
    }
}
