package com.skillforge.institution.config;

import com.skillforge.core.shared.web.HostIdempotencyInterceptor;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 注册共享幂等保护；领域回执自行核验重放，POST授权/资格查询始终重新复验。 */
@Component
public class IdempotencyInterceptor extends HostIdempotencyInterceptor {
    public IdempotencyInterceptor(StringRedisTemplate redis) {
        super(redis, Set.of(
                "/api/v1/teams/save-and-publish",
                "/api/v1/teams/publish-with-receipt",
                "/api/v1/knowledge/bases/revisions/prepare-and-publish",
                "/api/v1/capabilities/skill-drafts/save-and-publish",
                "/api/v1/builder/workflows/save-and-publish",
                "/api/v1/tools/onboard",
                "/api/v1/connectors/instances/delete-with-receipt",
                "/api/v1/agents/workflows/onboard",
                "/api/v1/tenant/sub-institutions/provision",
                "/api/v1/tenant/agent-definitions","/api/v1/tenant/agent-definitions/update",
                "/api/v1/tenant/agent-definitions/publish","/api/v1/tenant/agent-definitions/deprecate","/api/v1/tenant/agent-definitions/restore",
                "/api/v1/tenant/agent-grants","/api/v1/tenant/agent-grants/narrow","/api/v1/tenant/agent-grants/revoke",
                "/api/v1/platform/agent-deployments",
                "/api/v1/platform/agent-deployments/receipt/query",
                "/api/v1/platform/agent-deployments/bindings",
                "/api/v1/platform/agent-deployments/authorization",
                "/api/v1/tenant/agent-deployments",
                "/api/v1/tenant/agent-deployments/receipt/query",
                "/api/v1/tenant/agent-deployments/bindings",
                "/api/v1/tenant/agent-deployments/authorization",
                "/api/v1/open-platform/agent-deployments",
                "/api/v1/open-platform/agent-deployments/bindings",
                "/api/v1/open-platform/agent-deployments/authorization",
                "/api/v1/open-platform/business-scopes/ensure",
                "/api/v1/open-platform/business-scopes/status",
                "/api/v1/open-platform/groups/ensure",
                "/api/v1/open-platform/groups/close",
                "/api/v1/open-platform/groups/members/update",
                "/api/v1/open-platform/groups/agents/update",
                "/api/v1/open-platform/groups/messages",
                "/api/v1/open-platform/groups/memories/create",
                "/api/v1/open-platform/groups/memories/review",
                "/api/v1/open-platform/groups/memories/correct",
                "/api/v1/open-platform/groups/memories/forget",
                "/api/v1/open-platform/groups/memories/restore",
                "/api/v1/memories/review",
                "/api/v1/memories/correct",
                "/api/v1/memories/forget",
                "/api/v1/memories/restore",
                "/api/v1/open-platform/groups/triggers",
                "/api/v1/open-platform/groups/runs/respond",
                "/api/v1/open-platform/groups/runs/commands",
                "/api/v1/open-platform/groups/controls",
                "/api/v1/open-platform/groups/controls/terminalize",
                "/api/v1/open-platform/groups/controls/release",
                "/api/v1/open-platform/groups/controls/authorize",
                "/api/v1/open-platform/groups/deliveries/qualify",
                "/api/v1/open-platform/groups/sender-endpoints/register",
                "/api/v1/open-platform/groups/sender-endpoints/retire",
                "/api/v1/open-platform/groups/sender-endpoints/ack",
                "/api/v1/chat",
                "/api/v1/chat/sync",
                "/api/v1/chat/runs/cancel",
                "/api/v1/chat/runs/plan-mode",
                "/api/v1/chat/runs/steer",
                "/api/v1/open-platform/control-operations",
                "/api/v1/open-platform/sessions",
                "/api/v1/open-platform/sessions/messages",
                "/api/v1/open-platform/sessions/workflow-invocations",
                "/api/v1/open-platform/sessions/context-messages",
                "/api/v1/open-platform/sessions/actions",
                "/api/v1/open-platform/sessions/callbacks",
                "/api/v1/open-platform/sessions/commands",
                "/api/v1/runtime/callbacks/deliveries"));
    }
}
