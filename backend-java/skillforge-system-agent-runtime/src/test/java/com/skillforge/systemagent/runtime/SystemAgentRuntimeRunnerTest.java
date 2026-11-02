package com.skillforge.systemagent.runtime;

import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.billing.metering.LlmUsage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.*;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import com.skillforge.runtime.starter.*;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimeTrustedAccess;
import com.skillforge.core.foundation.integration.runtime.AgentRuntimePlatformProperties;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SystemAgentRuntimeRunnerTest {
    @Test void completedUsesOriginalTypedRunWithoutPreparingOrReadmittingAndPreservesActualUsage() {
        var f=new Fixture();var result=new LlmResult("{\"result\":\"完成\"}","model",new LlmUsage(10,3,13));
        when(f.executions.query(any(),eq(f.run.identity().runRef()))).thenReturn(Optional.of(f.run));when(f.executions.completed(f.run,"17")).thenReturn(result);
        TenantContext.setTenantId("outer-tenant");try{assertEquals(Optional.of(result),f.runner.completed(f.frozen));assertEquals("outer-tenant",TenantContext.getTenantId());}finally{TenantContext.clear();}
        verifyNoInteractions(f.models);verify(f.executions,never()).admit(any(),any(),any(),any(),anyString());verify(f.executions,never()).wake(any());verify(f.store,never()).register(any(),anyString(),any(),anyLong(),anyString());
    }
    @Test void originalSourceConflictCannotBeHiddenByACompletedNewExecution() {
        var f=new Fixture();f.row.generation=9;
        assertThrows(IllegalStateException.class,()->f.runner.completed(f.frozen));verifyNoInteractions(f.executions,f.models);
    }
    @Test void existingCompletedRunIsQueriedWithoutAnotherModelPreparationOrAdmission() {
        var f=new Fixture();when(f.executions.query(any(),eq(f.run.identity().runRef()))).thenReturn(Optional.of(f.run));var result=new LlmResult("真实结果","model",new LlmUsage(1,2,3));when(f.executions.completed(f.run,"17")).thenReturn(result);
        assertEquals(result,f.runner.execute(f.frozen));verifyNoInteractions(f.models);verify(f.executions,never()).admit(any(),any(),any(),any(),anyString());
    }
    @Test void firstAdmissionKeepsSystemRequesterAndBusinessOriginSeparateFromTheServiceActor() {
        var f = new Fixture(); var original = f.run.identity(); var owner = original.owner(); var tenant = original.runRef().tenantId();
        var service = new PrincipalRef.ServicePrincipal(tenant, "service-actor", SystemAgentRuntimeContracts.INTEGRATION_ID, 1);
        var actor = new VerifiedIdentity(service, owner, Set.of("sessions.read", "sessions.write"), 1, new ResultRef("current-verification"));
        var access = new AccessEnvelope(new AuthenticationRef("service-auth"), tenant, owner.target(), Optional.of(original.agentId()),
                owner.subject(), new RequestId("system-agent-access:business"), SystemAgentRuntimeContracts.CHANNEL,
                SystemAgentRuntimeContracts.SOURCE, original.trace().traceId(), original.trace().spanId());
        when(f.principals.ensureInternal(eq(tenant.value()), eq(SystemAgentRuntimeContracts.INTEGRATION_ID), anySet()))
                .thenReturn(new ServicePrincipalService.TrustedPrincipal(service.principalId(), service.integrationId(), service.credentialVersion()));
        when(f.trustedAccess.serviceAccess(eq(service), eq(owner), anyString(), anyString(), any(), anyString(), anyString())).thenReturn(access);
        when(f.identities.verify(any())).thenReturn(new IdentityPolicyPort.IdentityResult.Verified(actor));
        when(f.executions.query(any(), eq(original.runRef()))).thenReturn(Optional.empty());
        when(f.models.prepare(any(), any())).thenReturn(mock(SystemAgentModelProvisioner.Prepared.class));
        when(f.executions.admit(any(), eq(actor), any(), any(), eq(f.frozen.input()))).thenReturn(f.run);
        var result = new LlmResult("完成", "model", new LlmUsage(1, 2, 3));
        when(f.executions.completed(f.run, "17")).thenReturn(result);
        assertEquals(result, f.runner.execute(f.frozen));
        var captured = org.mockito.ArgumentCaptor.forClass(RunIdentity.class);
        verify(f.executions).admit(captured.capture(), eq(actor), any(), any(), eq(f.frozen.input()));
        assertEquals(new RunRequester.System(new PrincipalRef.SystemPrincipal(tenant, "platform-system-agent")), captured.getValue().requester());
        assertEquals(new RunOriginRef(tenant, f.frozen.id()), captured.getValue().originRef());
        assertTrue(captured.getValue().sourceMessageRef().isEmpty()); assertEquals(owner.subject(), captured.getValue().owner().subject());
        assertEquals(actor.verificationRef(), captured.getValue().causationIdentityRef()); assertInstanceOf(PrincipalRef.ServicePrincipal.class, actor.principal());
    }

    private static final class Fixture {
        private final SystemAgentExecutions executions=mock(SystemAgentExecutions.class);private final SystemAgentRuntimeStore store=mock(SystemAgentRuntimeStore.class);private final SystemAgentModelProvisioner models=mock(SystemAgentModelProvisioner.class);
        private final JdbcIdentityPolicyPort identities=mock(JdbcIdentityPolicyPort.class);
        private final AgentRuntimeTrustedAccess trustedAccess=mock(AgentRuntimeTrustedAccess.class);
        private final ServicePrincipalService principals=mock(ServicePrincipalService.class);
        private final SystemAgentRuntimeStore.SourceRow row=new SystemAgentRuntimeStore.SourceRow();private final FrozenRun frozen;private final Run run;private final SystemAgentRuntimeRunner runner;
        private Fixture(){var now=Instant.parse("2026-10-22T00:00:00Z");var config=new Config("业务用途","","返回业务JSON","17",30,5,List.of());frozen=new FrozenRun("business","11",SystemAgentRuntimeContracts.TENANT_ID,"purpose","AGENT_RUN",1L,true,config,3,"原业务输入",List.of(),new RunView("business","11",1L,true,"RUNNING",null,null,null,null,0,now));
            var tenant=new TenantId(SystemAgentRuntimeContracts.TENANT_ID);var owner=SessionOwner.user(new SessionRef(tenant,"system-agent:business"),new AgentId("11"),new EndUserId(1));var identity=new RunIdentity(owner,owner.target().requireAgent(),new InputId("system-agent-input:business"),new RunRef(tenant,"system-agent-run:business"),new TraceEnvelope("trace","span",new InputId("system-agent-input:business"),Optional.empty()),new ResultRef("actor"), new RunRequester.System(new PrincipalRef.SystemPrincipal(tenant,SystemAgentRuntimeContracts.SYSTEM_PRINCIPAL_ID)), Optional.empty(), new RunOriginRef(tenant,"business"));
            run=new Run(identity,new ResultRef("profile"),RunStatus.COMPLETED,7,1,false,Phase.FINISHED,new Progress(1,0,0,new ResultRef("context")),Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),new ResultRef("actor"),Optional.empty(),now,now);
            var sources=mock(SystemAgentCapabilitySource.class);var snapshot=new SystemAgentCapabilitySource.SourceSnapshot("business","system-agent:11:published:1",1,config,3,List.of(),SystemAgentCapabilitySource.inputFingerprint("原业务输入"));when(sources.from(frozen)).thenReturn(snapshot);when(sources.encode(snapshot)).thenReturn("frozen-source");when(sources.decode("frozen-source")).thenReturn(snapshot);row.generation=1;row.sourceData="frozen-source";when(store.source(owner,identity.causationInputId())).thenReturn(Optional.of(row));
            when(identities.registerTrusted(any(),eq(owner),anyString(),anyString(),any())).thenReturn(new AuthenticationRef("trusted"));
            runner=new SystemAgentRuntimeRunner(executions,trustedAccess,identities,new AgentRuntimeProperties(),new AgentRuntimePlatformProperties(),principals,store,sources,models,new ObjectMapper(),Clock.fixed(now,ZoneOffset.UTC));
        }
    }
}
