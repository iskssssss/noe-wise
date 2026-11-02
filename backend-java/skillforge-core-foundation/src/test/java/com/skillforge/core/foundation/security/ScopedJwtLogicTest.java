package com.skillforge.core.foundation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.jwt.SaJwtUtil;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 使用真实 JWT 编解码证明域隔离、声明不可覆盖与精确过期边界。 */
class ScopedJwtLogicTest {
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private static final String SECRET = "test-only-signing-key-for-issuer-and-audience";
    private static final String PLATFORM_SECRET = "test-only-independent-platform-jwt-signing-key";

    private ScopedJwtLogic logic(String issuer, String audience, Instant now) {
        return logic(issuer, audience, now, SECRET);
    }

    /** 两个真实realm可配置不同签名密钥，测试不依赖意外共享密钥。 */
    private ScopedJwtLogic logic(String issuer, String audience, Instant now, String secret) {
        var logic = new ScopedJwtLogic(issuer, audience, Clock.fixed(now, ZoneOffset.UTC));
        logic.setConfig(new SaTokenConfig().setJwtSecretKey(secret));
        return logic;
    }

    @Test void signsTrustedScopeAndRejectsClaimOverrides() {
        var logic = logic("institution", "institution-api", NOW);
        String token = logic.createTokenValue("42", "web", 60,
                Map.of("iss", "forged", "aud", "forged", "exp", Long.MAX_VALUE, "tenant_id", "7"));
        assertThat(logic.getExtra(token, "iss")).isEqualTo("institution");
        assertThat(logic.getExtra(token, "aud")).isEqualTo("institution-api");
        assertThat(((Number) logic.getExtra(token, "exp")).longValue()).isEqualTo(NOW.getEpochSecond() + 60);
        assertThat(logic.getExtra(token, "tenant_id")).isEqualTo("7");
    }

