package dev.voroby.telegram.music.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.voroby.telegram.music.config.MusicProperties;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioHeader;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * 音频元数据探测、内嵌封面/歌词提取与智能 Opus 转码服务：
 * - 全部子进程使用 ProcessBuilder 参数数组，彻底消除 /bin/sh -c 注入与特殊字符失败；
 * - jaudiotagger + ffprobe 双保险（同时读 format.tags 与 stream.tags，不区分大小写）；
 * - 封面用 ffmpeg -threads 1 压缩为 240px JPEG 并按 SHA-256 去重，适配 1GB 内存与 8GB 磁盘；
 * - 无损源单进程 ffmpeg -threads 1 转 192k VBR Ogg Opus；<=15MB 常规有损原样直通。
 */
@Service
public class AudioMetadataService {
    private static final Logger log = LoggerFactory.getLogger(AudioMetadataService.class);

    static {
        // 抑制 jaudiotagger 冗长 JUL 日志
        java.util.logging.Logger.getLogger("org.jaudiotagger").setLevel(Level.OFF);
    }

    private final MusicProperties properties;
    private final TrackNormalizationService normService;

    public AudioMetadataService(MusicProperties properties, TrackNormalizationService normService) {
        this.properties = properties;
        this.normService = normService;
    }

    /**
     * 完整探测本地音频文件：标题、歌手、专辑、时长、编解码、码率、采样率、位深、内嵌歌词与封面。
     */
    @Nonnull
    public AudioProbeResult probe(@Nonnull File file,
                                  @Nullable String fallbackTitle,
                                  @Nullable String fallbackArtist,
                                  @Nullable String fallbackFileName,
                                  @Nullable String fallbackCaption,
                                  @Nullable String fallbackMimeType) {
        String title = "";
        String artist = "";
        String album = "";
        String embeddedLyrics = "";
        String coverRelPath = null;
        String codec = "";
        int bitrateKbps = 0;
        int sampleRate = 0;
        int bitDepth = 0;
        int durationSec = 0;

        // 1. 先用 jaudiotagger 毫秒级读内嵌标签、内嵌歌词与封面字节
        byte[] rawArtworkBytes = null;
        try {
            AudioFile af = AudioFileIO.read(file);
            AudioHeader header = af.getAudioHeader();
            if (header != null) {
                durationSec = header.getTrackLength();
                bitrateKbps = (int) header.getBitRateAsNumber();
                sampleRate = header.getSampleRateAsNumber();
                bitDepth = header.getBitsPerSample();
                codec = StringUtils.defaultString(header.getEncodingType());
            }
            Tag tag = af.getTag();
            if (tag != null) {
                title = firstNonBlank(tag.getFirst(FieldKey.TITLE));
                artist = firstNonBlank(tag.getFirst(FieldKey.ARTIST), tag.getFirst(FieldKey.ALBUM_ARTIST));
                album = firstNonBlank(tag.getFirst(FieldKey.ALBUM));
                embeddedLyrics = firstNonBlank(tag.getFirst(FieldKey.LYRICS));
                Artwork artwork = tag.getFirstArtwork();
                if (artwork != null && artwork.getBinaryData() != null && artwork.getBinaryData().length > 100
                        && artwork.getBinaryData().length <= 8 * 1024 * 1024) {
                    rawArtworkBytes = artwork.getBinaryData();
                }
            }
        } catch (Exception e) {
            log.debug("jaudiotagger read skipped/failed for {}: {}", file.getName(), e.getMessage());
        }

        // 2. 用 ffprobe 精确读取 codec / bitrate / sample_rate / bit_depth / duration 及 format+stream 双层 tags
        FfprobeInfo fp = runFfprobe(file);
        if (fp != null) {
            if (StringUtils.isNotBlank(fp.codec)) codec = fp.codec;
            if (fp.bitrateKbps > 0) bitrateKbps = fp.bitrateKbps;
            if (fp.sampleRate > 0) sampleRate = fp.sampleRate;
            if (fp.bitDepth > 0) bitDepth = fp.bitDepth;
            if (fp.durationSec > 0) durationSec = fp.durationSec;

            if (StringUtils.isBlank(title)) title = firstNonBlank(fp.tags.get("title"));
            if (StringUtils.isBlank(artist)) artist = firstNonBlank(fp.tags.get("artist"), fp.tags.get("album_artist"), fp.tags.get("performer"));
            if (StringUtils.isBlank(album)) album = firstNonBlank(fp.tags.get("album"));
            if (StringUtils.isBlank(embeddedLyrics)) {
                embeddedLyrics = firstNonBlank(fp.tags.get("lyrics"), fp.tags.get("unsyncedlyrics"), fp.tags.get("lyric"));
            }
        }

        // 3. 保存压缩后的 240px JPEG 封面（按 SHA-256 去重，不占 JVM 堆解码图像）
        if (rawArtworkBytes != null) {
            coverRelPath = saveCompressedCover(rawArtworkBytes);
        }

        // 4. 逐级兜底：TDLib 原始字段 -> 文件名/Caption 解析
        title = normService.cleanDisplayText(title);
        artist = normService.cleanDisplayText(artist);
        album = normService.cleanDisplayText(album);

        if (StringUtils.isBlank(title)) {
            title = normService.cleanDisplayText(fallbackTitle);
        }
        if (StringUtils.isBlank(artist)) {
            artist = normService.cleanDisplayText(fallbackArtist);
        }

        String effectiveFileName = StringUtils.defaultIfBlank(fallbackFileName, file.getName());
        if (StringUtils.isBlank(title) || StringUtils.isBlank(artist)) {
            TrackNormalizationService.ParsedName parsed = normService.parseFromFilenameOrCaption(effectiveFileName, fallbackCaption);
            if (StringUtils.isBlank(title)) title = parsed.title();
            if (StringUtils.isBlank(artist)) artist = parsed.artist();
        }
        if (StringUtils.isBlank(title)) {
            title = normService.cleanDisplayText(FilenameUtils.getBaseName(effectiveFileName));
        }

        String versionTag = normService.extractVersionTag(title, effectiveFileName);
        int qualityScore = normService.computeQualityScore(codec, bitrateKbps, sampleRate, bitDepth, effectiveFileName, fallbackMimeType);
        boolean lossless = normService.isLosslessCodecOrExt(codec, effectiveFileName, fallbackMimeType);

        return new AudioProbeResult(
                title, artist, album, versionTag, durationSec,
                codec, bitrateKbps, sampleRate, bitDepth, qualityScore, lossless,
                StringUtils.trimToNull(embeddedLyrics), coverRelPath
        );
    }

