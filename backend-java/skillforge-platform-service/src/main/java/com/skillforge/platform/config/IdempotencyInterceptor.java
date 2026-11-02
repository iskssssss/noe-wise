package com.skillforge.platform.config;

import com.skillforge.core.shared.web.HostIdempotencyInterceptor;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 注册共享幂等保护；有领域回执的宿主入口自行核验指纹与重放。 */
@Component
public class IdempotencyInterceptor extends HostIdempotencyInterceptor {
    public IdempotencyInterceptor(StringRedisTemplate redis) {
        super(redis, Set.of(
                "/api/v1/admin/system-agent-tools",
                "/api/v1/admin/system-agent-tools/update",
                "/api/v1/admin/system-agent-tools/publish",
                "/api/v1/platform/management-principals",
                "/api/v1/platform/management-principals/rotate",
                "/api/v1/platform/management-principals/revoke",
                "/api/v1/platform/institutions/provision",
                "/api/v1/platform/institutions/associate",
                "/api/v1/platform/institution-relations/attach",
                "/api/v1/platform/institution-relations/move",
                "/api/v1/platform/institution-relations/detach",
                "/api/v1/platform/institution-management-grants",
                "/api/v1/platform/institution-management-grants/revoke",
                "/api/v1/platform/institution-credentials/issue",
                "/api/v1/platform/institution-credentials/rotate",
                "/api/v1/platform/institution-credentials/revoke",
                "/api/v1/platform/agent-grants",
                "/api/v1/platform/agent-grants/receipt/query",
                "/api/v1/platform/agent-grants/narrow",
                "/api/v1/platform/agent-grants/revoke",
                "/api/v1/platform/agent-definitions",
                "/api/v1/platform/agent-definitions/update",
                "/api/v1/platform/agent-definitions/publish",
                "/api/v1/platform/agent-definitions/deprecate",
                "/api/v1/platform/agent-definitions/restore",
                "/api/v1/platform/billing/process-states"));
    }
}
