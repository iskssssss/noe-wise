package com.skillforge.institution.config;

import com.skillforge.core.shared.web.CachedBodyHttpServletRequest;

import cn.dev33.satoken.stp.StpUtil;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.auth.ChannelTokenAuthenticator;
import com.skillforge.institution.auth.OpenApiAuthenticator;
import com.skillforge.institution.auth.ServicePrincipalAuthenticator;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 外部认证拦截器（开放路径专用，order(-10) 先于其他拦截器）。
 *
 * <p>对 {@link ExternalAuthPaths#OPEN_PATHS} 清单内的接口做认证分流：
 * ① B 端 JWT（放行，角色校验照旧）→ ② {@code X-Channel-Token}（C 端渠道令牌，
 * {@link ChannelTokenAuthenticator}）→ ③ {@code X-Api-Key}+HMAC（OpenAPI 集成方，
 * {@link OpenApiAuthenticator}）；而 {@code /api/v1/open-platform/**} 仅接受
 * ServicePrincipal 的 {@code X-Api-Key}+HMAC。全部缺失时回退 {@link StpUtil#checkLogin()}
 * 触发统一 401。
 *
 * <p>渠道身份只由认证器写入 {@link ChannelContext}，请求体不接受渠道身份声明。
 */
@Component
public class ExternalAuthInterceptor implements org.springframework.web.servlet.HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ExternalAuthInterceptor.class);

    /** 渠道令牌请求头名（接口契约 §2.2）。 */
    private static final String HEADER_CHANNEL_TOKEN = "X-Channel-Token";

    /** API Key 请求头名（接口契约 §2.5）。 */
    private static final String HEADER_API_KEY = "X-Api-Key";

    /** 时间戳请求头名（接口契约 §2.5）。 */
    private static final String HEADER_TIMESTAMP = "X-Timestamp";

    /** 签名请求头名（接口契约 §2.5）。 */
    private static final String HEADER_SIGNATURE = "X-Signature";

    /** C 端渠道令牌认证器。 */
    private final ChannelTokenAuthenticator channelTokenAuthenticator;

    /** OpenAPI 集成方认证器。 */
    private final OpenApiAuthenticator openApiAuthenticator;

    /** Tenant Open Platform 服务身份认证器。 */
    private final ServicePrincipalAuthenticator servicePrincipalAuthenticator;

    /**
     * 构造外部认证拦截器。
     *
     * @param channelTokenAuthenticator 渠道令牌认证器
     * @param openApiAuthenticator      OpenAPI 认证器
     */
    public ExternalAuthInterceptor(
            ChannelTokenAuthenticator channelTokenAuthenticator,
            OpenApiAuthenticator openApiAuthenticator,
            ServicePrincipalAuthenticator servicePrincipalAuthenticator) {
        this.channelTokenAuthenticator = channelTokenAuthenticator;
        this.openApiAuthenticator = openApiAuthenticator;
        this.servicePrincipalAuthenticator = servicePrincipalAuthenticator;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 异步 dispatch（SSE 等）跳过：主请求已完成认证，异步线程无 Sa-Token 上下文
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        // B 端 JWT 通道优先：角色校验由 RoleCheckInterceptor 照常执行
        if (!ExternalAuthPaths.isOpenPlatformPath(request.getRequestURI()) && StpUtil.isLogin()) {
            return true;
        }
        // C 端渠道令牌
        String channelToken = request.getHeader(HEADER_CHANNEL_TOKEN);
        if (ExternalAuthPaths.isSignedOpenApiPath(request.getRequestURI())
                && channelToken != null && !channelToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "此接口需要 OpenAPI API Key + HMAC 签名凭证");
        }
        if ((ExternalAuthPaths.isOpenPlatformPath(request.getRequestURI())||ExternalAuthPaths.isInstitutionManagementPath(request.getRequestURI()))
                && channelToken != null && !channelToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "Open Platform 接口仅接受 ServicePrincipal 凭证");
        }
        if (channelToken != null && !channelToken.isBlank()) {
            channelTokenAuthenticator.authenticate(channelToken, request);
            return true;
        }
        // OpenAPI 集成方（API Key + HMAC）
        String apiKey = request.getHeader(HEADER_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            if (ExternalAuthPaths.isOpenPlatformPath(request.getRequestURI())||ExternalAuthPaths.isInstitutionManagementPath(request.getRequestURI())) {
                servicePrincipalAuthenticator.authenticate(apiKey, request.getHeader(HEADER_TIMESTAMP),
                        request.getHeader(HEADER_SIGNATURE), request, requireCachedBody(request));
                return true;
            } else {
                openApiAuthenticator.authenticate(
                        apiKey,
                        request.getHeader(HEADER_TIMESTAMP),
                        request.getHeader(HEADER_SIGNATURE),
                        request,
                        readBody(request));
            }
            return true;
        }
        if (ExternalAuthPaths.isOpenPlatformPath(request.getRequestURI()))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Open Platform 接口仅接受 ServicePrincipal 凭证");
        // 无任何凭证：回退登录校验，由全局异常处理器输出统一 401
        StpUtil.checkLogin();
        return true;
    }

    /** SP必须验证过滤器保留的原始字节；未包装请求与multipart不能借空摘要绕过正文完整性。 */
    private byte[] requireCachedBody(HttpServletRequest request) {
        if (request instanceof CachedBodyHttpServletRequest cached) return cached.getCachedBody();
        throw new ApiException(HttpStatus.UNAUTHORIZED, "ServicePrincipal 请求体未缓存，无法验证签名");
    }

    /**
     * 读取请求体（缓存包装器预读，读多少次均安全）。
     *
     * @param request 当前 HTTP 请求（经 BodyCachingFilter 包装；multipart 透传）
     * @return 请求体 UTF-8 文本；非缓存包装或无体返回空串
     */
    private String readBody(HttpServletRequest request) {
        if (request instanceof CachedBodyHttpServletRequest cached) {
            return new String(cached.getCachedBody(), StandardCharsets.UTF_8);
        }
        return "";
    }
}
