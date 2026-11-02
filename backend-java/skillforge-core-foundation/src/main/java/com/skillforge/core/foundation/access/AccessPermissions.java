package com.skillforge.core.foundation.access;

import java.util.List;
import org.springframework.stereotype.Component;

/** access 域拥有的权限定义；业务资源消费者使用资源动作编码完成对象授权。 */
@Component
public final class AccessPermissions implements FoundationPermissionContributor {
    /** 查看组织、成员、角色与权限目录。 */
    public static final String READ = "foundation.access.read";
    /** 管理部门、岗位和成员资料。 */
    public static final String ORGANIZATION_MANAGE = "foundation.access.organization.manage";
    /** 管理角色、权限及数据范围，授予新权限必须由具备同等权限的操作者执行。 */
    public static final String ROLE_MANAGE = "foundation.access.role.manage";
    /** 管理 Agent、Workflow 和知识库共享授权。 */
    public static final String RESOURCE_MANAGE = "foundation.access.resource.manage";

    /** 返回本域实际实现的管理与业务资源动作。 */
    @Override
    public List<FoundationPermission> permissions() {
        java.util.ArrayList<FoundationPermission> result = new java.util.ArrayList<>(List.of(
                new FoundationPermission(READ, "查看组织与权限", FoundationPermission.Audience.BOTH),
                new FoundationPermission(ORGANIZATION_MANAGE, "管理组织与成员", FoundationPermission.Audience.BOTH),
                new FoundationPermission(ROLE_MANAGE, "管理角色与数据范围", FoundationPermission.Audience.BOTH),
                new FoundationPermission(RESOURCE_MANAGE, "管理资源授权", FoundationPermission.Audience.TENANT)));
        result.add(new FoundationPermission("foundation.sessions.write", "写入本人范围会话", FoundationPermission.Audience.TENANT));
        for (String kind : List.of("agent", "workflow", "knowledge")) {
            for (String action : List.of("read", "execute", "manage")) {
                result.add(new FoundationPermission("foundation.resource." + kind + "." + action,
                        kind + "资源" + action, FoundationPermission.Audience.TENANT));
            }
        }
        return List.copyOf(result);
    }
}
