package com.skillforge.core.foundation.institution;

import com.skillforge.core.foundation.access.FoundationPermission;
import com.skillforge.core.foundation.access.FoundationPermissionContributor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 已实现机构关系与直属目录动作，沿现有IAM角色授权；关系本身不授下级业务权限。 */
@Component
public final class InstitutionRelationPermissionContributor implements FoundationPermissionContributor {
    @Override public List<FoundationPermission> permissions() {
        List<FoundationPermission> values=new ArrayList<>();
        for(String action:List.of("read","attach","move","detach"))
            values.add(new FoundationPermission("platform.institution_relations."+action,"机构关系 "+action,FoundationPermission.Audience.PLATFORM));
        for(String action:List.of("read","provision"))
            values.add(new FoundationPermission("tenant.sub_institutions."+action,"直属机构 "+action,FoundationPermission.Audience.TENANT));
        for(String action:com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions.ALL)
            values.add(new FoundationPermission(action,"直属机构用量 "+action,FoundationPermission.Audience.TENANT));
        for(String action:List.of("read","create","revoke"))
            values.add(new FoundationPermission("platform.institution_management_grants."+action,"总部部署管理许可 "+action,FoundationPermission.Audience.PLATFORM));
        return List.copyOf(values);
    }
}
