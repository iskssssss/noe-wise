package com.skillforge.institution.auth;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.institution.config.ExternalAuthPaths;
import com.skillforge.institution.security.HmacSignatureVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Tenant Open Platform 的 API key + HMAC 服务身份认证器。 */
@Component
public class ServicePrincipalAuthenticator {

    private static final long TIMESTAMP_WINDOW_SECONDS = 300;

    private final ServicePrincipalMapper principals;
    private final CryptoService crypto;

    public ServicePrincipalAuthenticator(ServicePrincipalMapper principals, CryptoService crypto) {
        this.principals = principals;
        this.crypto = crypto;
    }

    /** 认证成功后只注入数据库冻结的 tenant、integration、scope 与 credential version。 */
    public ServicePrincipalDO authenticate(
            String apiKey, String timestamp, String signature, HttpServletRequest request, byte[] body) {
        if (body == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少可验证的 ServicePrincipal 请求体");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少 ServicePrincipal API Key");
        }
        ServicePrincipalDO principal = principals.selectForAuthByApiKeyHash(TokenHash.sha256Hex(apiKey));
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ServicePrincipal API Key 无效");
        }
        validate(principal);
        String normalizedTimestamp = timestamp(timestamp);
        String secret;
        try {
            secret = crypto.decrypt(principal.getSigningSecretEncrypted());
        } catch (RuntimeException invalidSecret) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ServicePrincipal 记录无效");
        }
        String canonical = HmacSignatureVerifier.canonicalBytes(
                normalizedTimestamp, request.getMethod(), HmacSignatureVerifier.requestTarget(request.getRequestURI(), request.getQueryString()), body);
        if (!HmacSignatureVerifier.verify(secret, canonical, signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ServicePrincipal 签名校验失败");
        }
        TenantContext.setTenantId(String.valueOf(principal.getTenantId()));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                principal.getPrincipalId(), String.valueOf(principal.getTenantId()), principal.getIntegrationId(),
                principal.getCredentialVersion(), scopes(principal.getScopes())));
        request.setAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE, ExternalAuthPaths.SOURCE_SERVICE_PRINCIPAL);
        return principal;
    }

    private String timestamp(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "缺少请求时间戳");
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException malformed) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请求时间戳格式非法");
        }
        if (Math.abs(System.currentTimeMillis() / 1000 - parsed) > TIMESTAMP_WINDOW_SECONDS) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请求时间戳超出允许偏差");
        }
        return value.trim();
    }

    private void validate(ServicePrincipalDO principal) {
        if (principal.getTenantId() == null || principal.getPrincipalId() == null
                || principal.getPrincipalId().isBlank() || principal.getIntegrationId() == null
                || principal.getIntegrationId().isBlank() || principal.getCredentialVersion() == null
                || principal.getSigningSecretEncrypted() == null
                || principal.getSigningSecretEncrypted().isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ServicePrincipal 记录无效");
        }
    }

    private Set<String> scopes(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : source) {
            if (scope != null && !scope.isBlank()) {
                scopes.add(scope);
            }
        }
        return Set.copyOf(scopes);
    }
}
