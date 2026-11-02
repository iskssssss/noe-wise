package com.skillforge.institution.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.channel.constant.ChannelConstants;
import com.skillforge.core.channel.enums.ChannelStatus;
import com.skillforge.core.channel.enums.ChannelType;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.institution.config.ExternalAuthPaths;
import com.skillforge.institution.security.HmacSignatureVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * OpenAPI 集成方认证器单元测试。
 *
 * <p>覆盖 API Key 查渠道、时间戳防重放窗口（±300s）、HMAC 验签
 * （正确通过/篡改拒绝/密钥缺失）、渠道类型校验与 IP 白名单。
 */
class OpenApiAuthenticatorTest {

    /** 固定测试 secret（解密后的明文）。 */
    private static final String SECRET = "test-api-secret-0123456789abcdef";

    /** 渠道表映射器 Mock。 */
    private ChannelMapper channelMapper;

    /** 加密服务 Mock。 */
    private CryptoService cryptoService;

    /** 被测认证器。 */
    private OpenApiAuthenticator authenticator;

    /** 请求 Mock。 */
    private HttpServletRequest request;

    /**
     * 初始化认证器与 Mock。
     */
    @BeforeEach
    void setUp() {
        channelMapper = mock(ChannelMapper.class);
        cryptoService = mock(CryptoService.class);
        authenticator = new OpenApiAuthenticator(channelMapper, cryptoService);
        request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/chat/sync");
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        when(cryptoService.decrypt(anyString())).thenReturn(SECRET);
    }

    /**
     * 清理线程上下文，避免测试间串扰。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        ChannelContext.clear();
    }

    /**
     * 合法 API Key + 正确签名应认证通过并注入上下文。
     */
    @Test
    void validApiKeyWithCorrectSignaturePasses() {
        ChannelDO channel = openApiChannel(Map.of());
        when(channelMapper.selectForAuthByApiKeyHash(anyString())).thenReturn(channel);
        long ts = System.currentTimeMillis() / 1000;
        String body = "{\"message\":\"hi\"}";
        String canonical = HmacSignatureVerifier.canonical(
                String.valueOf(ts), "POST", "/api/v1/chat/sync", body);

        ChannelDO result = authenticator.authenticate(
                "ak_valid", String.valueOf(ts),
                HmacSignatureVerifier.sign(SECRET, canonical), request, body);

        assertNotNull(result);
        assertEquals("1001", TenantContext.getTenantId());
        assertEquals("2001", ChannelContext.getChannelId());
    }

