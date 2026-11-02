package com.skillforge.core.chat.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.ExecutionStore.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 产品Session与实际typed Store事务组成的隔离观测夹具；不写旧Run或SessionEvent表。 */
final class SessionObservationFixture {
    static final long PLAN_ID = 910_000L;
    static final Instant MESSAGE_TIME = Instant.parse("2026-09-30T08:02:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutionBudgetPort.Resources CAPACITY = new ExecutionBudgetPort.Resources(
            20, 0, 0, 0, Long.MAX_VALUE / 8, Long.MAX_VALUE / 8, Long.MAX_VALUE / 4);
    private final JdbcTemplate jdbc;
    private final JdbcExecutionStore store;
    enum AnswerCase { COMPLETED, EMPTY_COMPLETION, FAILED, CANCELLED, UNKNOWN, UNSAFE }

    SessionObservationFixture(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
        // 存储边界测试显式核租户；完整IAM由宿主集成验收负责。
        this.store = new JdbcExecutionStore(Objects.requireNonNull(jdbc.getDataSource()),
                new com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort(jdbc.getDataSource(), Map.of()),
                (session, run, actor, prepared) -> {
            try (var query = session.getConnection().createStatement(); var rows = query.executeQuery("SELECT current_setting('app.tenant_id',true)")) {
                if (!rows.next() || !run.identity().runRef().tenantId().value().equals(rows.getString(1)))
                    throw new SecurityException("observation_fixture_tenant_mismatch");
                return true;
            } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
        });
    }

    void populate(long tenantId, String sessionId, String rootA, String rootB,
            long firstPrompt, long secondPrompt, long thirdPrompt) {
        createSession(tenantId, sessionId);
        appendRound(tenantId, sessionId, rootA, AnswerCase.COMPLETED, 940_000L + tenantId,
                List.of(new long[]{firstPrompt, 3}, new long[]{secondPrompt, 5}, new long[]{thirdPrompt, 7}));
        appendRound(tenantId, sessionId, rootB, AnswerCase.COMPLETED, 950_000L + tenantId, List.of());
    }
    void populateAnswerCase(long tenantId, String sessionId, String root, AnswerCase answerCase) {
        createSession(tenantId, sessionId);
        appendRound(tenantId, sessionId, root, answerCase, 940_000L + tenantId,
                answerCase == AnswerCase.CANCELLED ? List.of() : List.of(new long[]{2, 1}));
    }
    void appendCompletedRound(long tenantId, String sessionId, String root) {
        appendRound(tenantId, sessionId, root, AnswerCase.COMPLETED, 980_000L + tenantId, List.of());
    }
    void appendCompletedRound(long tenantId, String sessionId, String root, long messageId) {
        appendRound(tenantId, sessionId, root, AnswerCase.COMPLETED, messageId, List.of());
    }
    void populateInputOnly(long tenantId, String sessionId, String rootA, String rootB) {
        createSession(tenantId, sessionId);
        for (int index = 0; index < 2; index++) {
            String root = index == 0 ? rootA : rootB;
            appendUser(tenantId, sessionId, root, 940_000L + tenantId + index * 10_000L);
        }
    }
    static String answerMessageId(long tenantId, String root) {
        return AgentExecutionProgram.finishedEventId(new RunRef(new TenantId(Long.toString(tenantId)), "run-" + root)).value();
    }

    private void createSession(long tenantId, String sessionId) {
        long user = 920_000L + tenantId; long agent = 910_000L + tenantId;
        jdbc.update("INSERT INTO nw_tenants(id,name,slug,plan_id) VALUES(?,?,?,?)", tenantId, "机构-" + tenantId, "tenant-" + tenantId, PLAN_ID);
        inTenantTransaction(tenantId, () -> {
            jdbc.update("INSERT INTO nw_end_users(id,tenant_id,display_name) VALUES(?,?,?)", user, tenantId, "用户-" + tenantId);
            jdbc.update("INSERT INTO nw_agents(id,tenant_id,agent_key,name) VALUES(?,?,?,?)", agent, tenantId, "agent-" + tenantId, "会话观测 Agent");
            jdbc.update("""
                    INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,summary_text,message_count,created_at,updated_at,origin_kind,actor_ref,responsibility_ref)
                    VALUES(?,?,?,?,?,'真实typed执行事实',0,?,?,'system','system:observation-fixture',?)
                    """, 930_000L + tenantId, sessionId, tenantId, user, agent,
                    java.sql.Timestamp.from(Instant.parse("2026-09-30T08:00:00Z")), java.sql.Timestamp.from(Instant.parse("2026-09-30T08:01:00Z")), "tenant:" + tenantId);
        });
    }
    private void appendUser(long tenant, String session, String root, long rowId) {
        inTenantTransaction(tenant, () -> {
            jdbc.update("""
                    INSERT INTO nw_chat_messages(id,tenant_id,session_id,end_user_id,message_id,role,content,metadata,created_at)
                    VALUES(?,?,?,?,?,'user',?,CAST(? AS jsonb),?)
                    """, rowId, tenant, session, 920_000L + tenant, root, "question " + root,
                    json(Map.of("root_input_id", root, "run_id", "run-" + root)), java.sql.Timestamp.from(MESSAGE_TIME));
            jdbc.update("UPDATE nw_chat_sessions SET message_count=message_count+1 WHERE tenant_id=? AND session_id=?", tenant, session);
        });
    }