    /**
     * EBU R128 Pass 1：测量音频的集成响度 (LUFS)、真峰值 (dBTP)、动态范围 (LRA) 与偏移量。
     * 对极静音频（< -60 LUFS 或 -inf）返回 null，避免误放大静音底噪。
     */
    @Nullable
    public LoudnessMeasurement measureLoudness(@Nonnull File srcFile) {
        String filter = String.format(Locale.ROOT,
                "loudnorm=I=%.1f:TP=%.1f:LRA=%.1f:print_format=json",
                properties.getLoudnessTargetLufs(),
                properties.getLoudnessTruePeak(),
                properties.getLoudnessLra());
        List<String> cmd = List.of(
                "ffmpeg", "-hide_banner", "-nostats",
                "-threads", "1",
                "-i", srcFile.getAbsolutePath(),
                "-vn",
                "-af", filter,
                "-f", "null", "-"
        );
        CommandResult res = execArgs(cmd, 90);
        if (!res.success || StringUtils.isBlank(res.output)) {
            return null;
        }
        try {
            int jsonStart = res.output.lastIndexOf('{');
            int jsonEnd = res.output.lastIndexOf('}');
            if (jsonStart < 0 || jsonEnd <= jsonStart) {
                return null;
            }
            JsonObject obj = JsonParser.parseString(res.output.substring(jsonStart, jsonEnd + 1)).getAsJsonObject();
            double inputI = parseFiniteDouble(obj, "input_i");
            double inputTp = parseFiniteDouble(obj, "input_tp");
            double inputLra = parseFiniteDouble(obj, "input_lra");
            double inputThresh = parseFiniteDouble(obj, "input_thresh");
            double targetOffset = parseFiniteDouble(obj, "target_offset");
            if (!Double.isFinite(inputI) || inputI < -60.0 || !Double.isFinite(inputTp)
                    || !Double.isFinite(inputLra) || !Double.isFinite(inputThresh) || !Double.isFinite(targetOffset)) {
                return null;
            }
            return new LoudnessMeasurement(inputI, inputTp, inputLra, inputThresh, targetOffset);
        } catch (Exception e) {
            log.debug("measureLoudness parse skipped for {}: {}", srcFile.getName(), e.getMessage());
            return null;
        }
    }

