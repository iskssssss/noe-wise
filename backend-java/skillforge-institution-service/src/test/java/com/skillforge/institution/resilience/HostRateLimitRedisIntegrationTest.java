package com.skillforge.institution.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.web.HostRateLimitInterceptor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 独立 Redis 验证真实计数、首次 TTL 和限流响应；不接入开发实例。 */
@Testcontainers
class HostRateLimitRedisIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC);
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private HostRateLimitInterceptor interceptor;
    private String tenant;

    @BeforeEach
    void setup() {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
        var settings = mock(OperationalSettingsService.class);
        when(settings.read("rate_limits")).thenReturn(new OperationalSettingsService.Snapshot(1,
                Map.of("requests_per_minute_default", 100)));
        interceptor = new HostRateLimitInterceptor(redis, settings, CLOCK);
        tenant = UUID.randomUUID().toString();
    }

    @AfterEach
    void close() {
        TenantContext.clear();
        if (connection != null) connection.destroy();
    }

    @Test
    void concurrentIncrementsAlwaysHaveExpiryAndLaterCallsDoNotRenewIt() throws Exception {
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(12);
            var start = new CountDownLatch(1);
            var pending = java.util.stream.IntStream.range(0, 12).mapToObj(index -> workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                invoke();
                return true;
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (var result : pending) assertTrue(result.get(10, TimeUnit.SECONDS));
        }
        var keys = redis.keys("rate:" + tenant + ":*");
        assertFalse(keys.isEmpty());
        long total = 0;
        for (String key : keys) {
            total += Long.parseLong(redis.opsForValue().get(key));
            long ttl = redis.getExpire(key);
            assertTrue(ttl > 0 && ttl <= 60);
        }
        assertEquals(12, total);
        String currentKey = "rate:" + tenant + ":127.0.0.1:" + (CLOCK.millis() / 60_000);
        redis.opsForValue().set(currentKey, "99", Duration.ofSeconds(20));
        invoke();
        // 用注入计数而非 sleep 验证窗口内请求不会重新设置60秒。
        long remainingTtl = redis.getExpire(currentKey);
        assertTrue(remainingTtl > 0 && remainingTtl <= 20);
    }

    @Test
    void overLimitRetains429AndRetryAfterAndAsyncDispatchDoesNotCount() throws Exception {
        String key = "rate:" + tenant + ":127.0.0.1:" + (CLOCK.millis() / 60_000);
        redis.opsForValue().set(key, "100", Duration.ofSeconds(60));
        TenantContext.setTenantId(tenant);
        var response = new MockHttpServletResponse();
        var failure = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> interceptor.preHandle(request(), response, new Object()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, failure.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
        var async = request();
        async.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        assertTrue(interceptor.preHandle(async, new MockHttpServletResponse(), new Object()));
        assertEquals("101", redis.opsForValue().get(key));
    }

    private void invoke() throws Exception {
        TenantContext.setTenantId(tenant);
        try {
            assertTrue(interceptor.preHandle(request(), new MockHttpServletResponse(), new Object()));
        } finally {
            TenantContext.clear();
        }
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/api/v1/test");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
