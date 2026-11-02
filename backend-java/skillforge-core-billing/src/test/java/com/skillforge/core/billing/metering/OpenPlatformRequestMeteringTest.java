package com.skillforge.core.billing.metering;

import com.skillforge.core.billing.export.PlatformUsageFrozenSource;
import com.skillforge.core.billing.export.PlatformUsageGrantHopRow;
import com.skillforge.core.billing.export.PlatformUsageMapper;
import com.skillforge.core.billing.export.PlatformUsageRecorder;
import com.skillforge.core.foundation.integration.IntegrationProvenanceMapper;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OpenPlatformRequestMeteringTest {
    private final SqlSession sql=mock(SqlSession.class);
    private final PlatformUsageMapper usage=mock(PlatformUsageMapper.class);
    private final IntegrationProvenanceMapper sources=mock(IntegrationProvenanceMapper.class);
    private final RequestCostMapper facts=mock(RequestCostMapper.class);
    private final OpenPlatformRequestMetering metering=new OpenPlatformRequestMetering(new PlatformUsageRecorder(usage,mock(IntegrationProvenanceService.class)));
    private final TenantId tenant=new TenantId("7");
    private final SessionOwner owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("5"),new EndUserId(9));
    private final RunIdentity identity=new RunIdentity(owner,new AgentId("5"),new InputId("input"),new RunRef(tenant,"run"),
            new TraceEnvelope("trace","span",new InputId("root"),Optional.empty()),new ResultRef("verified"),
            new RunRequester.EndUser(owner.requireUser()),Optional.empty(),new RunOriginRef(tenant,"execution-input:run"));
    private PlatformUsageFrozenSource frozen;

    @BeforeEach void setup(){
        when(sql.getMapper(PlatformUsageMapper.class)).thenReturn(usage);when(sql.getMapper(IntegrationProvenanceMapper.class)).thenReturn(sources);
        when(sql.getMapper(RequestCostMapper.class)).thenReturn(facts);
        when(sources.run("7","run")).thenReturn(new IntegrationProvenanceService.Source("external","service_principal:sp","end_user:9","integration","scope",5L,"session","root",null,null,null,null));
        frozen=new PlatformUsageFrozenSource();frozen.setSourceOwnerKind("tenant");frozen.setSourceOwnerTenantId(7L);
        frozen.setAgentDefinitionVersion("frozen-v3");frozen.setAgentRevisionId(13L);frozen.setAgentRevision(3);
        when(usage.frozenSource("7","run",5L)).thenReturn(frozen);when(usage.frozenGrantHops("7","run",5L)).thenReturn(List.of());
        when(facts.insert(any())).thenReturn(1);
    }
    @Test void requestUsesExactAdmissionAndFrozenOriginWithoutSpringMapperOrGuessedMoney(){
        metering.record(sql,identity,"integration");
        var captured=ArgumentCaptor.forClass(CostEventDO.class);verify(facts).insert(captured.capture());var event=captured.getValue();
        assertEquals("input:request",event.getEventId());assertEquals("input",event.getRequestId());
        assertEquals("request",event.getEventType());assertEquals("request",event.getMeasurementUnit());assertEquals(1,event.getQuantity());
        assertNull(event.getTokens());assertNull(event.getPromptTokens());assertNull(event.getCompletionTokens());assertNull(event.getTotalAmount());
        assertEquals("not_applicable",event.getAmountStatus());assertEquals("measured",event.getUsageEventKind());
        assertEquals("session",event.getSessionId());assertEquals("run",event.getRunId());assertEquals("root",event.getRootInputId());
        assertEquals(9L,event.getEndUserId());assertEquals(5L,event.getAgentId());assertEquals("integration",event.getIntegrationId());
        assertEquals("scope",event.getBusinessScopeId());assertEquals("service_principal:sp",event.getActorRef());
        assertEquals("END_USER",event.getRequesterKind());assertEquals("end_user:9",event.getRequesterRef());
        assertEquals("frozen-v3",event.getAgentDefinitionVersion());assertEquals(13L,event.getAgentRevisionId());
        assertEquals("input",event.getMeta().get("input_id"));assertEquals("root",event.getMeta().get("root_input_id"));
        assertEquals("trace",event.getMeta().get("trace_id"));assertEquals("span",event.getMeta().get("span_id"));
        verify(usage).insertPending(event);verify(usage,never()).insertGrantHops(any());
    }
    @Test void originalFrozenGrantChainSharesFactAndPendingSqlSessionInOrder(){
        when(sources.run("7","run")).thenReturn(new IntegrationProvenanceService.Source("external","service_principal:sp","end_user:9",
                "integration","scope",5L,"session","root",31L,4L,41L,2L));
        frozen.setDeploymentId(31L);frozen.setDeploymentRevision(4L);frozen.setGrantId(41L);frozen.setGrantRevision(2L);
        var hop=new PlatformUsageGrantHopRow();hop.setOrdinal(1);hop.setGrantId(41L);hop.setRevision(2L);
        hop.setGrantorKind("tenant");hop.setGrantorTenantId(3L);hop.setTargetTenantId(7L);
        when(usage.frozenGrantHops("7","run",5L)).thenReturn(List.of(hop));
        metering.record(sql,identity,"integration");
        var captured=ArgumentCaptor.forClass(CostEventDO.class);
        var order=inOrder(facts,usage);
        order.verify(usage).frozenSource("7","run",5L);order.verify(usage).frozenGrantHops("7","run",5L);
        order.verify(facts).insert(captured.capture());var event=captured.getValue();
        assertEquals(List.of(hop.toHop()),event.getGrantChain());
        assertEquals(31L,event.getDeploymentId());assertEquals(4L,event.getDeploymentRevision());
        assertEquals(41L,event.getGrantId());assertEquals(2L,event.getGrantRevision());
        order.verify(usage).insertGrantHops(same(event));order.verify(usage).insertPending(same(event));
        verify(sql).getMapper(RequestCostMapper.class);verify(sql,times(2)).getMapper(PlatformUsageMapper.class);
        verify(sources).run("7","run");verifyNoMoreInteractions(sources);
    }
    @Test void sameFactInsertReplayCannotAddSecondPendingOrGrantChain(){
        when(facts.insert(any())).thenReturn(0);metering.record(sql,identity,"integration");
        verify(usage,never()).insertPending(any());verify(usage,never()).insertGrantHops(any());
    }
    @Test void integrationOrFrozenAuthorizationMismatchFailsBeforeAnyFactWrite(){
        assertThrows(IllegalStateException.class,()->metering.record(sql,identity,"other"));verifyNoInteractions(facts);
        frozen.setDeploymentId(31L);
        assertThrows(IllegalStateException.class,()->metering.record(sql,identity,"integration"));verifyNoInteractions(facts);
    }
    @Test void missingFrozenRunSourceDoesNotFallBackToCurrentSession(){
        when(sources.run("7","run")).thenReturn(null);
        assertThrows(IllegalStateException.class,()->metering.record(sql,identity,"integration"));
        verifyNoInteractions(facts);verify(sources,never()).session(any(),any());
    }
    @Test void sourceWriteFailurePropagatesWithoutPublishingPartialFact(){
        var failure=new IllegalStateException("controlled_insert_failure");when(facts.insert(any())).thenThrow(failure);
        assertSame(failure,assertThrows(IllegalStateException.class,()->metering.record(sql,identity,"integration")));
        verify(usage,never()).insertPending(any());
    }
    @Test void borrowedConfigurationUsesDeclaredXmlAndCanBeInstalledOnce(){
        var configuration=new Configuration();OpenPlatformRequestMetering.configure(configuration);OpenPlatformRequestMetering.configure(configuration);
        assertTrue(configuration.hasStatement(RequestCostMapper.class.getName()+".insert"));
        assertTrue(configuration.hasStatement(PlatformUsageMapper.class.getName()+".insertPending"));
        assertTrue(configuration.hasStatement(IntegrationProvenanceMapper.class.getName()+".run"));
    }
}
