package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolInteractionResult;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CommittedSessionEvent;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.PortConflict;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.session.SessionCollaboration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PlanTodoToolHandlersTest {
    private static final TenantId TENANT = new TenantId("tenant");
    private static final InputId INPUT = new InputId("input");
    private static final SessionOwner OWNER = SessionOwner.user(new SessionRef(TENANT, "session"),
            new AgentId("agent"), new EndUserId(7));
    private static final RunIdentity RUN = new RunIdentity(OWNER, new AgentId((OWNER).target().id()), INPUT, new RunRef(TENANT, "run"), new TraceEnvelope("trace", "span", INPUT, Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((OWNER).requireUser()), java.util.Optional.empty(), new RunOriginRef((OWNER).sessionRef().tenantId(), "input:" + (INPUT).value()));
    private static final ObjectMapper JSON = new ObjectMapper();

    private static PublicOutputGuard publicGuard() {
        var guard=mock(PublicOutputGuard.class);
        when(guard.capture(any(RunIdentity.class))).thenReturn(new PublicOutputGuard.Scope(List.of()));return guard;
    }

    @Test
    void exitPlanWaitsForReviewAndOnlyApprovalSelectsInactive() throws Exception {
        var fixture = new LogFixture(true);
        var handler = new PlanModeExitToolHandler(fixture.log, publicGuard());
        var context = context("exit-plan");
        var arguments = Map.<String, Object>of("plan", "# 发布计划\n\n1. 验证\n2. 发布");

        var waiting = assertInstanceOf(PlatformToolInteractionResult.InputRequired.class,
                handler.invoke(arguments, context));
        assertEquals(Set.of(PlanModeExitToolHandler.REQUEST_ID), waiting.inputRequests().keySet());
        var request = waiting.inputRequests().get(PlanModeExitToolHandler.REQUEST_ID);
        assertEquals(ToolInputMethod.ELICITATION_CREATE, request.method());
        assertEquals(PlanModeExitToolHandler.REQUEST_SCHEMA, request.params().schemaId());
        var payloads = new com.skillforge.runtime.session.SessionRuntimePayloads();
        var limits = new com.skillforge.runtime.json.CodecLimits(100_000, 10_000, 32);
        assertDoesNotThrow(() -> payloads.validate(request.params(), limits));
        var requestJson = JSON.readTree(request.params().canonicalJson());
        assertEquals("发布计划", requestJson.path("title").textValue());
        assertEquals(List.of("approve", "keep_planning"),
                JSON.convertValue(requestJson.path("options"), List.class));

        var keep = response("keep_planning", "增加回滚步骤");
        keep.values().forEach(value -> payloads.validate(value.result(), limits));
        var rejected = assertInstanceOf(PlatformToolInteractionResult.Rejected.class,
                handler.resume(arguments, keep, waiting.requestState(), context));
        assertEquals("plan_review_keep_planning", rejected.output().get("error"));
        assertEquals("增加回滚步骤", rejected.output().get("feedback"));
        assertTrue(new SessionCollaboration(fixture.log).read(OWNER).active());
        assertFalse(new SessionCollaboration(fixture.log).read(OWNER).pending());

        var approved = assertInstanceOf(PlatformToolInteractionResult.Completed.class,
                handler.resume(arguments, response("approve", ""), waiting.requestState(), context));
        assertEquals(Map.of("success", true, "approved", true), approved.output());
        var state = new SessionCollaboration(fixture.log).read(OWNER);
        assertTrue(state.active());
        assertTrue(state.pending());
        assertFalse(state.selectedActive());
    }

    @Test
    void exitPlanRejectsInactiveMalformedAndForgedReviewWithoutWriting() throws Exception {
        var inactive = new LogFixture(false);
        var handler = new PlanModeExitToolHandler(inactive.log, publicGuard());
        var rejected = assertInstanceOf(PlatformToolInteractionResult.Rejected.class,
                handler.invoke(Map.of("plan", "# Plan\nstep"), context("inactive")));
        assertEquals("plan_mode_inactive", rejected.output().get("error"));

        var active = new LogFixture(true);
        handler = new PlanModeExitToolHandler(active.log, publicGuard());
        assertEquals("invalid_plan", assertInstanceOf(PlatformToolInteractionResult.Rejected.class,
                handler.invoke(Map.of("plan", "no heading"), context("invalid"))).output().get("error"));
        var waiting = assertInstanceOf(PlatformToolInteractionResult.InputRequired.class,
                handler.invoke(Map.of("plan", "# Plan\nstep"), context("forged")));
        var forged = assertInstanceOf(PlatformToolInteractionResult.Rejected.class,
                handler.resume(Map.of("plan", "# Changed\nstep"), response("approve", ""),
                        waiting.requestState(), context("forged")));
        assertEquals("plan_review_state_invalid", forged.output().get("error"));
        assertEquals(1, active.events.size());
    }

    @Test
    void exitPlanRequiresFrozenDecimalCheckpointInV2Callback() throws Exception {
        var fixture = new LogFixture(true);
        var handler = new PlanModeExitToolHandler(fixture.log, publicGuard());
        var arguments = Map.<String,Object>of("plan", "# 验收计划\n1. 核验");
        var waiting = assertInstanceOf(PlatformToolInteractionResult.InputRequired.class,
                handler.invoke(arguments, context("callback-shape")));
        var malformed = new ArrayList<TypedPayload>();
        malformed.add(new TypedPayload(PlanModeExitToolHandler.RESPONSE_SCHEMA,
                "{\"decision\":\"approve\",\"feedback\":\"\"}"));
        malformed.add(new TypedPayload("skillforge.runtime.plan-review-response.v1",
                "{\"decision\":\"approve\",\"feedback\":\"\"}"));
        malformed.add(new TypedPayload(PlanModeExitToolHandler.RESPONSE_SCHEMA,
                "{\"checkpointRevision\":\"7\",\"decision\":\"approve\",\"feedback\":\"\",\"extra\":true}"));
        for (String revision : List.of("7", "7.0", "null", "\"0\"", "\"07\"", "\"9223372036854775808\""))
            malformed.add(new TypedPayload(PlanModeExitToolHandler.RESPONSE_SCHEMA,
                    "{\"checkpointRevision\":"+revision+",\"decision\":\"approve\",\"feedback\":\"\"}"));
        for (var payload : malformed) {
            var responses = Map.of(PlanModeExitToolHandler.REQUEST_ID,
                    new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE,payload));
            var rejected = assertInstanceOf(PlatformToolInteractionResult.Rejected.class,
                    handler.resume(arguments,responses,waiting.requestState(),context("callback-shape")));
            assertEquals("plan_review_response_invalid",rejected.output().get("error"));
        }
        assertFalse(new SessionCollaboration(fixture.log).read(OWNER).pending());
        assertEquals(1,fixture.events.size());
    }

    private static Map<String, ToolInputResponse> response(String decision, String feedback) throws Exception {
        return Map.of(PlanModeExitToolHandler.REQUEST_ID, new ToolInputResponse(
                ToolInputMethod.ELICITATION_CREATE,
                new TypedPayload(PlanModeExitToolHandler.RESPONSE_SCHEMA,
                        JSON.writeValueAsString(Map.of("checkpointRevision", "7", "decision", decision, "feedback", feedback)))));
    }

    private static PlatformToolExecutionContext context(String invocationId) {
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(TENANT, OWNER.requireUser()),
                OWNER, Set.of("runtime:invoke"), 1, new ResultRef("verified"));
        return new PlatformToolExecutionContext(new PortCallContext(RUN, identity,
                new InvocationControl(Instant.now().plusSeconds(30), () -> false)),
                invocationId, "attempt-" + invocationId);
    }

    private static final class LogFixture {
        private final SessionLogPort log = mock(SessionLogPort.class);
        private final List<CommittedSessionEvent> events = new ArrayList<>();
        private final Map<EventId, CommittedSessionEvent> byId = new LinkedHashMap<>();

        private LogFixture(boolean active) {
            if (active) {
                var event = new CommittedSessionEvent(OWNER, INPUT, Optional.of(RUN.runRef()),
                        Optional.empty(), Optional.empty(), Optional.empty(), new EventId("plan-mode-seed"), 1,
                        SessionCollaboration.MODE, "1", RUN.trace(), new TypedPayload(
                                "skillforge.runtime.plan-mode.v1",
                                "{\"active\":true,\"sourceEventId\":\"seed\",\"sourceSequence\":\"1\"}"),
                        Instant.EPOCH);
                events.add(event);
                byId.put(event.eventId(), event);
            }
            when(log.readAfter(any(), org.mockito.ArgumentMatchers.<Set<String>>any())).thenAnswer(call -> {
                SessionLogPort.ReadSessionLog query = call.getArgument(0);
                @SuppressWarnings("unchecked") Set<String> types = call.getArgument(1);
                return events.stream()
                        .filter(event -> event.owner().equals(query.owner()))
                        .filter(event -> event.sessionSequence() > query.afterSequence())
                        .filter(event -> types.isEmpty() || types.contains(event.eventType()))
                        .limit(query.limit()).toList();
            });
            when(log.findByEvent(any(), any())).thenAnswer(call -> {
                CommittedSessionEvent event = byId.get((EventId) call.getArgument(1));
                return event != null && event.owner().equals(call.getArgument(0))
                        ? Optional.of(event) : Optional.empty();
            });
            when(log.append(any())).thenAnswer(call -> append(call.getArgument(0)));
        }

        private SessionLogPort.AppendResult append(SessionLogPort.AppendSessionEvent command) {
            CommittedSessionEvent prior = byId.get(command.event().eventId());
            if (prior != null) {
                boolean same = prior.owner().equals(command.target().owner())
                        && prior.inputId().equals(command.target().inputId())
                        && prior.runRef().equals(command.target().runRef())
                        && prior.eventType().equals(command.event().eventType())
                        && prior.eventVersion().equals(command.event().eventVersion())
                        && prior.trace().equals(command.event().trace())
                        && prior.payload().equals(command.event().payload())
                        && prior.occurredAt().equals(command.event().occurredAt());
                return same ? new SessionLogPort.AppendResult.Replayed(prior)
                        : new SessionLogPort.AppendResult.Conflict(new PortConflict(
                                "session_event", prior.eventId().value(), new ResultRef("conflict")));
            }
            var target = command.target();
            var draft = command.event();
            var event = new CommittedSessionEvent(target.owner(), target.inputId(), target.runRef(),
                    target.executionRef(), target.actionRef(), target.delegationRef(), draft.eventId(),
                    events.size() + 1L, draft.eventType(), draft.eventVersion(), draft.trace(),
                    draft.payload(), draft.occurredAt());
            events.add(event);
            byId.put(event.eventId(), event);
            return new SessionLogPort.AppendResult.Appended(event);
        }
    }
}
