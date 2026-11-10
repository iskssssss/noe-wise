package com.skillforge.institution.group;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.memory.MemoryGovernanceMapper;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 只验证群宿主选择原恢复命令；持久幂等与迁移约束由 Memory Port 测试覆盖。 */
class GroupMemoryServiceTest {
    @AfterEach void clearContexts() { TenantContext.clear(); ServicePrincipalContext.clear(); }

    @Test void forgottenPendingFactRestoresPendingAndReplaysAfterHeadAdvances() {
        var fixture=new Fixture();
        var pending=fact(1,FactStatus.PENDING_CONFIRM);
        var forgotten=fact(2,FactStatus.FORGOTTEN);
        var restored=fact(3,FactStatus.PENDING_CONFIRM);
        var approved=fact(4,FactStatus.ACTIVE);
        when(fixture.memories.findCurrent(any(),eq(forgotten.ref())))
                .thenReturn(Optional.of(forgotten),Optional.of(restored),Optional.of(approved));
        when(fixture.memories.findRevision(any(),eq(forgotten.ref()),eq(2L))).thenReturn(Optional.of(forgotten));
        when(fixture.memories.findRevision(any(),eq(forgotten.ref()),eq(1L))).thenReturn(Optional.of(pending));
        when(fixture.runtime.mutate(any())).thenReturn(new MemoryRuntime.MutationResult.Recorded(restored,mock(CommittedSessionEvent.class),false),
                new MemoryRuntime.MutationResult.Recorded(restored,mock(CommittedSessionEvent.class),true));
        var command=new GroupMemoryContracts.Revision("11","3","7",forgotten.ref().factId(),2);

        assertEquals(restored,fixture.service.restore(command,"restore-pending"));
        assertEquals(restored,fixture.service.restore(command,"restore-pending"));
        assertEquals(restored,fixture.service.restore(command,"restore-pending"));

        var calls=ArgumentCaptor.forClass(MemoryRuntime.MutationCall.class);
        verify(fixture.runtime,times(3)).mutate(calls.capture());
        var original=calls.getAllValues().getFirst();
        var transition=assertInstanceOf(MemoryRuntime.Mutation.Transition.class,original.mutation());
        assertEquals(FactStatus.PENDING_CONFIRM,transition.target());
        assertEquals(2,transition.expectedRevision());
        assertEquals(new IdempotencyKey("restore-pending"),transition.idempotencyKey());
        for(var call:calls.getAllValues()) {
            assertEquals(original.mutation(),call.mutation());
            assertEquals(original.occurredAt(),call.occurredAt());
            assertEquals(forgotten.createdAt(),call.occurredAt());
            assertEquals(original.context().runIdentity(),call.context().runIdentity());
        }
    }

    @Test void approvedFactWithReviewMarkerRestoresActiveEvenWhenReplayHeadIsPending() {
        var fixture=new Fixture();
        var approved=fact(4,FactStatus.ACTIVE);
        var forgotten=fact(5,FactStatus.FORGOTTEN);
        var restored=fact(6,FactStatus.ACTIVE);
        var laterPending=fact(7,FactStatus.PENDING_CONFIRM);
        assertTrue(approved.reviewRequired());
        when(fixture.memories.findCurrent(any(),eq(forgotten.ref())))
                .thenReturn(Optional.of(forgotten),Optional.of(laterPending));
        when(fixture.memories.findRevision(any(),eq(forgotten.ref()),eq(5L))).thenReturn(Optional.of(forgotten));
        when(fixture.memories.findRevision(any(),eq(forgotten.ref()),eq(4L))).thenReturn(Optional.of(approved));
        when(fixture.runtime.mutate(any())).thenReturn(new MemoryRuntime.MutationResult.Recorded(restored,mock(CommittedSessionEvent.class),false),
                new MemoryRuntime.MutationResult.Recorded(restored,mock(CommittedSessionEvent.class),true));
        var command=new GroupMemoryContracts.Revision("11","3","7",forgotten.ref().factId(),5);

        assertEquals(restored,fixture.service.restore(command,"restore-approved"));
        assertEquals(restored,fixture.service.restore(command,"restore-approved"));

        var calls=ArgumentCaptor.forClass(MemoryRuntime.MutationCall.class);
        verify(fixture.runtime,times(2)).mutate(calls.capture());
        var first=calls.getAllValues().getFirst();
        assertEquals(FactStatus.ACTIVE,assertInstanceOf(MemoryRuntime.Mutation.Transition.class,first.mutation()).target());
        assertEquals(first.mutation(),calls.getAllValues().getLast().mutation());
        assertEquals(forgotten.createdAt(),first.occurredAt());
        assertEquals(first.occurredAt(),calls.getAllValues().getLast().occurredAt());
    }

