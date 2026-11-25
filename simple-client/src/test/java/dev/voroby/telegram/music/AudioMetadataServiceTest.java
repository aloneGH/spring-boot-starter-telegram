package dev.voroby.telegram.music;

import dev.voroby.telegram.music.config.MusicProperties;
import dev.voroby.telegram.music.service.AudioMetadataService;
import dev.voroby.telegram.music.service.TrackNormalizationService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudioMetadataServiceTest {

    @TempDir
    Path tempDir;

    private boolean hasFfmpegAndLibopus() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders").start();
            String out = new String(p.getInputStream().readAllBytes());
            return p.waitFor() == 0 && out.contains("libopus");
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void testSpecialFilenameFlacProbeAndTranscodeTo192kOpus() throws Exception {
        Assumptions.assumeTrue(hasFfmpegAndLibopus(), "ffmpeg with libopus required");

        MusicProperties props = new MusicProperties();
        props.setOpusBitrate("192k");
        props.setLossyReencodeThresholdMb(15);
        props.setTempDir(tempDir.resolve("tmp").toString());
        props.setCoverDir(tempDir.resolve("covers").toString());

        TrackNormalizationService norm = new TrackNormalizationService();
        AudioMetadataService svc = new AudioMetadataService(props, norm);

        // 生成含特殊字符（双引号、$变量、空格、中文）且带完整 Vorbis 标签（含 album 与内嵌歌词）的真实 FLAC
        File flacFile = tempDir.resolve("A$AP \"测试\" - 晴天 (FLAC).flac").toFile();
        Process gen = new ProcessBuilder(List.of(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-c:a", "flac",
                "-metadata", "TITLE=晴天",
                "-metadata", "ARTIST=周杰伦",
                "-metadata", "ALBUM=叶惠美",
                "-metadata", "LYRICS=[00:01.00]故事的小黄花",
                flacFile.getAbsolutePath()
        )).start();
        assertEquals(0, gen.waitFor(), "ffmpeg should generate test flac");
        assertTrue(flacFile.exists() && flacFile.length() > 0);

        AudioMetadataService.AudioProbeResult probe = svc.probe(flacFile, null, null, flacFile.getName(), null, "application/octet-stream");
        assertEquals("晴天", probe.title());
        assertEquals("周杰伦", probe.artist());
        assertEquals("叶惠美", probe.album());
        assertTrue(probe.lossless());
        assertTrue(probe.qualityScore() >= 900);
        assertEquals(2, probe.durationSec());
        assertNotNull(probe.embeddedLyrics());
        assertTrue(probe.embeddedLyrics().contains("故事的小黄花"));

        AudioMetadataService.TranscodeOutput out = svc.prepareForUpload(flacFile, probe, flacFile.getName());
        assertTrue(out.isTempFile());
        assertEquals("audio/ogg", out.mimeType());
        assertEquals("opus", out.storedCodec());
        assertEquals(192, out.storedBitrateKbps());
        assertTrue(out.file().exists() && out.file().length() > 0);

        // 验证转码后的 .ogg 文件保留了元数据且响度已线性归一化到 -14 LUFS 附近
        AudioMetadataService.AudioProbeResult outProbe = svc.probe(out.file(), null, null, out.uploadFileName(), null, out.mimeType());
        assertEquals("opus", outProbe.codec());
        assertEquals("晴天", outProbe.title());
        assertEquals("周杰伦", outProbe.artist());
        assertEquals("叶惠美", outProbe.album());
        assertTrue(out.loudnessNormalized());
        AudioMetadataService.LoudnessMeasurement outLufs = svc.measureLoudness(out.file());
        assertNotNull(outLufs);
        assertEquals(-14.0, outLufs.inputI(), 1.5, "Normalized FLAC->Opus should be near -14 LUFS");
    }

    @Test
    void testSmallLossyMp3PassthroughWithoutReencode() throws Exception {
        Assumptions.assumeTrue(hasFfmpegAndLibopus(), "ffmpeg required");

        MusicProperties props = new MusicProperties();
        props.setOpusBitrate("192k");
        props.setLossyReencodeThresholdMb(15);
        props.setTempDir(tempDir.resolve("tmp").toString());
        props.setCoverDir(tempDir.resolve("covers").toString());

        AudioMetadataService svc = new AudioMetadataService(props, new TrackNormalizationService());

        // 生成响度接近 -14 LUFS 的常规 MP3（lavfi sine 默认约 -21 LUFS，+7dB 后约 -14 LUFS，应在 ±2.5dB 容差内直通）
        File mp3File = tempDir.resolve("test_song.mp3").toFile();
        Process gen = new ProcessBuilder(List.of(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-af", "volume=+7dB",
                "-c:a", "libmp3lame", "-b:a", "192k",
                "-metadata", "title=夜曲",
                "-metadata", "artist=周杰伦",
                "-metadata", "album=十一月的萧邦",
                mp3File.getAbsolutePath()
        )).start();
        Assumptions.assumeTrue(gen.waitFor() == 0, "libmp3lame encoder required");

        AudioMetadataService.LoudnessMeasurement lm = svc.measureLoudness(mp3File);
        assertNotNull(lm);
        assertEquals(-14.0, lm.inputI(), 2.0, "Test MP3 loudness should be near -14 LUFS, got " + lm.inputI());

        AudioMetadataService.AudioProbeResult probe = svc.probe(mp3File, null, null, mp3File.getName(), null, "audio/mpeg");
        assertFalse(probe.lossless());
        assertEquals("夜曲", probe.title());
        assertEquals("十一月的萧邦", probe.album());

        AudioMetadataService.TranscodeOutput out = svc.prepareForUpload(mp3File, probe, mp3File.getName());
        assertFalse(out.isTempFile(), "Normal-loudness lossy MP3 must passthrough without re-encoding");
        assertTrue(out.loudnessNormalized());
        assertEquals(mp3File.getAbsolutePath(), out.file().getAbsolutePath());
        assertEquals("audio/mpeg", out.mimeType());
    }

    @Test
    void testQuietLossyMp3Triggers2PassLinearLoudnormToMinus14Lufs() throws Exception {
        Assumptions.assumeTrue(hasFfmpegAndLibopus(), "ffmpeg with libopus required");

        MusicProperties props = new MusicProperties();
        props.setOpusBitrate("192k");
        props.setLossyReencodeThresholdMb(15);
        props.setLoudnessTargetLufs(-14.0);
        props.setLoudnessLossyToleranceDb(2.5);
        props.setTempDir(tempDir.resolve("tmp").toString());
        props.setCoverDir(tempDir.resolve("covers").toString());

        AudioMetadataService svc = new AudioMetadataService(props, new TrackNormalizationService());

        // 生成非常小声（约 -26 LUFS）的 MP3，应触发 2-pass linear loudnorm 提升到 -14 LUFS
        File quietMp3 = tempDir.resolve("quiet_old_song.mp3").toFile();
        Process gen = new ProcessBuilder(List.of(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-af", "volume=-23dB",
                "-c:a", "libmp3lame", "-b:a", "192k",
                "-metadata", "title=老歌",
                "-metadata", "artist=经典歌手",
                quietMp3.getAbsolutePath()
        )).start();
        Assumptions.assumeTrue(gen.waitFor() == 0, "libmp3lame encoder required");

        AudioMetadataService.LoudnessMeasurement before = svc.measureLoudness(quietMp3);
        assertNotNull(before);
        assertTrue(before.inputI() < -22.0, "Source MP3 should be quiet (< -22 LUFS), got " + before.inputI());

        AudioMetadataService.AudioProbeResult probe = svc.probe(quietMp3, null, null, quietMp3.getName(), null, "audio/mpeg");
        AudioMetadataService.TranscodeOutput out = svc.prepareForUpload(quietMp3, probe, quietMp3.getName());
        assertTrue(out.isTempFile(), "Quiet MP3 exceeding tolerance should be normalized to Opus");
        assertTrue(out.loudnessNormalized());
        assertEquals("audio/ogg", out.mimeType());

        AudioMetadataService.LoudnessMeasurement after = svc.measureLoudness(out.file());
        assertNotNull(after);
        assertEquals(-14.0, after.inputI(), 1.5, "Normalized output should be near -14 LUFS, got " + after.inputI());
    }
}