    /**
     * 按配置决定转码或直通（整合 EBU R128 两遍线性响度归一化）：
     * - 无损格式 OR 体积超过 lossyReencodeThresholdMb OR 响度偏离目标超过 lossyToleranceDb
     *   -> 单进程 ffmpeg 2-pass linear loudnorm 转 Ogg Opus (192k VBR)；
     * - 体积与响度均达标的有损格式（MP3/M4A/Opus）-> 直接原样直通，零重编码损失。
     */
    @Nonnull
    public TranscodeOutput prepareForUpload(@Nonnull File srcFile,
                                            @Nonnull AudioProbeResult probe,
                                            @Nullable String originalFileName) throws IOException {
        long thresholdBytes = (long) properties.getLossyReencodeThresholdMb() * 1024L * 1024L;
        boolean alreadyOpus = "opus".equalsIgnoreCase(probe.codec());

        LoudnessMeasurement lm = null;
        boolean needsLoudnessFix = false;
        if (properties.isLoudnessEnabled()) {
            lm = measureLoudness(srcFile);
            if (lm != null) {
                double diffDb = Math.abs(lm.inputI() - properties.getLoudnessTargetLufs());
                needsLoudnessFix = diffDb > properties.getLoudnessLossyToleranceDb();
            }
        }

        boolean needTranscode = probe.lossless()
                || (!alreadyOpus && srcFile.length() > thresholdBytes)
                || needsLoudnessFix;

        String baseName = buildSafeBaseName(probe.title(), probe.artist(), originalFileName);
        if (!needTranscode) {
            String ext = StringUtils.defaultIfBlank(FilenameUtils.getExtension(
                    StringUtils.defaultIfBlank(originalFileName, srcFile.getName())), "mp3").toLowerCase(Locale.ROOT);
            String mime = mimeForExtension(ext);
            String finalName = baseName + "." + ext;
            String storedCodec = StringUtils.defaultIfBlank(probe.codec(), ext);
            Double lufs = lm != null ? round1(lm.inputI()) : null;
            return new TranscodeOutput(srcFile, false, finalName, mime, storedCodec, probe.bitrateKbps(),
                    srcFile.length(), lufs, properties.isLoudnessEnabled());
        }

        return transcodeToNormalizedOpus(srcFile, probe.title(), probe.artist(), probe.album(), baseName, lm);
    }

    /**
     * 执行单进程 192k VBR Ogg Opus 编码（带可选 EBU R128 Pass 2 线性响度归一化）。
     */
    @Nonnull
    public TranscodeOutput transcodeToNormalizedOpus(@Nonnull File srcFile,
                                                     @Nullable String title,
                                                     @Nullable String artist,
                                                     @Nullable String album,
                                                     @Nullable String baseNameHint,
                                                     @Nullable LoudnessMeasurement lm) throws IOException {
        String baseName = StringUtils.isNotBlank(baseNameHint)
                ? baseNameHint : buildSafeBaseName(title, artist, srcFile.getName());
        File tmpDir = new File(properties.getTempDir());
        Files.createDirectories(tmpDir.toPath());
        File dstFile = new File(tmpDir, UUID.randomUUID() + ".ogg");

        String bitrate = StringUtils.defaultIfBlank(properties.getOpusBitrate(), "192k");
        List<String> cmd = new ArrayList<>(List.of(
                "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                "-threads", "1",
                "-i", srcFile.getAbsolutePath(),
                "-vn",
                "-map_metadata", "0"
        ));
        if (lm != null && properties.isLoudnessEnabled()) {
            String linearFilter = String.format(Locale.ROOT,
                    "loudnorm=I=%.1f:TP=%.1f:LRA=%.1f:measured_I=%.2f:measured_LRA=%.2f:measured_TP=%.2f:measured_thresh=%.2f:offset=%.2f:linear=true",
                    properties.getLoudnessTargetLufs(),
                    properties.getLoudnessTruePeak(),
                    properties.getLoudnessLra(),
                    lm.inputI(), lm.inputLra(), lm.inputTp(), lm.inputThresh(), lm.targetOffset());
            cmd.add("-af");
            cmd.add(linearFilter);
            cmd.add("-ar");
            cmd.add("48000");
        }
        cmd.addAll(List.of(
                "-c:a", "libopus",
                "-b:a", bitrate,
                "-vbr", "on",
                "-compression_level", "10"
        ));
        if (StringUtils.isNotBlank(title)) {
            cmd.add("-metadata");
            cmd.add("title=" + title);
        }
        if (StringUtils.isNotBlank(artist)) {
            cmd.add("-metadata");
            cmd.add("artist=" + artist);
        }
        if (StringUtils.isNotBlank(album)) {
            cmd.add("-metadata");
            cmd.add("album=" + album);
        }
        cmd.add(dstFile.getAbsolutePath());

        CommandResult res = execArgs(cmd, 180);
        if (!res.success || !dstFile.exists() || dstFile.length() == 0) {
            FileUtils.deleteQuietly(dstFile);
            throw new IOException("ffmpeg opus transcode failed (exit=" + res.exitCode + "): " + res.output);
        }

        int targetKbps = parseBitrateKbps(bitrate);
        boolean normalized = lm != null && properties.isLoudnessEnabled();
        Double lufs = normalized ? round1(properties.getLoudnessTargetLufs()) : null;
        return new TranscodeOutput(dstFile, true, baseName + ".ogg", "audio/ogg", "opus", targetKbps,
                dstFile.length(), lufs, normalized);
    }

