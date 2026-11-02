package com.skillforge.core.agent.deployment;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.core.foundation.access.AccessPermissions;
import com.skillforge.core.foundation.access.FoundationPermissionCatalogue;
import com.skillforge.core.foundation.assets.AssetsJobsPermissionContributor;
import com.skillforge.core.foundation.catalog.CatalogPermissionContributor;
import com.skillforge.core.foundation.catalog.navigation.ControlledRoutes;
import com.skillforge.core.foundation.institution.InstitutionRelationPermissionContributor;
import com.skillforge.core.foundation.management.ManagementPermissionContributor;
import com.skillforge.core.foundation.messaging.config.MessagingPermissions;
import com.skillforge.core.foundation.operations.FoundationOperationsConfiguration;
import com.skillforge.core.foundation.security.SecurityPermissions;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 用各域真实生产贡献者核对代码路由，不为菜单定义另一份测试权限目录。 */
class ControlledRoutePermissionTest {
    /** 所有默认路由都必须使用所属宿主注册权限，且路径/键唯一。 */
    @Test void everyRouteUsesARegisteredPermissionForItsHost() {
        var catalogue=new FoundationPermissionCatalogue(List.of(new AccessPermissions(),new ManagementPermissionContributor(),
                new CatalogPermissionContributor(),new AssetsJobsPermissionContributor(),new MessagingPermissions(),new SecurityPermissions(),
                new FoundationOperationsConfiguration().operationsPermissions(),new InstitutionRelationPermissionContributor(),
                new AgentManagementPermissionContributor()));
        for(boolean platform:new boolean[]{true,false}) {
            var keys=new HashSet<String>();var paths=new HashSet<String>();
            for(var route:ControlledRoutes.list(platform)) {
                assertTrue(catalogue.supports(route.permission(),platform),route.key()+" 的权限未注册："+route.permission());
                assertTrue(keys.add(route.key()),"路由键重复");assertTrue(paths.add(route.path()),"路径重复");
                assertTrue(route.path().startsWith(platform?"/platform/":"/admin/"));
            }
        }
    }
}