    /** Run/Action/Attempt/用量与公开事件只通过真实admit/dispatch/complete/finish提交。 */
    private void appendRound(long tenantId, String sessionId, String root, AnswerCase answerCase, long messageId, List<long[]> usages) {
        appendUser(tenantId, sessionId, root, messageId);
        var tenant = new TenantId(Long.toString(tenantId)); var runRef = new RunRef(tenant, "run-" + root);
        var owner = SessionOwner.user(new SessionRef(tenant, sessionId), new AgentId(Long.toString(910_000L + tenantId)), new EndUserId(920_000L + tenantId));
        var verified = new VerifiedIdentity(new PrincipalRef.SystemPrincipal(tenant, "observation-fixture"), owner,
                Set.of("sessions.write"), 1, new ResultRef("observation-verified:" + root));
        var identity = new RunIdentity(owner, owner.target().requireAgent(), new InputId(root), runRef,
                new TraceEnvelope("trace-" + root, "span-" + root, new InputId(root), Optional.empty()), verified.verificationRef(),
                new RunRequester.EndUser(owner.requireUser()),Optional.of(new SourceMessageRef(owner.sessionRef(),root)),new RunOriginRef(tenant,root));
        var limits = new ExecutionBudgetPort.Limits(ExecutionBudgetPort.Scenario.STANDARD, CAPACITY, CAPACITY, CAPACITY, 3600, 3600, "observation-fixture:v2");
        var admitted = store.admit(new Admission(verified, identity, "input:" + root, new ResultRef("configuration:" + root),
                material("input:" + root, Map.of("text", "question " + root)), material("context:" + root, Map.of()),
                new ExecutionBudgetPort.Registration(runRef, runRef, ExecutionBudgetPort.Role.PRIMARY, new ExecutionBudgetPort.Policy.Enforced(limits)),
                List.of(AgentExecutionProgram.admissionEvent(identity)), List.of()));
        if (!admitted.created()) throw new IllegalStateException("observation_fixture_run_already_present");
        var current = store.claim(new Claim(runRef, "observation-worker", "claim:" + root, Duration.ofMinutes(5))).orElseThrow();
        for (int index = 0; index < usages.size(); index++) {
            long ordinal = index + 1L; String attempt = "attempt:" + root + ':' + ordinal;
            var prepared = new Prepared(new ActionRef(tenant, "model:" + root + ':' + ordinal), ordinal, Kind.MODEL,
                    "fixture-model", new ResultRef("model-capability:" + root), material("request:" + attempt, Map.of("text", "question " + root)), "fingerprint:" + attempt);
            long[] tokens = usages.get(index);
            var demand = new ExecutionBudgetPort.Demand(runRef, attempt, prepared.fingerprint(), ExecutionBudgetPort.Category.BUSINESS_MODEL,
                    ExecutionBudgetPort.Resources.call(ExecutionBudgetPort.Category.BUSINESS_MODEL, Math.max(1, tokens[0]), Math.max(1, tokens[1])), false);
            var dispatched = store.dispatch(new Dispatch(runRef, current.revision(), current.lease().orElseThrow(), prepared, attempt, demand, verified, Optional.empty(), List.of()));
            if (!(dispatched instanceof DispatchResult.Registered registered)) throw new IllegalStateException("observation_fixture_dispatch_rejected");
            if (!store.authorizeSend(registered, prepared)) throw new IllegalStateException("observation_fixture_send_rejected");
            boolean unknown = answerCase == AnswerCase.UNKNOWN;
            var kind = unknown ? ResultKind.UNKNOWN : answerCase == AnswerCase.FAILED ? ResultKind.FAILED : ResultKind.SUCCEEDED;
            String resultEventId = "model-result:" + root + ':' + ordinal;
            var eventData = trace(identity); eventData.put("actionId", prepared.action().actionId()); eventData.put("status", kind.name()); eventData.put("summary", unknown ? "模型结果未知" : "模型调用已完成");
            var context = material("context:" + attempt, Map.of("iteration", ordinal));
            var usage = unknown ? ExecutionBudgetPort.Usage.unreported()
                    : ExecutionBudgetPort.Usage.reported(OptionalLong.of(tokens[0]), OptionalLong.of(tokens[1]));
            var result = store.complete(new Completion(runRef, attempt, kind, material("result:" + attempt, Map.of("kind", kind.name())), Optional.empty(),
                    Optional.of("invocation:" + attempt), usage, registered.run().revision(), registered.run().lease().orElseThrow(),
                    new Progress(ordinal, 0, 0, context.reference()), context,
                    List.of(new Event(new EventId(resultEventId), AgentExecutionProgram.MODEL_RESULT_EVENT,
                            new TypedPayload(AgentExecutionProgram.MODEL_RESULT_SCHEMA, json(eventData)))), Optional.empty()));
            current = result.run();
            providerAudit(960_000L + tenantId * 10 + index, tenantId, sessionId, root, attempt, resultEventId,
                    registered.run().updatedAt(), tokens[0], tokens[1], kind);
        }
        if (answerCase == AnswerCase.UNKNOWN) {
            if (current.phase() != Phase.UNCERTAIN || store.usage(runRef).complete())
                throw new IllegalStateException("observation_fixture_unknown_lost");
            return;
        }
        var status = answerCase == AnswerCase.FAILED ? RunStatus.FAILED : answerCase == AnswerCase.CANCELLED ? RunStatus.CANCELLED : RunStatus.COMPLETED;
        var body = trace(identity); body.put("status", status.name()); body.put("reason", "fixture");
        body.put("text", switch (answerCase) {
            case COMPLETED -> "answer " + root;
            case UNSAFE -> "password=not-a-real-test-secret";
            default -> "";
        }); body.put("evidence", List.of());
        var completed = new Event(AgentExecutionProgram.finishedEventId(runRef), AgentExecutionProgram.COMPLETED_EVENT,
                new TypedPayload(AgentExecutionProgram.COMPLETED_SCHEMA, json(body)));
        if (answerCase == AnswerCase.CANCELLED) store.cancel(new Cancel(verified, runRef, new InputId("cancel:" + root), List.of(completed)));
        else store.finish(new Finish(runRef, current.revision(), current.lease().orElseThrow(), status, List.of(completed)));
    }
    private static Map<String,Object> trace(RunIdentity identity) {
        var value = new LinkedHashMap<String,Object>(); value.put("inputId", identity.causationInputId().value());
        value.put("rootInputId", identity.trace().rootInputId().value()); value.put("traceId", identity.trace().traceId());
        value.put("spanId", identity.trace().spanId()); value.put("agentId", identity.agentId().value()); return value;
    }
    private static Material material(String reference, Map<String,?> value) {
        return new Material(new ResultRef(reference), new TypedPayload("application/json", json(value)));
    }

