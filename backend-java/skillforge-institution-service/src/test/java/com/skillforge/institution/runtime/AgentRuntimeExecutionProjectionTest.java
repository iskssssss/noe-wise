package com.skillforge.institution.runtime;

import com.skillforge.core.chat.session.SessionOutboxEntry;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionOutboxOutput;
import com.skillforge.core.chat.session.SessionOutboxProjectionBatch;
import com.skillforge.core.chat.session.SessionOutboxProjectionCheckpoint;
import com.skillforge.core.chat.session.SessionOutboxProjectionSource;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** 公开投影只消费准确保存的产品事实，精确等待身份和安全表单不依赖 Run 恢复。 */
class AgentRuntimeExecutionProjectionTest {
    @Test void admissionWithFrozenPlanModeAdvancesOutboxCursorWithoutPublishingInternalMode() {
        var fixture = new Fixture();
        var owner = com.skillforge.runtime.api.port.SessionOwner.user(fixture.session,
                new com.skillforge.runtime.api.AgentId("7"),new com.skillforge.runtime.api.EndUserId(9));
        var input = new com.skillforge.runtime.api.InputId("input-admission");
        var run = new com.skillforge.runtime.api.port.RunIdentity(owner,new com.skillforge.runtime.api.AgentId("7"),input,
                new RunRef(fixture.session.tenantId(),"run-admission"),
                new com.skillforge.runtime.api.TraceEnvelope("trace-admission","span-admission",input,java.util.Optional.empty()),
                new com.skillforge.runtime.api.ResultRef("identity-admission"),new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()),
                java.util.Optional.empty(),new com.skillforge.runtime.api.RunOriginRef(fixture.session.tenantId(),"origin-admission"));
        // 使用Gateway准入的同一生产事件工厂，不能以带公开trace的测试payload掩盖内部事实。
        var accepted = new ExecutionStore.SavedEvent(run.runRef(),1,1,Instant.EPOCH,AgentExecutionProgram.admissionEvent(run));
        var mode = new ExecutionStore.SavedEvent(run.runRef(),2,1,Instant.EPOCH,
                com.skillforge.runtime.session.SessionCollaboration.admissionMode(run,
                        new com.skillforge.runtime.session.SessionCollaboration.Selection(true,"selection-on",7)));
        fixture.projection.appendBorrowed(fixture.sql,fixture.session,List.of(accepted,mode),after->List.of());
        assertEquals(List.of("execution.accepted"),fixture.types());
        assertEquals(2,fixture.borrowedSequence); assertEquals(mode.event().id().value(),fixture.borrowedEventId);
        fixture.projection.appendBorrowed(fixture.sql,fixture.session,List.of(accepted,mode),after->List.of());
        assertEquals(List.of("execution.accepted"),fixture.types());
        var reconnect = new Fixture();
        when(reconnect.store.events(reconnect.session,0,100)).thenReturn(List.of(accepted,mode));
        reconnect.projection.reconcile(reconnect.session);
        assertEquals(List.of("execution.accepted"),reconnect.types());
        assertTrue(fixture.projection.project(owner,List.of(mode)).messages().isEmpty());
    }

    @Test void planModeInternalBoundaryKeepsSchemaTenantAndPublicTraceValidationStrict() {
        var fixture = new Fixture();
        var invalid = new ExecutionStore.SavedEvent(new RunRef(fixture.session.tenantId(),"run-1"),1,1,Instant.EPOCH,
                new ExecutionStore.Event(new EventId("mode-invalid"),com.skillforge.runtime.session.SessionCollaboration.MODE,
                        new TypedPayload("wrong-schema","{}")));
        assertEquals("execution_internal_event_invalid",assertThrows(IllegalArgumentException.class,
                ()->fixture.projection.appendBorrowed(fixture.sql,fixture.session,List.of(invalid),after->List.of())).getMessage());
        var foreign = new ExecutionStore.SavedEvent(new RunRef(new TenantId("8"),"run-1"),1,1,Instant.EPOCH,
                new ExecutionStore.Event(new EventId("mode-foreign"),com.skillforge.runtime.session.SessionCollaboration.MODE,
                        new TypedPayload(com.skillforge.runtime.session.SessionCollaboration.MODE_SCHEMA,"{}")));
        assertThrows(SecurityException.class,()->fixture.projection.appendBorrowed(fixture.sql,fixture.session,List.of(foreign),after->List.of()));
        var publicWithoutTrace = new ExecutionStore.SavedEvent(invalid.run(),1,1,Instant.EPOCH,
                new ExecutionStore.Event(new EventId("accepted-invalid"),AgentExecutionProgram.ACCEPTED_EVENT,
                        new TypedPayload(AgentExecutionProgram.ACCEPTED_SCHEMA,"{}")));
        assertEquals("execution_public_event_invalid",assertThrows(IllegalArgumentException.class,
                ()->fixture.projection.appendBorrowed(fixture.sql,fixture.session,List.of(publicWithoutTrace),after->List.of())).getMessage());
        assertTrue(fixture.outputs.isEmpty()); assertEquals(0,fixture.borrowedSequence);
    }

    @Test void internalContextFactsAdvanceBorrowedAndReconciledCursorsWithoutPublicTraceOrMessages() {
        var fixture=new Fixture();var run=new RunRef(fixture.session.tenantId(),"run-1");
        var material=new ExecutionStore.SavedEvent(run,1,2,Instant.EPOCH,new ExecutionStore.Event(new EventId("context-material"),
                "agent.context-material",new TypedPayload("skillforge.runtime.execution.context-material.v1","{\"sourceContext\":\"private-source\",\"messages\":{}}")));
        var attempt=new ExecutionStore.SavedEvent(run,2,2,Instant.EPOCH,new ExecutionStore.Event(new EventId("context-attempt"),
                "agent.context-compaction-attempt",new TypedPayload("skillforge.runtime.execution.context-compaction-attempt.v1","{\"summaryStatus\":\"UNAVAILABLE\",\"summaryReason\":\"SUMMARY_DISABLED\"}")));
        fixture.projection.appendBorrowed(fixture.sql,fixture.session,List.of(material,attempt),after->List.of());
        assertEquals(2,fixture.borrowedSequence);assertEquals("context-attempt",fixture.borrowedEventId);assertTrue(fixture.outputs.isEmpty());
        fixture.projection.appendBorrowed(fixture.sql,fixture.session,List.of(fixture.accepted(3)),after->List.of());
        assertEquals(List.of("execution.accepted"),fixture.types());assertEquals(3,fixture.borrowedSequence);
        verifyNoInteractions(fixture.store,fixture.sql);
        var reconnect=new Fixture();when(reconnect.store.events(reconnect.session,0,100)).thenReturn(List.of(material,attempt,reconnect.accepted(3)));
        reconnect.projection.reconcile(reconnect.session);assertEquals(List.of("execution.accepted"),reconnect.types());
        var owner=com.skillforge.runtime.api.port.SessionOwner.user(fixture.session,new com.skillforge.runtime.api.AgentId("7"),new com.skillforge.runtime.api.EndUserId(9));
        assertTrue(fixture.projection.project(owner,List.of(material,attempt)).messages().isEmpty());
    }

    @Test void internalContextEventNamesDoNotBypassTenantOrSchemaValidation() {
        var fixture=new Fixture();
        for(var type:List.of("agent.context-material","agent.context-compaction-attempt")) {
            var saved=new ExecutionStore.SavedEvent(new RunRef(fixture.session.tenantId(),"run-1"),1,2,Instant.EPOCH,
                    new ExecutionStore.Event(new EventId("wrong-schema"),type,new TypedPayload("unexpected.v1","{}")));
            assertEquals("execution_internal_event_invalid",assertThrows(IllegalArgumentException.class,
                    ()->fixture.projection.appendBorrowed(fixture.sql,fixture.session,List.of(saved),after->List.of())).getMessage());
        }
        var foreign=new ExecutionStore.SavedEvent(new RunRef(new TenantId("8"),"foreign"),1,2,Instant.EPOCH,
                new ExecutionStore.Event(new EventId("foreign-context"),"agent.context-material",new TypedPayload("skillforge.runtime.execution.context-material.v1","{}")));
        assertThrows(SecurityException.class,()->fixture.projection.appendBorrowed(fixture.sql,fixture.session,List.of(foreign),after->List.of()));
        assertTrue(fixture.outputs.isEmpty());assertEquals(0,fixture.borrowedSequence);
    }

    @Test void observationAdvancesTheCommittedCursorWithoutCreatingDuplicateChatProcessEvents() {
        var fixture = new Fixture();
        var fact = new com.skillforge.runtime.session.ExecutionObservation.Fact("action-1", "step:model-1",
                com.skillforge.runtime.session.ExecutionObservation.Kind.TOOL, "search",
                com.skillforge.runtime.session.ExecutionObservation.Status.REGISTERED,
                "run-1", "action-1", "attempt-1", "step:model-1", "1", "input-1", "root-1",
                "trace-1", "span-1", null, null, null);
        var metadata = new ExecutionStore.SavedEvent(new RunRef(fixture.session.tenantId(), "run-1"), 1, 2,
                Instant.parse("2026-10-25T01:00:00Z"), new ExecutionStore.Event(new EventId("observation-1"),
                com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE,
                com.skillforge.runtime.session.ExecutionObservation.encode(fact, 100_000, 10_000, 32)));
        fixture.projection.appendBorrowed(fixture.sql, fixture.session, List.of(metadata), after -> List.of());
        assertTrue(fixture.outputs.isEmpty());
        assertEquals(1L, fixture.borrowedSequence);
        fixture.projection.appendBorrowed(fixture.sql, fixture.session, List.of(fixture.accepted(2)), after -> List.of());
        assertEquals(List.of("execution.accepted"), fixture.types());
        assertEquals(2L, fixture.borrowedSequence);
        verifyNoInteractions(fixture.store, fixture.sql);
    }

    @Test void unsupportedOrWrongOwnerObservationCannotBypassThePublicProjectionBoundary() {
        var fixture = new Fixture();
        var invalid = new ExecutionStore.SavedEvent(new RunRef(fixture.session.tenantId(), "run-1"), 1, 2,
                Instant.parse("2026-10-25T01:00:00Z"), new ExecutionStore.Event(new EventId("observation-invalid"),
                com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE,
                new TypedPayload(com.skillforge.runtime.session.ExecutionObservation.SCHEMA, "{}")));
        assertThrows(IllegalArgumentException.class, () -> fixture.projection.appendBorrowed(fixture.sql, fixture.session, List.of(invalid), after -> List.of()));
        assertTrue(fixture.outputs.isEmpty());
        var wrongOwner = new com.skillforge.runtime.session.ExecutionObservation.Fact("action-1", "step:model-1",
                com.skillforge.runtime.session.ExecutionObservation.Kind.TOOL, "search",
                com.skillforge.runtime.session.ExecutionObservation.Status.REGISTERED,
                "other-run", "action-1", "attempt-1", "step:model-1", "1", "input-1", "root-1",
                "trace-1", "span-1", null, null, null);
        var mismatched = new ExecutionStore.SavedEvent(invalid.run(), invalid.sequence(), invalid.revision(), invalid.occurredAt(),
                new ExecutionStore.Event(new EventId("observation-owner-mismatch"), com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE,
                        com.skillforge.runtime.session.ExecutionObservation.encode(wrongOwner, 100_000, 10_000, 32)));
        assertThrows(IllegalArgumentException.class, () -> fixture.projection.appendBorrowed(fixture.sql, fixture.session, List.of(mismatched), after -> List.of()));
    }

    @Test void workflowDispatchAndResultProjectOneProcessWithoutPublishingRawResultBlocks() throws Exception {
        var fixture = new Fixture();
        var startBody = workflowBody(fixture, false, "DISPATCHED", null);
        var resultBody = workflowBody(fixture, true, "COMPLETED", "{\"produced\":{\"count\":0,\"enabled\":false,\"nested\":{\"label\":\"proof\"}}}");
        var started = new ExecutionStore.SavedEvent(new RunRef(fixture.session.tenantId(), "run-1"), 1, 2, Instant.parse("2026-10-24T01:00:00Z"),
                new ExecutionStore.Event(new EventId("workflow-start"), AgentExecutionProgram.WORKFLOW_STARTED_EVENT,
                        new TypedPayload(AgentExecutionProgram.WORKFLOW_STARTED_SCHEMA, fixture.json.writeValueAsString(startBody))));
        var finished = new ExecutionStore.SavedEvent(started.run(), 2, 3, started.occurredAt().plusSeconds(1),
                new ExecutionStore.Event(new EventId("workflow-result"), AgentExecutionProgram.WORKFLOW_RESULT_EVENT,
                        new TypedPayload(AgentExecutionProgram.WORKFLOW_RESULT_SCHEMA, fixture.json.writeValueAsString(resultBody))));
        fixture.projection.appendBorrowed(fixture.sql, fixture.session, List.of(started, finished), after -> List.of());
        assertEquals(List.of("process.started", "process.finished"), fixture.types());
        assertEquals(fixture.data("process.started").get("process_id"), fixture.data("process.finished").get("process_id"));
        assertEquals("workflow", fixture.data("process.finished").get("type"));
        assertEquals("succeeded", fixture.data("process.finished").get("status"));
        assertFalse(fixture.json.writeValueAsString(fixture.outputs).contains("produced"));
        var safe = AgentRuntimeExecutionPublicEvents.protectWorkflow(finished.event(), new PublicOutputGuard(fixture.privatePath), fixture.json);
        var payload = fixture.json.readTree(fixture.json.readTree(safe.data().canonicalJson()).path("output").path("canonicalJson").textValue());
        assertEquals(0, payload.path("produced").path("count").intValue());
        assertTrue(payload.path("produced").path("enabled").isBoolean()); assertFalse(payload.path("produced").path("enabled").booleanValue());
    }

    @Test void workflowUnknownCancelledAndFailureKeepActualStatusWithoutOutput() throws Exception {
        for (var item : Map.of("UNKNOWN", "outcome_unknown", "CANCELLED", "cancelled", "FAILED", "failed", "TERMINATED", "failed").entrySet()) {
            var fixture = new Fixture(); var body = workflowBody(fixture, true, item.getKey(), null);
            fixture.events(AgentExecutionProgram.WORKFLOW_RESULT_EVENT, AgentExecutionProgram.WORKFLOW_RESULT_SCHEMA, body);
            fixture.projection.reconcile(fixture.session);
            assertEquals(item.getValue(), fixture.data("process.finished").get("status"));
            assertFalse(body.get("outputAccepted").equals(true)); assertNull(body.get("output"));
        }
    }

    private static Map<String, Object> workflowBody(Fixture fixture, boolean finished, String status, String output) {
        var body = fixture.base(); body.put("actionId", "workflow-action"); body.put("executionId", "workflow-execution");
        body.put("status", status); body.put("summary", finished ? "工作流执行结果已保存" : "工作流调用已登记");
        if (finished) {
            body.put("executionRevision", "4"); body.put("outputAccepted", output != null);
            body.put("output", output == null ? null : Map.of("schemaId", "workflow-output.v1", "canonicalJson", output));
        }
        return body;
    }

    @Test void emptyBorrowedBatchDoesNotEnterMailboxReadOrOwnTheTransaction() {
        var fixture = new Fixture();
        fixture.projection.appendBorrowed(fixture.sql, fixture.session, List.of(), after -> {
            throw new AssertionError("空批次不应回读日志");
        });
        verifyNoInteractions(fixture.mailbox, fixture.store, fixture.sql);
    }

    @Test void borrowedBatchUsesExactSavedFactsWithoutReadingStoreOrOwningTheTransaction() {
        var fixture = new Fixture();
        fixture.borrowedSequence = 2; fixture.borrowedEventId = "event-2";
        var events = List.of(fixture.accepted(3), fixture.accepted(4));

        fixture.projection.appendBorrowed(fixture.sql, fixture.session, events, after -> {
            throw new AssertionError("连续新事件不应回读日志");
        });

        assertEquals(List.of("execution.accepted", "execution.accepted"), fixture.types());
        assertEquals(List.of("event-3", "event-4"), fixture.outputs.stream().map(output -> output.trace().eventId()).toList());
        assertEquals(List.of(3L, 4L), fixture.outputs.stream()
                .map(output -> ((Map<?, ?>) output.payload().get("data")).get("sequence")).toList());
        assertEquals(List.of(4L), fixture.borrowedSources.stream().map(SessionOutboxProjectionSource::sequence).toList());
        verify(fixture.mailbox).projectOutputsBorrowed(same(fixture.sql), eq("7"), eq("session-1"), any(), any());
        verifyNoMoreInteractions(fixture.mailbox);
        verifyNoInteractions(fixture.store, fixture.sql);
    }

    @Test void borrowedBatchSkipsAlreadyCoveredPrefixAndKeepsTheExactCurrentRunAndEventIdentity() {
        var fixture = new Fixture();
        fixture.borrowedSequence = 2; fixture.borrowedEventId = "event-2";
        var current = fixture.accepted(3);
        var otherRun = new ExecutionStore.SavedEvent(new RunRef(fixture.session.tenantId(), "run-2"),
                current.sequence(), current.revision(), current.occurredAt(), current.event());

        fixture.projection.appendBorrowed(fixture.sql, fixture.session,
                List.of(fixture.accepted(1), fixture.accepted(2), otherRun), after -> {
                    throw new AssertionError("部分重放不应回读日志");
                });

        assertEquals(List.of("execution.accepted"), fixture.types());
        assertEquals("event-3", fixture.outputs.getFirst().trace().eventId());
        assertEquals("run-2", fixture.outputs.getFirst().runtimeRunId());
        assertEquals("run-2", fixture.data("execution.accepted").get("run_id"));
        assertEquals(3L, fixture.borrowedSequence);
        verifyNoInteractions(fixture.store, fixture.sql);
    }

    @Test void borrowedPolicyResetFillsHistoryInBoundedPagesAndStopsAtCurrentBatchWatermark() {
        var fixture = new Fixture();
        fixture.borrowedPolicy = "human-assistance-v1";
        fixture.borrowedSequence = 900; fixture.borrowedEventId = "human-event-900";
        var history = java.util.stream.LongStream.rangeClosed(1, 260).mapToObj(fixture::accepted).toList();
        var reads = new ArrayList<Long>();

        fixture.projection.appendBorrowed(fixture.sql, fixture.session, history.subList(250, 252), after -> {
            reads.add(after);
            return history.stream().filter(saved -> saved.sequence() > after).limit(100).toList();
        });

        assertEquals(List.of(0L, 100L, 200L), reads);
        assertEquals(List.of(100L, 200L, 250L, 252L), fixture.borrowedSources.stream()
                .map(SessionOutboxProjectionSource::sequence).toList());
        assertEquals(252, fixture.outputs.size());
        assertEquals(java.util.stream.LongStream.rangeClosed(1, 252).mapToObj(value -> "event-" + value).toList(),
                fixture.outputs.stream().map(output -> output.trace().eventId()).toList());
        assertEquals(252L, fixture.borrowedSequence);
        assertEquals("event-252", fixture.borrowedEventId);
        verify(fixture.mailbox, times(4)).projectOutputsBorrowed(same(fixture.sql), eq("7"), eq("session-1"), any(), any());
        verifyNoMoreInteractions(fixture.mailbox);
        verifyNoInteractions(fixture.store, fixture.sql);
    }

    @Test void borrowedBatchAlreadyCoveredDoesNotReadOrRepublishFacts() {
        var fixture = new Fixture();
        fixture.borrowedSequence = 3; fixture.borrowedEventId = "event-3";
        fixture.projection.appendBorrowed(fixture.sql, fixture.session, List.of(fixture.accepted(2)), after -> {
            throw new AssertionError("已覆盖来源不应回读日志");
        });
        assertTrue(fixture.outputs.isEmpty());
        assertTrue(fixture.borrowedSources.isEmpty());
        assertEquals(3L, fixture.borrowedSequence);
        verifyNoInteractions(fixture.store, fixture.sql);
    }

    @Test void borrowedBatchRejectsCheckpointIdentityConflictBeforeProjectingAnyCurrentFact() {
        var fixture = new Fixture();
        fixture.borrowedSequence = 2; fixture.borrowedEventId = "different-event-2";
        var error = assertThrows(IllegalStateException.class, () -> fixture.projection.appendBorrowed(
                fixture.sql, fixture.session, List.of(fixture.accepted(1), fixture.accepted(2), fixture.accepted(3)),
                after -> { throw new AssertionError("冲突水位不应回读日志"); }));
        assertEquals("execution_projection_checkpoint_event_mismatch", error.getMessage());
        assertTrue(fixture.outputs.isEmpty());
        assertTrue(fixture.borrowedSources.isEmpty());
        verifyNoInteractions(fixture.store, fixture.sql);
    }

    @Test void borrowedBatchRejectsCrossTenantFactsBeforeEnteringMailbox() {
        var fixture = new Fixture();
        var saved = fixture.accepted(1);
        var foreign = new ExecutionStore.SavedEvent(new RunRef(new TenantId("8"), "run-1"),
                saved.sequence(), saved.revision(), saved.occurredAt(), saved.event());
        assertThrows(SecurityException.class, () -> fixture.projection.appendBorrowed(fixture.sql,
                fixture.session, List.of(foreign), after -> List.of()));
        verifyNoInteractions(fixture.mailbox, fixture.store, fixture.sql);
    }

    @Test void borrowedBatchRejectsMissingOrReorderedSequencesAndRepeatedEventIdentities() {
        var fixture = new Fixture();
        for (var events : List.of(List.of(fixture.accepted(1), fixture.accepted(3)),
                List.of(fixture.accepted(2), fixture.accepted(1)))) {
            assertThrows(IllegalArgumentException.class, () -> fixture.projection.appendBorrowed(
                    fixture.sql, fixture.session, events, after -> List.of()));
        }
        var saved = fixture.accepted(2);
        var repeated = new ExecutionStore.SavedEvent(saved.run(), saved.sequence(), saved.revision(),
                saved.occurredAt(), fixture.accepted(1).event());
        assertThrows(IllegalArgumentException.class, () -> fixture.projection.appendBorrowed(fixture.sql,
                fixture.session, List.of(fixture.accepted(1), repeated), after -> List.of()));
        verifyNoInteractions(fixture.mailbox, fixture.store, fixture.sql);
    }

    @Test void borrowedGapRejectsEmptyOrDiscontinuousHistoryWithoutAdvancingTheCheckpoint() {
        var empty = new Fixture();
        var error = assertThrows(IllegalStateException.class, () -> empty.projection.appendBorrowed(empty.sql,
                empty.session, List.of(empty.accepted(4)), after -> List.of()));
        assertEquals("execution_projection_history_incomplete", error.getMessage());
        var missing = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> missing.projection.appendBorrowed(missing.sql,
                missing.session, List.of(missing.accepted(4)), after -> List.of(missing.accepted(1), missing.accepted(3))));
        assertTrue(empty.outputs.isEmpty()); assertTrue(missing.outputs.isEmpty());
        assertEquals(0L, empty.borrowedSequence); assertEquals(0L, missing.borrowedSequence);
        verifyNoInteractions(empty.store, empty.sql, missing.store, missing.sql);
    }

    @Test void borrowedGapRejectsAnOversizedReaderPageAndCrossTenantHistory() {
        var oversized = new Fixture();
        var error = assertThrows(IllegalStateException.class, () -> oversized.projection.appendBorrowed(oversized.sql,
                oversized.session, List.of(oversized.accepted(102)),
                after -> java.util.Collections.nCopies(101, oversized.accepted(1))));
        assertEquals("execution_projection_page_limit_exceeded", error.getMessage());
        var foreign = new Fixture(); var saved = foreign.accepted(1);
        var otherTenant = new ExecutionStore.SavedEvent(new RunRef(new TenantId("8"), "other-run"),
                saved.sequence(), saved.revision(), saved.occurredAt(), saved.event());
        assertThrows(SecurityException.class, () -> foreign.projection.appendBorrowed(foreign.sql,
                foreign.session, List.of(foreign.accepted(2)), after -> List.of(otherTenant)));
        assertTrue(oversized.outputs.isEmpty()); assertTrue(foreign.outputs.isEmpty());
        assertEquals(0L, oversized.borrowedSequence); assertEquals(0L, foreign.borrowedSequence);
        verifyNoInteractions(oversized.store, oversized.sql, foreign.store, foreign.sql);
    }

    @Test void outputsCommittedOnlyNotifiesMailboxAndLeavesReconciliationToItsExistingConsumers() {
        var fixture = new Fixture();
        fixture.projection.outputsCommitted(fixture.session);
        verify(fixture.mailbox).outputsCommitted("7", "session-1");
        verifyNoMoreInteractions(fixture.mailbox);
        verifyNoInteractions(fixture.store, fixture.sql);
    }

    @Test void borrowedConversionFailureDoesNotAdvanceCheckpointOrNotifyAndLeavesRollbackToItsOwner() {
        var fixture = new Fixture(); var original = fixture.accepted(1);
        var unsupported = new ExecutionStore.SavedEvent(original.run(), original.sequence(), original.revision(),
                original.occurredAt(), new ExecutionStore.Event(original.event().id(), "unsupported-product-event", original.event().data()));
        var error = assertThrows(IllegalStateException.class, () -> fixture.projection.appendBorrowed(fixture.sql,
                fixture.session, List.of(unsupported), after -> { throw new AssertionError("连续新事件不应回读日志"); }));
        assertEquals("execution_public_event_unsupported", error.getMessage());
        assertTrue(fixture.outputs.isEmpty());
        assertEquals(0L, fixture.borrowedSequence);
        verify(fixture.mailbox).projectOutputsBorrowed(same(fixture.sql), eq("7"), eq("session-1"), any(), any());
        verifyNoMoreInteractions(fixture.mailbox);
        verifyNoInteractions(fixture.store, fixture.sql);
    }

    @Test void frozenMessageReferenceRequiresActualHistoricalIdVersionAndRunWithoutOutboxWrites() throws Exception {
        var fixture=new Fixture();var body=fixture.base();body.put("text","原已公开回答");body.put("status","COMPLETED");body.put("reason","respond");
        fixture.events(AgentExecutionProgram.COMPLETED_EVENT,AgentExecutionProgram.COMPLETED_SCHEMA,body);
        var owner=com.skillforge.runtime.api.port.SessionOwner.user(fixture.session,new com.skillforge.runtime.api.AgentId("agent-1"),new com.skillforge.runtime.api.EndUserId(9));
        var reference=new com.skillforge.core.chat.contract.ChatInputReference.Message(new com.skillforge.core.chat.contract.ChatInputReference.SessionSource(),"event-1",1);
        assertTrue(fixture.projection.publicReferenceExists(owner,new RunRef(fixture.session.tenantId(),"run-1"),reference));
        assertFalse(fixture.projection.publicReferenceExists(owner,new RunRef(fixture.session.tenantId(),"other-run"),reference));
        assertFalse(fixture.projection.publicReferenceExists(owner,null,new com.skillforge.core.chat.contract.ChatInputReference.Message(reference.source(),"event-1",2)));
        verifyNoInteractions(fixture.mailbox);
    }

    @Test void registeredModelStartedSupportsSourceAndExactProcessReferencesWithoutOutboxWrites() throws Exception {
        var fixture=new Fixture();var body=fixture.base();body.put("actionId","model-action");body.put("status","DISPATCHED");body.put("summary","模型调用已登记");
        fixture.events(AgentExecutionProgram.MODEL_STARTED_EVENT,AgentExecutionProgram.MODEL_STARTED_SCHEMA,body);
        var owner=com.skillforge.runtime.api.port.SessionOwner.user(fixture.session,new com.skillforge.runtime.api.AgentId("agent-1"),new com.skillforge.runtime.api.EndUserId(9));
        var source=new com.skillforge.core.chat.contract.ChatInputReference.SessionSource();
        var reference=new com.skillforge.core.chat.contract.ChatInputReference.Process(source,"process:run-1:model:model-action",3);
        var run=new RunRef(fixture.session.tenantId(),"run-1");
        assertTrue(fixture.projection.publicReferenceExists(owner,run,reference));
        assertFalse(fixture.projection.publicReferenceExists(owner,new RunRef(run.tenantId(),"other-run"),reference));
        assertFalse(fixture.projection.publicReferenceExists(owner,run,new com.skillforge.core.chat.contract.ChatInputReference.Process(source,reference.processId(),4)));
        var process=fixture.projection.readSource(owner,run).processes().getFirst();
        assertEquals(reference.processId(),process.processId());assertEquals(3L,process.updatedSequence());assertEquals("running",process.status());
        assertEquals("模型调用已登记",process.presentation().summary());
        verifyNoInteractions(fixture.mailbox);
    }

    @Test void frozenProcessReferenceRequiresExactSourceSequenceWithoutLatestReplacement() throws Exception {
        var fixture=new Fixture();var body=fixture.base();body.put("actionId","model-action");body.put("status","SUCCEEDED");body.put("summary","实际模型结果");
        fixture.events(AgentExecutionProgram.MODEL_RESULT_EVENT,AgentExecutionProgram.MODEL_RESULT_SCHEMA,body);
        var owner=com.skillforge.runtime.api.port.SessionOwner.user(fixture.session,new com.skillforge.runtime.api.AgentId("agent-1"),new com.skillforge.runtime.api.EndUserId(9));
        var source=new com.skillforge.core.chat.contract.ChatInputReference.SessionSource();
        assertTrue(fixture.projection.publicReferenceExists(owner,null,new com.skillforge.core.chat.contract.ChatInputReference.Process(source,"process:run-1:model:model-action",3)));
        assertFalse(fixture.projection.publicReferenceExists(owner,null,new com.skillforge.core.chat.contract.ChatInputReference.Process(source,"process:run-1:model:model-action",4)));
        var process=fixture.projection.readSource(owner,new RunRef(fixture.session.tenantId(),"run-1")).processes().getFirst();
        assertEquals("process:run-1:model:model-action",process.processId());
        assertEquals("input-1",process.inputId());assertEquals("root-1",process.rootInputId());
        assertEquals("run-1",process.runId());assertEquals("run-1",process.rootRunId());
        assertEquals("model-action",process.referenceId());assertEquals("reasoning",process.type());
        assertEquals(3L,process.sequence());assertEquals(3L,process.updatedSequence());assertEquals("succeeded",process.status());
        fixture.events(AgentExecutionProgram.MODEL_RESULT_EVENT,AgentExecutionProgram.MODEL_RESULT_SCHEMA,body,4,"event-2");
        assertFalse(fixture.projection.publicReferenceExists(owner,null,new com.skillforge.core.chat.contract.ChatInputReference.Process(source,"process:run-1:model:model-action",3)));
        assertTrue(fixture.projection.publicReferenceExists(owner,null,new com.skillforge.core.chat.contract.ChatInputReference.Process(source,"process:run-1:model:model-action",4)));
        verifyNoInteractions(fixture.mailbox);
    }
    @Test void workflowHumanCardKeepsExactEngineAndParentWaitWhileExternalWorkflowAndPlanOnlyShowProcess() throws Exception {
        for (String kind : List.of("workflow","plan")) {
            var fixture=new Fixture();var body=fixture.base();body.put("actionId","source-action");body.put("generation","3");body.put("kind",kind);body.put("requests",Map.of());body.put("question",null);
            fixture.events(AgentExecutionProgram.WAITING_EVENT,AgentExecutionProgram.WAITING_SCHEMA,body);fixture.projection.reconcile(fixture.session);
            assertEquals(List.of("execution.waiting","process.delta"),fixture.types());
        }
        var fixture=new Fixture();var body=fixture.base();body.put("actionId","source-action");body.put("generation","3");body.put("kind","workflow");body.put("requests",Map.of());body.put("question",null);
        body.put("workflow",Map.of("executionId","actual-engine","suspendRef","actual-suspend","actionKey","actual-engine-action","executionRevision","9007199254740993",
                "card",Map.of("schemaId","application/json","canonicalJson","{\"type\":\"confirm\",\"title\":\"确认实际结果\",\"actions\":[{\"id\":\"confirm\",\"label\":\"确认\"}]}")));
        fixture.events(AgentExecutionProgram.WAITING_EVENT,AgentExecutionProgram.WAITING_SCHEMA,body);fixture.projection.reconcile(fixture.session);
        assertEquals(List.of("execution.waiting","process.delta","message.card","hitl.required"),fixture.types());
        var required=fixture.data("hitl.required");assertEquals("workflow_input",required.get("interaction_type"));assertEquals("actual-engine",required.get("workflow_execution_id"));
        assertEquals("9007199254740993",required.get("execution_revision"));assertEquals("actual-engine-action",required.get("resume_action_key"));
        assertEquals(Map.of("action_id","source-action","generation","3"),required.get("tool_wait"));assertEquals("7",required.get("run_revision"));
        assertFalse(fixture.json.writeValueAsString(fixture.outputs).contains("checkpoint_revision"));
    }
    @Test void delegationDependencyWaitShowsProcessAndClosesOnProvenResultWithoutUserInputCard() throws Exception {
        var waiting = new Fixture(); var body = waiting.base();
        body.put("actionId", "delegation-action"); body.put("generation", "1"); body.put("kind", "delegation");
        body.put("requests", Map.of()); body.put("question", null);
        waiting.events(AgentExecutionProgram.WAITING_EVENT, AgentExecutionProgram.WAITING_SCHEMA, body);
        waiting.projection.reconcile(waiting.session);
        assertEquals(List.of("execution.waiting", "process.delta"), waiting.types());
        assertEquals("waiting", waiting.data("process.delta").get("status"));
        assertFalse(waiting.types().contains("hitl.required"));
        var resolved = new Fixture(); var ready = resolved.base();
        ready.put("actionId", "delegation-action"); ready.put("generation", "1"); ready.put("reason", "response_accepted"); ready.put("dependencyKind", "delegation");
        resolved.events(AgentExecutionProgram.RESOLVED_EVENT, AgentExecutionProgram.RESOLVED_SCHEMA, ready);
        resolved.projection.reconcile(resolved.session);
        assertEquals(List.of("interaction.resolved", "process.finished"), resolved.types());
        assertEquals(waiting.data("process.delta").get("process_id"),resolved.data("process.finished").get("process_id"));
        assertEquals("succeeded",resolved.data("process.finished").get("status"));
    }
    @Test void registeredModelPublishesStartedWithoutPretendingFirstTokenAndActualResultClosesSameProcess() throws Exception {
        var first = new Fixture(); var body = first.base();
        body.put("actionId", "model-action"); body.put("status", "DISPATCHED"); body.put("summary", "模型调用已登记");
        body.put("privateReasoning", "must-not-be-public");
        first.events(AgentExecutionProgram.MODEL_STARTED_EVENT, AgentExecutionProgram.MODEL_STARTED_SCHEMA, body);
        first.projection.reconcile(first.session);
        assertEquals(List.of("process.started"), first.types());
        assertEquals("running", first.data("process.started").get("status"));
        assertEquals("reasoning", first.data("process.started").get("type"));
        assertEquals("模型调用已登记", ((Map<?,?>)first.data("process.started").get("presentation")).get("summary"));
        assertFalse(first.json.writeValueAsString(first.outputs).contains("must-not-be-public"));
        for(var terminal:Map.of("SUCCEEDED","succeeded","FAILED","failed","UNKNOWN","outcome_unknown").entrySet()) {
            var last = new Fixture(); var completed = last.base();
            completed.put("actionId", "model-action"); completed.put("status", terminal.getKey()); completed.put("summary", "真实模型结果状态");
            last.events(AgentExecutionProgram.MODEL_RESULT_EVENT, AgentExecutionProgram.MODEL_RESULT_SCHEMA, completed);
            last.projection.reconcile(last.session);
            assertEquals(terminal.getValue(), last.data("process.finished").get("status"));
            assertEquals(first.data("process.started").get("process_id"), last.data("process.finished").get("process_id"));
        }
        var invalid = new Fixture(); var forged = invalid.base(); forged.put("actionId","model-action"); forged.put("status","SUCCEEDED");
        invalid.events(AgentExecutionProgram.MODEL_STARTED_EVENT,AgentExecutionProgram.MODEL_STARTED_SCHEMA,forged);
        assertThrows(IllegalArgumentException.class,()->invalid.projection.reconcile(invalid.session));
    }

    @Test void actualCitationsExposeOnlyMinimalPublicIdentityAndNeverPrivateMaterialSelectors() throws Exception {
        var fixture = new Fixture(); var body = fixture.base();
        body.put("text", "已根据实际知识依据回答"); body.put("status", "COMPLETED"); body.put("reason", "respond");
        body.put("evidence", List.of(Map.of("itemId", "item-27", "sourceId", "source-1", "sourceVersion", "7", "citationId", "k0123456789abcdef",
                "bundleId", "private-bundle", "resultRef", "private-result-material", "provenanceRefs", List.of("private-provenance"), "graphPath", List.of("private-graph"))));
        fixture.events(AgentExecutionProgram.COMPLETED_EVENT, AgentExecutionProgram.COMPLETED_SCHEMA, body);
        fixture.projection.reconcile(fixture.session);
        var citations = fixture.json.valueToTree(fixture.data("message.delta").get("citations"));
        assertEquals("item-27", citations.get(0).path("item_id").textValue());
        assertEquals("知识依据 1", citations.get(0).path("label").textValue());
        assertEquals("k0123456789abcdef", citations.get(0).path("citation_id").textValue());
        assertFalse(fixture.json.writeValueAsString(fixture.outputs).contains("private-"));
        assertEquals(List.of(), fixture.data("message.delta").get("references"));
    }

    @Test void humanWaitUsesTheSameExactWaitIdentityAndPublishesTheActualQuestion() throws Exception {
        var fixture = new Fixture(); var body = fixture.base();
        body.put("actionId", "human-action"); body.put("generation", "2"); body.put("kind", "human");
        body.put("question", "采用哪一种统计口径？"); body.put("requests", Map.of());
        fixture.events(AgentExecutionProgram.WAITING_EVENT, AgentExecutionProgram.WAITING_SCHEMA, body);
        fixture.projection.reconcile(fixture.session);
        assertEquals(List.of("message.card", "hitl.required"), fixture.types());
        assertEquals("user_input", fixture.data("hitl.required").get("interaction_type"));
        assertEquals("采用哪一种统计口径？", fixture.data("hitl.required").get("question"));
        assertEquals(Map.of("action_id", "human-action", "generation", "2"), fixture.data("hitl.required").get("tool_wait"));
    }

    @Test void acceptedOutputFlagDeterminesProcessFailureWithoutChangingTheActualSucceededResult() throws Exception {
        var fixture = new Fixture(); var body = fixture.base();
        body.put("actionId", "action-1"); body.put("toolId", "template"); body.put("callId", "call-1");
        body.put("status", "SUCCEEDED"); body.put("summary", "工具调用已完成，输出无法采纳"); body.put("outputAccepted", false);
        fixture.events(AgentExecutionProgram.TOOL_RESULT_EVENT, AgentExecutionProgram.TOOL_RESULT_SCHEMA, body);
        fixture.projection.reconcile(fixture.session);
        assertEquals(List.of("process.finished"), fixture.types());
        assertEquals("failed", fixture.data("process.finished").get("status"));
        assertEquals("SUCCEEDED", body.get("status"));
    }

    @Test void businessFailureIsVisibleWithoutReclassifyingTheActualToolOutcome() throws Exception {
        var fixture = new Fixture(); var body = fixture.base();
        body.put("actionId", "action-1"); body.put("toolId", "clock"); body.put("callId", "call-1");
        body.put("status", "SUCCEEDED"); body.put("summary", "工具调用失败"); body.put("outputAccepted", true);
        body.put("businessFailed", true);
        fixture.events(AgentExecutionProgram.TOOL_RESULT_EVENT, AgentExecutionProgram.TOOL_RESULT_SCHEMA, body);
        fixture.projection.reconcile(fixture.session);
        assertEquals("failed", fixture.data("process.finished").get("status"));
        assertEquals("SUCCEEDED", body.get("status"));
    }

    @Test void unsafeHumanQuestionBecomesOnlyASafePresentationRejection() throws Exception {
        var fixture = new Fixture(); var body = fixture.base();
        body.put("actionId", "human-action"); body.put("generation", "2"); body.put("kind", "human");
        body.put("question", fixture.privatePath); body.put("requests", Map.of());
        var candidate = new ExecutionStore.Event(new EventId("wait-1"), AgentExecutionProgram.WAITING_EVENT,
                new TypedPayload(AgentExecutionProgram.WAITING_SCHEMA, fixture.json.writeValueAsString(body)));
        var protectedEvent = AgentRuntimeExecutionPublicEvents.protectWaiting(candidate, new PublicOutputGuard(fixture.privatePath), fixture.json);
        assertEquals(AgentRuntimeExecutionPublicEvents.REJECTED_EVENT, protectedEvent.type());
        assertFalse(protectedEvent.data().canonicalJson().contains("question"));
        assertFalse(protectedEvent.data().canonicalJson().contains("requests"));
        assertEquals(fixture.privatePath, body.get("question"));
    }

    @Test void toolWaitPublishesCurrentIdentityWithoutOpaqueContinuationAndNeverReadsRun() throws Exception {
        var fixture = new Fixture();
        var body = fixture.base();
        body.put("actionId", "action-1"); body.put("generation", "9007199254740993"); body.put("kind", "tool");
        body.put("requests", Map.of("quantity", Map.of("method", "elicitation/create", "params", Map.of(
                "schemaId", "skillforge.runtime.tool.mcp-input-request.v1", "canonicalJson",
                "{\"message\":\"请输入数量\",\"requestedSchema\":{\"type\":\"object\",\"properties\":{\"quantity\":{\"type\":\"integer\"}}}}"))));
        body.put("requestState", "private-opaque-state");
        fixture.events(AgentExecutionProgram.WAITING_EVENT, AgentExecutionProgram.WAITING_SCHEMA, body);

        fixture.projection.committed(fixture.session);

        assertEquals(List.of("message.card", "hitl.required", "process.delta"), fixture.types());
        var pending = fixture.data("hitl.required");
        assertEquals("7", pending.get("run_revision"));
        assertEquals(Map.of("action_id", "action-1", "generation", "9007199254740993"), pending.get("tool_wait"));
        assertFalse(fixture.json.writeValueAsString(fixture.outputs).contains("private-opaque-state"));
        assertFalse(pending.containsKey("checkpoint_revision"));
        assertEquals("waiting", fixture.data("process.delta").get("status"));
        verify(fixture.store).events(fixture.session, 0, 100);
        verifyNoMoreInteractions(fixture.store);
    }

    @Test void browserWaitUsesActualConnectionRequestAndNoGenericFormOrOpaqueState() throws Exception {
        var fixture = new Fixture();
        var body = fixture.base();
        body.put("actionId", "action-browser"); body.put("generation", "1"); body.put("kind", "tool");
        body.put("requests", Map.of("browser-connection", Map.of("method", "elicitation/create", "params", Map.of(
                "schemaId", "skillforge.browser.connection-request.v1", "canonicalJson",
                "{\"connection_request_id\":\"connection-1\",\"reason\":\"OFFLINE\",\"message\":\"请连接浏览器\"}"))));
        fixture.events(AgentExecutionProgram.WAITING_EVENT, AgentExecutionProgram.WAITING_SCHEMA, body);
        fixture.projection.reconcile(fixture.session);
        assertEquals(Map.of("type", "browser_connection", "title", "连接浏览器以继续任务",
                "connection_request_id", "connection-1", "reason", "OFFLINE"), fixture.data("message.card").get("card"));
        assertEquals("tool_input", fixture.data("hitl.required").get("interaction_type"));
    }

    @Test void currentPublicPolicyRejectsUnsafeFormWithoutPretendingTheRunFailed() throws Exception {
        var fixture = new Fixture();
        var body = fixture.base();
        body.put("actionId", "action-1"); body.put("generation", "1"); body.put("kind", "tool");
        body.put("requests", Map.of("quantity", Map.of("method", "elicitation/create", "params", Map.of(
                "schemaId", "skillforge.runtime.tool.mcp-input-request.v1", "canonicalJson",
                fixture.json.writeValueAsString(Map.of("message", fixture.privatePath, "requestedSchema", Map.of("type", "object")))))));
        fixture.events(AgentExecutionProgram.WAITING_EVENT, AgentExecutionProgram.WAITING_SCHEMA, body);
        fixture.projection.reconcile(fixture.session);
        assertEquals(List.of("interaction.rejected"), fixture.types());
        assertEquals(PublicOutputGuard.REJECTION_CODE, fixture.data("interaction.rejected").get("reason"));
        assertFalse(fixture.json.writeValueAsString(fixture.outputs).contains(fixture.privatePath));
    }

    @Test void finalAnswerAndCompletionComeFromTheSameCommittedFact() throws Exception {
        var fixture = new Fixture();
        var body = fixture.base(); body.put("text", "模板中的真实结果"); body.put("status", "COMPLETED"); body.put("reason", "respond");
        fixture.events(AgentExecutionProgram.COMPLETED_EVENT, AgentExecutionProgram.COMPLETED_SCHEMA, body);
        fixture.projection.reconcile(fixture.session);
        assertEquals(List.of("message.delta", "execution.completed"), fixture.types());
        assertEquals("模板中的真实结果", fixture.data("message.delta").get("content"));
        assertEquals("agent",((Map<?,?>)fixture.data("message.delta").get("author")).get("kind"));
        assertEquals(fixture.base().get("agentId"),((Map<?,?>)fixture.data("message.delta").get("author")).get("agent_id"));
        assertEquals("run-1",((Map<?,?>)fixture.data("message.delta").get("author")).get("run_id"));
        assertEquals("final", fixture.data("message.delta").get("phase"));
        assertEquals("completed", fixture.data("message.delta").get("state"));
        assertEquals("completed", fixture.data("execution.completed").get("status"));
        assertEquals("assistant-preview:run-1", fixture.data("message.delta").get("preview_message_id"));
        assertEquals(fixture.data("message.delta").get("preview_message_id"), fixture.data("execution.completed").get("preview_message_id"));
        assertEquals("run-1", fixture.outputs.getFirst().runtimeRunId());
    }

    @Test void terminalPhaseNeverPromotesFailureOrRejectedOutputToCompleted() throws Exception {
        for (String status : List.of("FAILED", "CANCELLED", "COMPLETED")) {
            var fixture = new Fixture(); var body = fixture.base();
            body.put("text", fixture.privatePath); body.put("status", status); body.put("reason", "terminal");
            fixture.events(AgentExecutionProgram.COMPLETED_EVENT, AgentExecutionProgram.COMPLETED_SCHEMA, body);
            fixture.projection.reconcile(fixture.session);
            assertEquals("final", fixture.data("message.delta").get("phase"));
            assertEquals("failed", fixture.data("message.delta").get("state"));
            assertEquals(PublicOutputGuard.SAFE_MESSAGE, fixture.data("message.delta").get("content"));
            assertEquals("failed", fixture.data("execution.completed").get("status"));
        }
    }

    /** 持久事件页与锁后游标使用同一已保存身份；收集的命令仅用于验证公开边界。 */
    @Test void safePublicEventKeepsTheExactFrozenDataShapeAndStableIdentity() {
        var fixture=new Fixture();var outputBuilder = SessionOutboxEntry.builder();
        outputBuilder.tenantId(7L);outputBuilder.sessionId("session-1");outputBuilder.eventId("output:source");outputBuilder.sessionSequence(1L);outputBuilder.createdAt(Instant.parse("2026-10-22T01:00:00Z"));
        var data=Map.<String,Object>of("message_id","source","message_version",1,"role","user","content","原始公开消息","metadata",Map.of("author",Map.of("kind","end_user","end_user_id","9")));
        outputBuilder.payload(Map.of("event","message.delta","data",data));
        var output = outputBuilder.build();
        var event=fixture.projection.publicEvent(output);
        // 业务载荷保持原形；正式传输投影额外补入持久行的可信envelope。
        var expected=new LinkedHashMap<String,Object>(data);
        expected.put("session_id","session-1");expected.put("event_id","output:source");
        expected.put("session_sequence",1L);expected.put("event_version",1);
        expected.put("occurred_at","2026-10-22T09:00:00+08:00");expected.put("durability","persistent");
        assertEquals(expected,event.data());assertEquals(data,output.getPayload().get("data"));
        assertEquals("message.delta",event.event());assertEquals("session-1",event.sessionId());
        assertEquals("output:source",event.eventId());assertEquals(1L,event.sessionSequence());
        assertEquals(1,event.eventVersion());assertEquals(Instant.parse("2026-10-22T01:00:00Z"),event.occurredAt());
        assertNull(event.inputId());assertNull(event.rootInputId());assertNull(event.causedByEventId());
    }

    @Test void redactedPublicAssistantEventKeepsTransportEnvelope() {
        var fixture = new Fixture();
        var builder = SessionOutboxEntry.builder();
        builder.tenantId(7L); builder.sessionId("session-1"); builder.eventId("output:source");
        builder.sessionSequence(1L); builder.inputId("input-1"); builder.rootInputId("root-1");
        builder.causedByEventId("cause-1"); builder.traceId("trace-1");
        builder.createdAt(Instant.parse("2026-10-22T01:00:00Z"));
        builder.payload(Map.of("event", "message.delta", "data", Map.of("message_id", "message-1",
                "role", "assistant", "content", fixture.privatePath)));
        var event = fixture.projection.publicEvent(builder.build());
        assertEquals(PublicOutputGuard.SAFE_MESSAGE, event.data().get("content"));
        assertEquals("output:source", event.data().get("event_id"));
        assertEquals("session-1", event.data().get("session_id"));
        assertEquals(1L, event.data().get("session_sequence"));
        assertEquals("input-1", event.data().get("input_id"));
        assertEquals("root-1", event.data().get("root_input_id"));
        assertEquals("cause-1", event.data().get("caused_by_event_id"));
        assertEquals("trace-1", event.data().get("trace_id"));
        assertEquals(1, event.data().get("event_version"));
        assertEquals("2026-10-22T09:00:00+08:00", event.data().get("occurred_at"));
        assertEquals("persistent", event.data().get("durability"));
    }

    private static final class Fixture {
        private final ObjectMapper json = new ObjectMapper();
        private final ExecutionStore store = mock(ExecutionStore.class);
        private final SessionMailboxService mailbox = mock(SessionMailboxService.class);
        private final SqlSession sql = mock(SqlSession.class);
        private final SessionRef session = new SessionRef(new TenantId("7"), "session-1");
        private final String privatePath = java.nio.file.Path.of("projection-private-artifacts").toAbsolutePath().normalize().toString();
        private final List<SessionOutboxOutput> outputs = new ArrayList<>();
        /** 模拟同一事务内的持锁水位；不同公开政策会由 Mailbox 从空水位重新投影。 */
        private long borrowedSequence;
        /** 与持锁来源水位完全对应的事件身份，零水位使用空字符串。 */
        private String borrowedEventId = "";
        /** 上一次持锁投影的公开政策；null 使用本次真实来源的政策。 */
        private String borrowedPolicy;
        /** 历史页与当前批实际完成投影的来源顺序，不代表数据库已经提交。 */
        private final List<SessionOutboxProjectionSource> borrowedSources = new ArrayList<>();
        private final AgentRuntimeExecutionProjection projection = new AgentRuntimeExecutionProjection(store, mailbox,
                new PublicOutputGuard(privatePath), json, 100_000, 10_000, 32);

        /** 每批回调以实际Policy创建空游标，测试不猜测投影内部Policy版本。 */
        private Fixture() {
            when(mailbox.projectionCheckpoint(eq("7"), eq("session-1"), anyString())).thenAnswer(call ->
                    SessionOutboxProjectionCheckpoint.empty(call.getArgument(2)));
            when(mailbox.projectOutputsIncrementally(eq("7"), eq("session-1"), any(), any())).thenAnswer(call -> {
                var source = call.<com.skillforge.core.chat.session.SessionOutboxProjectionSource>getArgument(2);
                var project = call.<Function<SessionOutboxProjectionCheckpoint, SessionOutboxProjectionBatch>>getArgument(3);
                outputs.addAll(project.apply(SessionOutboxProjectionCheckpoint.empty(source.policyVersion())).outputs());
                return List.of();
            });
            when(mailbox.projectOutputsBorrowed(same(sql), eq("7"), eq("session-1"), any(), any())).thenAnswer(call -> {
                var source = call.<SessionOutboxProjectionSource>getArgument(3);
                var checkpoint = borrowedPolicy == null || borrowedPolicy.equals(source.policyVersion())
                        ? new SessionOutboxProjectionCheckpoint(borrowedSequence, borrowedEventId, source.policyVersion(), "{}")
                        : SessionOutboxProjectionCheckpoint.empty(source.policyVersion());
                if (checkpoint.covers(source)) return List.of();
                var project = call.<Function<SessionOutboxProjectionCheckpoint, SessionOutboxProjectionBatch>>getArgument(4);
                var batch = project.apply(checkpoint);
                outputs.addAll(batch.outputs());
                borrowedSequence = source.sequence(); borrowedEventId = source.eventId(); borrowedPolicy = source.policyVersion();
                borrowedSources.add(source);
                return List.of();
            });
        }

        /** 连续事件夹具保留真实源序号与稳定身份，用于同事务批次及历史页补投。 */
        private ExecutionStore.SavedEvent accepted(long sequence) {
            var event = new ExecutionStore.Event(new EventId("event-" + sequence), AgentExecutionProgram.ACCEPTED_EVENT,
                    new TypedPayload(AgentExecutionProgram.ACCEPTED_SCHEMA, json.valueToTree(base()).toString()));
            return new ExecutionStore.SavedEvent(new RunRef(session.tenantId(), "run-1"), sequence, 7,
                    Instant.parse("2026-10-22T01:02:03Z"), event);
        }

        /** 这些因果字段来自执行事实，投影不回查身份或整 Run。 */
        private Map<String, Object> base() {
            return new LinkedHashMap<>(Map.of("inputId", "input-1", "rootInputId", "root-1", "traceId", "trace-1",
                    "spanId", "span-1", "agentId", "agent-1"));
        }

        /** 给定明确产品事实类型，不从私有材料拼装测试期望。 */
        private void events(String type, String schema, Map<String, Object> body) throws Exception {
            events(type,schema,body,3,"event-1");
        }

        /** 指定真实源序号，后来的同过程事件不能替代被冻结的旧选择器。 */
        private void events(String type, String schema, Map<String, Object> body, long sourceSequence, String sourceEventId) throws Exception {
            var event = new ExecutionStore.Event(new EventId(sourceEventId), type, new TypedPayload(schema, json.writeValueAsString(body)));
            var saved = new ExecutionStore.SavedEvent(new RunRef(session.tenantId(), "run-1"), sourceSequence, 7,
                    Instant.parse("2026-10-22T01:02:03Z"), event);
            when(store.events(session, 0, 100)).thenReturn(List.of(saved));
        }

        /** 命令类型体现当前用户可观察事件顺序。 */
        private List<String> types() { return outputs.stream().map(SessionOutboxOutput::eventType).toList(); }

        /** 仅读取公开Outbox信封，不获取私有执行结果。 */
        @SuppressWarnings("unchecked") private Map<String, Object> data(String eventType) {
            return (Map<String, Object>) outputs.stream().filter(output -> output.eventType().equals(eventType))
                    .findFirst().orElseThrow().payload().get("data");
        }
    }
}
