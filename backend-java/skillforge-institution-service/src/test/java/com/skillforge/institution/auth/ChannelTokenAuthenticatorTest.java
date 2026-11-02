package com.skillforge.institution.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.channel.constant.ChannelConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.config.ExternalAuthPaths;
import com.skillforge.institution.gateway.ChannelGateway;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * C 端渠道令牌认证器单元测试。
 *
 * <p>覆盖令牌解析成功注入上下文、无效/缺失令牌 401、IP 白名单 403/放行。
 */
class ChannelTokenAuthenticatorTest {

    /** 渠道网关 Mock。 */
    private ChannelGateway channelGateway;

    /** 被测认证器。 */
    private ChannelTokenAuthenticator authenticator;

    /** 请求 Mock。 */
    private HttpServletRequest request;

    /**
     * 初始化认证器与 Mock。
     */
    @BeforeEach
    void setUp() {
        channelGateway = mock(ChannelGateway.class);
        authenticator = new ChannelTokenAuthenticator(channelGateway);
        request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
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
     * 合法令牌应解析渠道并注入租户/渠道上下文与认证来源。
     */
    @Test
    void validTokenInjectsTenantAndChannelContext() {
        ChannelDO channel = channel(1001L, 2001L, Map.of());
        when(channelGateway.resolveChannel("ch_valid")).thenReturn(channel);

        ChannelDO result = authenticator.authenticate("ch_valid", request);

        assertNotNull(result);
        assertEquals("1001", TenantContext.getTenantId());
        assertEquals("2001", ChannelContext.getChannelId());
        verify(request).setAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE, ExternalAuthPaths.SOURCE_CHANNEL);
    }

    /**
     * 无效令牌应抛 401。
     */
    @Test
    void invalidTokenThrowsUnauthorized() {
        when(channelGateway.resolveChannel(anyString())).thenReturn(null);
        ApiException ex = assertThrows(ApiException.class,
                () -> authenticator.authenticate("ch_invalid", request));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
    }

    /**
     * 缺失令牌应抛 401。
     */
    @Test
    void blankTokenThrowsUnauthorized() {
        assertThrows(ApiException.class, () -> authenticator.authenticate("", request));
        assertThrows(ApiException.class, () -> authenticator.authenticate(null, request));
    }

    /**
     * 来源 IP 不在白名单应抛 403。
     */
    @Test
    void ipNotInWhitelistThrowsForbidden() {
        ChannelDO channel = channel(1001L, 2001L, Map.of(ChannelConstants.IP_WHITELIST, "9.9.9.9"));
        when(channelGateway.resolveChannel("ch_valid")).thenReturn(channel);
        ApiException ex = assertThrows(ApiException.class,
                () -> authenticator.authenticate("ch_valid", request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    /**
     * 来源 IP 命中白名单应认证通过。
     */
    @Test
    void ipInWhitelistPasses() {
        ChannelDO channel = channel(1001L, 2001L,
                Map.of(ChannelConstants.IP_WHITELIST, List.of("1.2.3.4", "9.9.9.9")));
        when(channelGateway.resolveChannel("ch_valid")).thenReturn(channel);
        assertNotNull(authenticator.authenticate("ch_valid", request));
    }

    /**
     * 白名单为逗号分隔字符串时同样生效。
     */
    @Test
    void ipWhitelistAsCommaSeparatedStringWorks() {
        ChannelDO channel = channel(1001L, 2001L,
                Map.of(ChannelConstants.IP_WHITELIST, "9.9.9.9, 1.2.3.4"));
        when(channelGateway.resolveChannel("ch_valid")).thenReturn(channel);
        assertNotNull(authenticator.authenticate("ch_valid", request));
    }

    /**
     * 构造渠道测试数据。
     *
     * @param tenantId 租户 ID
     * @param id       渠道 ID
     * @param config   渠道配置
     * @return 激活状态的渠道
     */
    private ChannelDO channel(Long tenantId, Long id, Map<String, Object> config) {
        ChannelDO channel = new ChannelDO();
        channel.setId(id);
        channel.setTenantId(tenantId);
        channel.setChannelType(com.skillforge.core.channel.enums.ChannelType.WEB_WIDGET.code());
        channel.setStatus(com.skillforge.core.channel.enums.ChannelStatus.ACTIVE.code());
        channel.setConfig(config);
        return channel;
    }
}
