package com.skillforge.core.foundation.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillforge.core.foundation.catalog.navigation.ControlledRoutes;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 聚合页面可见性使用已持有的真实动作，不能伪造业务权限或扩张其他页面。 */
class ControlledRoutesTest {
    /** 两端轨迹目录复用真实会话读取权限，不能通过导航扩大到其他读取动作。 */
    @Test void trajectoryRoutesUseSessionReadInBothHosts() {
        for (boolean platform : new boolean[] {false, true}) {
            var route = ControlledRoutes.require("trajectories", platform);
            assertThat(route.path()).isEqualTo((platform ? "/platform/" : "/admin/") + "trajectories");
            assertThat(ControlledRoutes.visiblePermission(route, Set.of("foundation.sessions.read")))
                    .isEqualTo("foundation.sessions.read");
            assertThat(ControlledRoutes.visiblePermission(route, Set.of("foundation.sessions.write"))).isNull();
        }
    }
    /** 双宿主任一消息能力均能显示入口，投影返回实际已获准的动作。 */
    @Test void messagingAcceptsAnyImplementedPageActionInBothHosts() {
        for (boolean platform : new boolean[] {false, true}) {
            var route = ControlledRoutes.require("foundation.messaging", platform);
            for (String action : Set.of("inbox", "read", "publish", "configure", "operate")) {
                String permission = "foundation.messaging." + action;
                assertThat(ControlledRoutes.visiblePermission(route, Set.of(permission))).isEqualTo(permission);
            }
            assertThat(ControlledRoutes.visiblePermission(route, Set.of("foundation.access.read", "foundation.messaging.unknown"))).isNull();
            assertThat(ControlledRoutes.visiblePermission(route, Set.of())).isNull();
        }
    }

    /** 消息入口的聚合规则不能替代组织、文件等其他领域的独立读取动作。 */
    @Test void messagingPermissionDoesNotExposeOtherFoundationPages() {
        for (boolean platform : new boolean[] {false, true}) {
            assertThat(ControlledRoutes.visiblePermission(ControlledRoutes.require("foundation.access", platform),
                    Set.of("foundation.messaging.publish"))).isNull();
            assertThat(ControlledRoutes.visiblePermission(ControlledRoutes.require("foundation.assets", platform),
                    Set.of("foundation.messaging.configure"))).isNull();
        }
    }
    @Test void toolsEntryAcceptsEitherExistingReadPermissionWithoutGrantingAdvancedPages() {
        var tools = ControlledRoutes.require("tools", false);
        assertThat(tools.path()).isEqualTo("/admin/tools");
        assertThat(ControlledRoutes.visiblePermission(tools, Set.of("foundation.capabilities.read")))
                .isEqualTo("foundation.capabilities.read");
        assertThat(ControlledRoutes.visiblePermission(tools, Set.of("foundation.connectors.read")))
                .isEqualTo("foundation.connectors.read");
        assertThat(ControlledRoutes.visiblePermission(tools, Set.of("foundation.capabilities.create"))).isNull();
        assertThat(ControlledRoutes.visiblePermission(ControlledRoutes.require("tool_deployments", false),
                Set.of("foundation.connectors.read"))).isNull();
        assertThat(ControlledRoutes.visiblePermission(ControlledRoutes.require("connectors", false),
                Set.of("foundation.capabilities.read"))).isNull();
    }
}
