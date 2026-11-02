package com.skillforge.platform.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 生产平台依赖路由的 HTTP 契约与探测接线；隔离替身不代替真实 PostgreSQL/Redis 验收。 */
class HealthControllerTest {
    /** Dashboard 原 GET 路径提供本宿主两个真实探测入口的状态，Redis 连接由本次请求释放。 */
    @Test void dashboardDependencyRouteUsesPlatformDatabaseAndRedis() throws Exception {
        var database = mock(HealthMapper.class);
        var redis = mock(StringRedisTemplate.class);
        var factory = mock(RedisConnectionFactory.class);
        var connection = mock(RedisConnection.class);
        when(database.ping()).thenReturn(1);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        var mvc = MockMvcBuilders.standaloneSetup(new HealthController(database, redis)).build();

        mvc.perform(get("/health/deps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.deps.postgres.ok").value(true))
                .andExpect(jsonPath("$.deps.redis.ok").value(true))
                .andExpect(jsonPath("$.deps.postgres.error").isEmpty())
                .andExpect(jsonPath("$.deps.redis.error").isEmpty());
        verify(database).ping();
        verify(connection).ping();
        verify(connection).close();
    }

    /** 各依赖异常准确返回 degraded/503 与固定分类，不将 SQL、端点或凭据映射成健康或响应正文。 */
    @Test void unavailableDependenciesReturn503WithoutLeakingExceptionMessages() throws Exception {
        var database = mock(HealthMapper.class);
        var redis = mock(StringRedisTemplate.class);
        var factory = mock(RedisConnectionFactory.class);
        var connection = mock(RedisConnection.class);
        String secret = "private-health-marker";
        when(database.ping())
                .thenThrow(new IllegalStateException("SELECT '" + secret + "' at db.internal"));
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenThrow(new IllegalStateException("redis://" + secret + "@cache.internal"));
        var mvc = MockMvcBuilders.standaloneSetup(new HealthController(database, redis)).build();

        var response = mvc.perform(get("/health/deps"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("degraded"))
                .andExpect(jsonPath("$.deps.postgres.ok").value(false))
                .andExpect(jsonPath("$.deps.postgres.error").value("postgres_unavailable"))
                .andExpect(jsonPath("$.deps.redis.ok").value(false))
                .andExpect(jsonPath("$.deps.redis.error").value("redis_unavailable"))
                .andReturn().getResponse();
        assertFalse(response.getContentAsString().contains(secret));
        assertFalse(response.getContentAsString().contains("internal"));
        assertFalse(response.getContentAsString().contains("SELECT"));
        verify(connection).close();
    }
}
