package com.skillforge.institution.auth;

import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.config.ExternalAuthPaths;
import com.skillforge.institution.gateway.ChannelGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * C 端渠道令牌认证器（接口契约 §2.2）。
 *
 * <p>请求头 {@code X-Channel-Token} 携带渠道明文令牌：经 {@link ChannelGateway}
 * （SECURITY DEFINER 函数绕过 RLS）按 SHA-256 哈希解析激活渠道；令牌无效/停用
 * 返回 401，IP 白名单未命中返回 403。成功后注入租户与渠道上下文。
 */
@Component
public class ChannelTokenAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ChannelTokenAuthenticator.class);

    /** 渠道网关：按令牌哈希解析渠道。 */
    private final ChannelGateway channelGateway;

    /**
     * 构造渠道令牌认证器。
     *
     * @param channelGateway 渠道网关
     */
    public ChannelTokenAuthenticator(ChannelGateway channelGateway) {
        this.channelGateway = channelGateway;
    }

    /**
     * 认证渠道令牌并注入上下文。
     *
     * @param channelToken 请求头 X-Channel-Token 明文
     * @param request      当前 HTTP 请求
     * @return 认证通过的激活渠道
     * @throws ApiException 令牌无效/停用 401；IP 不在白名单 403
     */
    public ChannelDO authenticate(String channelToken, HttpServletRequest request) {
        if (channelToken == null || channelToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少渠道令牌");
        }
        ChannelDO channel = channelGateway.resolveChannel(channelToken);
        if (channel == null) {
            log.warn("渠道令牌认证失败: token 无效或渠道已停用, ip={}", request.getRemoteAddr());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "渠道令牌无效");
        }
        IpWhitelist.requireAllowed(channel, request);
        ChannelAuthContext.inject(channel, request, ExternalAuthPaths.SOURCE_CHANNEL);
        log.info("外部认证成功 source={} channelId={} tenantId={} ip={}",
                ExternalAuthPaths.SOURCE_CHANNEL, channel.getId(), channel.getTenantId(),
                request.getRemoteAddr());
        return channel;
    }
}