    /** 外部供应商审计明确关联原typed Attempt；UNKNOWN无usage_event，不能用零补齐缺报。 */
    private void providerAudit(long id, long tenantId, String sessionId, String root, String attempt, String sourceEvent,
            Instant startedAt, long prompt, long completion, ResultKind resultKind) {
        inTenantTransaction(tenantId, () -> {
            String invocation = "invocation:" + attempt;
            boolean unknown = resultKind == ResultKind.UNKNOWN;
            jdbc.update("""
                    INSERT INTO nw_external_invocations(id,tenant_id,invocation_id,attempt_id,operation_type,provider,service,status,
                        input_id,root_input_id,trace_id,span_id,source_event_id,started_at,metadata)
                    VALUES(?,?,?,?,'model.chat','provider-a','service-a',?,?,?,?,?,?,?,CAST(? AS jsonb))
                    """, id, tenantId, invocation, attempt,
                    unknown ? com.skillforge.core.billing.invocation.ExternalInvocationStatus.UNRESOLVED.name() : resultKind.name(), root, root,
                    "trace-" + root, "span-" + attempt, sourceEvent, java.sql.Timestamp.from(startedAt),
                    "{\"model\":\"model-a\",\"call_point\":\"agent-runtime.model\"}");
            if (unknown) return;
            jdbc.update("""
                    INSERT INTO nw_usage_events(id,tenant_id,event_type,provider,model,occurred_at,ingested_at,event_id,
                        session_id,root_input_id,run_id,attempt_id,invocation_id,prompt_tokens,completion_tokens,usage_source,usage_event_kind,report_revision,origin_kind,actor_ref,responsibility_ref)
                    VALUES(?,?,'llm.chat','provider-a','model-a',?,?,?,?,?,?,?,?,?,?,'PROVIDER_REPORTED','usage_report',1,'system','system:observation-fixture',?)
                    """, id + 100_000L, tenantId, java.sql.Timestamp.from(startedAt), java.sql.Timestamp.from(startedAt.plusSeconds(1)),
                    "usage:" + attempt, sessionId, root, "run-" + root, attempt, invocation, prompt, completion, "tenant:" + tenantId);
        });
    }
    private static String json(Map<String,?> fields) {
        try { return JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(fields); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("observation_fixture_json_invalid", failure); }
    }
    private void inTenantTransaction(long tenant, Runnable write) {
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)", String.class, Long.toString(tenant)); write.run();
        });
    }
}
