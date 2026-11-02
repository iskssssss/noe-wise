package com.skillforge.institution.auth;

import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.channel.constant.ChannelConstants;
import com.skillforge.core.channel.enums.ChannelType;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.institution.config.ExternalAuthPaths;
import com.skillforge.institution.security.HmacSignatureVerifier;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * OpenAPI 集成方认证器（接口契约 §2.5）。
 *
 * <p>请求头 {@code X-Api-Key} + {@code X-Timestamp} + {@code X-Signature}：
 * API Key 哈希经 SECURITY DEFINER 函数定位激活渠道（须为 openapi 类型）→
 * 时间戳防重放（偏差 ≤300s）→ HMAC-SHA256 验签（canonical 由拦截器传入的
 * 请求体构建，常量时间比较）→ IP 白名单；任一失败返回 401/403。
 */
@Component
public class OpenApiAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(OpenApiAuthenticator.class);

    /** 时间戳防重放窗口（秒）：|now - X-Timestamp| 超出即拒绝。 */
    private static final long TIMESTAMP_WINDOW_SECONDS = 300;

    /** 渠道表映射器。 */
    private final ChannelMapper channelMapper;

    /** 应用层加密服务：解密渠道专用凭据列中的 API secret。 */
    private final CryptoService cryptoService;

    /**
     * 构造 OpenAPI 认证器。
     *
     * @param channelMapper  渠道表映射器
     * @param cryptoService  加密服务
     */
    public OpenApiAuthenticator(ChannelMapper channelMapper, CryptoService cryptoService) {
        this.channelMapper = channelMapper;
        this.cryptoService = cryptoService;
    }

    /**
     * 认证 OpenAPI 调用并注入上下文。
     *
     * @param apiKey    请求头 X-Api-Key
     * @param timestamp 请求头 X-Timestamp（Unix 秒）
     * @param signature 请求头 X-Signature（HMAC-SHA256 hex）
     * @param request   当前 HTTP 请求
     * @param body      请求体原文（GET 空串，由 BodyCachingFilter 缓存提供）
     * @return 认证通过的激活渠道
     * @throws ApiException 认证任一环节失败 401/403
     */
    public ChannelDO authenticate(
            String apiKey, String timestamp, String signature,
            HttpServletRequest request, String body) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少 API Key");
        }
        ChannelDO channel = channelMapper.selectForAuthByApiKeyHash(TokenHash.sha256Hex(apiKey));
        if (channel == null) {
            log.warn("OpenAPI 认证失败: API Key 无效或渠道已停用, ip={}", request.getRemoteAddr());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "API Key 无效");
        }
        if (!ChannelType.OPENAPI.code().equals(channel.getChannelType())) {
            log.warn("OpenAPI 认证失败: 渠道类型非 openapi, channelId={}", channel.getId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "API Key 仅适用于 OpenAPI 渠道");
        }
        checkTimestamp(timestamp);
        checkSignature(channel, timestamp, signature, request, body);
        IpWhitelist.requireAllowed(channel, request);
        ChannelAuthContext.inject(channel, request, ExternalAuthPaths.SOURCE_OPENAPI);
        log.info("外部认证成功 source={} channelId={} tenantId={} ip={}",
                ExternalAuthPaths.SOURCE_OPENAPI, channel.getId(), channel.getTenantId(),
                request.getRemoteAddr());
        return channel;
    }

    /**
     * 时间戳防重放校验：Unix 秒，与服务器时间偏差 ≤ 300s。
     *
     * @param timestamp 请求头 X-Timestamp
     */
    private void checkTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少请求时间戳");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请求时间戳格式非法");
        }
        long diff = Math.abs(System.currentTimeMillis() / 1000 - ts);
        if (diff > TIMESTAMP_WINDOW_SECONDS) {
            log.warn("OpenAPI 认证失败: 时间戳超出允许偏差 {}s, diff={}s",
                    TIMESTAMP_WINDOW_SECONDS, diff);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请求时间戳超出允许偏差");
        }
    }

    /**
     * HMAC-SHA256 验签：canonical = ts + "\n" + method + "\n" + path + "\n" + sha256(body)。
     *
     * @param channel   目标渠道（取解密后的 api secret）
     * @param timestamp 请求头 X-Timestamp
     * @param signature 请求头 X-Signature
     * @param request   当前 HTTP 请求（取 method 与 path）
     * @param body      请求体原文
     */
    private void checkSignature(
            ChannelDO channel, String timestamp, String signature,
            HttpServletRequest request, String body) {
        String secretEncrypted = channel.getApiSecretEncrypted();
        if (secretEncrypted == null || secretEncrypted.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "渠道未配置 API secret");
        }
        String apiSecret = cryptoService.decrypt(secretEncrypted);
        String canonical = HmacSignatureVerifier.canonical(
                timestamp.trim(), request.getMethod(), HmacSignatureVerifier.requestTarget(request.getRequestURI(), request.getQueryString()),
                body == null ? "" : body);
        if (!HmacSignatureVerifier.verify(apiSecret, canonical, signature)) {
            log.warn("OpenAPI 认证失败: 签名校验不通过, channelId={}", channel.getId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "签名校验失败");
        }
    }
}
