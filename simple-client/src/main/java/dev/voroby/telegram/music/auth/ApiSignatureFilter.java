package dev.voroby.telegram.music.auth;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
public class ApiSignatureFilter extends OncePerRequestFilter {

    private static final long TIME_DIFF_LIMIT = 5 * 60 * 1000; // 允许5分钟时间差

    @Value("${app.security.api-keys}")
    private String rawApiKeys;

    private Map<String, String> validApiKeys;

    @PostConstruct
    public void init() {
        // 将字符串拆分并存入 Set，过滤掉空格和空值
        validApiKeys = new HashMap<>();
        for (String item : rawApiKeys.split(";")) {
            if (StringUtils.isBlank(item)) {
                continue;
            }

            String[] parts = item.split(":");
            validApiKeys.put(parts[0], parts[1]);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getServletPath();
        return path.startsWith("/api/authorization");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 包装 Request 以便多次读取 Body
        ContentCachingRequestWrapper requestWrapper = new ContentCachingRequestWrapper(request, 0);

        // 1. 获取 Header 中的签名参数
        String apiKey = requestWrapper.getHeader("X-API-KEY");
        String signature = requestWrapper.getHeader("X-SIGNATURE");
        String timestamp = requestWrapper.getHeader("X-TIMESTAMP");
        String nonce = requestWrapper.getHeader("X-NONCE");

        // 1. 快速检查 API Key 是否存在于白名单中
        String secret = validApiKeys.getOrDefault(apiKey, null);
        if (StringUtils.isBlank(secret)) {
            renderError(response, "Invalid API Key");
            return;
        }

        // 2. 基础校验 (空值、时间戳有效期)
        if (StringUtils.isAnyBlank(apiKey, signature, timestamp, nonce) || isTimestampInvalid(timestamp)) {
            renderError(response, "Invalid Request Headers");
            return;
        }

        // 3. 验证签名
        if (verifySignature(requestWrapper, signature, timestamp, nonce, secret)) {
            filterChain.doFilter(requestWrapper, response);
        } else {
            renderError(response, "Signature Verification Failed");
        }
    }

    // 实现时间戳校验
    private boolean isTimestampInvalid(String timestampStr) {
        try {
            long requestTime = Long.parseLong(timestampStr);
            long currentTime = System.currentTimeMillis();
            return Math.abs(currentTime - requestTime) > TIME_DIFF_LIMIT;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    // 实现错误响应渲染
    private void renderError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }

    private boolean verifySignature(ContentCachingRequestWrapper request, String sign, String ts, String nonce,
                                    String secretKey) {
        // 构建签名原串：apiKey + timestamp + nonce + body
        String body = new String(request.getContentAsByteArray(), StandardCharsets.UTF_8);
        String baseString = request.getHeader("X-API-KEY") + ts + nonce + body;

        // 使用 HmacSHA256 计算签名
        String calculatedSign = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, secretKey).hmacHex(baseString);
        return calculatedSign.equalsIgnoreCase(sign);
    }
}

