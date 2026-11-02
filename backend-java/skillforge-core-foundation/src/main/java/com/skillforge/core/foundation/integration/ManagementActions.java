package com.skillforge.core.foundation.integration;

import java.util.Map;
import java.util.Set;

/** 当前管理机器明确开放的动作与固定 HTTP 路径；机构执行和身份自管理均不在白名单内。 */
public final class ManagementActions {
    /** 精确授权编码，禁止通配符和未实现的后继阶段动作。 */
    public static final Set<String> SCOPES = Set.of(
            "platform.institutions.read", "platform.institutions.provision", "platform.institutions.associate",
            "platform.institution_relations.read","platform.institution_relations.attach","platform.institution_relations.move","platform.institution_relations.detach",
            "platform.institution_management_grants.read","platform.institution_management_grants.create","platform.institution_management_grants.revoke",
            "platform.institution_credentials.read", "platform.institution_credentials.issue",
            "platform.institution_credentials.rotate", "platform.institution_credentials.revoke",
            "platform.agent_definitions.read", "platform.agent_definitions.edit",
            "platform.agent_definitions.validate", "platform.agent_definitions.publish",
            "platform.agent_definitions.deprecate", "platform.agent_definitions.restore",
            "platform.agent_grants.read", "platform.agent_grants.create", "platform.agent_grants.narrow", "platform.agent_grants.revoke",
            "platform.agent_deployments.read", "platform.agent_deployments.create", "platform.agent_deployments.bind",
            "platform.agent_deployments.switch_authorization", "platform.usage.events.read",
            "platform.usage.summary.read", "platform.usage.details.read");
    private static final String PREFIX = "/api/v1/platform/";
    private static final Map<String, String> ROUTES = Map.ofEntries(
            route("GET", "institutions", "platform.institutions.read"),
            route("POST", "institutions/provision", "platform.institutions.provision"),
            route("POST", "institutions/associate", "platform.institutions.associate"),
            route("GET","institution-relations","platform.institution_relations.read"),
            route("POST","institution-relations/attach","platform.institution_relations.attach"),
            route("POST","institution-relations/move","platform.institution_relations.move"),
            route("POST","institution-relations/detach","platform.institution_relations.detach"),
            route("GET","institution-management-grants","platform.institution_management_grants.read"),
            route("POST","institution-management-grants","platform.institution_management_grants.create"),
            route("POST","institution-management-grants/revoke","platform.institution_management_grants.revoke"),
            route("GET", "institution-credentials", "platform.institution_credentials.read"),
            route("POST", "institution-credentials/issue", "platform.institution_credentials.issue"),
            route("POST", "institution-credentials/rotate", "platform.institution_credentials.rotate"),
            route("POST", "institution-credentials/revoke", "platform.institution_credentials.revoke"),
            route("GET", "agent-definitions", "platform.agent_definitions.read"),
            route("GET", "agent-definitions/detail", "platform.agent_definitions.read"),
            route("GET", "agent-definitions/history", "platform.agent_definitions.read"),
            route("GET", "agent-definitions/impact", "platform.agent_definitions.read"),
            route("PUT", "agent-definitions/update", "platform.agent_definitions.edit"),
            route("POST", "agent-definitions/validate", "platform.agent_definitions.validate"),
            route("POST", "agent-definitions/publish", "platform.agent_definitions.publish"),
            route("POST", "agent-definitions/deprecate", "platform.agent_definitions.deprecate"),
            route("POST", "agent-definitions/restore", "platform.agent_definitions.restore"),
            route("GET", "agent-grants", "platform.agent_grants.read"),
            route("POST", "agent-grants/receipt/query", "platform.agent_grants.read"),
            route("POST", "agent-grants", "platform.agent_grants.create"),
            route("POST", "agent-grants/narrow", "platform.agent_grants.narrow"),
            route("POST", "agent-grants/revoke", "platform.agent_grants.revoke"),
            route("GET", "agent-deployments", "platform.agent_deployments.read"),
            route("POST", "agent-deployments/receipt/query", "platform.agent_deployments.read"),
            route("GET", "agent-deployments/detail", "platform.agent_deployments.read"),
            route("GET", "agent-deployments/definitions", "platform.agent_deployments.read"),
            route("GET", "agent-deployments/grants", "platform.agent_deployments.read"),
            route("POST", "agent-deployments", "platform.agent_deployments.create"),
            route("PUT", "agent-deployments/bindings", "platform.agent_deployments.bind"),
            route("POST", "agent-deployments/authorization", "platform.agent_deployments.switch_authorization"),
            route("GET", "usage/events", "platform.usage.events.read"),
            route("GET", "usage/summary", "platform.usage.summary.read"),
            route("GET", "usage/details", "platform.usage.details.read"));
    /** 静态协议目录不持有状态。 */
    private ManagementActions() { }
    /** 返回明确方法和固定路径对应动作，其他路径返回 null 并由认证链拒绝。 */
    public static String action(String method, String path) { return ROUTES.get(method + " " + path); }
    /** 机构宿主仅承载明确的平台部署管理路径，供精确 JWT realm 路由使用，不赋予权限。 */
    public static boolean isDeploymentPath(String path) {
        return Set.of(PREFIX + "agent-deployments", PREFIX + "agent-deployments/bindings",
                PREFIX + "agent-deployments/authorization", PREFIX + "agent-deployments/detail",
                PREFIX + "agent-deployments/definitions", PREFIX + "agent-deployments/grants",
                PREFIX + "agent-deployments/receipt/query").contains(path);
    }
    /** 固定构造路由键，query 不参与路由匹配但仍参与 HMAC。 */
    private static Map.Entry<String, String> route(String method, String path, String action) {
        return Map.entry(method + " " + PREFIX + path, action);
    }
}
