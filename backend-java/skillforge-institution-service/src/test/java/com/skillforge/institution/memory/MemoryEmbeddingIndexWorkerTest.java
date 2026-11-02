package com.skillforge.institution.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.runtime.MemorySearchProperties;
import com.skillforge.runtime.adapter.jdbc.memory.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import com.skillforge.runtime.starter.AgentRuntimeMemorySearchBindings;
import com.skillforge.runtime.adapter.jdbc.memory.search.MemorySearchDocument;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MemoryEmbeddingIndexWorkerTest {
    private static final Instant NOW=Instant.parse("2026-10-23T00:00:00Z");
    private final JdbcMemoryPort store=mock(JdbcMemoryPort.class);
    private final MemorySearchEmbedding embedding=mock(MemorySearchEmbedding.class);
    private final ActiveMemoryTenantMapper tenants=mock(ActiveMemoryTenantMapper.class);
    private final MemorySearchProperties settings=new MemorySearchProperties();
    private final MemoryAccessPolicy access=mock(MemoryAccessPolicy.class);

    @Test void restoresOriginalIdentityWithFreshControlAndCompletesOnlyTheClaimedWork() {
        settings.setMode(MemorySearchProperties.Mode.HYBRID);settings.setModelConfigId("901");
        settings.setModelRevision(2L);settings.setDimensions(3);settings.setIndexBatchSize(17);
        var currentPolicy=settings.freeze();
        var work=work();var worker=worker(work);
        when(embedding.embed(any())).thenAnswer(call->{
            assertEquals("101",TenantContext.getTenantId());
            var request=call.getArgument(0,MemorySearchEmbedding.Request.class);
            assertEquals(work.runIdentity(),request.context().runIdentity());
            assertEquals(work.identity(),request.context().identity());
            assertEquals("fixed-index-invocation",request.invocationId());
            assertEquals(NOW.plusSeconds(30),request.context().control().deadlineAt());
            return new MemorySearchEmbedding.Outcome.Available(List.of(1d,2d,3d));
        });
        TenantContext.setTenantId("999");
        try {
            worker.poll();assertEquals("999",TenantContext.getTenantId());
        }finally {TenantContext.clear();}
        verify(store).completeEmbedding(work,List.of(1d,2d,3d));
        verify(store,never()).failEmbedding(any(),anyString(),anyBoolean());
        var sequence=inOrder(store,embedding,access);
        sequence.verify(store).enqueueMissing(new TenantId("101"),currentPolicy,17);
        sequence.verify(store).claimEmbedding(eq(new TenantId("101")),anyString(),eq(Duration.ofMinutes(2)));
        sequence.verify(store).findCurrent(any(MemoryPort.CurrentFactReadBatch.class));
        sequence.verify(access).authorize(argThat(request->request.operation()==MemoryAccessPolicy.Operation.QUERY
                &&request.owner().equals(fact(work,FactStatus.ACTIVE).owner())
                &&request.context().identity().equals(work.identity())&&request.factRef().isEmpty()));
        sequence.verify(embedding).embed(any());
        sequence.verify(store).completeEmbedding(work,List.of(1d,2d,3d));
    }

    @Test void currentAuthorizationDenialOrUnavailableNeverDispatchesFromOldIdentitySnapshot() {
        var work=work();var worker=worker(work);
        when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Denied(
                "memory_group_current_authorization_denied",new ResultRef("current-authorization")));
        worker.poll();
        verify(store).failEmbedding(work,"memory_index_current_access_denied",false);
        verifyNoInteractions(embedding);
        worker=worker(work);
        when(access.authorize(any())).thenThrow(new IllegalStateException("current authorization unavailable"));
        worker.poll();
        verify(store).failEmbedding(work,"memory_index_current_access_unavailable",false);
        verifyNoInteractions(embedding);
    }

    @Test void missingForgottenOrExpiredCurrentFactsNeverReachProvider() {
        var work=work();var worker=worker(work);
        when(store.findCurrent(any(MemoryPort.CurrentFactReadBatch.class))).thenReturn(List.of(Optional.empty()));
        worker.poll();
        verify(store).failEmbedding(work,"memory_index_current_fact_missing",false);
        worker=worker(work);
        when(store.findCurrent(any(MemoryPort.CurrentFactReadBatch.class))).thenReturn(List.of(Optional.of(fact(work,FactStatus.FORGOTTEN))));
        worker.poll();
        verify(store).failEmbedding(work,"memory_index_source_inactive",false);
        worker=worker(work);
        when(store.findCurrent(any(MemoryPort.CurrentFactReadBatch.class)))
                .thenReturn(List.of(Optional.of(fact(work,FactStatus.ACTIVE,Optional.of(NOW.minusSeconds(1))))));
        worker.poll();
        verify(store,times(2)).failEmbedding(work,"memory_index_source_inactive",false);
        verifyNoInteractions(embedding);
        verifyNoInteractions(access);
    }

    @Test void recordsUnknownOnOriginalWorkAndDoesNotDispatchAgainWhenNoWorkCanBeClaimed() {
        var work=work();var worker=worker(work);
        when(embedding.embed(any())).thenReturn(new MemorySearchEmbedding.Outcome.OutcomeUnknown("memory_embedding_outcome_unknown"));
        worker.poll();worker.poll();
        verify(embedding,times(1)).embed(any());
        verify(store).failEmbedding(work,"memory_embedding_outcome_unknown",true);
        verify(store,never()).completeEmbedding(any(),anyList());
        verify(store,times(2)).enqueueMissing(new TenantId("101"),MemoryRetrievalPolicy.lexicalV1(),64);
        var requests=org.mockito.ArgumentCaptor.forClass(MemorySearchEmbedding.Request.class);
        verify(embedding).embed(requests.capture());
        assertEquals(work.invocationId(),requests.getValue().invocationId());
    }

    private MemoryEmbeddingIndexWorker worker(MemoryEmbeddingWork work) {
        when(tenants.after(0)).thenReturn(List.of("101"));
        when(tenants.after(101)).thenReturn(List.of());
        when(store.claimEmbedding(eq(new TenantId("101")),anyString(),eq(Duration.ofMinutes(2))))
                .thenReturn(Optional.of(work),Optional.empty());
        when(store.findCurrent(any(MemoryPort.CurrentFactReadBatch.class))).thenReturn(List.of(Optional.of(fact(work,FactStatus.ACTIVE))));
        when(access.authorize(any())).thenReturn(new MemoryAccessPolicy.Decision.Allowed(new ResultRef("current-allowed")));
        var search=new AgentRuntimeMemorySearchBindings(current->new MemorySearchDocument(work.text(),work.projectionVersion()),
                tenant->settings.freeze(),embedding);
        return new MemoryEmbeddingIndexWorker(store,embedding,settings,tenants,Clock.fixed(NOW,ZoneOffset.UTC),access,search);
    }

    private static MemoryFact fact(MemoryEmbeddingWork work,FactStatus status) {
        return fact(work,status,Optional.empty());
    }

    private static MemoryFact fact(MemoryEmbeddingWork work,FactStatus status,Optional<Instant> expiresAt) {
        return new MemoryFact(new MemoryFactRef(work.tenantId(),work.factId()),new MemoryOwner.Session(work.runIdentity().owner().sessionRef()),
                new MemoryTypeRef("preference"),new RetentionClassRef("standard"),"v1","user:7","reminder",
                new TypedPayload("skillforge.memory.preference.v1","{\"text\":\"正式正文\"}"),List.of(new EventId("original-source")),
                work.runIdentity().owner().sessionRef(),Optional.empty(),Optional.empty(),"source-extractor",1,NOW.minusSeconds(10),NOW.minusSeconds(10),
                Optional.empty(),expiresAt,0.9,0.7,false,Optional.empty(),Optional.empty(),0,status);
    }

    private static MemoryEmbeddingWork work() {
        var tenant=new TenantId("101");var agent=new AgentId("202");var input=new InputId("original-input");
        var owner=SessionOwner.user(new SessionRef(tenant,"original-session"),agent,new EndUserId(7));
        var run=new RunIdentity(owner,agent,input,new RunRef(tenant,"original-run"),
                new TraceEnvelope("original-trace","original-span",new InputId("original-root-input"),Optional.empty()),
                new ResultRef("original-identity"),new RunRequester.EndUser(new EndUserId(7)),Optional.empty(),new RunOriginRef(tenant,"original-origin"));
        var verified=new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant,new EndUserId(7)),owner,Set.of("memory:manage"),2,new ResultRef("original-verification"));
        return new MemoryEmbeddingWork(tenant,"original-fact",1,"memory-body-v1","lucene-cjk-9.12.3-v1",
                new MemoryRetrievalPolicy.EmbeddingModel("901",2,3),"body-digest","正式正文","fixed-index-invocation", "claim-token",NOW.plusSeconds(90),run,verified);
    }
}
