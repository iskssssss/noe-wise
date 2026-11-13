package com.skillforge.core.agent.deployment;

import com.skillforge.core.foundation.access.FoundationPermission;
import com.skillforge.core.foundation.access.FoundationPermissionContributor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 平台直管定义、原授与目标部署的精确动作，普通Agent动作不隐含这些权限。 */
@Component
public final class AgentManagementPermissionContributor implements FoundationPermissionContributor {
    private static String actionName(String action) {
        return switch (action) {
            case "read" -> "读取";
            case "create" -> "创建";
            case "edit" -> "维护";
            case "validate" -> "校验";
            case "publish" -> "发布";
            case "deprecate" -> "弃用";
            case "restore" -> "恢复";
            case "narrow" -> "收窄";
            case "revoke" -> "撤销";
            case "bind" -> "绑定";
            case "switch_authorization" -> "切换授权";
            default -> action;
        };
    }
    @Override public List<FoundationPermission> permissions() {
        List<FoundationPermission> values=new ArrayList<>();
        for(String action:List.of("read","create","edit","validate","publish","deprecate","restore")) {
            values.add(new FoundationPermission("platform.agent_definitions."+action,"平台共享定义"+actionName(action),FoundationPermission.Audience.PLATFORM));
            values.add(new FoundationPermission("tenant.agent_definitions."+action,"机构共享定义"+actionName(action),FoundationPermission.Audience.TENANT));
        }
        for(String action:List.of("read","create","narrow","revoke")) {
            values.add(new FoundationPermission("platform.agent_grants."+action,"平台授权"+actionName(action),FoundationPermission.Audience.PLATFORM));
            values.add(new FoundationPermission("tenant.agent_grants."+action,"机构授权"+actionName(action),FoundationPermission.Audience.TENANT));
        }
        for(String action:List.of("read","create","bind","switch_authorization")) {
            values.add(new FoundationPermission("platform.agent_deployments."+action,"平台部署"+actionName(action),FoundationPermission.Audience.PLATFORM));
            values.add(new FoundationPermission("tenant.agent_deployments."+action,"机构部署"+actionName(action),FoundationPermission.Audience.TENANT));
        }
        return List.copyOf(values);
    }
}
