package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.media.ModelContent;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.ModelPort.ModelRole;
import com.skillforge.runtime.memory.MemoryRuntime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 默认组的准备和原请求采纳边界；实际 PostgreSQL/Program 组合由独立集成套件负责。 */
class GroupMemoryRecallTest {
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private final MemoryRuntime runtime = mock(MemoryRuntime.class);
    private final JdbcMemoryPort memories = mock(JdbcMemoryPort.class);
    private final GroupMemoryRecall recall = new GroupMemoryRecall(runtime, memories,
            Clock.fixed(NOW, ZoneOffset.UTC), new MemorySearchProperties());

    @Test void freezesOnlyOriginalRequesterAndOriginalSourceTextThenReusesMessageWithoutAnotherSearch() {
        var context = context(Set.of("groups.memory.read"));
        var receipt = mock(MemoryRuntime.RetrievalReceipt.class);
        var event = mock(CommittedSessionEvent.class);
        when(event.eventId()).thenReturn(new EventId("injected-event"));
        when(receipt.committedEvent()).thenReturn(event);
        when(receipt.phase()).thenReturn(MemoryRuntime.Phase.INJECTED);
        when(receipt.trace()).thenReturn(new MemoryRetrievalTrace("group-default-memory:run", List.of(), List.of(), List.of(), List.of()));
        when(receipt.injectedFacts()).thenReturn(List.of());
        when(runtime.findLatestRetrieval(any(), anyString())).thenReturn(Optional.empty());
        when(runtime.retrieve(any())).thenReturn(new MemoryRuntime.RetrievalResult.Recorded(receipt));
        var original = originalMessages();
        var prepared = recall.prepare(context, original, original);
        assertEquals(original.size() + 1, prepared.size());
        assertEquals("group-default-memory:run", prepared.getLast().messageId());
        assertTrue(prepared.getLast().textContent().contains("injected-event"));
        var call = ArgumentCaptor.forClass(MemoryRuntime.RetrievalCall.class);
        verify(runtime).retrieve(call.capture());
        MemoryQuery query = call.getValue().query();
        assertEquals("周报安排", query.intent());
        assertEquals(Optional.of(new EndUserId(7)), query.subjectEndUserId());
        assertEquals(Set.of(MemoryScope.GROUP, MemoryScope.GROUP_MEMBER), query.scopes());
        assertEquals(new MemoryOwner.Group(new GroupBindingRef(new TenantId("1"), "20")), query.owner());
        assertSame(prepared, recall.prepare(context, original, prepared));
        verify(runtime, times(1)).retrieve(any());
        verify(runtime, times(1)).findLatestRetrieval(any(), anyString());
        verify(runtime, times(2)).requireCurrentInjectedRetrievals(eq(context), any());
    }

    @Test void aGroupWithoutMemoryScopeKeepsOrdinaryConversationAndDoesNotFabricateEmptyRetrieval() {
        var history = originalMessages();
        assertSame(history, recall.prepare(context(Set.of("groups.triggers.write")), history, history));
        verify(runtime, never()).findLatestRetrieval(any(), anyString());
        verify(runtime, never()).retrieve(any());
        verifyNoInteractions(memories);
    }

    @Test void firstPreparationCanSkipCurrentReadScopeDenialOnlyBeforeAnyInjection() {
        when(runtime.findLatestRetrieval(any(), anyString())).thenReturn(Optional.empty());
        when(runtime.retrieve(any())).thenReturn(new MemoryRuntime.RetrievalResult.Stopped(
                new MemoryRuntime.Failure("memory_group_current_authorization_denied", Optional.empty())));
        var history = originalMessages();
        assertSame(history, recall.prepare(context(Set.of("groups.memory.read")), history, history));
        verify(runtime, never()).advance(any(), any(), any(), any());
    }

    @Test void revokedInjectedMaterialBlocksBeforeAnyNewSearchEvenWhenOriginalActorNowLacksMemoryScope() {
        var context = context(Set.of("groups.triggers.write"));
        when(runtime.requireCurrentInjectedRetrievals(eq(context), any())).thenReturn(true);
        var failure = assertThrows(IllegalStateException.class,
                () -> recall.prepare(context, originalMessages(), originalMessages()));
        assertEquals("memory_model_material_no_longer_usable", failure.getMessage());
        assertEquals("memory_group_current_authorization_denied", failure.getCause().getMessage());
        verify(runtime, never()).retrieve(any());
    }

    @Test void failedCurrentMaterialCheckDoesNotTurnIntoEmptyContextOrAnotherRetrieval() {
        var context = context(Set.of("groups.memory.read"));
        when(runtime.requireCurrentInjectedRetrievals(eq(context), any()))
                .thenThrow(new IllegalStateException("memory_frozen_fact_no_longer_usable"));
        var failure = assertThrows(IllegalStateException.class,
                () -> recall.prepare(context, originalMessages(), originalMessages()));
        assertEquals("memory_model_material_no_longer_usable", failure.getMessage());
        assertEquals("memory_frozen_fact_no_longer_usable", failure.getCause().getMessage());
        verify(runtime, never()).findLatestRetrieval(any(), anyString());
        verify(runtime, never()).retrieve(any());
    }

    @Test void adoptionRestoresOnlyTheOriginalPreparedMessageWithoutRuntimeOrCurrentGovernanceReads() {
        var history = originalMessages();
        var frozen = new java.util.ArrayList<>(history);
        frozen.add(new ModelMessage("group-default-memory:run", ModelRole.USER, "原冻结记忆"));
        var restored = recall.restore(context(Set.of()).runIdentity(), history, frozen);
        assertEquals(frozen, restored);
        assertSame(restored, recall.restore(context(Set.of()).runIdentity(), restored, frozen));
        verifyNoInteractions(runtime, memories);
        var changed = List.of(new ModelMessage("group-default-memory:run", ModelRole.USER, "替换正文"));
        assertEquals("memory_default_frozen_message_mismatch", assertThrows(IllegalStateException.class,
                () -> recall.restore(context(Set.of()).runIdentity(), restored, changed)).getMessage());
    }

    private static List<ModelMessage> originalMessages() {
        return List.of(new ModelMessage("group-source:old", ModelRole.USER, "其他作者的旧消息"),
                new ModelMessage("group-source:source", ModelRole.USER,
                        List.of(new ModelContent.Text("群消息来源：作者7"), new ModelContent.Text("周报安排"))));
    }

    private static PortCallContext context(Set<String> scopes) {
        var tenant = new TenantId("1");
        var group = new GroupBindingRef(tenant, "20");
        var owner = new SessionOwner(new SessionRef(tenant, "session"), SessionSubject.group(group), SessionTarget.group(group));
        var input = new InputId("input");
        var identity = new RunIdentity(owner, new AgentId("10"), input, new RunRef(tenant, "run"),
                new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"),
                new RunRequester.EndUser(new EndUserId(7)), Optional.of(new SourceMessageRef(owner.sessionRef(), "source")),
                new RunOriginRef(tenant, "origin"));
        var actor = new VerifiedIdentity(new PrincipalRef.ServicePrincipal(tenant, "principal", "integration", 1),
                owner, scopes, 1, new ResultRef("verified"));
        return new PortCallContext(identity, actor, new InvocationControl(NOW.plusSeconds(30), () -> false));
    }
}
