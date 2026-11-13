package com.skillforge.core.foundation.institution;

import com.skillforge.core.foundation.access.FoundationPermission;
import com.skillforge.core.foundation.access.FoundationPermissionContributor;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 已实现机构关系与直属目录动作，沿现有IAM角色授权；关系本身不授下级业务权限。 */
@Component
public final class InstitutionRelationPermissionContributor implements FoundationPermissionContributor {
    private static String actionName(String action) {
        return switch (action) {
            case "read" -> "读取";
            case "attach" -> "接入";
            case "move" -> "迁移";
            case "detach" -> "解除";
            case "provision" -> "开通";
            case "create" -> "创建";
            case "revoke" -> "撤销";
            default -> action;
        };
    }
    private static String usageActionName(String code) {
        if (com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions.SUMMARY.equals(code)) return "汇总读取";
        if (com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions.DETAILS.equals(code)) return "明细读取";
        if (com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions.EVENTS.equals(code)) return "事件读取";
        return code;
    }
    @Override public List<FoundationPermission> permissions() {
        List<FoundationPermission> values=new ArrayList<>();
        for(String action:List.of("read","attach","move","detach"))
            values.add(new FoundationPermission("platform.institution_relations."+action,"机构关系"+actionName(action),FoundationPermission.Audience.PLATFORM));
        for(String action:List.of("read","provision"))
            values.add(new FoundationPermission("tenant.sub_institutions."+action,"直属机构"+actionName(action),FoundationPermission.Audience.TENANT));
        for(String action:com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions.ALL)
            values.add(new FoundationPermission(action,"直属机构用量"+usageActionName(action),FoundationPermission.Audience.TENANT));
        for(String action:List.of("read","create","revoke"))
            values.add(new FoundationPermission("platform.institution_management_grants."+action,"总部部署管理许可"+actionName(action),FoundationPermission.Audience.PLATFORM));
        return List.copyOf(values);
    }
}
