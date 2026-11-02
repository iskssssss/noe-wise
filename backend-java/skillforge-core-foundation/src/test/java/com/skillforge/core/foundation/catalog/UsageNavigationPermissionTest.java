package com.skillforge.core.foundation.catalog;

import com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions;
import com.skillforge.core.foundation.catalog.navigation.ControlledRoutes;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 独立读取动作均能进入用量页，组织目录或另一宿主权限不能扩大该入口。 */
class UsageNavigationPermissionTest {
    @Test void headquartersAcceptsEachReadActionWithoutRequiringSummary() {
        var route=ControlledRoutes.require("headquarters_usage",false);
        for(String action:HeadquartersUsageActions.ALL)
            assertEquals(action,ControlledRoutes.visiblePermission(route,Set.of(action)));
        assertNull(ControlledRoutes.visiblePermission(route,Set.of("tenant.sub_institutions.read")));
        assertNull(ControlledRoutes.visiblePermission(route,Set.of("platform.usage.details.read")));
    }

    @Test void platformReadActionsRemainIndependentOfEachOtherAndHeadquarters() {
        var route=ControlledRoutes.require("usage",true);
        for(String action:Set.of("platform.usage.summary.read","platform.usage.details.read","platform.usage.events.read"))
            assertEquals(action,ControlledRoutes.visiblePermission(route,Set.of(action)));
        assertNull(ControlledRoutes.visiblePermission(route,HeadquartersUsageActions.ALL));
        var deployment=ControlledRoutes.require("agent_deployments",false);
        assertNull(ControlledRoutes.visiblePermission(deployment,HeadquartersUsageActions.ALL));
        var relations=ControlledRoutes.require("institution_relations",true);
        assertEquals("platform.institution_relations.read",ControlledRoutes.visiblePermission(relations,Set.of("platform.institution_relations.read")));
        assertNull(ControlledRoutes.visiblePermission(relations,Set.of("foundation.tenants.read")));
    }
}
