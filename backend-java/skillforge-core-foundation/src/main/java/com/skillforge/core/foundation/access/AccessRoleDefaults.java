package com.skillforge.core.foundation.access;

import java.util.Set;

/** 初次建制内置普通角色的明确动作白名单，实际授权仍写入关系表，不在请求时兜底。 */
final class AccessRoleDefaults {
    /** 开发成员的资源管理域，不含组织、套餐、安全和平台运营。 */
    private static final Set<String> DEVELOPER_AREAS = Set.of("agents","workflows","knowledge","capabilities","skills","channels","connectors","tool_deployments","applications","sessions","memory","handoff","teams","browser","artifacts","open_platform","webhooks");
    /** 开发成员可用业务动作。 */
    private static final Set<String> DEVELOPER_ACTIONS = Set.of("read","create","update","delete","publish","run","cancel","export");
    private AccessRoleDefaults() { }
    /** 返回初次建制时可持久授予的权限；未知域和动作不放行。 */
    static boolean accepts(String roleCode,String permission) {
        if(Set.of("foundation.access.read","foundation.messaging.inbox","foundation.preferences.manage","foundation.security.self").contains(permission)) return true;
        if("auditor".equals(roleCode)) return Set.of("foundation.audit.read","foundation.usage.read","foundation.billing.read","foundation.operations.audit_read","foundation.operations.audit_export").contains(permission);
        if(!"developer".equals(roleCode)) return false;
        if("foundation.sessions.write".equals(permission)) return true;
        if(permission.matches("foundation\\.resource\\.(agent|workflow|knowledge)\\.(read|execute|manage)")) return true;
        String[] parts=permission.split("\\.");
        return parts.length==3 && DEVELOPER_AREAS.contains(parts[1]) && DEVELOPER_ACTIONS.contains(parts[2]);
    }
}
