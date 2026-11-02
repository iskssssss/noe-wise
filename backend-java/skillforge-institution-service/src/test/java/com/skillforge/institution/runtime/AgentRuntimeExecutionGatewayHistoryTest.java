package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.RequestContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.ChatRuntimeGateway;
import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.message.MemoryService;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class AgentRuntimeExecutionGatewayHistoryTest {
    @Test void completeHistoryRetainsMoreThanOneHundredMessagesAndDeduplicatesByIdentityOnly() {
        var f = new AgentRuntimeExecutionGatewayGoalTest.Fixture(false);
        var memory = (MemoryService) ReflectionTestUtils.getField(f.gateway, "memory");
        var projection = (AgentRuntimeExecutionProjection) ReflectionTestUtils.getField(f.gateway, "history");
        var time = Instant.parse("2026-10-28T00:00:00Z");
        var archived = new ArrayList<ChatMessageRecord>();
        var projected = new ArrayList<ChatMessageRecord>();
        for (int i = 0; i < 70; i++) {
            archived.add(new ChatMessageRecord("u" + i, "user", "确认", time.plusSeconds(i * 2), Map.of("input_id", "i" + i)));
            projected.add(new ChatMessageRecord("a" + i, "assistant", "已完成", time.plusSeconds(i * 2 + 1), Map.of("source_sequence", i + 1)));
        }
        projected.add(archived.get(0));
        archived.add(new ChatMessageRecord("public-current", "user", "当前请求", time.plusSeconds(200), Map.of("input_id", "private-current")));
        when(memory.listArchived("1", "session", 0)).thenReturn(archived);
        var history = mock(ChatHistoryResponse.class); when(history.messages()).thenReturn(projected);
        when(projection.read(f.session)).thenReturn(history);
        @SuppressWarnings("unchecked") var result = (List<ChatMessageRecord>) ReflectionTestUtils.invokeMethod(f.gateway, "history", f.session, "private-current");
        assertEquals(140, result.size());
        assertEquals(List.of("u0", "a0", "u1", "a1"), result.subList(0, 4).stream().map(ChatMessageRecord::messageId).toList());
        assertEquals(70, result.stream().filter(message -> message.role().equals("user")).count());
        assertEquals("a69", result.getLast().messageId());
        verify(memory).listArchived("1", "session", 0); verifyNoMoreInteractions(memory);
        verify(projection).read(f.session); verifyNoMoreInteractions(projection);
    }

    @Test void humanReplyUsesPublicClientIdentityInsteadOfPrivateInputIdentity() throws Exception {
        var f = new AgentRuntimeExecutionGatewayGoalTest.Fixture(true);
        var original = f.submission.request();
        var request = new ChatRequest(original.content(), original.sessionId(), original.agentId(), original.teamId(),
                original.workflowId(), original.scopeId(), original.scopeType(), original.locale(), "public-response",
                original.hitlResponse(), Map.of("input_id", "private-response"), original.references());
        TenantContext.runWithTenant("1", () -> f.gateway.submit(new ChatRuntimeGateway.Submission(request, f.session, "7", 9L, null)));
        var resume = ArgumentCaptor.forClass(ExecutionStore.Resume.class); verify(f.store).resume(resume.capture());
        var message = resume.getValue().events().stream().filter(event -> event.type().equals(AgentRuntimeExecutionResponses.USER_MESSAGE_EVENT)).findFirst().orElseThrow();
        var body = new ObjectMapper().readTree(message.data().canonicalJson());
        assertEquals("public-response", body.path("messageId").asText());
        assertEquals("private-response", body.path("inputId").asText());
    }

    @Test void publicUserEventKeepsOnlyFrozenAttachmentMetadataAndOrderedImageSelectors() throws Exception {
        var f = new AgentRuntimeExecutionGatewayGoalTest.Fixture(false);
        var identity = f.store.load(new RunRef(new TenantId("1"), "run:original")).orElseThrow().identity();
        var json = new ObjectMapper();
        var attachment = new com.skillforge.institution.attachment.SessionAttachmentService.FrozenMetadata(
                "selected-file", "资料.txt", "attachments/selected-file/input.txt", 37L, "synthetic-sha", "2026-11-01T00:00:00Z");
        var frozen = new TypedPayload("input.v1", json.writeValueAsString(Map.of("extraParameters", Map.of(
                "attachment_ids", List.of(attachment.id()), "attachments", List.of(attachment),
                "attachment_readings", List.of(Map.of("text", "PRIVATE_ATTACHMENT_BODY_MUST_NOT_BE_PUBLIC"))))));
        for (var parts : List.<List<com.skillforge.core.chat.contract.ChatContent>>of(List.of(), List.of(
                new com.skillforge.core.chat.contract.ChatContent.Image("b07853ea-a384-4eb6-a62f-a7aef8a84d00")))) {
            ExecutionStore.Event event = ReflectionTestUtils.invokeMethod(f.gateway, "userEvent", identity,
                    new InputId("attachment-input"), "", "attachment-message", parts, Optional.of(frozen));
            var body = json.readTree(event.data().canonicalJson());
            assertEquals("", body.path("text").asText());
            assertEquals(List.of(attachment.id()), json.convertValue(body.path("attachmentIds"), List.class));
            assertEquals("资料.txt", body.path("attachments").get(0).path("name").asText());
            assertFalse(event.data().canonicalJson().contains("PRIVATE_ATTACHMENT_BODY_MUST_NOT_BE_PUBLIC"));
            var projection = new AgentRuntimeExecutionProjection(mock(ExecutionStore.class),
                    mock(com.skillforge.core.chat.session.SessionMailboxService.class),
                    new com.skillforge.core.chat.integration.publicread.PublicOutputGuard("./private-goal-artifacts"), json, 100_000, 10_000, 32);
            var outputs = new ArrayList<com.skillforge.core.chat.session.SessionOutboxOutput>();
            ReflectionTestUtils.invokeMethod(projection, "project", identity.owner().sessionRef(),
                    new ExecutionStore.SavedEvent(identity.runRef(), 1, 1, Instant.parse("2026-10-30T00:00:00Z"), event), outputs);
            var projected = json.valueToTree(outputs.getFirst().payload()).path("data");
            assertEquals(body.path("attachmentIds"), projected.path("attachment_ids"));
            assertEquals(body.path("attachments"), projected.path("attachments"));
            if (!parts.isEmpty()) assertEquals(json.valueToTree(parts), projected.path("content_parts"));
            assertFalse(projected.has("attachment_readings"));
            assertEquals("attachment-message", projected.path("message_id").asText());
        }
    }

    @Test void rejectedUserPresentationDoesNotRevealAttachmentNamesOrImageSelectors() throws Exception {
        var f = new AgentRuntimeExecutionGatewayGoalTest.Fixture(false);
        var identity = f.store.load(new RunRef(new TenantId("1"), "run:original")).orElseThrow().identity();
        var json = new ObjectMapper();
        var attachment = new com.skillforge.institution.attachment.SessionAttachmentService.FrozenMetadata(
                "file-id", "Authorization: Bearer private-attachment-secret", "attachments/file-id/input.txt", 37L, "synthetic-sha", "2026-11-01T00:00:00Z");
        var frozen = new TypedPayload("input.v1", json.writeValueAsString(Map.of("extraParameters", Map.of("attachments", List.of(attachment)))));
        ExecutionStore.Event safe = ReflectionTestUtils.invokeMethod(f.gateway, "userEvent", identity,
                new InputId("attachment-input"), "", "attachment-message", List.of(), Optional.of(frozen));
        assertEquals("所选资料", json.readTree(safe.data().canonicalJson()).path("attachments").get(0).path("name").asText());
        ExecutionStore.Event rejected = ReflectionTestUtils.invokeMethod(f.gateway, "userEvent", identity,
                new InputId("attachment-input"), "Authorization: Bearer private-request-secret", "attachment-message",
                List.of(new com.skillforge.core.chat.contract.ChatContent.Image("b07853ea-a384-4eb6-a62f-a7aef8a84d00")), Optional.of(frozen));
        var body = json.readTree(rejected.data().canonicalJson());
        assertEquals(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.SAFE_MESSAGE, body.path("text").asText());
        assertFalse(body.has("attachmentIds")); assertFalse(body.has("attachments")); assertFalse(body.has("contentParts"));
        assertFalse(rejected.data().canonicalJson().contains("private-attachment-secret"));
    }

    @Test void onlyRealUserAdmissionCommitsOriginalPublicMessageIdentityAndParts() throws Exception {
        for (boolean user : List.of(true, false)) {
            var f = new AgentRuntimeExecutionGatewayGoalTest.Fixture(false);
            var projection = (AgentRuntimeExecutionProjection) ReflectionTestUtils.getField(f.gateway, "history");
            var history = mock(ChatHistoryResponse.class); when(history.messages()).thenReturn(List.of());
            when(projection.read(f.session)).thenReturn(history);
            var request = new ChatRequest("真实请求", "session", "7", null, null, null, null, null, "public-message", null, Map.of("input_id", "private-input"));
            var submission = new ChatRuntimeGateway.Submission(request, f.session, "7", 9L, null);
            var frozen = new TypedPayload("input.v1", "{\"content\":[{\"type\":\"text\",\"text\":\"真实请求\"}],\"extraParameters\":{}}");
            when(f.references.request(anyList(), anyMap(), anyList())).thenReturn(frozen);
            when(f.references.freeze(eq(f.session), eq(frozen), any())).thenReturn(frozen);
            var catalog = (AgentRuntimeExecutionCatalog) ReflectionTestUtils.getField(f.gateway, "catalog");
            var material = new ExecutionStore.Material(new ResultRef("catalog"), new TypedPayload("catalog.v1", "{}"));
            when(catalog.freeze(any(), any(), any(), any())).thenReturn(material);
            when(catalog.read(eq(material), any())).thenAnswer(call -> new AgentRuntimeExecutionCatalog.FrozenCatalog(
                    call.getArgument(1), List.of(), Map.of(), List.of(), List.of(), Set.of(), List.of(),
                    PlanPort.State.absent(), com.skillforge.runtime.session.SessionCollaboration.Selection.inactive(), Optional.empty()));
            var provisioner = (AgentRuntimeHostProvisioner) ReflectionTestUtils.getField(f.gateway, "provisioner");
            var budget = mock(ExecutionBudgetPort.Registration.class);
            when(budget.run()).thenReturn(new RunRef(new TenantId("1"), "run:private-input"));
            when(provisioner.prepareExecutionRun(anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(new AgentRuntimeHostProvisioner.ExecutionPreparation(new ResultRef("profile"), budget));
            when(((AgentExecutionProgram) ReflectionTestUtils.getField(f.gateway, "program")).initialContext(any()))
                    .thenReturn(new ExecutionStore.Material(new ResultRef("context"), new TypedPayload("context.v1", "{}")));
            when(f.store.admit(any())).thenAnswer(call -> {
                var admission = (ExecutionStore.Admission) call.getArgument(0);
                var run = mock(ExecutionStore.Run.class); when(run.identity()).thenReturn(admission.run());
                return new ExecutionStore.AdmissionResult(run, true);
            });
            RequestContext.setTraceId("history-test");
            try {
                TenantContext.runWithTenant("1", () -> { if (user) f.gateway.submit(submission); else f.gateway.submitGoalRound(submission); });
            } finally { RequestContext.clear(); }
            var admission = ArgumentCaptor.forClass(ExecutionStore.Admission.class); verify(f.store).admit(admission.capture());
            var messages = admission.getValue().events().stream().filter(event -> event.type().equals(AgentRuntimeExecutionResponses.USER_MESSAGE_EVENT)).toList();
            assertEquals(user ? 1 : 0, messages.size());
            if (user) {
                var body = new ObjectMapper().readTree(messages.getFirst().data().canonicalJson());
                assertEquals("public-message", body.path("messageId").asText());
                assertEquals("private-input", body.path("inputId").asText());
                assertEquals("真实请求", body.path("contentParts").get(0).path("text").asText());
            }
        }
    }
}