    @Test void restoringPendingRevisionCannotBecomeApprovalAfterCurrentHeadChanges() {
        var fixture=new Fixture();
        var pending=fact(1,FactStatus.PENDING_CONFIRM);
        var current=fact(2,FactStatus.FORGOTTEN);
        when(fixture.memories.findCurrent(any(),eq(current.ref()))).thenReturn(Optional.of(current));
        when(fixture.memories.findRevision(any(),eq(current.ref()),eq(1L))).thenReturn(Optional.of(pending));

        var rejected=assertThrows(ApiException.class,()->fixture.service.restore(
                new GroupMemoryContracts.Revision("11","3","7",current.ref().factId(),1),"pending-is-not-restorable"));

        assertEquals(400,rejected.getStatus().value());
        assertEquals("group_memory_review_required",rejected.getDetail());
        verifyNoInteractions(fixture.runtime);
    }

    @Test void missingPreForgetRevisionDoesNotDefaultToActive() {
        var fixture=new Fixture();
        var forgotten=fact(2,FactStatus.FORGOTTEN);
        when(fixture.memories.findCurrent(any(),eq(forgotten.ref()))).thenReturn(Optional.of(forgotten));
        when(fixture.memories.findRevision(any(),eq(forgotten.ref()),eq(2L))).thenReturn(Optional.of(forgotten));
        when(fixture.memories.findRevision(any(),eq(forgotten.ref()),eq(1L))).thenReturn(Optional.empty());

        var rejected=assertThrows(ApiException.class,()->fixture.service.restore(
                new GroupMemoryContracts.Revision("11","3","7",forgotten.ref().factId(),2),"missing-prior"));

        assertEquals(503,rejected.getStatus().value());
        assertEquals("memory_restore_history_missing",rejected.getDetail());
        verifyNoInteractions(fixture.runtime);
    }

    private static MemoryFact fact(long revision,FactStatus status) {
        var tenant=new TenantId("1");
        return new MemoryFact(new MemoryFactRef(tenant,"group-fact"),new MemoryOwner.Group(new GroupBindingRef(tenant,"11")),
                new MemoryTypeRef("preference"),new RetentionClassRef("standard"),"v2","group:11","format",
                new TypedPayload("skillforge.memory.preference.v2","{}"),List.of(new EventId("source")),new SessionRef(tenant,"session"),
                Optional.empty(),Optional.empty(),"test",revision,Instant.parse("2026-11-11T00:00:00Z").plusSeconds(revision),Instant.parse("2026-11-11T00:00:00Z"),
                Optional.empty(),Optional.empty(),0.9,0.6,true,Optional.empty(),Optional.empty(),1,status);
    }

    private static final class Fixture {
        final JdbcMemoryPort memories=mock(JdbcMemoryPort.class);
        final MemoryRuntime runtime=mock(MemoryRuntime.class);
        final GroupMemoryService service;

        Fixture() {
            TenantContext.setTenantId("1");
            ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp","1","integration",1,Set.of("groups.memory.write")));
            var groups=mock(GroupBindingService.class);
            var binding=mock(GroupContracts.GroupView.class);
            when(binding.sessionId()).thenReturn("session");
            when(groups.requireActive("1","integration","11")).thenReturn(binding);
            var mapper=mock(GroupMemoryMapper.class);
            when(mapper.lockEligibility(1,"integration",11,3,7,"sp",1)).thenReturn(true);
            service=new GroupMemoryService(groups,mock(InstitutionIntegrationService.class),mapper,mock(GroupMemorySources.class),
                    memories,runtime,new ObjectMapper(),mock(MemoryGovernanceMapper.class));
        }
    }
}
