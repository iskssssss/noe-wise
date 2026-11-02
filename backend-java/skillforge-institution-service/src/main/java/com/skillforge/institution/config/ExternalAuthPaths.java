package com.skillforge.institution.config;

import java.util.List;

/**
 * 外部调用开放路径与认证来源常量。
 *
 * <p>集中声明开放给外部调用（C 端渠道 / OpenAPI 集成方）的接口路径，供
 * {@link SaTokenConfiguration}（Sa-Token 放行）与 {@link ExternalAuthInterceptor}
 * （外部认证）共用同一份清单，避免两处漂移。
 *
 * <p>安全语义：清单内接口由外部认证二选一放行（B 端 JWT 照常），清单外接口
 * 保持 B 端 JWT 鉴权。开放面见接口契约 §2.6。
 */
public final class ExternalAuthPaths {

    /** 管理类开放接口仅接受签名集成凭证，不向终端渠道令牌开放。 */
    public static final List<String> SIGNED_OPENAPI_PATHS = List.of(
            "/api/v1/agents", "/api/v1/agents/templates", "/api/v1/agents/detail",
            "/api/v1/agents/update", "/api/v1/agents/publish", "/api/v1/agents/revise", "/api/v1/agents/delete",
            "/api/v1/chat/end-users", "/api/v1/chat/sessions/detail",
            "/api/v1/chat/sessions/close", "/api/v1/billing/traces/detail",
            "/api/v1/webhook-subscriptions", "/api/v1/webhook-subscriptions/pause");

    public static boolean isSignedOpenApiPath(String uri) {
        return uri != null && SIGNED_OPENAPI_PATHS.contains(uri);
    }

    /** 固定机构管理路径仅可使用人员JWT或当前显式scope的本机构ServicePrincipal。 */
    public static final List<String> INSTITUTION_MANAGEMENT_PATHS=List.of(
            "/api/v1/tenant/sub-institutions","/api/v1/tenant/sub-institutions/provision",
            "/api/v1/tenant/sub-institutions/provision-options/plans",
            "/api/v1/tenant/usage/summary","/api/v1/tenant/usage/details","/api/v1/tenant/usage/events",
            "/api/v1/tenant/agent-definitions","/api/v1/tenant/agent-definitions/detail","/api/v1/tenant/agent-definitions/history",
            "/api/v1/tenant/agent-definitions/impact","/api/v1/tenant/agent-definitions/update","/api/v1/tenant/agent-definitions/validate",
            "/api/v1/tenant/agent-definitions/publish","/api/v1/tenant/agent-definitions/deprecate","/api/v1/tenant/agent-definitions/restore",
            "/api/v1/tenant/agent-grants","/api/v1/tenant/agent-grants/narrow","/api/v1/tenant/agent-grants/revoke");
    public static boolean isInstitutionManagementPath(String uri){return uri!=null&&INSTITUTION_MANAGEMENT_PATHS.contains(uri);}

    /** 请求 attribute：外部认证来源。 */
    public static final String ATTR_AUTH_SOURCE = "AUTH_SOURCE";

    /** 外部认证来源：C 端渠道令牌。 */
    public static final String SOURCE_CHANNEL = "CHANNEL";

    /** 外部认证来源：OpenAPI 集成方（API Key + HMAC）。 */
    public static final String SOURCE_OPENAPI = "OPENAPI";

    /** 外部认证来源：Tenant Open Platform ServicePrincipal。 */
    public static final String SOURCE_SERVICE_PRINCIPAL = "SERVICE_PRINCIPAL";

    /** ServicePrincipal 独占的开放平台路径前缀。 */
    public static final String OPEN_PLATFORM_PREFIX = "/api/v1/open-platform/";

    /** 开放给外部调用的接口路径清单（Spring PathPattern）。 */
    public static final List<String> OPEN_PATHS = java.util.stream.Stream.concat(java.util.stream.Stream.concat(SIGNED_OPENAPI_PATHS.stream(), List.of(
            // 聊天
            "/api/v1/chat",
            "/api/v1/chat/agents",
            "/api/v1/chat/teams",
            "/api/v1/chat/teams/disable",
            "/api/v1/chat/work",
            "/api/v1/chat/work/history",
            "/api/v1/chat/work/tasks/detail",
            "/api/v1/chat/work/controls",
            "/api/v1/chat/commands",
            "/api/v1/chat/client-profile",
            "/api/v1/chat/browser/devices",
            "/api/v1/chat/browser/pair",
            "/api/v1/chat/browser/revoke",
            "/api/v1/chat/browser/rename",
            "/api/v1/chat/browser/select",
            "/api/v1/chat/browser/tasks",
            "/api/v1/chat/browser/cancel",
            "/api/v1/chat/browser/connection-requests/detail",
            "/api/v1/chat/browser/connection-requests/cancel",
            "/api/v1/chat/sessions/media/upload",
            "/api/v1/chat/sessions/media/upload-receipt",
            "/api/v1/chat/sessions/media/content",
            "/api/v1/chat/sessions/media/limits",
            "/api/v1/chat/sessions/artifacts",
            "/api/v1/chat/sessions/workspace",
            "/api/v1/chat/sessions/attachments",
            "/api/v1/chat/sessions/attachments/upload",
            "/api/v1/chat/sessions/deliverables",
            "/api/v1/chat/deliverables/download-ticket",
            "/api/v1/chat/deliverables/preview-ticket",
            "/api/v1/chat/deliverables/recovery-inputs",
            "/api/v1/chat/artifacts/download-ticket",
            "/api/v1/chat/artifacts/preview-ticket",
            "/api/v1/chat/artifacts/diff",
            "/api/v1/chat/artifacts/recovery-inputs",
            "/api/v1/chat/sync",
            "/api/v1/chat/history",
            "/api/v1/chat/limits",
            "/api/v1/chat/sessions",
            "/api/v1/chat/replay",
            "/api/v1/chat/events",
            "/api/v1/chat/runs/collaboration",
            "/api/v1/chat/runs/plan-mode",
            "/api/v1/chat/runs/cancel",
            "/api/v1/chat/runs/status",
            "/api/v1/chat/runs/budget",
            "/api/v1/chat/runs/steer",
            "/api/v1/chat/runs/steering",
            "/api/v1/chat/runs/delivery",
            "/api/v1/chat/goals",
            "/api/v1/chat/goals/create",
            "/api/v1/chat/goals/pause",
            "/api/v1/chat/goals/resume",
            "/api/v1/messages",
            // 入站回调注册（渠道/OpenAPI 集成方注册事件回调；角色注解对外部认证来源放行）
            "/api/v1/callbacks/registrations",
            // 知识对象调用；人审写入提案使用机构成员身份，不能把外部凭据当作审核人。
            "/api/v1/knowledge/bases", "/api/v1/knowledge/bases/**",
            "/api/v1/knowledge/documents/**", "/api/v1/knowledge/chunks/**", "/api/v1/knowledge/search",
            // Tenant Open Platform：只允许 ServicePrincipal，不回退为 Channel/OpenAPI 身份
            "/api/v1/open-platform/**",
            "/api/v1/tenant/sub-institutions", "/api/v1/tenant/sub-institutions/provision"
    ).stream()),INSTITUTION_MANAGEMENT_PATHS.stream()).distinct().toList();

    public static boolean isOpenPlatformPath(String requestUri) {
        return requestUri != null && requestUri.startsWith(OPEN_PLATFORM_PREFIX);
    }

    private ExternalAuthPaths() {
    }
}