    @Test void rejectsWrongDomainEvenWhenSigningKeyIsAccidentallyShared() {
        String token = logic("institution", "institution-api", NOW).createTokenValue("42", "web", 60, Map.of());
        for (var receiver : new ScopedJwtLogic[]{logic("platform", "institution-api", NOW),
                logic("institution", "platform-api", NOW)}) {
            assertThatThrownBy(() -> receiver.getLoginIdByToken(token)).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> receiver.getExtra(token, "aud")).isInstanceOf(ApiException.class);
        }
    }

    @Test void institutionHostAcceptsPlatformRealmOnlyOnExactDeploymentManagementPaths() {
        var receiver = new ScopedJwtLogic("institution", "institution-api", Clock.fixed(NOW, ZoneOffset.UTC),
                "platform", "platform-api", PLATFORM_SECRET);
        receiver.setConfig(new SaTokenConfig().setJwtSecretKey(SECRET));
        String platform = logic("platform", "platform-api", NOW, PLATFORM_SECRET).createTokenValue("42", "web", 60, Map.of());
        String mixed = logic("platform", "platform-api", NOW, PLATFORM_SECRET).createTokenValue("42", "web", 60, Map.of("tenant_id", "7"));
        var request = org.mockito.Mockito.mock(cn.dev33.satoken.context.model.SaRequest.class);
        try (var holder = org.mockito.Mockito.mockStatic(cn.dev33.satoken.context.SaHolder.class)) {
            holder.when(cn.dev33.satoken.context.SaHolder::getRequest).thenReturn(request);
            for (String path : new String[]{"/api/v1/platform/agent-deployments", "/api/v1/platform/agent-deployments/bindings",
                    "/api/v1/platform/agent-deployments/detail"}) {
                org.mockito.Mockito.when(request.getRequestPath()).thenReturn(path);
                assertThat(receiver.getExtra(platform, "iss")).isEqualTo("platform");
                assertThatThrownBy(() -> receiver.getExtra(mixed, "iss")).isInstanceOf(ApiException.class);
            }
            for (String path : new String[]{"/api/v1/open-platform/sessions", "/api/v1/chat",
                    "/api/v1/platform/agent-deployments/arbitrary", "/api/v1/admin/usage/details"}) {
                org.mockito.Mockito.when(request.getRequestPath()).thenReturn(path);
                assertThatThrownBy(() -> receiver.getExtra(platform, "iss")).isInstanceOf(ApiException.class);
            }
        }
        assertThatThrownBy(() -> receiver.getExtra(platform, "iss")).isInstanceOf(ApiException.class);
    }

    @Test void deploymentManagementReadsOnlyThePlatformHeaderAndKeepsInstitutionRoutesSeparate() {
        var receiver = new ScopedJwtLogic("institution", "institution-api", Clock.fixed(NOW, ZoneOffset.UTC),
                "platform", "platform-api", PLATFORM_SECRET);
        receiver.setConfig(new SaTokenConfig().setJwtSecretKey(SECRET).setTokenName("Authorization")
                .setTokenPrefix("Bearer").setIsReadBody(false).setIsReadCookie(false));
        String platform = logic("platform", "platform-api", NOW, PLATFORM_SECRET).createTokenValue("42", "web", 60, Map.of());
        String institution = logic("institution", "institution-api", NOW).createTokenValue("43", "web", 60,
                Map.of("tenant_id", "7"));
        var request = org.mockito.Mockito.mock(cn.dev33.satoken.context.model.SaRequest.class);
        var storage = org.mockito.Mockito.mock(cn.dev33.satoken.context.model.SaStorage.class);
        try (var holder = org.mockito.Mockito.mockStatic(cn.dev33.satoken.context.SaHolder.class)) {
            holder.when(cn.dev33.satoken.context.SaHolder::getRequest).thenReturn(request);
            holder.when(cn.dev33.satoken.context.SaHolder::getStorage).thenReturn(storage);
            org.mockito.Mockito.when(request.getHeader("X-Platform-Token")).thenReturn("Bearer " + platform);
            org.mockito.Mockito.when(request.getHeader("Authorization")).thenReturn("Bearer " + institution);
            org.mockito.Mockito.when(request.getRequestPath()).thenReturn("/api/v1/platform/agent-deployments");
            assertThat(receiver.getTokenValue()).isEqualTo(platform);
            assertThat(receiver.getExtra(receiver.getTokenValue(), "iss")).isEqualTo("platform");
            assertThatThrownBy(() -> receiver.getExtra(institution, "iss")).isInstanceOf(ApiException.class);
            org.mockito.Mockito.when(request.getHeader("X-Platform-Token")).thenReturn(null);
            assertThat(receiver.getTokenValue()).isNull();

            org.mockito.Mockito.when(request.getHeader("X-Platform-Token")).thenReturn("Bearer " + platform);
            org.mockito.Mockito.when(request.getRequestPath()).thenReturn("/api/v1/admin/usage/details");
            assertThat(receiver.getTokenValue()).isEqualTo(institution);
            assertThat(receiver.getExtra(receiver.getTokenValue(), "iss")).isEqualTo("institution");
            assertThatThrownBy(() -> receiver.getExtra(platform, "iss")).isInstanceOf(ApiException.class);
        }
    }

    /** 跨宿主只解析已验证平台身份，不伪造本地登录副本；安全会话撤销由真实双宿主HTTP测试覆盖。 */
    @Test void deploymentManagementParsesVerifiedIdentityWithoutReplicaLocalLoginState() {
        var receiver = new ScopedJwtLogic("institution", "institution-api", Clock.fixed(NOW, ZoneOffset.UTC),
                "platform", "platform-api", PLATFORM_SECRET);
        receiver.setConfig(new SaTokenConfig().setJwtSecretKey(SECRET).setTokenName("Authorization")
                .setTokenPrefix("Bearer").setIsReadBody(false).setIsReadCookie(false));
        String platform = logic("platform", "platform-api", NOW, PLATFORM_SECRET).createTokenValue("42", "web", 60, Map.of());
        String institution = logic("institution", "institution-api", NOW).createTokenValue("43", "web", 60,
                Map.of("tenant_id", "7"));
        var request = org.mockito.Mockito.mock(cn.dev33.satoken.context.model.SaRequest.class);
        var storage = org.mockito.Mockito.mock(cn.dev33.satoken.context.model.SaStorage.class);
        var dao = org.mockito.Mockito.mock(cn.dev33.satoken.dao.SaTokenDao.class);
        try (var holder = org.mockito.Mockito.mockStatic(cn.dev33.satoken.context.SaHolder.class);
                var manager = org.mockito.Mockito.mockStatic(cn.dev33.satoken.SaManager.class)) {
            holder.when(cn.dev33.satoken.context.SaHolder::getRequest).thenReturn(request);
            holder.when(cn.dev33.satoken.context.SaHolder::getStorage).thenReturn(storage);
            manager.when(cn.dev33.satoken.SaManager::getSaTokenDao).thenReturn(dao);
            org.mockito.Mockito.when(request.getHeader("X-Platform-Token")).thenReturn("Bearer " + platform);
            org.mockito.Mockito.when(request.getHeader("Authorization")).thenReturn("Bearer " + institution);
            org.mockito.Mockito.when(request.getRequestPath()).thenReturn("/api/v1/platform/agent-deployments");
            assertThat(receiver.getLoginIdNotHandle(platform)).isEqualTo("42");
            assertThat(receiver.getLoginId()).isEqualTo("42");
            org.mockito.Mockito.verifyNoInteractions(dao);

            for (String invalidId : new String[]{"0", "non-decimal", "9223372036854775808"}) {
                String invalid = logic("platform", "platform-api", NOW, PLATFORM_SECRET).createTokenValue(invalidId, "web", 60, Map.of());
                assertThatThrownBy(() -> receiver.getLoginIdNotHandle(invalid)).isInstanceOf(ApiException.class);
            }

            org.mockito.Mockito.when(request.getRequestPath()).thenReturn("/api/v1/admin/usage/details");
            String institutionKey = "Authorization:login:token:" + institution;
            org.mockito.Mockito.when(dao.get(institutionKey)).thenReturn("43");
            assertThat(receiver.getLoginIdNotHandle(institution)).isEqualTo("43");
            assertThat(receiver.splicingKeyTokenValue(institution)).isEqualTo(institutionKey);
            org.mockito.Mockito.verify(dao).get(institutionKey);
            assertThatThrownBy(() -> receiver.getLoginIdNotHandle(platform)).isInstanceOf(ApiException.class);
        }
    }

    @Test void expiresAtExactBoundaryAndRejectsMissingOrForgedClaims() {
        var issuing = logic("institution", "institution-api", NOW);
        String token = issuing.createTokenValue("42", "web", 60, Map.of());
        assertThat(logic("institution", "institution-api", NOW.plusSeconds(59)).getExtra(token, "iss"))
                .isEqualTo("institution");
        assertThatThrownBy(() -> logic("institution", "institution-api", NOW.plusSeconds(60)).getLoginIdByToken(token))
                .isInstanceOf(ApiException.class);
        Map<String, Object> claims = new LinkedHashMap<>(Map.of("iss", "institution", "aud", "institution-api",
                "exp", NOW.getEpochSecond() + 60));
        for (String key : new String[]{"iss", "aud", "exp"}) {
            var incomplete = new LinkedHashMap<>(claims); incomplete.remove(key);
            String invalid = SaJwtUtil.createToken("login", "42", incomplete, SECRET);
            assertThatThrownBy(() -> issuing.getExtra(invalid, "iss")).isInstanceOf(ApiException.class);
        }
        String forged = SaJwtUtil.createToken("login", "42", claims, "another-signing-key");
        assertThatThrownBy(() -> issuing.getLoginIdByToken(forged)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> issuing.getExtra(forged, "iss")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> issuing.createTokenValue("42", "web", -1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 正确realm声明不能替代正确realm密钥；精确路径之外也不能用平台密钥验证机构请求。 */
    @Test void deploymentRealmUsesItsIndependentKeyAndNeverFallsBackToInstitutionKey() {
        var receiver = new ScopedJwtLogic("institution", "institution-api", Clock.fixed(NOW, ZoneOffset.UTC),
                "platform", "platform-api", PLATFORM_SECRET);
        receiver.setConfig(new SaTokenConfig().setJwtSecretKey(SECRET));
        String platform = logic("platform", "platform-api", NOW, PLATFORM_SECRET).createTokenValue("42", "web", 60, Map.of());
        String forgedPlatform = logic("platform", "platform-api", NOW, SECRET).createTokenValue("42", "web", 60, Map.of());
        String institution = logic("institution", "institution-api", NOW).createTokenValue("43", "web", 60, Map.of("tenant_id", "7"));
        var request = org.mockito.Mockito.mock(cn.dev33.satoken.context.model.SaRequest.class);
        try (var holder = org.mockito.Mockito.mockStatic(cn.dev33.satoken.context.SaHolder.class)) {
            holder.when(cn.dev33.satoken.context.SaHolder::getRequest).thenReturn(request);
            org.mockito.Mockito.when(request.getRequestPath()).thenReturn("/api/v1/platform/agent-deployments");
            assertThat(receiver.jwtSecretKey()).isEqualTo(PLATFORM_SECRET);
            assertThat(receiver.getExtra(platform, "iss")).isEqualTo("platform");
            assertThatThrownBy(() -> receiver.getExtra(forgedPlatform, "iss")).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> receiver.getExtra(institution, "iss")).isInstanceOf(ApiException.class);
            org.mockito.Mockito.when(request.getRequestPath()).thenReturn("/api/v1/admin/agents");
            assertThat(receiver.jwtSecretKey()).isEqualTo(SECRET);
            assertThat(receiver.getExtra(institution, "iss")).isEqualTo("institution");
            assertThatThrownBy(() -> receiver.getExtra(platform, "iss")).isInstanceOf(ApiException.class);
        }
        assertThat(receiver.jwtSecretKey()).isEqualTo(SECRET);
    }

    /** 启用额外realm却缺少专用密钥必须在装配时失败，秘密不进入配置toString。 */
    @Test void configuredManagementRealmRequiresExplicitSecretAndDoesNotExposeItInDiagnostics() {
        assertThatThrownBy(() -> new ScopedJwtLogic("institution", "institution-api", Clock.fixed(NOW, ZoneOffset.UTC),
                "platform", "platform-api", null)).isInstanceOf(IllegalArgumentException.class);
        var properties = new com.skillforge.core.shared.config.SkillForgeProperties();
        properties.setManagementJwtSecretKey(PLATFORM_SECRET);
        properties.setPlatformAdminPassword("initial-admin-password-must-stay-secret");
        assertThat(properties.toString()).doesNotContain(PLATFORM_SECRET, "initial-admin-password-must-stay-secret");
    }
}