    /**
     * 无效 API Key（渠道不存在）应抛 401。
     */
    @Test
    void queryIsBoundToSignatureForChannelCredentials() {
        ChannelDO channel = openApiChannel(Map.of());
        when(channelMapper.selectForAuthByApiKeyHash(anyString())).thenReturn(channel);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/knowledge/bases/detail");
        when(request.getQueryString()).thenReturn("kbId=1");
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        String signature = HmacSignatureVerifier.sign(SECRET, HmacSignatureVerifier.canonical(
                timestamp, "GET", "/api/v1/knowledge/bases/detail?kbId=1", ""));
        assertNotNull(authenticator.authenticate("ak_valid", timestamp, signature, request, ""));
        when(request.getQueryString()).thenReturn("kbId=2");
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> authenticator.authenticate("ak_valid", timestamp, signature, request, "")).getStatus());
    }

    @Test
    void invalidApiKeyThrowsUnauthorized() {
        when(channelMapper.selectForAuthByApiKeyHash(anyString())).thenReturn(null);
        ApiException ex = assertThrows(ApiException.class,
                () -> authenticator.authenticate("ak_invalid", "1700000000", "sig", request, ""));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    /**
     * 渠道类型非 openapi 应抛 401。
     */
    @Test
    void nonOpenApiChannelThrowsUnauthorized() {
        ChannelDO channel = new ChannelDO();
        channel.setChannelType(ChannelType.WEB_WIDGET.code());
        when(channelMapper.selectForAuthByApiKeyHash(anyString())).thenReturn(channel);
        ApiException ex = assertThrows(ApiException.class,
                () -> authenticator.authenticate("ak_x", "1700000000", "sig", request, ""));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    /**
     * 时间戳超出 300s 偏差应拒绝。
     */
    @Test
    void staleTimestampRejected() {
        ChannelDO channel = openApiChannel(Map.of());
        when(channelMapper.selectForAuthByApiKeyHash(anyString())).thenReturn(channel);
        long ts = System.currentTimeMillis() / 1000 - 301;
        ApiException ex = assertThrows(ApiException.class,
                () -> authenticator.authenticate("ak_x", String.valueOf(ts), "sig", request, ""));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    /**
     * 时间戳在 300s 窗口内应通过（签名正确时）。
     */
    @Test
    void timestampWithinWindowPasses() {
        ChannelDO channel = openApiChannel(Map.of());
        when(channelMapper.selectForAuthByApiKeyHash(anyString())).thenReturn(channel);
        long ts = System.currentTimeMillis() / 1000 - 299;
        String canonical = HmacSignatureVerifier.canonical(
                String.valueOf(ts), "POST", "/api/v1/chat/sync", "");
        assertNotNull(authenticator.authenticate(
                "ak_x", String.valueOf(ts),
                HmacSignatureVerifier.sign(SECRET, canonical), request, ""));
    }

    /**
     * 签名与请求体不符应拒绝。
     */
    @Test
    void tamperedSignatureRejected() {
        ChannelDO channel = openApiChannel(Map.of());
        when(channelMapper.selectForAuthByApiKeyHash(anyString())).thenReturn(channel);
        long ts = System.currentTimeMillis() / 1000;
        String body = "{\"message\":\"hi\"}";
        String canonical = HmacSignatureVerifier.canonical(
                String.valueOf(ts), "POST", "/api/v1/chat/sync", "{\"message\":\"hacked\"}");
        ApiException ex = assertThrows(ApiException.class,
                () -> authenticator.authenticate(
                        "ak_x", String.valueOf(ts),
                        HmacSignatureVerifier.sign(SECRET, canonical), request, body));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    /**
     * 渠道专用密文列缺失时应拒绝，即使 config 声明旧密钥字段。
     */
    @Test
    void missingDedicatedSecretRejected() {
        ChannelDO channel = openApiChannel(Map.of("api_secret_encrypted", "legacy-config-secret"));
        channel.setApiSecretEncrypted(null);
        when(channelMapper.selectForAuthByApiKeyHash(anyString())).thenReturn(channel);
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        String signature = HmacSignatureVerifier.sign(SECRET,
                HmacSignatureVerifier.canonical(timestamp, "POST", "/api/v1/chat/sync", ""));
        ApiException ex = assertThrows(ApiException.class,
                () -> authenticator.authenticate("ak_x", timestamp, signature, request, ""));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    /**
     * 来源 IP 不在白名单应抛 403。
     */
    @Test
    void ipNotInWhitelistThrowsForbidden() {
        ChannelDO channel = openApiChannel(Map.of(
                ChannelConstants.IP_WHITELIST, "9.9.9.9"));
        when(channelMapper.selectForAuthByApiKeyHash(anyString())).thenReturn(channel);
        long ts = System.currentTimeMillis() / 1000;
        String canonical = HmacSignatureVerifier.canonical(
                String.valueOf(ts), "POST", "/api/v1/chat/sync", "");
        ApiException ex = assertThrows(ApiException.class,
                () -> authenticator.authenticate(
                        "ak_x", String.valueOf(ts),
                        HmacSignatureVerifier.sign(SECRET, canonical), request, ""));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    /**
     * 构造 OpenAPI 渠道测试数据。
     *
     * @param config 渠道配置
     * @return 激活状态的 openapi 渠道
     */
    private ChannelDO openApiChannel(Map<String, Object> config) {
        ChannelDO channel = new ChannelDO();
        channel.setId(2001L);
        channel.setTenantId(1001L);
        channel.setChannelType(ChannelType.OPENAPI.code());
        channel.setStatus(ChannelStatus.ACTIVE.code());
        channel.setConfig(config);
        channel.setApiSecretEncrypted("enc");
        return channel;
    }
}
