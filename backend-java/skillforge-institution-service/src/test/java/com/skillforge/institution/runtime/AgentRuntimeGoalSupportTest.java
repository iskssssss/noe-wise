package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.CapabilityMountSnapshot;
import com.skillforge.core.capability.tool.ToolCatalog;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeGoalSupportTest {
    @Test void readonlySupportUsesPublishedMountsAndRequiresBothPlatformModelTools() {
        var publications = mock(AgentPublicationService.class);
        var mounts = mock(CapabilityMountResolver.class);
        var catalog = mock(ToolCatalog.class);
        var published = new CapabilityMountSetResult(1L, 7L, 3, List.of());
        when(publications.requirePublished(1L, 7L)).thenReturn(
                new AgentPublicationService.Publication(4L, 2, null, published, Instant.EPOCH));
        when(mounts.restore(any())).thenAnswer(call -> {
            var value = call.getArgument(0, CapabilityMountSnapshot.class);
            assertEquals(1L, value.tenantId()); assertEquals(7L, value.agentId());
            assertEquals(3, value.generation()); assertEquals(published.mounts(), value.mounts());
            return value;
        });
        var read = catalogTool("get_goal"); var update = catalogTool("update_goal");
        var support = new AgentRuntimeGoalSupport(publications, mounts, catalog);
        when(catalog.list(any())).thenReturn(List.of(read));
        assertFalse(support.read(1L, 7L).available());
        assertThrows(ApiException.class, () -> support.require(1L, 7L));
        when(catalog.list(any())).thenReturn(List.of(read, update));
        assertTrue(support.read(1L, 7L).available());
        when(update.target().deployment().providerType()).thenReturn(ToolDeployment.ProviderType.MCP);
        assertFalse(support.read(1L, 7L).available());
        when(update.target().deployment().providerType()).thenReturn(ToolDeployment.ProviderType.PLATFORM);
        when(update.target().version().metadata().invocationPolicy().modelInvocable()).thenReturn(false);
        assertFalse(support.read(1L, 7L).available());
    }

    @Test void admissionChecksActualFrozenBindingsAndModelPermission() {
        var read = runtimeTool("get_goal"); var update = runtimeTool("update_goal");
        assertDoesNotThrow(() -> AgentRuntimeGoalSupport.require(List.of(read, update)));
        assertEquals("goal_tools_unavailable", assertThrows(ApiException.class,
                () -> AgentRuntimeGoalSupport.require(List.of(read))).getCode());
        when(update.binding()).thenReturn(new ToolBinding(ToolBinding.ProviderType.MCP, "mcp", "update_goal"));
        assertThrows(ApiException.class, () -> AgentRuntimeGoalSupport.require(List.of(read, update)));
        when(update.binding()).thenReturn(new ToolBinding(ToolBinding.ProviderType.PLATFORM, "update_goal", "update_goal"));
        when(update.target().modelInvocable()).thenReturn(false);
        assertThrows(ApiException.class, () -> AgentRuntimeGoalSupport.require(List.of(read, update)));
    }

    private ToolCatalog.Entry catalogTool(String name) {
        var entry = mock(ToolCatalog.Entry.class, RETURNS_DEEP_STUBS);
        when(entry.definition().name()).thenReturn(name);
        when(entry.target().deployment().providerRef()).thenReturn(name);
        when(entry.target().deployment().providerType()).thenReturn(ToolDeployment.ProviderType.PLATFORM);
        when(entry.target().version().metadata().invocationPolicy().modelInvocable()).thenReturn(true);
        return entry;
    }
    private SessionToolDirectory.Entry runtimeTool(String name) {
        var entry = mock(SessionToolDirectory.Entry.class, RETURNS_DEEP_STUBS);
        when(entry.binding()).thenReturn(new ToolBinding(ToolBinding.ProviderType.PLATFORM, name, name));
        when(entry.target().modelInvocable()).thenReturn(true);
        return entry;
    }
}
