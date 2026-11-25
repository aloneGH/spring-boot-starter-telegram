package dev.voroby.telegram.music.service;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责歌名/歌手/专辑文本清洗、版本隔离、规范去重键构建、文件名反解析与音质评分。
 */
@Service
public class TrackNormalizationService {

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape", "alac", "wma", "aiff", "dsf", "dff"
    );

    private static final Set<String> LOSSLESS_CODECS = Set.of(
            "flac", "wav", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le",
            "alac", "ape", "aiff", "dsf", "dff", "wv", "wavpack", "tta"
    );

    // 常见音质与渠道噪声后缀
    private static final Pattern NOISE_BRACKETS = Pattern.compile(
            "(?i)[(\\[【（]\\s*(hq|sq|hi-?res|flac|lossless|320k|128k|256k|mp3|wav|m4a|ape|dsd|24bit|16bit|无损|高品质|高品|超品|母带|臻品|完整版|正式版|官方版|原版|标准版|cd版)\\s*[)\\]】）]"
    );
    private static final Pattern NOISE_SUFFIX = Pattern.compile(
            "(?i)(?:[-_\\s]+(?:hq|sq|hi-?res|flac|320k|256k|192k|128k|lossless|无损|高品质))+$"
    );
    private static final Pattern URL_WATERMARK = Pattern.compile("(?i)(https?://\\S+|t\\.me/\\S+|@[a-z0-9_]{4,})");
    private static final Pattern PUNCT_TO_SPACE = Pattern.compile("[\\p{Punct}，。！？、；：“”‘’（）【】《》·—…~`]+");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    // 版本标签识别（英文词需边界防误伤 alive/discover，中文版本词直接匹配支持 "演唱会现场" 等组合）
    private static final Pattern VERSION_LIVE = Pattern.compile("(?i)(?:(?:^|[\\s(\\[【（_-])(live|unplugged)(?:$|[\\s)\\]】）_-])|现场|演唱会)");
    private static final Pattern VERSION_DJ = Pattern.compile("(?i)(?:(?:^|[\\s(\\[【（_-])dj(?![a-z0-9])|电音版|慢摇|串烧)");
    private static final Pattern VERSION_REMIX = Pattern.compile("(?i)(?:(?:^|[\\s(\\[【（_-])(remix|mashup|bootleg)(?:$|[\\s)\\]】）_-])|混音版)");
    private static final Pattern VERSION_INSTRUMENTAL = Pattern.compile("(?i)(?:(?:^|[\\s(\\[【（_-])(instrumental|karaoke|off\\s*vocal)(?:$|[\\s)\\]】）_-])|伴奏|纯音乐|卡拉ok)");
    private static final Pattern VERSION_COVER = Pattern.compile("(?i)(?:(?:^|[\\s(\\[【（_-])cover(?:$|[\\s)\\]】）_-])|翻唱)");
    private static final Pattern VERSION_ACOUSTIC = Pattern.compile("(?i)(?:(?:^|[\\s(\\[【（_-])(acoustic|piano)(?:$|[\\s)\\]】）_-])|木吉他|钢琴版)");

    // 去除标题里的版本括注用于提取纯歌名（保留版本信息到 versionTag）
    private static final Pattern VERSION_BRACKET_STRIP = Pattern.compile(
            "(?i)[(\\[【（][^)\\]】）]*(?:live|现场|dj|remix|伴奏|instrumental|cover|翻唱|acoustic|钢琴版|演唱会)[^)\\]】）]*[)\\]】）]"
    );

    public boolean isAudioFileName(String fileName) {
        if (StringUtils.isBlank(fileName)) {
            return false;
        }
        String ext = FilenameUtils.getExtension(fileName).toLowerCase(Locale.ROOT);
        return AUDIO_EXTENSIONS.contains(ext);
    }

    public boolean isAudioMimeType(String mimeType) {
        if (StringUtils.isBlank(mimeType)) {
            return false;
        }
        String m = mimeType.toLowerCase(Locale.ROOT);
        return m.startsWith("audio/") || m.contains("flac") || m.contains("mpeg") || m.contains("ogg") || m.contains("opus");
    }

    public boolean isLosslessCodecOrExt(String codec, String fileName, String mimeType) {
        if (StringUtils.isNotBlank(codec)) {
            String c = codec.toLowerCase(Locale.ROOT);
            if (LOSSLESS_CODECS.contains(c) || c.startsWith("pcm_")) {
                return true;
            }
        }
        if (StringUtils.isNotBlank(fileName)) {
            String ext = FilenameUtils.getExtension(fileName).toLowerCase(Locale.ROOT);
            if (Set.of("flac", "wav", "alac", "ape", "aiff", "dsf", "dff", "wv").contains(ext)) {
                return true;
            }
        }
        if (StringUtils.isNotBlank(mimeType)) {
            String m = mimeType.toLowerCase(Locale.ROOT);
            if (m.contains("flac") || m.contains("wav") || m.contains("alac") || m.contains("ape") || m.contains("aiff")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清洗展示用字段：去掉前后空白、修复历史 "null" 占位、去除 URL/水印。
     */
    public String cleanDisplayText(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.equalsIgnoreCase("null") || s.equalsIgnoreCase("unknown") || s.equalsIgnoreCase("<unknown>")) {
            return "";
        }
        // 修复旧 VoiceNote String.join("-", null, performer) 产生的 "null-xxx" 或 "xxx-null"
        if (s.startsWith("null-")) {
            s = s.substring(5).trim();
        }
        if (s.endsWith("-null")) {
            s = s.substring(0, s.length() - 5).trim();
        }
        s = URL_WATERMARK.matcher(s).replaceAll("").trim();
        s = NOISE_BRACKETS.matcher(s).replaceAll("").trim();
        s = NOISE_SUFFIX.matcher(s).replaceAll("").trim();
        s = MULTI_SPACE.matcher(s).replaceAll(" ").trim();
        return s;
    }

    /**
     * 规范化检索/匹配文本：繁转简 + 全角转半角 + 小写 + 去噪声与标点。
     */
    public String normalizeForIndex(String raw) {
        String cleaned = cleanDisplayText(raw);
        if (cleaned.isEmpty()) {
            return "";
        }
        String simp = ChineseNormalizer.toSimplifiedAndHalfWidth(cleaned).toLowerCase(Locale.ROOT);
        simp = PUNCT_TO_SPACE.matcher(simp).replaceAll(" ");
        return MULTI_SPACE.matcher(simp).replaceAll(" ").trim();
    }

    /**
     * 识别曲目版本标签："" 表示标准原版。
     */
    public String extractVersionTag(String title, String fileName) {
        String combined = ChineseNormalizer.toSimplifiedAndHalfWidth(
                StringUtils.defaultString(title) + " " + StringUtils.defaultString(fileName)
        );
        if (VERSION_INSTRUMENTAL.matcher(combined).find()) return "instrumental";
        if (VERSION_DJ.matcher(combined).find()) return "dj";
        if (VERSION_REMIX.matcher(combined).find()) return "remix";
        if (VERSION_LIVE.matcher(combined).find()) return "live";
        if (VERSION_COVER.matcher(combined).find()) return "cover";
        if (VERSION_ACOUSTIC.matcher(combined).find()) return "acoustic";
        return "";
    }

    /**
     * 去除标题中的版本括注，供歌词搜索与 norm_key 核心标题使用。
     */
    public String stripVersionAnnotations(String title) {
        if (StringUtils.isBlank(title)) {
            return "";
        }
        String s = VERSION_BRACKET_STRIP.matcher(title).replaceAll("").trim();
        return cleanDisplayText(s);
    }

    /**
     * 规范化歌手名：统一多歌手分隔符并按字典序排序（解决 "周杰伦/费玉清" vs "费玉清、周杰伦" 重复）。
     */
    public String normalizeArtistKey(String artist) {
        if (StringUtils.isBlank(artist)) {
            return "";
        }
        String simp = ChineseNormalizer.toSimplifiedAndHalfWidth(cleanDisplayText(artist)).toLowerCase(Locale.ROOT);
        simp = simp.replaceAll("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b", "/");
        String[] parts = simp.split("[,/&、;；+]+");
        List<String> tokens = new ArrayList<>();
        for (String p : parts) {
            String t = PUNCT_TO_SPACE.matcher(p).replaceAll(" ");
            t = MULTI_SPACE.matcher(t).replaceAll(" ").trim();
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        Collections.sort(tokens);
        return String.join("&", tokens);
    }

    /**
     * 构建稳定去重键：norm(coreTitle) + "::" + normArtist + "::" + versionTag
     */
    public String buildNormKey(String title, String artist, String fileName, String versionTag) {
        String coreTitle = normalizeForIndex(stripVersionAnnotations(title));
        if (coreTitle.isEmpty() && StringUtils.isNotBlank(fileName)) {
            coreTitle = normalizeForIndex(stripVersionAnnotations(FilenameUtils.getBaseName(fileName)));
        }
        String normArtist = normalizeArtistKey(artist);
        String ver = StringUtils.defaultString(versionTag).trim().toLowerCase(Locale.ROOT);
        if (coreTitle.isEmpty()) {
            return "unknown::" + Math.abs(Objects.hash(title, artist, fileName));
        }
        return coreTitle + "::" + normArtist + "::" + ver;
    }

    /**
     * 从文件名或消息 Caption 智能兜底解析 (title, artist)。
     */
    public ParsedName parseFromFilenameOrCaption(String fileName, String caption) {
        String candidate = null;
        if (StringUtils.isNotBlank(fileName)) {
            candidate = FilenameUtils.getBaseName(fileName);
        }
        if (StringUtils.isBlank(candidate) && StringUtils.isNotBlank(caption)) {
            // 只取第一行避免长文案干扰
            candidate = caption.split("\\R")[0];
        }
        if (StringUtils.isBlank(candidate)) {
            return new ParsedName("", "");
        }
        String cleaned = cleanDisplayText(candidate);
        if (cleaned.isEmpty()) {
            return new ParsedName("", "");
        }
        // 去掉开头的曲目编号，如 "01. 周杰伦 - 晴天" 或 "03 - 晴天"
        cleaned = cleaned.replaceFirst("^\\d{1,3}[.\\s_-]+", "").trim();

        // 优先匹配带空格的 " - " 或 " – "
        String[] parts = cleaned.split("\\s+[-–—]\\s+", 2);
        if (parts.length == 2) {
            String left = cleanDisplayText(parts[0]);
            String right = cleanDisplayText(parts[1]);
            // 中文音乐群最常见命名是 "歌手 - 歌名"（如 "周杰伦 - 可爱女人"）
            if (!left.isEmpty() && !right.isEmpty()) {
                return new ParsedName(right, left);
            }
        }
        // 其次匹配单个 "-"（仅当恰好两段）
        String[] tight = cleaned.split("-");
        if (tight.length == 2) {
            String left = cleanDisplayText(tight[0]);
            String right = cleanDisplayText(tight[1]);
            if (!left.isEmpty() && !right.isEmpty()) {
                // 如果右侧看起来像专辑/HQ 后缀已被清理，否则认为 "歌名-歌手" 或 "歌手-歌名"
                return new ParsedName(left, right);
            }
        }
        return new ParsedName(cleaned, "");
    }

    /**
     * 精确音质评分（下载后经 ffprobe 探测得到真实参数）：
     * - 无损 (FLAC/WAV/ALAC/APE/DSF): 900 ~ 1000
     * - 高码率有损 (Opus>=192k: 850, AAC>=256k: 820, MP3>=300k: 800)
     * - 中码率有损 (Opus>=128k: 680, AAC>=128k: 650, MP3>=192k: 600)
     * - 低码率有损 (<128k): 100 ~ 400
     */
    public int computeQualityScore(String codec, int bitrateKbps, int sampleRateHz, int bitDepth, String fileName, String mimeType) {
        if (isLosslessCodecOrExt(codec, fileName, mimeType)) {
            int srBonus = sampleRateHz > 0 ? Math.min(60, sampleRateHz / 2000) : 22; // 44.1k->22, 48k->24, 96k->48, 192k->60
            int bdBonus = bitDepth > 0 ? Math.min(40, bitDepth) : 16;              // 16bit->16, 24bit->24
            return Math.min(1000, 900 + srBonus + bdBonus);
        }
        String c = codec == null ? "" : codec.toLowerCase(Locale.ROOT);
        int br = Math.max(0, bitrateKbps);
        if (c.contains("opus")) {
            if (br >= 240) return 870;
            if (br >= 180) return 850;
            if (br >= 120) return 680;
            if (br >= 90) return 520;
            return br > 0 ? Math.min(400, br * 4) : 650;
        }
        if (c.contains("aac") || c.contains("m4a") || c.contains("vorbis")) {
            if (br >= 250) return 820;
            if (br >= 180) return 720;
            if (br >= 120) return 650;
            return br > 0 ? Math.min(450, br * 3) : 600;
        }
        // MP3 或其他有损
        if (br >= 300) return 800;
        if (br >= 240) return 720;
        if (br >= 180) return 600;
        if (br >= 120) return 480;
        return br > 0 ? Math.min(350, br * 3) : 450;
    }

    /**
     * 采集期估算音质评分（尚未下载文件，基于扩展名/MIME/文件大小/时长估算），用于事前跳过明显低品质重复源。
     */
    public int estimateQualityScore(String mimeType, String fileName, long fileSize, int durationSec) {
        if (isLosslessCodecOrExt(null, fileName, mimeType)) {
            // 仅根据体积粗估 Hi-Res vs CD 无损
            if (durationSec > 0 && fileSize > 0) {
                long kbps = (fileSize * 8L) / (durationSec * 1000L);
                return kbps > 1400 ? 975 : 940;
            }
            return 940;
        }
        int estKbps = 0;
        if (durationSec > 0 && fileSize > 0) {
            estKbps = (int) ((fileSize * 8L) / (durationSec * 1000L));
        }
        String ext = StringUtils.defaultString(FilenameUtils.getExtension(fileName)).toLowerCase(Locale.ROOT);
        String codecHint = ext;
        if (codecHint.isEmpty() && StringUtils.isNotBlank(mimeType)) {
            String m = mimeType.toLowerCase(Locale.ROOT);
            if (m.contains("ogg") || m.contains("opus")) codecHint = "opus";
            else if (m.contains("mp4") || m.contains("aac")) codecHint = "aac";
            else codecHint = "mp3";
        }
        return computeQualityScore(codecHint, estKbps, 0, 0, fileName, mimeType);
    }

    /**
     * 计算两个归一化字符串的简单词级/字符级 Jaccard + 包含相似度 [0.0, 1.0]，用于歌词打分。
     */
    public double textSimilarity(String a, String b) {
        String na = normalizeForIndex(a);
        String nb = normalizeForIndex(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return 0.0;
        }
        if (na.equals(nb)) {
            return 1.0;
        }
        String ca = na.replace(" ", "");
        String cb = nb.replace(" ", "");
        if (ca.equals(cb)) {
            return 0.98;
        }
        if (ca.contains(cb) || cb.contains(ca)) {
            double ratio = (double) Math.min(ca.length(), cb.length()) / Math.max(ca.length(), cb.length());
            return 0.65 + 0.30 * ratio;
        }
        Set<Character> sa = new HashSet<>();
        for (char c : ca.toCharArray()) sa.add(c);
        Set<Character> sb = new HashSet<>();
        for (char c : cb.toCharArray()) sb.add(c);
        int inter = 0;
        for (char c : sa) {
            if (sb.contains(c)) inter++;
        }
        int union = sa.size() + sb.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    public record ParsedName(String title, String artist) {
    }
}