    @Nullable
    public String saveCompressedCover(@Nonnull byte[] rawImageBytes) {
        File tmpIn = null;
        File tmpOut = null;
        try {
            File coverDir = new File(properties.getCoverDir());
            Files.createDirectories(coverDir.toPath());
            File tmpDir = new File(properties.getTempDir());
            Files.createDirectories(tmpDir.toPath());

            String hash = DigestUtils.sha256Hex(rawImageBytes).substring(0, 24);
            String fileName = hash + ".jpg";
            File finalFile = new File(coverDir, fileName);
            if (finalFile.exists() && finalFile.length() > 0) {
                finalFile.setLastModified(System.currentTimeMillis());
                return fileName;
            }

            tmpIn = new File(tmpDir, "cov_in_" + UUID.randomUUID());
            tmpOut = new File(tmpDir, "cov_out_" + UUID.randomUUID() + ".jpg");
            Files.write(tmpIn.toPath(), rawImageBytes);

            List<String> cmd = List.of(
                    "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-threads", "1",
                    "-i", tmpIn.getAbsolutePath(),
                    "-vf", "scale='min(240,iw)':-2",
                    "-q:v", "5",
                    "-frames:v", "1",
                    tmpOut.getAbsolutePath()
            );
            CommandResult res = execArgs(cmd, 20);
            if (res.success && tmpOut.exists() && tmpOut.length() > 0) {
                FileUtils.moveFile(tmpOut, finalFile);
                return fileName;
            }
            // 若原图本身已经很小（<64KB JPEG），直接保存作为兜底
            if (rawImageBytes.length <= 64 * 1024 && rawImageBytes.length > 2
                    && (rawImageBytes[0] & 0xFF) == 0xFF && (rawImageBytes[1] & 0xFF) == 0xD8) {
                Files.write(finalFile.toPath(), rawImageBytes);
                return fileName;
            }
        } catch (Exception e) {
            log.debug("saveCompressedCover failed: {}", e.getMessage());
        } finally {
            FileUtils.deleteQuietly(tmpIn);
            FileUtils.deleteQuietly(tmpOut);
        }
        return null;
    }

    @Nullable
    private FfprobeInfo runFfprobe(@Nonnull File file) {
        List<String> cmd = List.of(
                "ffprobe", "-v", "error",
                "-show_format", "-show_streams",
                "-select_streams", "a:0",
                "-print_format", "json",
                file.getAbsolutePath()
        );
        CommandResult res = execArgs(cmd, 25);
        if (!res.success || StringUtils.isBlank(res.output)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(res.output).getAsJsonObject();
            String codec = "";
            int bitrateKbps = 0;
            int sampleRate = 0;
            int bitDepth = 0;
            int durationSec = 0;
            Map<String, String> tags = new HashMap<>();

            if (root.has("format") && root.get("format").isJsonObject()) {
                JsonObject fmt = root.getAsJsonObject("format");
                if (fmt.has("duration")) {
                    durationSec = (int) Math.round(Double.parseDouble(fmt.get("duration").getAsString()));
                }
                if (fmt.has("bit_rate")) {
                    bitrateKbps = (int) (Long.parseLong(fmt.get("bit_rate").getAsString()) / 1000L);
                }
                collectTagsLowercase(fmt.getAsJsonObject("tags"), tags);
            }
            if (root.has("streams") && root.get("streams").isJsonArray()) {
                JsonArray streams = root.getAsJsonArray("streams");
                if (!streams.isEmpty() && streams.get(0).isJsonObject()) {
                    JsonObject st = streams.get(0).getAsJsonObject();
                    if (st.has("codec_name")) codec = st.get("codec_name").getAsString();
                    if (st.has("sample_rate")) {
                        sampleRate = Integer.parseInt(st.get("sample_rate").getAsString());
                    }
                    if (st.has("bits_per_raw_sample")) {
                        try { bitDepth = Integer.parseInt(st.get("bits_per_raw_sample").getAsString()); } catch (Exception ignored) {}
                    }
                    if (bitDepth == 0 && st.has("bits_per_sample")) {
                        bitDepth = st.get("bits_per_sample").getAsInt();
                    }
                    if (bitrateKbps == 0 && st.has("bit_rate")) {
                        try { bitrateKbps = (int) (Long.parseLong(st.get("bit_rate").getAsString()) / 1000L); } catch (Exception ignored) {}
                    }
                    if (durationSec == 0 && st.has("duration")) {
                        try { durationSec = (int) Math.round(Double.parseDouble(st.get("duration").getAsString())); } catch (Exception ignored) {}
                    }
                    // Vorbis Comment (FLAC/Ogg) 标签常存在于 stream.tags
                    collectTagsLowercase(st.getAsJsonObject("tags"), tags);
                }
            }
            return new FfprobeInfo(codec, bitrateKbps, sampleRate, bitDepth, durationSec, tags);
        } catch (Exception e) {
            log.warn("Failed to parse ffprobe json for {}: {}", file.getName(), e.getMessage());
            return null;
        }
    }

