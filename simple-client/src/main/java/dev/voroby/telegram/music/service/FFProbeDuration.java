package dev.voroby.telegram.music.service;

import com.google.gson.annotations.SerializedName;

public class FFProbeDuration {
    @SerializedName("format")
    public Format format;

    public static class Format {
        @SerializedName("duration")
        public String duration;
    }
}
