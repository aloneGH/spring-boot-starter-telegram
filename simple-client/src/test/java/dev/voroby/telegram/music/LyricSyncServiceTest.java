package dev.voroby.telegram.music;

import com.sun.net.httpserver.HttpServer;
import dev.voroby.telegram.music.config.MusicProperties;
import dev.voroby.telegram.music.migration.SchemaInitializer;
import dev.voroby.telegram.music.model.Track;
import dev.voroby.telegram.music.model.TrackLyric;
import dev.voroby.telegram.music.repository.MusicCatalogRepository;
import dev.voroby.telegram.music.repository.TrackLyricRepository;
import dev.voroby.telegram.music.service.LyricSyncService;
import dev.voroby.telegram.music.service.TrackNormalizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LyricSyncServiceTest {

    @TempDir
    Path tempDir;

    private HttpServer mockServer;
    private LyricSyncService lyricService;
    private MusicCatalogRepository catalogRepo;
    private TrackLyricRepository lyricRepo;

    @BeforeEach
    void setUp() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.start();
        int port = mockServer.getAddress().getPort();

        String url = "jdbc:sqlite:" + tempDir.resolve("lyric_test.db");
        DriverManagerDataSource ds = new DriverManagerDataSource(url);
        ds.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        TrackNormalizationService norm = new TrackNormalizationService();
        new SchemaInitializer(jdbc).initialize();
        catalogRepo = new MusicCatalogRepository(jdbc, norm);
        lyricRepo = new TrackLyricRepository(jdbc);

        MusicProperties props = new MusicProperties();
        props.setLrclibBaseUrl("http://127.0.0.1:" + port + "/lrclib");
        props.setNeteaseBaseUrl("http://127.0.0.1:" + port + "/netease");
        lyricService = new LyricSyncService(props, catalogRepo, lyricRepo, norm);
    }

    @AfterEach
    void tearDown() {
        if (mockServer != null) mockServer.stop(0);
    }

    @Test
    void testNeteaseDurationAndVersionScoringWithTranslationMerge() {
        // LRCLIB 返回 404，触发网易云兜底
        mockServer.createContext("/lrclib", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        // 网易云返回 3 条候选：第 1 条是 Live 版（错版），第 2 条时长差 25s（错版），第 3 条才是时长精确匹配的原版！
        mockServer.createContext("/netease/api/search/get", exchange -> {
            String json = """
                    {"code":200,"result":{"songs":[
                      {"id":101,"name":"晴天 (Live)","artists":[{"name":"周杰伦"}],"duration":295000},
                      {"id":102,"name":"晴天","artists":[{"name":"周杰伦"}],"duration":244000},
                      {"id":103,"name":"晴天","artists":[{"name":"周杰伦"}],"duration":269000}
                    ]}}
                    """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });
        mockServer.createContext("/netease/api/song/lyric", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            assertTrue(query.contains("id=103"), "Must pick duration-matched studio version id=103, not songs[0]");
            String json = """
                    {"code":200,
                     "lrc":{"lyric":"[00:10.00]Hello world\\n[00:20.00]Second line"},
                     "tlyric":{"lyric":"[00:10.00]你好世界\\n[00:20.00]第二行"}}
                    """;
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });

        Track track = new Track();
        track.setNormKey("晴天::周杰伦::");
        track.setTitle("晴天");
        track.setArtist("周杰伦");
        track.setDurationSec(269);
        track.setVersionTag("");
        track.setStatus(Track.STATUS_READY);
        long id = catalogRepo.upsertTrackAndFts(track);
        track.setId(id);

        TrackLyric result = lyricService.fetchAndPersistLyric(track);
        assertNotNull(result);
        assertEquals(TrackLyric.STATUS_FOUND, result.getStatus());
        assertEquals(TrackLyric.SOURCE_NETEASE, result.getSource());
        assertEquals(269000, result.getMatchedDurationMs());

        String formatted = lyricService.formatLyricForClient(result);
        assertNotNull(formatted);
        assertTrue(formatted.contains("[00:10.00]Hello world"));
        assertTrue(formatted.contains("[00:10.00]你好世界"));
    }

    @Test
    void testNetease460SetsRetryInsteadOfPermanentNotFound() {
        mockServer.createContext("/lrclib", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        mockServer.createContext("/netease/api/search/get", exchange -> {
            byte[] bytes = "{\"code\":460,\"message\":\"Cheating\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        });

        Track track = new Track();
        track.setNormKey("测试::歌手::");
        track.setTitle("测试");
        track.setArtist("歌手");
        track.setDurationSec(200);
        track.setStatus(Track.STATUS_READY);
        long id = catalogRepo.upsertTrackAndFts(track);
        track.setId(id);

        TrackLyric result = lyricService.fetchAndPersistLyric(track);
        assertNotNull(result);
        assertEquals(TrackLyric.STATUS_RETRY, result.getStatus());
        assertEquals(1, result.getRetryCount());
        assertNotNull(result.getNextRetryAt());
    }
}
