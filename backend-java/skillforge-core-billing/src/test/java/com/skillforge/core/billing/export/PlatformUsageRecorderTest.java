package com.skillforge.core.billing.export;

import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Set;

/** 原生/系统/外部来源共用原子发布，缺持久根失败关闭而非伪造身份。 */
class PlatformUsageRecorderTest {
    private final PlatformUsageMapper mapper=mock(PlatformUsageMapper.class);
    private final IntegrationProvenanceService sources=mock(IntegrationProvenanceService.class);
    private final PlatformUsageRecorder recorder=new PlatformUsageRecorder(mapper,sources);
    @AfterEach void clear() { TransactionSynchronizationManager.clear(); }
    @Test void freezesOriginalRunAndNeverReadsCurrentSession() {
        var event=event();event.setRunId("run");event.setSessionId("session");
        when(sources.resolveRun("7","run")).thenReturn(new IntegrationProvenanceService.Source(
                "external","service_principal:sp","integration:crm","crm","store",5L,"session","input",9L,1L,8L,1L));
        var frozen=new PlatformUsageFrozenSource();frozen.setAgentDefinitionVersion("1.2.0");
        frozen.setSourceOwnerKind("platform");frozen.setAgentDefinitionId(20L);
        frozen.setAgentRevisionId(50L);frozen.setAgentRevision(3);
        frozen.setDeploymentId(9L);frozen.setDeploymentRevision(1L);frozen.setGrantId(8L);frozen.setGrantRevision(1L);
        when(mapper.frozenSource("7","run",5L)).thenReturn(frozen);
        when(mapper.frozenGrantHops("7","run",5L)).thenReturn(List.of(hop(8L)));
        recorder.attribute(event);
        assertEquals("external",event.getOriginKind());assertEquals("service_principal:sp",event.getActorRef());
        assertEquals(9L,event.getDeploymentId());assertEquals("1.2.0",event.getAgentDefinitionVersion());
        assertEquals("platform",event.getSourceOwnerKind());assertNull(event.getSourceOwnerTenantId());
        assertEquals(20L,event.getAgentDefinitionId());assertEquals(50L,event.getAgentRevisionId());
        assertEquals(3,event.getAgentRevision());assertEquals(1L,event.getDeploymentRevision());
        assertEquals(8L,event.getGrantId());assertEquals(8L,event.getGrantChain().getFirst().grantId());
        verify(sources,never()).resolveSession("7","session");
    }
    @Test void missingRunDoesNotFallBackToSessionOrDefaultApplication() {
        var event=event();event.setRunId("missing");event.setSessionId("session");
        assertThrows(IllegalStateException.class,()->recorder.attribute(event));
        verify(sources,never()).resolveSession("7","session");
    }
    @Test void nativeAndSystemWithoutIntegrationAreLegitimateAndEnqueueInSameTransaction() {
        var event=event();event.setCallPoint("knowledge.rebuild");recorder.attribute(event);
        assertEquals("system",event.getOriginKind());assertNull(event.getIntegrationId());assertNull(event.getAgentId());
        assertThrows(IllegalStateException.class,()->recorder.enqueue(event));
        TransactionSynchronizationManager.setActualTransactionActive(true);recorder.enqueue(event);
        verify(mapper).insertPending(event);
    }
    @Test void revisionsKeepOriginalAttribution() {
        var event=event();event.setAdjustmentOfEventId(2L);event.setOriginKind("native");event.setActorRef("member:5");
        when(mapper.grantHops(Set.of(7L),List.of(2L))).thenReturn(List.of(hop(8L)));
        recorder.attribute(event);assertEquals("member:5",event.getActorRef());verifyNoInteractions(sources);
        assertEquals(8L,event.getGrantChain().getFirst().grantId());
        verify(mapper,never()).frozenSource(any(),any(),any());
    }
    @Test void controlOperationDoesNotFabricateConversationOrAgent() {
        var event=event();event.setScopeType("control_operation");event.setScopeId("op");
        when(sources.resolveControlOperation("7","op")).thenReturn(new IntegrationProvenanceService.Source(
                "external","service_principal:sp","integration:crm","crm",null,null,null,null,null,null,null,null));
        recorder.attribute(event);assertEquals("crm",event.getIntegrationId());assertNull(event.getSessionId());assertNull(event.getAgentId());
    }
    @Test void actualGroupRequesterComesFromRunRatherThanSessionCreator() {
        var event=event();event.setRunId("group-run");event.setSessionId("shared-session");
        when(sources.resolveRun("7","group-run")).thenReturn(new IntegrationProvenanceService.Source(
                "external","end_user:17","integration:crm","crm",null,5L,"shared-session","root",null,null,null,null));
        var frozen=new PlatformUsageFrozenSource();frozen.setSourceOwnerKind("tenant");frozen.setSourceOwnerTenantId(7L);
        frozen.setAgentDefinitionVersion("agent:5:1");when(mapper.frozenSource("7","group-run",5L)).thenReturn(frozen);
        when(mapper.executionSource("7","group-run")).thenReturn(new PlatformUsageExecutionSource(
                91L,"END_USER","end_user:17","shared-session","message-17"));
        recorder.attribute(event);
        assertEquals(91L,event.getGroupBindingId());assertEquals("end_user:17",event.getRequesterRef());
        assertEquals("message-17",event.getOriginEventId());assertEquals("END_USER",event.getRequesterKind());
        verify(sources,never()).resolveSession(any(),any());
    }

