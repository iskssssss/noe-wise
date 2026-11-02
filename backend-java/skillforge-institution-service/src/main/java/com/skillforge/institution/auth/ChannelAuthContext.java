package com.skillforge.institution.auth;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.institution.config.ExternalAuthPaths;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 渠道认证上下文注入工具。
 *
 * <p>外部认证（C 端渠道令牌 / OpenAPI 集成方）成功后，将解析出的租户与渠道写入
 * 请求线程上下文：{@link TenantContext} 供 RLS 与业务使用，{@link ChannelContext}
 * 供路由权威读取，{@code AUTH_SOURCE} 请求属性供角色拦截器识别外部认证来源。
 * 清理由外层 {@code TraceIdFilter} 在请求线程离开过滤链时统一负责，包括 SSE 异步交接。
 */
public final class ChannelAuthContext {

    private ChannelAuthContext() {
    }

    /**
     * 注入认证结果上下文。
     *
     * @param channel 已认证的渠道
     * @param request 当前 HTTP 请求
     * @param source  外部认证来源（{@link ExternalAuthPaths#SOURCE_CHANNEL} /
     *                {@link ExternalAuthPaths#SOURCE_OPENAPI}）
     */
    public static void inject(ChannelDO channel, HttpServletRequest request, String source) {
        TenantContext.setTenantId(channel.getTenantId().toString());
        ChannelContext.setChannelId(channel.getId().toString());
        request.setAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE, source);
    }
}
