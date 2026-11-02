package com.skillforge.core.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 相同幂等键只拦截相同授权身份和操作，领域回执入口由自身账本重放。 */
class HostIdempotencyInterceptorTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Set<String> storedKeys = new HashSet<>();
    private final HostIdempotencyInterceptor interceptor =
            new HostIdempotencyInterceptor(redis, Set.of("/receipt"));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setupRedisAndIdentity() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), eq("1"), eq(Duration.ofHours(24))))
                .thenAnswer(call -> storedKeys.add(call.getArgument(0)));
        TenantContext.setTenantId("7");
        RequestContext.setUserId("member-1");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        ChannelContext.clear();
        ServicePrincipalContext.clear();
        ManagementContext.clear();
        RequestObservability.clear();
    }

    @Test
    void repeatedKeyIsIsolatedByAuthenticatedTenantActorMethodAndPath() throws Exception {
        assertThat(accept(request("POST", "/resource"))).isTrue();
        var forged = request("POST", "/resource");
        forged.addHeader("X-Tenant-ID", "8");
        forged.addHeader("X-User-ID", "member-2");
        assertThat(assertThrows(ApiException.class, () -> accept(forged)).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        TenantContext.setTenantId("8");
        assertThat(accept(request("POST", "/resource"))).isTrue();
        TenantContext.setTenantId("7");
        RequestContext.setUserId("member-2");
        assertThat(accept(request("POST", "/resource"))).isTrue();
        RequestContext.setUserId("member-1");
        assertThat(accept(request("PUT", "/resource"))).isTrue();
        assertThat(accept(request("POST", "/other-resource"))).isTrue();
        assertThat(storedKeys).hasSize(5);
    }

    @Test
    void servicePrincipalAndChannelScopesComeFromVerifiedContexts() throws Exception {
        ServicePrincipalContext.set(principal("integration-1"));
        assertThat(accept(request("POST", "/resource"))).isTrue();
        assertThrows(ApiException.class, () -> accept(request("POST", "/resource")));
        ServicePrincipalContext.set(principal("integration-2"));
        assertThat(accept(request("POST", "/resource"))).isTrue();

        ServicePrincipalContext.clear();
        ChannelContext.setChannelId("channel-1");
        assertThat(accept(request("POST", "/resource"))).isTrue();
        ChannelContext.setChannelId("channel-2");
        assertThat(accept(request("POST", "/resource"))).isTrue();
        assertThat(storedKeys).hasSize(4);
    }

    @Test
    void domainReceiptPathsNeverConsumeRedisKeyBeforeLedgerReplay() throws Exception {
        assertThat(accept(request("POST", "/receipt"))).isTrue();
        assertThat(accept(request("POST", "/receipt"))).isTrue();
        verifyNoInteractions(redis);
    }

    @Test
    void managementActorsHaveIndependentKeysAndRotationKeepsTheSameIdempotencyIdentity() throws Exception {
        TenantContext.clear();
        ManagementContext.set(new ManagementContext.Identity("101", 1, 1));
        assertThat(accept(request("POST", "/resource"))).isTrue();
        ManagementContext.set(new ManagementContext.Identity("101", 2, 2));
        assertThrows(ApiException.class, () -> accept(request("POST", "/resource")));
        ManagementContext.set(new ManagementContext.Identity("102", 1, 1));
        assertThat(accept(request("POST", "/resource"))).isTrue();
        assertThat(storedKeys).hasSize(2);
    }

    private boolean accept(MockHttpServletRequest request) throws Exception {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.addHeader("Idempotency-Key", "same-key");
        return request;
    }

    private ServicePrincipalContext.Identity principal(String integration) {
        return new ServicePrincipalContext.Identity("principal", "7", integration, 1, Set.of("resources:write"));
    }
}
