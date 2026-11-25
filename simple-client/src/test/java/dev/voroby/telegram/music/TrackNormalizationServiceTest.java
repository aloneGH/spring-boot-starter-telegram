package dev.voroby.telegram.music;

import dev.voroby.telegram.music.service.ChineseNormalizer;
import dev.voroby.telegram.music.service.TrackNormalizationService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TrackNormalizationServiceTest {

    private final TrackNormalizationService norm = new TrackNormalizationService();

    @Test
    void testChineseNormalizerAndDisplayClean() {
        assertEquals("周杰伦", ChineseNormalizer.toSimplifiedAndHalfWidth("周杰倫"));
        assertEquals("陈奕迅", ChineseNormalizer.toSimplifiedAndHalfWidth("陳奕迅"));
        assertEquals("爱情", norm.normalizeForIndex("愛情 [FLAC] (HQ)"));
        assertEquals("周杰伦", norm.cleanDisplayText("null-周杰伦"));
        assertEquals("晴天", norm.cleanDisplayText("晴天-null"));
    }

    @Test
    void testVersionIsolationAndMultiArtistNormKey() {
        String k1 = norm.buildNormKey("珊瑚海 [FLAC]", "周杰倫 / 梁心頤", "a.flac",
                norm.extractVersionTag("珊瑚海 [FLAC]", "a.flac"));
        String k2 = norm.buildNormKey("珊瑚海 (HQ)", "梁心颐、周杰伦", "b.mp3",
                norm.extractVersionTag("珊瑚海 (HQ)", "b.mp3"));
        assertEquals(k1, k2, "Same song with traditional/simplified and reversed multi-artists must share norm_key");

        String kLive = norm.buildNormKey("珊瑚海 (Live)", "周杰伦、梁心颐", "c.flac",
                norm.extractVersionTag("珊瑚海 (Live)", "c.flac"));
        assertNotEquals(k1, kLive, "Live version must not merge with studio version");
        assertEquals("live", norm.extractVersionTag("晴天 (演唱会现场)", ""));
        assertEquals("dj", norm.extractVersionTag("谁 (DJ何鹏版)", ""));
        assertEquals("instrumental", norm.extractVersionTag("青花瓷 (伴奏)", ""));
    }

    @Test
    void testQualityScoreOrdering() {
        int hiresFlac = norm.computeQualityScore("flac", 2800, 96000, 24, "a.flac", "audio/flac");
        int cdFlac = norm.computeQualityScore("flac", 950, 44100, 16, "b.flac", "audio/flac");
        int opus192 = norm.computeQualityScore("opus", 192, 48000, 0, "c.ogg", "audio/ogg");
        int mp3_320 = norm.computeQualityScore("mp3", 320, 44100, 0, "d.mp3", "audio/mpeg");
        int opus128 = norm.computeQualityScore("opus", 128, 48000, 0, "e.ogg", "audio/ogg");
        int mp3_128 = norm.computeQualityScore("mp3", 128, 44100, 0, "f.mp3", "audio/mpeg");

        assertTrue(hiresFlac > cdFlac, "Hi-Res FLAC > CD FLAC");
        assertTrue(cdFlac > opus192, "CD FLAC > Opus 192k");
        assertTrue(opus192 > mp3_320, "Opus 192k > MP3 320k");
        assertTrue(mp3_320 > opus128, "MP3 320k > Opus 128k");
        assertTrue(opus128 > mp3_128, "Opus 128k > MP3 128k");
    }

    @Test
    void testAudioFileExtensionRecognition() {
        assertTrue(norm.isAudioFileName("track.flac"));
        assertTrue(norm.isAudioFileName("track.APE"));
        assertTrue(norm.isAudioFileName("track.m4a"));
        assertTrue(norm.isAudioFileName("track.opus"));
        assertFalse(norm.isAudioFileName("cover.jpg"));
        assertFalse(norm.isAudioFileName("video.mp4"));
    }

    @Test
    void testFilenameParsing() {
        TrackNormalizationService.ParsedName p = norm.parseFromFilenameOrCaption("01. 周杰伦 - 晴天 [FLAC].flac", null);
        assertEquals("晴天", p.title());
        assertEquals("周杰伦", p.artist());
    }
}
