package com.skillforge.institution.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** 后台轮询的租户作用域在正常和失败路径均不能泄漏到下个任务。 */
class MemoryWorkerTenantScopeTest {
    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void extractionBindsEachDiscoveredTenantAndRestoresCallerOnFailure() {
        var mapper = mock(MemoryExtractionMapper.class);
        var tenants = mock(ActiveMemoryTenantMapper.class);
        when(tenants.after(0)).thenReturn(List.of("1", "2"));
        when(mapper.next(anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertEquals(call.getArgument(0), TenantContext.getTenantId());
            throw new IllegalStateException("injected claim failure");
        });
        var worker = new MemoryExtractionWorker(mapper, mock(PlatformTransactionManager.class),
                mock(MemoryCandidateGovernance.class), mock(MemoryRuntime.class), mock(MemoryExtractionAgent.class), mock(MemoryExtractionReuse.class),
                new ObjectMapper(), mock(MemoryExtractionPolicyMapper.class), tenants,
                mock(com.skillforge.institution.group.GroupMemoryExtractionInput.class),
                mock(com.skillforge.institution.group.GroupMemoryExtractionQueue.class));
        TenantContext.setTenantId("caller");
        worker.poll();
        verify(mapper).next("1", "memory-extractor-v2", "memory-extraction-policy-v2");
        verify(mapper).next("2", "memory-extractor-v2", "memory-extraction-policy-v2");
        assertEquals("caller", TenantContext.getTenantId());
    }

    @Test
    void decayBindsThroughGovernanceAndRestoresEmptyCaller() {
        var mapper = mock(MemoryDecayMapper.class);
        var tenants = mock(ActiveMemoryTenantMapper.class);
        var governance = mock(MemoryGovernanceService.class);
        when(tenants.after(0)).thenReturn(List.of("1"));
        when(mapper.due("1", "", MemoryDecayWorker.BATCH_SIZE))
                .thenReturn(List.of(new MemoryDecayMapper.Due("fact", 1)));
        doAnswer(call -> {
            assertEquals("1", TenantContext.getTenantId());
            return null;
        }).when(governance).transition(eq("1"), eq("fact"), eq(1L), any(), anyString(), anyString());
        var worker = new MemoryDecayWorker(mapper, mock(PlatformTransactionManager.class), governance, tenants);
        worker.poll();
        verify(governance).transition(eq("1"), eq("fact"), eq(1L), any(), anyString(), anyString());
        assertNull(TenantContext.getTenantId());
    }
    @Test
    void malformedCandidatePersistsOnlyStableFailureCode() {
        var mapper = mock(MemoryExtractionMapper.class);
        var tenants = mock(ActiveMemoryTenantMapper.class);
        when(tenants.after(0)).thenReturn(List.of("1"));
        var frozen = new MemoryExtractionMaterial.FrozenInput(java.time.Instant.EPOCH, List.of(), List.of());
        var job = new MemoryExtractionMapper.Job("1", "source-sensitive", "preference", "key", "agent",
                "AGENT", "agent", 1, "session", null, "inbound.message",
                "memory-extraction-policy-v2", 0L, 0, null, null, null, null, null, null, null);
        when(mapper.input(any(), eq("memory-extractor-v2"))).thenReturn(frozen);
        when(mapper.next("1", "memory-extractor-v2", "memory-extraction-policy-v2")).thenReturn(job);
        when(mapper.claim(any(), eq("memory-extractor-v2"))).thenReturn(1);
        var reuse = mock(MemoryExtractionReuse.class);
        var source = mock(com.skillforge.runtime.api.port.CommittedSessionEvent.class);
        when(source.agentId()).thenReturn(new com.skillforge.runtime.api.AgentId("agent"));
        when(source.eventType()).thenReturn("inbound.message");
        when(source.executionRef()).thenReturn(java.util.Optional.empty());
        when(source.occurredAt()).thenReturn(java.time.Instant.EPOCH);
        when(source.payload()).thenReturn(new com.skillforge.runtime.api.TypedPayload("inbound.v1",
                "{\"content\":[{\"type\":\"text\",\"text\":\"正式来源\"}],\"metadata\":{\"memoryCandidate\":{\"sensitive\":true,\"object\":{\"secret\":\"sensitive-payload\"}}}}"));
        when(reuse.source(any(), any())).thenReturn(source);
        var runtime = mock(MemoryRuntime.class);
        var worker = new MemoryExtractionWorker(mapper, mock(PlatformTransactionManager.class),
                mock(MemoryCandidateGovernance.class), runtime, mock(MemoryExtractionAgent.class), reuse,
                new ObjectMapper(), mock(MemoryExtractionPolicyMapper.class), tenants,
                mock(com.skillforge.institution.group.GroupMemoryExtractionInput.class),
                mock(com.skillforge.institution.group.GroupMemoryExtractionQueue.class));
        worker.poll();
        verify(mapper).finish(any(), eq("memory-extractor-v2"), eq("FAILED"), isNull(), isNull(), eq("memory_inbound_text_invalid"));
        verifyNoInteractions(runtime);
        assertNull(TenantContext.getTenantId());
    }

}