    @Test void backgroundMemoryUsageSeparatesActualSystemActorFromOriginalRequesterAndPayer() {
        var event=event();event.setScopeType(PlatformUsageRecorder.SYSTEM_AGENT_SCOPE_TYPE);event.setScopeId("71");
        event.setEndUserId(1L);
        when(mapper.systemSource("7",71L)).thenReturn(new PlatformUsageSystemSource(71L,88L,"configuration:88:2",
                "invocation:71","memory-extraction",false,5L,17L,"crm",91L,"shared-session","source-17","input-17","store"));
        recorder.attribute(event);
        assertEquals(7L,event.getTenantId());assertEquals(88L,event.getAgentId());assertEquals(5L,event.getResponsibilityAgentId());
        assertEquals("system:system-agent:88",event.getActorRef());assertEquals("agent:5",event.getResponsibilityRef());
        assertEquals("SYSTEM",event.getRequesterKind());assertEquals("system:system-agent:88",event.getRequesterRef());
        assertEquals(17L,event.getEndUserId());assertEquals(91L,event.getGroupBindingId());assertEquals("crm",event.getIntegrationId());assertEquals("store",event.getBusinessScopeId());
        assertEquals("source-17",event.getOriginEventId());assertEquals("input-17",event.getRootInputId());
        assertEquals(71L,event.getSystemAgentRunId());assertEquals("configuration:88:2",event.getSystemAgentConfigurationRef());
        assertEquals("invocation:71",event.getSystemAgentInvocationRef());assertEquals("platform",event.getSourceOwnerKind());
        verifyNoInteractions(sources);
    }

    @Test void missingPreDispatchMemoryBindingCannotBecomeAnonymousSystemUsage() {
        var event=event();event.setScopeType(PlatformUsageRecorder.SYSTEM_AGENT_SCOPE_TYPE);event.setScopeId("71");
        assertEquals("usage_system_run_source_missing",assertThrows(IllegalStateException.class,()->recorder.attribute(event)).getMessage());
        when(mapper.systemSource("7",71L)).thenReturn(new PlatformUsageSystemSource(71L,88L,"configuration:88:2",
                "invocation:71","memory-extraction",false,null,null,null,null,null,null,null,null));
        assertEquals("usage_memory_extraction_source_missing",assertThrows(IllegalStateException.class,()->recorder.attribute(event)).getMessage());
        verifyNoInteractions(sources);
    }

    @Test void auxiliarySystemCallKeepsOriginalParentRunResponsibilityAndFrozenDefinition() {
        var event=event();event.setRunId("parent-run");event.setScopeType(PlatformUsageRecorder.SYSTEM_AGENT_SCOPE_TYPE);event.setScopeId("71");
        when(mapper.systemSource("7",71L)).thenReturn(new PlatformUsageSystemSource(71L,88L,"configuration:88:2",
                "invocation:71","output-requirements",false,null,null,null,null,null,null,null,null));
        when(sources.resolveRun("7","parent-run")).thenReturn(new IntegrationProvenanceService.Source(
                "external","end_user:17","integration:crm","crm",null,5L,"shared-session","root",null,null,null,null));
        var frozen=new PlatformUsageFrozenSource();frozen.setSourceOwnerKind("tenant");frozen.setSourceOwnerTenantId(7L);
        frozen.setAgentDefinitionVersion("parent-definition:1");when(mapper.frozenSource("7","parent-run",5L)).thenReturn(frozen);
        when(mapper.executionSource("7","parent-run")).thenReturn(new PlatformUsageExecutionSource(
                91L,"END_USER","end_user:17","shared-session","message-17"));
        recorder.attribute(event);
        assertEquals(5L,event.getAgentId());assertEquals("parent-run",event.getRunId());
        assertEquals("integration:crm",event.getResponsibilityRef());assertEquals("parent-definition:1",event.getAgentDefinitionVersion());
        assertEquals("tenant",event.getSourceOwnerKind());assertEquals(91L,event.getGroupBindingId());
        assertEquals(71L,event.getSystemAgentRunId());assertEquals("configuration:88:2",event.getSystemAgentConfigurationRef());
    }

