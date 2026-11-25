package dev.voroby.telegram.music.service;

import dev.voroby.telegram.music.dto.ArtistItem;
import dev.voroby.telegram.music.dto.MediaResponse;
import dev.voroby.telegram.music.dto.MusicItem;
import dev.voroby.telegram.music.model.Track;
import dev.voroby.telegram.music.repository.MusicCatalogRepository;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 SQLite FTS5 的简繁统一、多词混合检索（覆盖歌名、歌手、专辑、文件名）。
 */
@RestController("MusicSearch")
@RequestMapping("/search")
public class MusicSearchService {
    private static final int PAGE_SIZE = 100;

    private final MusicCatalogRepository catalogRepository;

    public MusicSearchService(MusicCatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    @GetMapping("/music")
    public MediaResponse<MusicItem> searchMusic(@RequestParam(name = "q") String query,
                                                @RequestParam(name = "pn", defaultValue = "1") int pn) {
        if (StringUtils.isBlank(query)) {
            return new MediaResponse<>(false, List.of());
        }
        int pageZero = Math.max(pn - 1, 0);
        MusicCatalogRepository.PageResult<Track> page =
                catalogRepository.searchReadyTracks(query.trim(), pageZero, PAGE_SIZE);
        List<MusicItem> items = page.items().stream()
                .map(t -> MusicStreamService.toMusicItem(t.getStoredChatId() != null ? t.getStoredChatId() : 0L, t))
                .toList();
        return new MediaResponse<>(page.hasMore(), items);
    }

    @GetMapping("/artist")
    public MediaResponse<ArtistItem> searchArtist(@RequestParam(name = "q") String query,
                                                  @RequestParam(name = "pn", defaultValue = "1") int pn) {
        if (StringUtils.isBlank(query)) {
            return new MediaResponse<>(false, List.of());
        }
        int pageZero = Math.max(pn - 1, 0);
        MusicCatalogRepository.PageResult<String> page =
                catalogRepository.searchArtists(query.trim(), pageZero, PAGE_SIZE);
        List<ArtistItem> artists = new ArrayList<>(page.items().size());
        long baseIdx = (long) pageZero * PAGE_SIZE + 1;
        for (int i = 0; i < page.items().size(); i++) {
            String name = page.items().get(i);
            artists.add(new ArtistItem(baseIdx + i, name));
        }
        return new MediaResponse<>(page.hasMore(), artists);
    }
}
