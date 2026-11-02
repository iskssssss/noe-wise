package com.skillforge.core.agent.capabilitymount;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.ToolDeploymentLookup;
import java.time.Clock;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 批量目录读取共享部署查询，保留装配顺序和缺失代的只读语义。 */
class CapabilityMountBatchReadTest {
    @BeforeAll static void initializeLambdaMappings() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentCapabilityMountSetDO.class);
        TableInfoHelper.initTableInfo(assistant, AgentCapabilityMountDO.class);
    }

    private final AgentCapabilityMountSetMapper sets = mock(AgentCapabilityMountSetMapper.class);
    private final AgentCapabilityMountMapper mounts = mock(AgentCapabilityMountMapper.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final ToolDeploymentLookup deployments = mock(ToolDeploymentLookup.class);
    private final CapabilityMountPersistenceAssembler mapping = new CapabilityMountPersistenceAssembler();
    private final CapabilityMountPersistenceRepository repository = new CapabilityMountPersistenceRepository(
            sets, mounts, agents, deployments, mapping, Clock.systemUTC());

    @Test
    void readsAllSetsMountsAndSharedDeploymentInThreeQueriesWithoutInitializingMissingSet() {
        when(sets.selectList(any())).thenReturn(List.of(set(11), set(12)));
        var first = mapping.toRow(mount("101", 11, 2), 30L);
        var second = mapping.toRow(mount("102", 12, 1), 30L);
        var tail = mapping.toRow(mount("103", 11, 0), 30L);
        when(mounts.selectList(any())).thenReturn(List.of(first, second, tail));
        var deployment = new ToolDeploymentLookup.Identity(30L, "shared-tool");
        when(deployments.findByIds(7L, List.of(30L))).thenReturn(List.of(deployment));

        var result = repository.readAllIfPresent(7L, List.of(11L, 12L, 13L, 11L));

        assertEquals(List.of("101", "103"), result.get(11L).mounts().stream().map(CapabilityMount::mountId).toList());
        assertEquals(List.of("102"), result.get(12L).mounts().stream().map(CapabilityMount::mountId).toList());
        assertFalse(result.containsKey(13L));
        assertEquals("shared-tool", ((CapabilityMount.ToolMountConfig) result.get(11L).mounts().getFirst().config()).toolDeploymentId());
        verify(sets).selectList(any()); verify(mounts).selectList(any()); verify(deployments).findByIds(7L, List.of(30L));
        verifyNoMoreInteractions(sets, mounts, deployments); verifyNoInteractions(agents);
    }

    @Test
    void absentSetsAndEmptyInputNeverReadMountRowsOrWrite() {
        assertTrue(repository.readAllIfPresent(7L, List.of()).isEmpty());
        when(sets.selectList(any())).thenReturn(List.of());
        assertTrue(repository.readAllIfPresent(7L, List.of(11L)).isEmpty());
        verify(sets).selectList(any()); verifyNoMoreInteractions(sets);
        verifyNoInteractions(mounts, agents, deployments);
    }

    private AgentCapabilityMountSetDO set(long agent) {
        var value = new AgentCapabilityMountSetDO(); value.setTenantId(7L); value.setAgentId(agent); value.setGeneration(1L);
        return value;
    }

    private CapabilityMount mount(String id, long agent, int priority) {
        return new CapabilityMount(id, 7L, agent, new CapabilityRef(CapabilityType.TOOL, "21", "22"),
                new CapabilityMount.ToolMountConfig("shared-tool", 1), priority, 1, true);
    }
}