    private static void collectTagsLowercase(@Nullable JsonObject tagObj, Map<String, String> out) {
        if (tagObj == null) return;
        for (Map.Entry<String, JsonElement> entry : tagObj.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                String k = entry.getKey().toLowerCase(Locale.ROOT);
                String v = entry.getValue().getAsString().trim();
                if (!v.isEmpty()) {
                    out.putIfAbsent(k, v);
                }
            }
        }
    }

    private CommandResult execArgs(List<String> args, int timeoutSeconds) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            process = pb.start();
            final Process proc = process;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Thread readerThread = new Thread(() -> {
                try (InputStream is = proc.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    int total = 0;
                    while ((n = is.read(buf)) != -1) {
                        if (total < 256 * 1024) { // 防止极端输出撑爆 1GB 内存
                            baos.write(buf, 0, n);
                            total += n;
                        }
                    }
                } catch (IOException ignored) {
                    // 子进程超时强杀关闭管道时会触发 IOException，属正常退出
                }
            }, "media-proc-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                readerThread.join(2000);
                return new CommandResult(false, -1, "command timed out after " + timeoutSeconds + "s");
            }
            readerThread.join(2000);
            int code = process.exitValue();
            return new CommandResult(code == 0, code, baos.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            if (process != null) process.destroyForcibly();
            return new CommandResult(false, -1, e.getMessage());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (StringUtils.isNotBlank(v)) return v.trim();
        }
        return "";
    }

    private static String buildSafeBaseName(String title, String artist, String originalFileName) {
        String raw;
        if (StringUtils.isNotBlank(title)) {
            raw = StringUtils.isNotBlank(artist) ? title + " - " + artist : title;
        } else if (StringUtils.isNotBlank(originalFileName)) {
            raw = FilenameUtils.getBaseName(originalFileName);
        } else {
            raw = "track_" + System.currentTimeMillis();
        }
        String safe = raw.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (safe.length() > 120) {
            safe = safe.substring(0, 120).trim();
        }
        return safe.isEmpty() ? "track" : safe;
    }

    private static String mimeForExtension(String ext) {
        return switch (ext) {
            case "ogg", "opus" -> "audio/ogg";
            case "m4a", "aac" -> "audio/mp4";
            case "flac" -> "audio/flac";
            case "wav" -> "audio/wav";
            default -> "audio/mpeg";
        };
    }

    private static int parseBitrateKbps(String bitrate) {
        try {
            return Integer.parseInt(bitrate.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 192;
        }
    }

    private static double parseFiniteDouble(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return Double.NaN;
        try {
            return Double.parseDouble(obj.get(key).getAsString().trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private record CommandResult(boolean success, int exitCode, String output) {}
    private record FfprobeInfo(String codec, int bitrateKbps, int sampleRate, int bitDepth, int durationSec, Map<String, String> tags) {}

    public record LoudnessMeasurement(
            double inputI,
            double inputTp,
            double inputLra,
            double inputThresh,
            double targetOffset
    ) {}

    public record AudioProbeResult(
            String title,
            String artist,
            String album,
            String versionTag,
            int durationSec,
            String codec,
            int bitrateKbps,
            int sampleRate,
            int bitDepth,
            int qualityScore,
            boolean lossless,
            @Nullable String embeddedLyrics,
            @Nullable String coverRelPath
    ) {}

    public record TranscodeOutput(
            File file,
            boolean isTempFile,
            String uploadFileName,
            String mimeType,
            String storedCodec,
            int storedBitrateKbps,
            long fileSize,
            @Nullable Double loudnessLufs,
            boolean loudnessNormalized
    ) {}
}
