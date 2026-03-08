package dev.voroby.telegram.music.service;

import com.google.gson.Gson;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

@Service
public class AudioConvertService {
    private static final Logger log = LoggerFactory.getLogger(AudioConvertService.class);

    public static final String ID3_TITLE = "title";
    public static final String ID3_ARTIST = "artist";
    public static final String ID3_ALBUM = "album";

    public AudioConvertService() {
    }

    private boolean exeCommand(@Nonnull String command, @Nonnull StringBuilder output) {
        ProcessBuilder processBuilder = new ProcessBuilder("/bin/sh", "-c", command);

        // 合并错误流到标准输出，方便调试
        processBuilder.redirectErrorStream(true);

        BufferedReader reader = null;
        try {
            Process process = processBuilder.start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            // 读取控制台输出（必须读取，否则缓冲区满会导致进程卡死）
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("exe command output: {}", line);
                output.append(line).append("\r\n");
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("exe command failed with exit code: {} -> {} -> {}", exitCode, command, output);
                return false;
            }

            return true;
        } catch (Exception e) {
            log.error("exe command failed", e);
        } finally {
            IOUtils.closeQuietly(reader);
        }

        return false;
    }

    private boolean exeCommand(@Nonnull String command) {
        return exeCommand(command, new StringBuilder());
    }

    public boolean convertToOpus(@Nullable String srcFilePath, @Nullable String destFilePath) {
        if (srcFilePath == null || destFilePath == null) {
            return false;
        }

        Map<String, String> id3Info = queryID3Info(srcFilePath);

        srcFilePath = "\"" + srcFilePath + "\"";
        destFilePath = "\"" + destFilePath + "\"";

        if (srcFilePath.endsWith(".flac")) {
            return convertFromFlacToOpus(srcFilePath, destFilePath);
        }

        List<String> commands = Arrays.asList(
                "ffmpeg", "-i", srcFilePath, "-f wav", "-", "|", "opusenc", "--bitrate 128k", "--comp 10", "--vbr"
        );
        commands = new ArrayList<>(commands);
        String title = id3Info.getOrDefault(ID3_TITLE, null);
        if (StringUtils.hasText(title)) {
            commands.add("--title \"" + title + "\"");
        }
        String artist = id3Info.getOrDefault(ID3_ARTIST, null);
        if (StringUtils.hasText(artist)) {
            commands.add("--artist \"" + artist + "\"");
        }
        String album = id3Info.getOrDefault(ID3_ALBUM, null);
        if (StringUtils.hasText(album)) {
            commands.add("--album \"" + album + "\"");
        }
        commands.add("-");
        commands.add(destFilePath);

        String command = String.join(" ", commands);
        boolean success = exeCommand(command);
        if (!success) {
            log.error("Failed to convert audio to Opus: {} -> {}", srcFilePath, destFilePath);
        }
        return success;
    }

    private boolean convertFromFlacToOpus(@Nullable String srcFilePath, @Nullable String destFilePath) {
        if (srcFilePath == null || destFilePath == null) {
            return false;
        }

        List<String> commands = Arrays.asList(
                "opusenc", "--bitrate 256k", "--comp 10", "--vbr", srcFilePath, destFilePath
        );
        String command = String.join(" ", commands);
        return exeCommand(command);
    }

    public int queryDuration(@Nullable String filePath) {
        if (filePath == null) {
            log.warn("queryDuration: failed, filePath is null");
            return 0;
        }

        filePath = "\"" + filePath + "\"";
        List<String> commands = Arrays.asList(
                "ffprobe", "-v error", "-show_entries", "format=duration", "-print_format json", filePath
        );
        String command = String.join(" ", commands);
        StringBuilder output = new StringBuilder();
        boolean success = exeCommand(command, output);
        if (!success) {
            return 0;
        }

        try {
            log.info("queryDuration: {} -> {}", filePath, output);
            FFProbeDuration probeResult = new Gson().fromJson(output.toString(), FFProbeDuration.class);
            return (int) Float.parseFloat(probeResult.format.duration);
        } catch (Exception e) {
            log.error("Failed to parse duration: {}", output);
        }
        return 0;
    }

    public Map<String, String> queryID3Info(@Nullable String filePath) {
        Map<String, String> result = new HashMap<>();
        if (filePath == null) {
            log.warn("queryID3Info: failed, filePath is null");
            return result;
        }

        filePath = "\"" + filePath + "\"";
        List<String> commands = Arrays.asList(
                "ffprobe", "-v error", "-show_entries", "format_tags=title,artist,album", "-print_format json", filePath
        );

        String command = String.join(" ", commands);
        StringBuilder output = new StringBuilder();
        boolean success = exeCommand(command, output);
        if (!success) {
            return result;
        }

        try {
            FFProbeId3Info probeResult = new Gson().fromJson(output.toString(), FFProbeId3Info.class);
            result.put(ID3_TITLE, probeResult.format.tags.getOrDefault("title", "").trim());
            result.put(ID3_ARTIST, probeResult.format.tags.getOrDefault("artist", "").trim());
            result.put(ID3_ALBUM, probeResult.format.tags.getOrDefault("album", "").trim());
        } catch (Exception e) {
            log.error("Failed to parse id3 info: {}", output);
        }

        return result;
    }
}
