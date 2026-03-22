package dev.voroby.telegram.music.service.netease;

public class LyricResponse {
    private Boolean sgc;
    private Boolean sfy;
    private Boolean qfy;
    private Lrc lrc;
    private Integer code;

    public LyricResponse() {
    }

    // Getter 和 Setter
    public Boolean getSgc() {
        return sgc;
    }

    public void setSgc(Boolean sgc) {
        this.sgc = sgc;
    }

    public Boolean getSfy() {
        return sfy;
    }

    public void setSfy(Boolean sfy) {
        this.sfy = sfy;
    }

    public Boolean getQfy() {
        return qfy;
    }

    public void setQfy(Boolean qfy) {
        this.qfy = qfy;
    }

    public Lrc getLrc() {
        return lrc;
    }

    public void setLrc(Lrc lrc) {
        this.lrc = lrc;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    // 内部类表示 lrc 节点
    public static class Lrc {
        private Integer version;
        private String lyric;

        public Lrc() {
        }

        public Integer getVersion() {
            return version;
        }

        public void setVersion(Integer version) {
            this.version = version;
        }

        public String getLyric() {
            return lyric;
        }

        public void setLyric(String lyric) {
            this.lyric = lyric;
        }
    }
}

