package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.constant.HealthConstants;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

/** 公开探针不能泄露持久化框架异常携带的 SQL、端点或凭据。 */
class HealthControllerTest {
    /** 两个依赖同时失败仍返回明确的 503 分类，原始异常正文不进入序列化响应。 */
    @Test
    void dependencyFailureResponsesExcludeRawExceptionMessages() throws Exception {
        var mapper = mock(HealthMapper.class);
        var redis = mock(StringRedisTemplate.class);
        String secret = "private-marker";
        when(mapper.ping()).thenThrow(new IllegalStateException("SELECT '" + secret + "' at db.internal"));
        when(redis.getConnectionFactory()).thenThrow(new IllegalStateException("redis://" + secret + "@cache.internal"));

        var response = new HealthController(mapper, redis).ready();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        var body = response.getBody();
        assertNotNull(body);
        assertEquals("postgres_unavailable", body.deps().get(HealthConstants.DEP_POSTGRES).error());
        assertEquals("redis_unavailable", body.deps().get(HealthConstants.DEP_REDIS).error());
        assertFalse(body.deps().get(HealthConstants.DEP_POSTGRES).ok());
        assertFalse(body.deps().get(HealthConstants.DEP_REDIS).ok());
        String serialized = new ObjectMapper().writeValueAsString(body);
        assertFalse(serialized.contains(secret));
        assertFalse(serialized.contains("internal"));
        assertFalse(serialized.contains("SELECT"));
    }
}
