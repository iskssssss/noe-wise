package com.skillforge.institution.config;

import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.web.HostRateLimitInterceptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 注册共享的租户请求限流。 */
@Component
public class RateLimitInterceptor extends HostRateLimitInterceptor {
    public RateLimitInterceptor(StringRedisTemplate redis, OperationalSettingsService settings) {
        super(redis, settings);
    }
}
