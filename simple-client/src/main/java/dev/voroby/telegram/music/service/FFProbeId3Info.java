package dev.voroby.telegram.music.service;

import com.google.gson.annotations.SerializedName;

import java.util.Map;

public class FFProbeId3Info {
    @SerializedName("format")
    public Format format;

    public static class Format {
        // ID3 信息（Vorbis Comment / ID3 Tags）都在这里
        @SerializedName("tags")
        public Map<String, String> tags;
    }
}
