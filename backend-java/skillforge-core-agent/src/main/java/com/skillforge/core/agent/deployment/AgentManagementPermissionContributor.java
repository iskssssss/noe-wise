package com.skillforge.core.agent.deployment;

import com.skillforge.core.foundation.access.FoundationPermission;
import com.skillforge.core.foundation.access.FoundationPermissionContributor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 平台直管定义、原授与目标部署的精确动作，普通Agent动作不隐含这些权限。 */
@Component
public final class AgentManagementPermissionContributor implements FoundationPermissionContributor {
    @Override public List<FoundationPermission> permissions() {
        List<FoundationPermission> values=new ArrayList<>();
        for(String action:List.of("read","create","edit","validate","publish","deprecate","restore")) {
            values.add(new FoundationPermission("platform.agent_definitions."+action,"平台定义 "+action,FoundationPermission.Audience.PLATFORM));
            values.add(new FoundationPermission("tenant.agent_definitions."+action,"机构共享定义 "+action,FoundationPermission.Audience.TENANT));
        }
        for(String action:List.of("read","create","narrow","revoke")) {
            values.add(new FoundationPermission("platform.agent_grants."+action,"平台授权 "+action,FoundationPermission.Audience.PLATFORM));
            values.add(new FoundationPermission("tenant.agent_grants."+action,"机构授权 "+action,FoundationPermission.Audience.TENANT));
        }
        for(String action:List.of("read","create","bind","switch_authorization")) {
            values.add(new FoundationPermission("platform.agent_deployments."+action,"平台部署 "+action,FoundationPermission.Audience.PLATFORM));
            values.add(new FoundationPermission("tenant.agent_deployments."+action,"机构部署 "+action,FoundationPermission.Audience.TENANT));
        }
        return List.copyOf(values);
    }
}
