package com.skillforge.core.agent.capabilitymount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMount.KnowledgeMountConfig;
import com.skillforge.core.capability.CapabilityMount.SkillMountConfig;
import com.skillforge.core.capability.CapabilityMount.ToolMountConfig;
import com.skillforge.core.capability.CapabilityMount.WorkflowMountConfig;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.SkillActivationMode;
import org.junit.jupiter.api.Test;

class CapabilityMountPersistenceAssemblerTest {

    private final CapabilityMountPersistenceAssembler mapper = new CapabilityMountPersistenceAssembler();

    @Test
    void skillMountUsesOnlySkillColumnsAndRoundTripsStrongTypes() {
        CapabilityMount mount = new CapabilityMount(
                "101", 10L, 20L,
                new CapabilityRef(CapabilityType.SKILL, "1001", "1002"),
                new SkillMountConfig(SkillActivationMode.ON_DEMAND, 4_000L),
                7, 3L, true);

        AgentCapabilityMountDO row = mapper.toRow(mount, null);

        assertEquals("SKILL", row.getCapabilityType());
        assertEquals(SkillActivationMode.ON_DEMAND.name(), row.getActivationMode());
        assertEquals(4_000L, row.getTokenBudget());
        assertEquals(null, row.getToolDeploymentId());
        assertEquals(mount, mapper.toDomain(row, null));
    }

    @Test
    void toolMountPersistsDeploymentForeignKeyButReadsBackStableDeploymentKey() {
        CapabilityMount mount = new CapabilityMount(
                "102", 10L, 20L,
                new CapabilityRef(CapabilityType.TOOL, "2001", "2002"),
                new ToolMountConfig("calendar-production", 9L),
                1, 5L, false);

        AgentCapabilityMountDO row = mapper.toRow(mount, 9002L);

        assertEquals(9002L, row.getToolDeploymentId());
        assertEquals(mount, mapper.toDomain(row, "calendar-production"));
        assertThrows(CapabilityMountPersistenceException.class, () -> mapper.toDomain(row, null));
    }

    @Test
    void workflowAndKnowledgeMountsKeepTheirTypedTargetFields() {
        CapabilityMount workflow = new CapabilityMount(
                "103", 10L, 20L,
                new CapabilityRef(CapabilityType.WORKFLOW, "3001", "3002"),
                new WorkflowMountConfig("7001", 2L, 1L),
                0, 1L, true);
        CapabilityMount knowledge = new CapabilityMount(
                "104", 10L, 20L,
                new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "4001", "4002"),
                new KnowledgeMountConfig(KnowledgeMountConfig.BindingMode.PINNED_REVISION, "8001"),
                2, 4L, true);

        AgentCapabilityMountDO workflowRow = mapper.toRow(workflow, null);
        AgentCapabilityMountDO knowledgeRow = mapper.toRow(knowledge, null);

        assertEquals(7001L, workflowRow.getWorkflowInstanceId());
        assertEquals(2L, workflowRow.getWorkflowInstanceRevision());
        assertEquals(8001L, knowledgeRow.getKnowledgePinnedRevisionId());
        assertEquals(workflow, mapper.toDomain(workflowRow, null));
        assertEquals(knowledge, mapper.toDomain(knowledgeRow, null));
    }

    @Test
    void malformedNumericIdentityIsRejectedInsteadOfBeingPersistedAsFreeText() {
        CapabilityMount mount = new CapabilityMount(
                "mount", 10L, 20L,
                new CapabilityRef(CapabilityType.SKILL, "calendar", "v1"),
                new SkillMountConfig(SkillActivationMode.ALWAYS, 1L),
                0, 1L, true);

        assertThrows(IllegalArgumentException.class, () -> mapper.toRow(mount, null));
    }
}
