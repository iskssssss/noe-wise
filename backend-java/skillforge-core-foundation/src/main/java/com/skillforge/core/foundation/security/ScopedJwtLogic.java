package com.skillforge.core.foundation.security;

import cn.dev33.satoken.jwt.SaJwtUtil;
import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** 机构和平台共用的 JWT 协议；签名、受众、签发方和期限均验证后才读取登录状态。 */
public final class ScopedJwtLogic extends StpLogicJwtForSimple {
    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final String managementIssuer;
    private final String managementAudience;
    /** 额外平台realm专用验签密钥，只在精确管理路径使用，不输出至诊断。 */
    private final String managementSecretKey;

    public ScopedJwtLogic(String issuer, String audience, Clock clock) {
        this(issuer, audience, clock, null, null, null);
    }

    /** 机构宿主只为精确平台部署管理入口接受独立平台 realm，其他机构入口仍严格隔离。 */
    public ScopedJwtLogic(String issuer, String audience, Clock clock, String managementIssuer, String managementAudience,
            String managementSecretKey) {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank())
            throw new IllegalArgumentException("JWT issuer 和 audience 必填");
        this.issuer = issuer;
        this.audience = audience;
        this.clock = Objects.requireNonNull(clock);
        boolean managementConfigured = managementIssuer != null || managementAudience != null || managementSecretKey != null;
        if (managementConfigured && (managementIssuer == null || managementIssuer.isBlank()
                || managementAudience == null || managementAudience.isBlank()
                || managementSecretKey == null || managementSecretKey.isBlank()))
            throw new IllegalArgumentException("管理 JWT issuer、audience 与验签密钥必须同时完整配置");
        this.managementIssuer = managementIssuer;
        this.managementAudience = managementAudience;
        this.managementSecretKey = managementSecretKey;
    }

    /** 平台realm使用平台独立验签密钥；机构与非精确管理路径始终使用宿主自身密钥。 */
    @Override
    public String jwtSecretKey() {
        return managementIssuer != null && platformDeploymentRequest() ? managementSecretKey : super.jwtSecretKey();
    }

    /** 机构代部署入口沿用平台认证头；精确路由外仍只读取宿主自身认证头。 */
    @Override
    public String getTokenValueNotCut() {
        if (managementIssuer != null && platformDeploymentRequest()) {
            return cn.dev33.satoken.context.SaHolder.getRequest().getHeader("X-Platform-Token");
        }
        return super.getTokenValueNotCut();
    }

    @Override
    public String createTokenValue(Object loginId, String deviceType, long timeout, Map<String, Object> extra) {
        if (timeout <= 0) throw new IllegalArgumentException("JWT 必须设置有限正数有效期");
        Map<String, Object> claims = new LinkedHashMap<>();
        if (extra != null) claims.putAll(extra);
        // 安全声明只能来自服务配置，调用方的扩展属性不得覆盖它们。
        claims.put("iss", issuer);
        claims.put("aud", audience);
        claims.put("exp", Math.addExact(clock.instant().getEpochSecond(), timeout));
        return super.createTokenValue(loginId, deviceType, timeout, claims);
    }

    /**
     * 精确平台跨宿主入口从已验签声明解析账号；请求随后仍强制验证Foundation数据库安全会话。
     * 默认SaTokenDao是进程内状态，不能作为另一个宿主的登录副本；机构普通入口保留原Simple校验。
     */
    @Override
    public String getLoginIdNotHandle(String token) {
        if (token == null || token.isBlank()) return null;
        var payload = requireClaims(token);
        if (managementIssuer != null && platformDeploymentRequest()) {
            Object login = payload.get(SaJwtUtil.LOGIN_ID);
            if (!(login instanceof String id) || !id.matches("[1-9][0-9]{0,18}")) throw unauthorized();
            try { Long.parseLong(id); } catch (NumberFormatException invalid) { throw unauthorized(); }
            return id;
        }
        return super.getLoginIdNotHandle(token);
    }

    @Override
    public Object getExtra(String token, String key) {
        return requireClaims(token).get(key);
    }

    /** 标准exp、签名及realm均验证后才公开声明；当前安全会话仍由Foundation数据库事实判定。 */
    private Map<String, Object> requireClaims(String token) {
        try {
            // Sa-Token 1.45 的 NotCheck 仅跳过内部 eff 期限；签名和 loginType 仍强制校验。
            // 本协议用注入时钟独立验证标准 exp，不依赖库的系统时钟期限。
            var payload = SaJwtUtil.getPayloadsNotCheck(token, getLoginType(), jwtSecretKey());
            Object expiration = payload.get("exp");
            boolean managementRequest = managementIssuer != null && platformDeploymentRequest();
            boolean ownRealm = !managementRequest && issuer.equals(payload.get("iss")) && audience.equals(payload.get("aud"));
            boolean managementRealm = managementIssuer != null && managementIssuer.equals(payload.get("iss"))
                    && managementAudience.equals(payload.get("aud")) && payload.get("tenant_id") == null
                    && managementRequest;
            if ((!ownRealm && !managementRealm)
                    || !(expiration instanceof Number number)
                    || number.doubleValue() != number.longValue()
                    || number.longValue() <= clock.instant().getEpochSecond()) throw unauthorized();
            return payload;
        } catch (ApiException failure) {
            throw failure;
        } catch (RuntimeException invalid) {
            // 不将解析器错误、令牌正文或签名信息透传到响应及普通日志。
            throw unauthorized();
        }
    }

    /** 读取已由 Servlet 容器确定的路径，脱离 HTTP 上下文时不接受额外认证域。 */
    private boolean platformDeploymentRequest() {
        try {
            return com.skillforge.core.foundation.integration.ManagementActions.isDeploymentPath(
                    cn.dev33.satoken.context.SaHolder.getRequest().getRequestPath());
        } catch (RuntimeException missingHttpContext) { return false; }
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "访问令牌无效或已过期", false);
    }
}
