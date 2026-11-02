package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 隔离数据库与真实HTTP验证原任务回执，查询不派发任务或模型。 */
class ConversationTaskReceiptHttpIntegrationTest extends ChatRuntimeChannelE2ETest {
    @Autowired private ConversationTaskPort tasks;
    @Autowired private SessionLogPort log;

    @Test void committedTaskReceiptIsReadOnlyAndMissingIsUnknown() throws Exception {
        var owner = SessionOwner.user(new SessionRef(new TenantId(tenantId), sessionId),
                new AgentId(agentId), new EndUserId(endUserId));
        var now = Instant.now();
        var input = new InputId("receipt-fixture");
        var sourceId = new EventId("receipt-fixture-source");
        var source = new SessionEventDraft(sourceId, "inbound.message", "1", "receipt-source-fingerprint",
                new TraceEnvelope("receipt-fixture", "receipt-fixture", input, Optional.empty()),
                new TypedPayload("user-message.v1", "{}"), now);
        var target = new SessionProjectionTarget(owner, input, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        TenantContext.runWithTenant(tenantId, () -> assertInstanceOf(SessionLogPort.AppendResult.Appended.class,
                log.append(new SessionLogPort.AppendSessionEvent(target, source))));
        var task = new ConversationTask("receipt-task", owner.sessionRef().tenantId(), owner.requireUser(),
                owner.target().requireAgent(), 1, ConversationTask.Status.OPEN, "核对资料", "等待资料",
                new TypedPayload(ConversationTask.STATE_SCHEMA, "{}"), List.of(new ConversationTask.Source(owner, sourceId)), List.of(), now);
        var event = new SessionEventDraft(new EventId("receipt-fixture-created"), "agent.task-updated", "1",
                "receipt-fixture-fingerprint", new TraceEnvelope("receipt-fixture", "receipt-fixture", input, Optional.empty()),
                new TypedPayload("skillforge.conversation-task.snapshot.v2", com.skillforge.institution.runtime.SessionSubjectJson
                        .configure(objectMapper.copy()).setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.LOWER_CAMEL_CASE)
                        .writeValueAsString(task)), now);
        var append = new SessionLogPort.AppendSessionEvent(new SessionProjectionTarget(owner, input,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), event);
        TenantContext.runWithTenant(tenantId, () -> assertInstanceOf(ConversationTaskPort.Result.Committed.class,
                tasks.commit(new ConversationTaskPort.Change(owner, task, 0, append, false, Optional.empty()))));
        String receipt = "/api/v1/conversation-tasks/receipt?session_id=" + sessionId + "&task_id=receipt-task&operation_id=receipt-operation";
        var missing = get(receipt);
        assertEquals(200, missing.statusCode(), missing.body());
        assertEquals("UNKNOWN", successData(missing).path("status").asText());
        var changed = send("/api/v1/conversation-tasks/update", "POST", Map.of("session_id", sessionId,
                "task_id", "receipt-task", "expected_revision", 1, "operation", "complete", "operation_id", "receipt-operation"), accessToken);
        assertEquals(200, changed.statusCode(), changed.body());
        var result = get(receipt);
        assertEquals(200, result.statusCode(), result.body());
        assertEquals("SUCCEEDED", successData(result).path("status").asText());
        assertEquals("receipt-task", successData(result).path("task").path("task_id").asText());
        assertEquals(2, successData(result).path("task").path("revision").asLong());
        assertEquals("COMPLETED", successData(result).path("task").path("status").asText());
        var repeated = get(receipt);
        assertEquals(successData(result), successData(repeated));
        assertEquals(1, countRuntime("select count(*) from agent_runtime_conversation_tasks where tenant_id=? and task_id=? and revision=2", tenantId, "receipt-task"));
    }
}
