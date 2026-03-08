package dev.voroby.telegram.music.service;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;

@Service
public class AudioConvertService {
    private static final Logger log = LoggerFactory.getLogger(AudioConvertService.class);

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

        srcFilePath = "\"" + srcFilePath + "\"";
        destFilePath = "\"" + destFilePath + "\"";

        if (srcFilePath.endsWith(".flac")) {
            return convertFromFlacToOpus(srcFilePath, destFilePath);
        }

        List<String> commands = Arrays.asList(
                "ffmpeg", "-i", srcFilePath, "-f wav", "-", "|", "opusenc", "--bitrate 128k", "--comp 10", "--vbr", "-",
                destFilePath
        );

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
                "ffprobe", "-v error", "-show_entries", "format=duration", "-of default=noprint_wrappers=1:nokey=1", filePath
        );
        String command = String.join(" ", commands);
        StringBuilder output = new StringBuilder();
        boolean success = exeCommand(command, output);
        if (!success) {
            return 0;
        }

        try {
            log.info("queryDuration: {} -> {}", filePath, output);
            return (int) Float.parseFloat(output.toString());
        } catch (Exception e) {
            log.error("Failed to parse duration: {}", output);
        }
        return 0;
    }
}
