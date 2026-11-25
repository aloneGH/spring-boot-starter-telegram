package dev.voroby.telegram.music;

import dev.voroby.telegram.music.config.MusicProperties;
import dev.voroby.telegram.music.dto.MediaResponse;
import dev.voroby.telegram.music.dto.MusicItem;
import dev.voroby.telegram.music.migration.SchemaInitializer;
import dev.voroby.telegram.music.model.Track;
import dev.voroby.telegram.music.model.TrackLyric;
import dev.voroby.telegram.music.repository.MusicCatalogRepository;
import dev.voroby.telegram.music.repository.TrackLyricRepository;
import dev.voroby.telegram.music.service.*;
import dev.voroby.telegram.music.tdlib.TdGatewayService;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class MusicApiControllerTest {

    @TempDir
    Path tempDir;

    private MusicCatalogRepository catalogRepo;
    private TrackLyricRepository lyricRepo;
    private TdGatewayService tdGateway;
    private MusicStreamService streamService;
    private MusicSearchService searchService;
    private MusicProperties props;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:sqlite:" + tempDir.resolve("api_test.db");
        DriverManagerDataSource ds = new DriverManagerDataSource(url);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        TrackNormalizationService norm = new TrackNormalizationService();
        new SchemaInitializer(jdbc).initialize();
        catalogRepo = new MusicCatalogRepository(jdbc, norm);
        lyricRepo = new TrackLyricRepository(jdbc);

        props = new MusicProperties();
        props.setCoverDir(tempDir.resolve("covers").toString());
        props.setTempDir(tempDir.resolve("tmp").toString());
        Files.createDirectories(Path.of(props.getCoverDir()));

        tdGateway = Mockito.mock(TdGatewayService.class);
        AudioMetadataService audioService = Mockito.mock(AudioMetadataService.class);
        LyricSyncService lyricService = new LyricSyncService(props, catalogRepo, lyricRepo, norm);

        streamService = new MusicStreamService(
                catalogRepo, lyricRepo, tdGateway, lyricService, audioService, props, Runnable::run, null);
        searchService = new MusicSearchService(catalogRepo);
    }

    @Test
    void testSearchRangeStreamLegacyCompatCoverAndLyric() throws Exception {
        long playlistId = catalogRepo.upsertPlaylist(-100111L, -100222L, "华语精选", "Music");

        File coverFile = Path.of(props.getCoverDir(), "abc.jpg").toFile();
        Files.write(coverFile.toPath(), new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3});

        Track t = new Track();
        t.setNormKey("晴天::周杰伦::");
        t.setTitle("晴天");
        t.setArtist("周杰伦");
        t.setAlbum("叶惠美");
        t.setDurationSec(269);
        t.setQualityScore(960);
        t.setStoredChatId(-100999L);
        t.setStoredMsgId(4160749568L);
        t.setStoredCodec("opus");
        t.setStoredBitrate(192);
        t.setStoredSize(4096);
        t.setMimeType("audio/ogg");
        t.setFileName("晴天 - 周杰伦.ogg");
        t.setCoverPath("abc.jpg");
        t.setMetadataEnriched(true);
        t.setStatus(Track.STATUS_READY);
        long trackId = catalogRepo.upsertTrackAndFts(t);
        catalogRepo.linkTrackToPlaylist(playlistId, trackId);

        TrackLyric l = new TrackLyric();
        l.setTrackId(trackId);
        l.setSource(TrackLyric.SOURCE_EMBEDDED);
        l.setLrc("[00:01.00]从前从前");
        l.setStatus(TrackLyric.STATUS_FOUND);
        lyricRepo.upsert(l);

        // 1. 繁体+多词混合搜索
        MediaResponse<MusicItem> searchRes = searchService.searchMusic("周杰倫 晴天", 1);
        assertEquals(1, searchRes.getData().size());
        MusicItem item = searchRes.getData().get(0);
        assertEquals(trackId, item.getMusicId());
        assertEquals("叶惠美", item.getAlbum());
        assertEquals("/music/cover/" + trackId, item.getCoverUrl());
        assertEquals("OPUS-192K", item.getQualityTag());

        // 2. 文件夹查询（兼容旧 legacy_chat_id + 虚拟歌单：最新入库 / 高品精选）
        assertTrue(streamService.folders().size() >= 3);
        assertEquals(MusicStreamService.VIRTUAL_FOLDER_LATEST, streamService.folders().get(0).getFolderId());
        assertEquals(MusicStreamService.VIRTUAL_FOLDER_HIQ, streamService.folders().get(1).getFolderId());
        assertEquals(1, streamService.musicList(MusicStreamService.VIRTUAL_FOLDER_LATEST, 1).getData().size());
        assertEquals(1, streamService.musicList(MusicStreamService.VIRTUAL_FOLDER_HIQ, 1).getData().size());

        MediaResponse<MusicItem> folderRes = streamService.musicList(-100222L, 1);
        assertEquals(1, folderRes.getData().size());

        // 3. 模拟本地音频文件并测试 HTTP Range (206) + 新旧 ID 双兼容
        File audioFile = tempDir.resolve("sample.ogg").toFile();
        Files.write(audioFile.toPath(), new byte[4096]);
        TdApi.File tdFile = new TdApi.File();
        tdFile.local = new TdApi.LocalFile();
        tdFile.local.isDownloadingCompleted = true;
        tdFile.local.path = audioFile.getAbsolutePath();
        when(tdGateway.downloadFileAwait(eq(-100999L), eq(4160749568L), any(Duration.class)))
                .thenReturn(new TdGatewayService.DownloadResult(tdFile, false, null));

        HttpHeaders rangeHeaders = new HttpHeaders();
        rangeHeaders.add(HttpHeaders.RANGE, "bytes=0-1023");
        // 通过新 trackId 播放（验证 206 + ETag + Cache-Control）
        ResponseEntity<ResourceRegion> r1 = streamService.streamMusic(trackId, -100111L, rangeHeaders);
        assertEquals(HttpStatus.PARTIAL_CONTENT, r1.getStatusCode());
        assertNotNull(r1.getHeaders().getETag());
        assertNotNull(r1.getBody());
        assertEquals(1024L, r1.getBody().getCount());

        // 预热接口 + 状态统计接口
        assertEquals(HttpStatus.ACCEPTED, streamService.prefetchMusic(trackId, -100111L).getStatusCode());
        Mockito.verify(tdGateway).prefetchFileAsync(-100999L, 4160749568L);
        var stats = streamService.stats();
        assertTrue(stats.containsKey("tracksByStatus"));
        assertTrue(stats.containsKey("storage"));

        // 故障恢复：卡在 PROCESSING 的源任务可被重置为 NEW
        dev.voroby.telegram.music.model.SourceTrack stuck = new dev.voroby.telegram.music.model.SourceTrack();
        stuck.setSourceChatId(-100111L);
        stuck.setSourceMsgId(777L);
        stuck.setSentAt(1700000000L);
        stuck.setStatus(dev.voroby.telegram.music.model.SourceTrack.STATUS_PROCESSING);
        catalogRepo.insertSourceTrackIgnore(stuck);
        assertEquals(1, catalogRepo.resetStaleProcessingSources(System.currentTimeMillis() + 1000L));

        // 通过旧收藏夹 (storedChatId, storedMsgId) 兜底播放
        ResponseEntity<ResourceRegion> r2 = streamService.streamMusic(4160749568L, -100999L, new HttpHeaders());
        assertEquals(HttpStatus.OK, r2.getStatusCode());
        assertNotNull(r2.getBody());
        assertEquals(4096L, r2.getBody().getCount());

        // 4. 封面、单曲详情刷新与歌词接口
        ResponseEntity<Resource> cov = streamService.getCover(trackId);
        assertEquals(HttpStatus.OK, cov.getStatusCode());

        ResponseEntity<MusicItem> infoByNewId = streamService.getMusicInfo(trackId, -100111L);
        assertEquals(HttpStatus.OK, infoByNewId.getStatusCode());
        assertNotNull(infoByNewId.getBody());
        assertEquals("叶惠美", infoByNewId.getBody().getAlbum());
        assertEquals("/music/cover/" + trackId, infoByNewId.getBody().getCoverUrl());

        ResponseEntity<MusicItem> infoByLegacy = streamService.getMusicInfo(4160749568L, -100999L);
        assertEquals(HttpStatus.OK, infoByLegacy.getStatusCode());
        assertNotNull(infoByLegacy.getBody());
        assertEquals(trackId, infoByLegacy.getBody().getMusicId());

        ResponseEntity<String> lyr = streamService.getLyric(trackId, -100111L);
        assertEquals(HttpStatus.OK, lyr.getStatusCode());
        assertEquals("[00:01.00]从前从前", lyr.getBody());
    }

    @Test
    void testCoverQuerySignatureAuth() throws Exception {
        dev.voroby.telegram.music.auth.ApiSignatureFilter filter = new dev.voroby.telegram.music.auth.ApiSignatureFilter();
        java.lang.reflect.Field f = dev.voroby.telegram.music.auth.ApiSignatureFilter.class.getDeclaredField("rawApiKeys");
        f.setAccessible(true);
        f.set(filter, "mykey:mysecret;");
        filter.init();

        String coverPath = "/music/cover/42";
        String validSig = new org.apache.commons.codec.digest.HmacUtils(
                org.apache.commons.codec.digest.HmacAlgorithms.HMAC_SHA_256, "mysecret")
                .hmacHex("mykey:" + coverPath);

        org.springframework.mock.web.MockHttpServletRequest okReq =
                new org.springframework.mock.web.MockHttpServletRequest("GET", coverPath);
        okReq.setServletPath(coverPath);
        okReq.setParameter("k", "mykey");
        okReq.setParameter("s", validSig);
        org.springframework.mock.web.MockHttpServletResponse okResp = new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.mock.web.MockFilterChain okChain = new org.springframework.mock.web.MockFilterChain();
        filter.doFilter(okReq, okResp, okChain);
        assertEquals(200, okResp.getStatus());
        assertNotNull(okChain.getRequest());

        org.springframework.mock.web.MockHttpServletRequest badReq =
                new org.springframework.mock.web.MockHttpServletRequest("GET", coverPath);
        badReq.setServletPath(coverPath);
        badReq.setParameter("k", "mykey");
        badReq.setParameter("s", "deadbeef");
        org.springframework.mock.web.MockHttpServletResponse badResp = new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.mock.web.MockFilterChain badChain = new org.springframework.mock.web.MockFilterChain();
        filter.doFilter(badReq, badResp, badChain);
        assertEquals(401, badResp.getStatus());
        assertNull(badChain.getRequest());
    }
}
