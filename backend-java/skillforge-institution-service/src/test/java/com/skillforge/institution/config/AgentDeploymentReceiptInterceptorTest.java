package com.skillforge.institution.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AgentDeploymentReceiptInterceptorTest {
    @Test void receiptQueryCanBeRepeatedWithoutConsumingTheOriginalKey() throws Exception {
        var redis=mock(StringRedisTemplate.class);
        var interceptor=new IdempotencyInterceptor(redis);
        var request=new MockHttpServletRequest("POST","/api/v1/tenant/agent-deployments/receipt/query");
        request.addHeader("Idempotency-Key","original-request");
        assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),new Object()));
        assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),new Object()));
        verifyNoInteractions(redis);
    }
}