    @Test void programChildConsumesOneTrustedAttributionForAllFrozenFactsButKeepsActualRun() {
        var event=event();event.setRunId("child");event.setAgentId(5L);event.setAttemptId("child-attempt");
        when(sources.resolveRun("7","parent")).thenReturn(new IntegrationProvenanceService.Source(
                "external","end_user:17","integration:crm","crm",null,5L,"session","root",null,null,null,null));
        var frozen=new PlatformUsageFrozenSource();frozen.setSourceOwnerKind("tenant");frozen.setSourceOwnerTenantId(7L);
        frozen.setAgentDefinitionVersion("parent-definition:1");when(mapper.frozenSource("7","parent",5L)).thenReturn(frozen);
        recorder.attribute(event,"parent");
        assertEquals("child",event.getRunId());assertEquals("child-attempt",event.getAttemptId());
        assertEquals("parent-definition:1",event.getAgentDefinitionVersion());
        verify(mapper).executionSource("7","child");verify(mapper).frozenGrantHops("7","parent",5L);
        verify(sources,never()).resolveRun("7","child");verify(mapper,never()).frozenSource("7","child",5L);
        var missing=event();missing.setRunId("child");
        assertEquals("usage_persistent_origin_missing",assertThrows(IllegalStateException.class,()->recorder.attribute(missing,"absent")).getMessage());
    }

    private CostEventDO event() { var e=new CostEventDO();e.setTenantId(7L);e.setId(1L);return e; }
    private PlatformUsageGrantHopRow hop(Long grant) {
        var row=new PlatformUsageGrantHopRow();row.setOrdinal(1);row.setGrantId(grant);row.setRevision(1L);
        row.setGrantorKind("platform");row.setTargetTenantId(7L);return row;
    }

    @Test void actualDelegatedActorUsesOwnFrozenSourceAndEnqueuesChainBeforePublication() {
        var event=event();event.setRunId("child-run");event.setAgentId(99L);
        when(sources.resolveRun("7","child-run")).thenReturn(new IntegrationProvenanceService.Source(
                "native","member:5","tenant:7",null,null,12L,"session","root",null,null,null,null));
        var frozen=new PlatformUsageFrozenSource();frozen.setSourceOwnerKind("tenant");frozen.setSourceOwnerTenantId(7L);
        frozen.setAgentRevisionId(1200L);frozen.setAgentRevision(4);frozen.setAgentDefinitionVersion("agent:4");
        when(mapper.frozenSource("7","child-run",12L)).thenReturn(frozen);
        recorder.attribute(event);
        assertEquals(12L,event.getAgentId());assertEquals(1200L,event.getAgentRevisionId());
        assertNull(event.getAgentDefinitionId());assertEquals(7L,event.getSourceOwnerTenantId());
        verify(mapper,never()).frozenSource("7","child-run",99L);
        event.setGrantChain(List.of(hop(8L).toHop()));
        TransactionSynchronizationManager.setActualTransactionActive(true);recorder.enqueue(event);
        var order=inOrder(mapper);order.verify(mapper).insertGrantHops(event);order.verify(mapper).insertPending(event);
    }

    @Test void missingFrozenBindingAndMismatchedAuthorizationCannotBorrowCurrentState() {
        var event=event();event.setRunId("run");
        when(sources.resolveRun("7","run")).thenReturn(new IntegrationProvenanceService.Source(
                "native","member:5","tenant:7",null,null,12L,"session","root",9L,2L,8L,3L));
        assertEquals("usage_frozen_agent_source_missing",assertThrows(IllegalStateException.class,
                ()->recorder.attribute(event)).getMessage());
        var frozen=new PlatformUsageFrozenSource();frozen.setDeploymentId(9L);frozen.setDeploymentRevision(1L);
        frozen.setGrantId(8L);frozen.setGrantRevision(3L);
        when(mapper.frozenSource("7","run",12L)).thenReturn(frozen);
        assertEquals("usage_frozen_authorization_mismatch",assertThrows(IllegalStateException.class,
                ()->recorder.attribute(event)).getMessage());
        verify(sources,never()).resolveSession(any(),any());
    }
}
