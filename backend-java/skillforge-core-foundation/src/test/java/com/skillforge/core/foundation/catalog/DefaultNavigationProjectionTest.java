package com.skillforge.core.foundation.catalog;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.catalog.navigation.ControlledRoutes;
import com.skillforge.core.foundation.catalog.navigation.NavigationContracts.Node;
import com.skillforge.core.foundation.catalog.navigation.NavigationMapper;
import com.skillforge.core.foundation.catalog.navigation.NavigationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 业务组只组织已经获准的叶节点，自有机构树包括空树继续优先。 */
class DefaultNavigationProjectionTest {
    private final FoundationAccess access=mock(FoundationAccess.class);
    private final NavigationMapper mapper=mock(NavigationMapper.class);
    private final NavigationService navigation=new NavigationService(mapper,access,new CatalogScope(access));

    @Test void templateGroupsPruneUnauthorizedAndDisabledBranchesUsingActualRoutePermission() {
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"11","401"));
        String permission=ControlledRoutes.require("agents",false).permission();
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of(),List.of(permission),1));
        when(mapper.nodes("tenant_template")).thenReturn(List.of(
                new Node("nav_agents",null,"智能体与协作",null,10,true,1),
                new Node("nav_data",null,"能力与数据",null,20,true,1),
                new Node("nav_disabled",null,"停用分组",null,30,false,1),
                new Node("agents","nav_agents","智能体","agents",10,true,2),
                new Node("knowledge","nav_data","知识库","knowledge",90,true,2),
                new Node("agents_hidden","nav_disabled","同权限但父组停用","agents",20,true,1)));
        var menu=navigation.current();assertEquals(1,menu.size());
        assertEquals("nav_agents",menu.getFirst().code());assertNull(menu.getFirst().path());
        assertEquals(1,menu.getFirst().children().size());assertEquals("/admin/agents",menu.getFirst().children().getFirst().path());
        assertEquals(permission,menu.getFirst().children().getFirst().permission());
    }

    @Test void initializedCustomTreeAndExplicitlyEmptyTreeNeverFallBackToGroupedTemplate() {
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"11","401"));
        when(access.currentSnapshot()).thenReturn(new FoundationAccess.Snapshot(List.of(),List.of(ControlledRoutes.require("agents",false).permission()),1));
        when(mapper.initialized("tenant:11")).thenReturn(true);
        when(mapper.nodes("tenant:11")).thenReturn(List.of(new Node("my_group",null,"我的分组",null,77,true,6),new Node("my_agents","my_group","自定义名称","agents",321,true,8)));
        var menu=navigation.current();assertEquals("我的分组",menu.getFirst().title());assertEquals(77,menu.getFirst().sortOrder());
        assertEquals("自定义名称",menu.getFirst().children().getFirst().title());assertEquals(321,menu.getFirst().children().getFirst().sortOrder());
        when(mapper.nodes("tenant:11")).thenReturn(List.of());assertTrue(navigation.current().isEmpty());
        verify(mapper,never()).nodes("tenant_template");
    }
}
