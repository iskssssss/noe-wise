package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.outbox.SessionOutboxChatEventSink;
import com.skillforge.core.chat.ChatRuntimeHistory;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionOutboxEventProjector;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.session.SessionOutboxOutput;
import com.skillforge.core.chat.session.SessionOutboxProjectionBatch;
import com.skillforge.core.chat.session.SessionOutboxProjectionCheckpoint;
import com.skillforge.core.chat.session.SessionOutboxProjectionSource;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.institution.browser.BrowserConnectionService;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.function.LongFunction;
import org.apache.ibatis.session.SqlSession;

/** 消费执行链的准确保存事件或持久事件页；不恢复 Run、Action 或旧 SessionLog。 */
public final class AgentRuntimeExecutionProjection implements AgentRuntimeEventProjection, ChatRuntimeHistory {
    private static final String POLICY = "execution-events-v1/" + PublicOutputGuard.POLICY_VERSION;
    private final ExecutionStore store;
    private final SessionMailboxService mailbox;
    private final PublicOutputGuard guard;
    private final ObjectMapper json;
    private final int maxCodeUnits;
    private final int maxNodes;
    private final int maxDepth;

    /** 组合持久事件源、产品 Outbox 和当前公开政策，不获取执行写权。 */
    public AgentRuntimeExecutionProjection(ExecutionStore store, SessionMailboxService mailbox,
            PublicOutputGuard guard, ObjectMapper json, int maxCodeUnits, int maxNodes, int maxDepth) {
        this.store = Objects.requireNonNull(store); this.mailbox = Objects.requireNonNull(mailbox);
        this.guard = Objects.requireNonNull(guard); this.json = Objects.requireNonNull(json);
        if (maxCodeUnits <= 0 || maxNodes <= 0 || maxDepth <= 0 || maxDepth > 128)
            throw new IllegalArgumentException("execution_observation_limits_invalid");
        this.maxCodeUnits = maxCodeUnits; this.maxNodes = maxNodes; this.maxDepth = maxDepth;
    }

    @Override public void reconcile(SessionView session) {
        var tenant = new TenantId(session.getTenantId().toString());
        reconcile(new SessionRef(tenant, session.getSessionId()));
    }

    /** 执行事务提交后的定向入口；投影成功提交 Outbox 后由 Mailbox 唤醒活跃订阅。 */
    public void committed(SessionRef session) { reconcile(session); }

    /** 事务所有者真实提交执行事实、Outbox 与 Webhook 责任后，仅唤醒活跃订阅。 */
    public void outputsCommitted(SessionRef session) {
        Objects.requireNonNull(session, "session");
        mailbox.outputsCommitted(session.tenantId().value(), session.sessionId());
    }

    /**
     * 将 Store 本事务刚保存的准确事件转换到同事务产品 Outbox。
     *
     * <p>Session 与 Run 归属由 Store 的已绑定事务提供；这里只核事件租户、身份和连续顺序，
     * 不查询 Run 或权限。正常锁后水位直接衔接本批时不读日志；政策重置或旧缺口通过
     * 同一 SqlSession 的有界 reader 逐页补齐，输出不越本批末水位，也不累计全部历史。
     * 调用方拥有提交、回滚、关闭与提交通知，本方法不另开事务或发送通知。</p>
     */
    public void appendBorrowed(SqlSession sql, SessionRef session, List<ExecutionStore.SavedEvent> saved,
            LongFunction<List<ExecutionStore.SavedEvent>> readAfter) {
        Objects.requireNonNull(sql, "sql"); Objects.requireNonNull(session, "session");
        Objects.requireNonNull(readAfter, "readAfter");
        var events = List.copyOf(saved);
        requireContinuousEvents(session, events);
        if (events.isEmpty()) return;
        var last = events.getLast();
        var source = new SessionOutboxProjectionSource(last.sequence(), last.event().id().value(), POLICY);
        mailbox.projectOutputsBorrowed(sql, session.tenantId().value(), session.sessionId(), source,
                checkpoint -> projectBorrowed(sql, session, events, checkpoint, readAfter));
    }

    /** 锁内先补旧缺口，再返回本批输出；每个历史页沿同一事务持锁写入自己的准确来源水位。 */
    private SessionOutboxProjectionBatch projectBorrowed(SqlSession sql, SessionRef session,
            List<ExecutionStore.SavedEvent> events, SessionOutboxProjectionCheckpoint checkpoint,
            LongFunction<List<ExecutionStore.SavedEvent>> readAfter) {
        long after = checkpoint.sequence();
        int pending = 0;
        while (pending < events.size() && events.get(pending).sequence() <= after) {
            var saved = events.get(pending++);
            if (saved.sequence() == after && !saved.event().id().value().equals(checkpoint.eventId()))
                throw new IllegalStateException("execution_projection_checkpoint_event_mismatch");
        }
        if (pending == events.size()) return new SessionOutboxProjectionBatch("{}", List.of());
        long first = events.get(pending).sequence();
        while (after < first - 1) {
            var page = List.copyOf(readAfter.apply(after));
            if (page.size() > 100) throw new IllegalStateException("execution_projection_page_limit_exceeded");
            var gap = new ArrayList<ExecutionStore.SavedEvent>();
            for (var saved : page) {
                // 本批事实由 Store 直接交付，reader 只负责其前方缺口，后续事件不能进入本批。
                if (saved.sequence() >= first) break;
                gap.add(saved);
            }
            if (gap.isEmpty() || gap.getFirst().sequence() != Math.incrementExact(after))
                throw new IllegalStateException("execution_projection_history_incomplete");
            requireContinuousEvents(session, gap);
            var last = gap.getLast();
            var source = new SessionOutboxProjectionSource(last.sequence(), last.event().id().value(), POLICY);
            mailbox.projectOutputsBorrowed(sql, session.tenantId().value(), session.sessionId(), source,
                    locked -> projectBatch(session, gap, locked.sequence()));
            after = last.sequence();
        }
        return projectBatch(session, events, after);
    }

    /** 连续页只转换锁后尚未处理的事实，缺失中间序号时拒绝推进公开水位。 */
    private SessionOutboxProjectionBatch projectBatch(SessionRef session, List<ExecutionStore.SavedEvent> events,
            long after) {
        var outputs = new ArrayList<SessionOutboxOutput>();
        for (var saved : events) {
            if (saved.sequence() <= after) continue;
            if (saved.sequence() != Math.incrementExact(after))
                throw new IllegalStateException("execution_projection_history_incomplete");
            project(session, saved, outputs);
            after = saved.sequence();
        }
        return new SessionOutboxProjectionBatch("{}", outputs);
    }

    /** 本批事件必须同租户、连续有序且身份不重复，避免错源或静默跳过中间事实。 */
    private static void requireContinuousEvents(SessionRef session, List<ExecutionStore.SavedEvent> events) {
        long previous = 0;
        var identities = new HashSet<String>();
        for (var saved : events) {
            if (!saved.run().tenantId().equals(session.tenantId()))
                throw new SecurityException("execution_event_tenant_mismatch");
            if (previous > 0 && saved.sequence() != Math.incrementExact(previous))
                throw new IllegalArgumentException("execution_source_sequence_invalid");
            if (!identities.add(saved.event().id().value()))
                throw new IllegalArgumentException("execution_source_event_duplicate");
            previous = saved.sequence();
        }
    }

    /** 人工协助拥有独立领域日志；只将真实已提交的人工回复投影到同一产品Outbox。 */
    public void reconcileHuman(SessionView session,com.skillforge.runtime.api.port.SessionLogPort log) {
        var owner=com.skillforge.institution.team.TeamWorkService.owner(session);
        String policy="human-assistance-v1/"+PublicOutputGuard.POLICY_VERSION;
        TenantContext.runWithTenant(owner.sessionRef().tenantId().value(),()->{
            long after=mailbox.projectionCheckpoint(owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId(),policy).sequence();
            while(true) {
                var page=log.readAfter(new com.skillforge.runtime.api.port.SessionLogPort.ReadSessionLog(owner,after,100),
                        java.util.Set.of(com.skillforge.runtime.session.SessionHumanAssistance.EVENT));
                if(page.isEmpty())return;var last=page.getLast();
                var source=new SessionOutboxProjectionSource(last.sessionSequence(),last.eventId().value(),policy);
                mailbox.projectOutputsIncrementally(owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId(),source,checkpoint->{
                    var outputs=new ArrayList<SessionOutboxOutput>();
                    for(var event:page)if(event.sessionSequence()>checkpoint.sequence()) {
                        if(!event.owner().equals(owner))throw new SecurityException("human_assistance_owner_mismatch");
                        var value=com.skillforge.runtime.session.SessionHumanAssistance.content(event);var scope=guard.capture(owner);
                        var trace=new TraceContext(event.inputId().value(),event.trace().rootInputId().value(),event.trace().traceId(),event.trace().spanId(),event.eventId().value(),null);
                        var sink=SessionOutboxChatEventSink.collecting(owner.sessionRef().tenantId().value(),owner.sessionRef().sessionId(),trace,outputs::add);
                        var data=new LinkedHashMap<String,Object>();data.put("message_id",event.eventId().value());data.put("message_version",1);data.put("role","assistant");data.put("state","completed");
                        data.put("content",scope.rejects(value.text())?PublicOutputGuard.SAFE_MESSAGE:value.text());data.put("created_at",event.occurredAt().toString());data.put("references",List.of());
                        data.put("author_type","human_operator");data.put("operator_name",scope.rejects(value.operatorName())?"坐席":value.operatorName());
                        data.put("sequence",event.sessionSequence());data.put("updated_sequence",event.sessionSequence());sink.send("message.delta",data);
                    }
                    return new SessionOutboxProjectionBatch("{}",outputs);
                });
                after=last.sessionSequence();if(page.size()<100)return;
            }
        });
    }

    /** 已授权的历史入口先补齐持久事件，再只折叠捕获水位之前的Outbox页。 */
    @Override public ChatHistoryResponse read(SessionView session) {
        var reference = new SessionRef(new TenantId(session.getTenantId().toString()), session.getSessionId());
        reconcile(reference);
        String cursor = mailbox.committedCursor(reference.tenantId().value(), reference.sessionId());
        var history = new AgentRuntimeExecutionHistory(json, guard.capture());
        if (cursor == null) return history.snapshot(reference.sessionId(), null);
        long through = mailbox.resolveOutputSequence(reference.tenantId().value(), reference.sessionId(), cursor);
        long after = 0;
        while (after < through) {
            var page = mailbox.listOutputsAfterSequence(reference.tenantId().value(), reference.sessionId(), after, 100);
            if (page.isEmpty()) throw new IllegalStateException("execution_public_history_incomplete");
            for (var output : page) {
                if (output.getSessionSequence() == null || output.getSessionSequence() <= after)
                    throw new IllegalStateException("execution_public_history_sequence_invalid");
                if (output.getSessionSequence() > through) return history.snapshot(reference.sessionId(), cursor);
                if (!session.getTenantId().equals(output.getTenantId()) || !reference.sessionId().equals(output.getSessionId()))
                    throw new SecurityException("execution_public_history_scope_mismatch");
                history.accept(output);
                after = output.getSessionSequence();
            }
        }
        return history.snapshot(reference.sessionId(), cursor);
    }

    /** 在现有公开投影补齐后，仅读取指定消息的当前安全快照。 */
    @Override public Optional<ChatMessageRecord> message(SessionView session, String messageId) {
        var reference = new SessionRef(new TenantId(session.getTenantId().toString()), session.getSessionId());
        reconcile(reference);
        return mailbox.message(reference.tenantId().value(), reference.sessionId(), messageId).map(output -> {
            if (!session.getTenantId().equals(output.getTenantId()) || !reference.sessionId().equals(output.getSessionId()))
                throw new SecurityException("execution_public_history_scope_mismatch");
            return AgentRuntimeExecutionHistory.message(output, json, guard.capture());
        });
    }

    /** 已完成群授权的有界查询复用同一消息折叠和当前公开政策，不再重建另一份历史。 */
    public ChatMessageRecord publicMessage(SessionOutboxEntry output) {
        return AgentRuntimeExecutionHistory.message(output,json,guard.capture());
    }

    /** 每次披露冻结Outbox前复核当前公开政策；身份、版本、序号和因果保持原值。 */
    public SessionOutboxEventProjector.Event publicEvent(SessionOutboxEntry output) {
        var event=SessionOutboxEventProjector.project(output);var scope=guard.capture();
        if (AgentRuntimeBodyPreview.EVENT.equals(event.event()) && scope.rejects(String.valueOf(event.data().get("content")))) {
            var data = new LinkedHashMap<>(event.data());
            data.put("content", ""); data.put("state", "rejected");
            return new SessionOutboxEventProjector.Event(event.event(), event.eventId(), event.sessionId(), event.sessionSequence(),
                    event.inputId(), event.rootInputId(), event.causedByEventId(), event.eventVersion(), event.occurredAt(), Map.copyOf(data));
        }
        if(java.util.Set.of("message.delta","message.card","message.card.update").contains(event.event())) {
            if(!"assistant".equals(event.data().get("role"))||!scope.rejects(String.valueOf(event.data())))return event;
            var message=AgentRuntimeExecutionHistory.message(output,json,scope);
            var data=new LinkedHashMap<String,Object>(message.metadata());
            data.put("message_id",message.messageId());data.put("message_version",message.messageVersion());
            data.put("role",message.role());data.put("content",message.content());
            data.put("created_at",message.createdAt().toString());data.put("references",message.references());
            for (String key : java.util.List.of("session_id", "event_id", "session_sequence", "input_id", "root_input_id",
                    "caused_by_event_id", "event_version", "trace_id", "occurred_at", "durability")) {
                if (event.data().containsKey(key)) data.put(key, event.data().get(key));
            }
            return new SessionOutboxEventProjector.Event(event.event(),event.eventId(),event.sessionId(),event.sessionSequence(),
                    event.inputId(),event.rootInputId(),event.causedByEventId(),event.eventVersion(),event.occurredAt(),Map.copyOf(data));
        }
        if(AgentRuntimeExecutionPublicEvents.rejectsPresentation(json.valueToTree(event.data()),scope,json))
            throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    PublicOutputGuard.REJECTION_CODE,PublicOutputGuard.SAFE_MESSAGE,false);
        return event;
    }

    /** 新Run首次加载需要的有界公开消息；补齐事件由调用方在首次加载边界显式执行一次。 */
    public List<ChatMessageRecord> recentMessages(SessionView session, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("execution_history_limit_invalid");
        var scope = guard.capture();
        return mailbox.recentMessages(session.getTenantId().toString(), session.getSessionId(), limit).stream()
                .map(output -> {
                    if (!session.getTenantId().equals(output.getTenantId()) || !session.getSessionId().equals(output.getSessionId()))
                        throw new SecurityException("execution_public_history_scope_mismatch");
                    return AgentRuntimeExecutionHistory.message(output, json, scope);
                }).sorted(java.util.Comparator.comparing(ChatMessageRecord::createdAt).thenComparing(ChatMessageRecord::messageId)).toList();
    }

    /** 已授权的成员来源视图；事件仅从其原Session读取，并精确筛选指定新Run。 */
    public ChatHistoryResponse readSource(SessionOwner owner, RunRef run) {
        Objects.requireNonNull(owner); Objects.requireNonNull(run);
        if (!owner.sessionRef().tenantId().equals(run.tenantId())) throw new SecurityException("execution_source_tenant_mismatch");
        var history = new AgentRuntimeExecutionHistory(json, guard.capture()); long after = 0;
        while (true) {
            var page = store.events(owner.sessionRef(), after, 100);
            for (var saved : page) {
                if (saved.sequence() <= after) throw new IllegalStateException("execution_source_sequence_invalid");
                if (saved.run().equals(run)) acceptSource(owner, saved, history);
                after = saved.sequence();
            }
            if (page.size() < 100) return history.snapshot(owner.sessionRef().sessionId(), null);
        }
    }

    /** 冻结InputRef重放只核原公开版本仍有真实来源；不能用最新版本补写原选择器。 */
    public boolean publicReferenceExists(SessionOwner owner, RunRef run, com.skillforge.core.chat.contract.ChatInputReference reference) {
        Objects.requireNonNull(owner); Objects.requireNonNull(reference);
        if (run != null && !owner.sessionRef().tenantId().equals(run.tenantId())) throw new SecurityException("execution_source_tenant_mismatch");
        if (!(reference instanceof com.skillforge.core.chat.contract.ChatInputReference.Message)
                && !(reference instanceof com.skillforge.core.chat.contract.ChatInputReference.Process)) throw new IllegalArgumentException("execution_reference_kind_invalid");
        var history = new AgentRuntimeExecutionHistory(json, guard.capture(owner)); long after = 0;
        while (true) {
            var page = store.events(owner.sessionRef(), after, 100);
            for (var saved : page) {
                if (saved.sequence() <= after || !saved.run().tenantId().equals(owner.sessionRef().tenantId()))
                    throw new IllegalStateException("execution_source_sequence_invalid");
                after = saved.sequence(); if (run != null && !saved.run().equals(run)) continue;
                acceptSource(owner, saved, history);
                var visible = history.snapshot(owner.sessionRef().sessionId(), null);
                if (reference instanceof com.skillforge.core.chat.contract.ChatInputReference.Message message) {
                    if (visible.messages().stream().anyMatch(value -> value.messageId().equals(message.messageId())
                            && value.messageVersion() == message.messageVersion())) return true;
                } else {
                    var process = (com.skillforge.core.chat.contract.ChatInputReference.Process) reference;
                    if (visible.processes().stream().anyMatch(value -> value.processId().equals(process.processId())
                            && value.updatedSequence() == process.updatedSequence())) return true;
                    if (saved.sequence() >= process.updatedSequence()) return false;
                }
            }
            if (page.size() < 100) return false;
        }
    }

    /** 调用方已按实际计划Action筛选的已提交事件，复用正式公开转换且不写Outbox或执行账。 */
    public ChatHistoryResponse project(SessionOwner owner, List<ExecutionStore.SavedEvent> events) {
        Objects.requireNonNull(owner); events = List.copyOf(events);
        var history = new AgentRuntimeExecutionHistory(json, guard.capture()); long previous = 0;
        for (var saved : events) {
            if (saved.sequence() <= previous) throw new IllegalArgumentException("execution_source_sequence_invalid");
            acceptSource(owner, saved, history); previous = saved.sequence();
        }
        return history.snapshot(owner.sessionRef().sessionId(), null);
    }

    private void acceptSource(SessionOwner owner, ExecutionStore.SavedEvent saved, AgentRuntimeExecutionHistory history) {
        var outputs = new ArrayList<SessionOutboxOutput>(); project(owner.sessionRef(), saved, outputs);
        for (var output : outputs) {
            // 临时呈现适配保留真实来源序号和时间；没有声称这些命令已取得产品Outbox持久序号。
            var trace = output.trace();
            var row = SessionOutboxEntry.builder().tenantId(Long.valueOf(owner.sessionRef().tenantId().value()))
                    .sessionId(owner.sessionRef().sessionId()).runtimeRunId(saved.run().runId()).eventId(output.eventId())
                    .eventType(output.eventType()).payload(output.payload()).sessionSequence(saved.sequence())
                    .inputId(trace.inputId()).rootInputId(trace.rootInputId()).traceId(trace.traceId())
                    .sourceEventId(trace.eventId()).causedByEventId(trace.causedByEventId())
                    .createdAt(saved.occurredAt()).build();
            history.accept(row);
        }
    }

    /** 提交完成通知和页面重连使用同一持久游标；并发投影以 Mailbox 锁后游标复验。 */
    public void reconcile(SessionRef session) {
        TenantContext.runWithTenant(session.tenantId().value(), () -> {
            long after = mailbox.projectionCheckpoint(session.tenantId().value(), session.sessionId(), POLICY).sequence();
            while (true) {
                var page = store.events(session, after, 100);
                if (page.isEmpty()) return;
                var last = page.getLast();
                var source = new SessionOutboxProjectionSource(last.sequence(), last.event().id().value(), POLICY);
                mailbox.projectOutputsIncrementally(session.tenantId().value(), session.sessionId(), source, checkpoint -> {
                    var outputs = new ArrayList<SessionOutboxOutput>();
                    for (var saved : page) if (saved.sequence() > checkpoint.sequence()) project(session, saved, outputs);
                    return new SessionOutboxProjectionBatch("{}", outputs);
                });
                after = last.sequence();
                if (page.size() < 100) return;
            }
        });
    }

    /** 新事件源尚未回收正文，因此没有历史截断事实可公开。 */
    @Override public Optional<Map<String, Object>> historyRetentionState(SessionView session) { return Optional.empty(); }

    private void project(SessionRef session, ExecutionStore.SavedEvent saved, List<SessionOutboxOutput> outputs) {
        if (!saved.run().tenantId().equals(session.tenantId())) throw new SecurityException("execution_event_tenant_mismatch");
        var event = saved.event();
        if (com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE.equals(event.type())) {
            var fact = com.skillforge.runtime.session.ExecutionObservation.decode(event.data(), maxCodeUnits, maxNodes, maxDepth);
            if (!fact.runId().equals(saved.run().runId()))
                throw new IllegalArgumentException("execution_observation_owner_mismatch");
            // 安全观测仅供授权轨迹查询；不生成第二份聊天过程或模型调用计数。
            return;
        }
        // 内部上下文材料与缩减诊断只占用原日志游标，不是公开聊天事实，不能要求公开因果字段。
        if (com.skillforge.runtime.session.AgentExecutionContextViews.EVENT.equals(event.type())) {
            if (!com.skillforge.runtime.session.AgentExecutionContextViews.SCHEMA.equals(event.data().schemaId()))
                throw new IllegalArgumentException("execution_internal_event_invalid");
            return;
        }
        // 准入事务内的规划模式采用事实仅供会话协作投影，不是公开聊天事件。
        if (com.skillforge.runtime.session.SessionCollaboration.MODE.equals(event.type())) {
            if (!com.skillforge.runtime.session.SessionCollaboration.MODE_SCHEMA.equals(event.data().schemaId()))
                throw new IllegalArgumentException("execution_internal_event_invalid");
            return;
        }
        if ("agent.context-compaction-attempt".equals(event.type())) {
            if (!"skillforge.runtime.execution.context-compaction-attempt.v1".equals(event.data().schemaId()))
                throw new IllegalArgumentException("execution_internal_event_invalid");
            return;
        }
        try {
            var value = json.readTree(event.data().canonicalJson());
            if (value == null || !value.isObject()) throw new IllegalArgumentException("execution_public_event_invalid");
            var trace = new TraceContext(text(value, "inputId"), text(value, "rootInputId"), text(value, "traceId"),
                    text(value, "spanId"), event.id().value(), null);
            var sink = SessionOutboxChatEventSink.collecting(session.tenantId().value(), session.sessionId(), trace, outputs::add);
            var data = new LinkedHashMap<String, Object>();
            data.put("session_id", session.sessionId()); data.put("run_id", saved.run().runId());
            data.put("input_id", trace.inputId()); data.put("root_input_id", trace.rootInputId());
            data.put("root_run_id", saved.run().runId()); data.put("runtime_event_id", event.id().value());
            data.put("sequence", saved.sequence()); data.put("updated_sequence", saved.sequence());
            data.put("created_at", saved.occurredAt().toString());
            if (matches(event, AgentExecutionProgram.MODEL_STARTED_EVENT, AgentExecutionProgram.MODEL_STARTED_SCHEMA)) {
                modelProcess(saved, value, data, sink, false);
                return;
            }
            if (matches(event, AgentExecutionProgram.MODEL_RESULT_EVENT, AgentExecutionProgram.MODEL_RESULT_SCHEMA)) {
                modelProcess(saved, value, data, sink, true);
                return;
            }
            if (matches(event, AgentExecutionProgram.ACCEPTED_EVENT, AgentExecutionProgram.ACCEPTED_SCHEMA)) {
                data.put("status", "running");
                sink.sendRuntime("execution.accepted", data, saved.run().runId());
                return;
            }
            if (matches(event, AgentExecutionProgram.RESOLVED_EVENT, AgentExecutionProgram.RESOLVED_SCHEMA)) {
                data.put("execution_id", saved.run().runId());
                data.put("tool_wait", Map.of("action_id", text(value, "actionId"), "generation", decimal(value, "generation")));
                data.put("reason", text(value, "reason"));
                sink.sendRuntime("interaction.resolved", data, saved.run().runId());
                if (java.util.Set.of("delegation", "workflow", "plan").contains(value.path("dependencyKind").asText())) dependencyProcess(saved, value, data, sink, true);
                return;
            }
            if (matches(event, AgentExecutionProgram.TOOL_RESULT_EVENT, AgentExecutionProgram.TOOL_RESULT_SCHEMA)) {
                toolResult(saved, value, data, sink);
                return;
            }
            if (matches(event, AgentExecutionProgram.WORKFLOW_STARTED_EVENT, AgentExecutionProgram.WORKFLOW_STARTED_SCHEMA)
                    || matches(event, AgentExecutionProgram.WORKFLOW_RESULT_EVENT, AgentExecutionProgram.WORKFLOW_RESULT_SCHEMA)) {
                workflowProcess(saved, value, data, sink,
                        AgentExecutionProgram.WORKFLOW_RESULT_EVENT.equals(event.type()));
                return;
            }
            if (matches(event, AgentRuntimeExecutionResponses.USER_MESSAGE_EVENT, AgentRuntimeExecutionResponses.USER_MESSAGE_SCHEMA)) {
                data.put("message_id", text(value, "messageId")); data.put("message_version", 1);
                if (value.has("contentParts")) data.put("content_parts", json.convertValue(value.get("contentParts"), List.class));
                if (value.has("attachmentIds")) data.put("attachment_ids", json.convertValue(value.get("attachmentIds"), List.class));
                if (value.has("attachments")) data.put("attachments", json.convertValue(value.get("attachments"), List.class));
                data.put("role", "user"); data.put("state", "completed");
                if (!value.path("text").isTextual()) throw new IllegalArgumentException("execution_public_event_invalid");
                data.put("content", value.path("text").textValue());
                data.put("references", List.of());
                sink.sendRuntime("message.delta", data, saved.run().runId());
                return;
            }
            if (AgentRuntimeExecutionPublicEvents.REJECTED_EVENT.equals(event.type())
                    && AgentRuntimeExecutionPublicEvents.REJECTED_SCHEMA.equals(event.data().schemaId())) {
                rejectedInteraction(saved, data, sink);
                return;
            }
            if (AgentExecutionProgram.WAITING_EVENT.equals(event.type())
                    && AgentExecutionProgram.WAITING_SCHEMA.equals(event.data().schemaId())) {
                waiting(saved, value, data, sink);
                return;
            }
            if (!AgentExecutionProgram.COMPLETED_EVENT.equals(event.type())
                    || !AgentExecutionProgram.COMPLETED_SCHEMA.equals(event.data().schemaId()))
                throw new IllegalStateException("execution_public_event_unsupported");
            String body = value.path("text").asText();
            var scope = guard.capture();
            boolean rejected = scope.rejects(body) || AgentRuntimeExecutionPublicEvents.citationsRejected(value, scope);
            String status = rejected ? "FAILED" : text(value, "status");
            if (!body.isEmpty() || rejected) {
                var message = new LinkedHashMap<>(data);
                message.put("message_id", event.id().value()); message.put("message_version", 1);
                message.put("preview_message_id", AgentRuntimeBodyPreview.messageId(saved.run().runId()));
                message.put("content", rejected ? PublicOutputGuard.SAFE_MESSAGE : body);
                message.put("citations", rejected ? List.of() : AgentRuntimeExecutionPublicEvents.citations(value));
                message.put("references", List.of());
                message.put("author",agentAuthor(saved,value));
                message.put("role", "assistant"); message.put("state", "COMPLETED".equals(status) ? "completed" : "failed");
                // 此处只投影已提交的正式终态事件；phase不授予成功状态，失败与公开输出拒绝仍保持failed。
                message.put("phase", "final");
                sink.sendRuntime("message.delta", message, saved.run().runId());
            }
            // 正式身份保持不变；消费者据此清除非权威预览并锁定 Run，迟到预览不能复活。
            data.put("preview_message_id", AgentRuntimeBodyPreview.messageId(saved.run().runId()));
            data.put("status", switch (status) { case "COMPLETED" -> "completed"; case "CANCELLED" -> "cancelled"; default -> "failed"; });
            data.put("reason", rejected ? PublicOutputGuard.REJECTION_CODE : value.path("reason").asText());
            sink.sendRuntime("execution.completed", data, saved.run().runId());
        } catch (java.io.IOException malformed) { throw new IllegalStateException("execution_public_event_invalid", malformed); }
    }

    /** 作者来自原正式公开事件的冻结Agent，不查询当前群服务目录覆盖历史。 */
    private static Map<String,Object> agentAuthor(ExecutionStore.SavedEvent saved,JsonNode value) {
        return Map.of("kind","agent","agent_id",text(value,"agentId"),"run_id",saved.run().runId(),"source_ref",saved.event().id().value());
    }

    /** 工具等待只公开准确恢复身份和真实表单，opaque continuation 材料不进入 Outbox。 */
    private void waiting(ExecutionStore.SavedEvent saved, JsonNode value, Map<String, Object> base,
            SessionOutboxChatEventSink sink) throws java.io.IOException {
        String kind = text(value, "kind");
        boolean human = "human".equals(kind);
        boolean dependency = java.util.Set.of("delegation", "workflow", "plan").contains(kind);
        if (!human && !dependency && !"tool".equals(kind)) throw new IllegalArgumentException("execution_wait_kind_unsupported");
        if (AgentRuntimeExecutionPublicEvents.rejectsWaiting(value, guard.capture(), json)) {
            rejectedInteraction(saved, base, sink);
            return;
        }
        var wait = Map.of("action_id", text(value, "actionId"), "generation", decimal(value, "generation"));
        if (dependency) {
            var data = new LinkedHashMap<>(base); data.put("execution_id", saved.run().runId());
            data.put("tool_wait", wait); data.put("status", "waiting_dependency");
            sink.sendRuntime("execution.waiting", data, saved.run().runId());
            dependencyProcess(saved, value, base, sink, false);
            if ("workflow".equals(kind) && value.has("workflow")) workflowHuman(saved, value, value.get("workflow"), base, wait, sink);
            return;
        }
        var requests = value.path("requests");
        if (!requests.isObject() || !human && requests.isEmpty()) throw new IllegalArgumentException("execution_wait_requests_invalid");
        Map<String, Object> card;
        String content;
        if (human) {
            content = text(value, "question");
            card = Map.of("type", "user_input", "title", "补充信息", "body", content, "tool_wait", wait);
        } else if (requests.size() == 1 && requests.has(BrowserConnectionService.INPUT_KEY)
                && BrowserConnectionService.REQUEST_SCHEMA.equals(requests.path(BrowserConnectionService.INPUT_KEY)
                        .path("params").path("schemaId").asText())) {
            var request = requests.path(BrowserConnectionService.INPUT_KEY);
            requireElicitation(request);
            var params = json.readTree(text(request.path("params"), "canonicalJson"));
            card = Map.of("type", "browser_connection", "title", "连接浏览器以继续任务",
                    "connection_request_id", text(params, "connection_request_id"), "reason", text(params, "reason"));
            content = "此任务需要连接你的浏览器";
        } else {
            var forms = new ArrayList<Map<String, Object>>();
            var names = new ArrayList<String>();
            requests.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (var name : names) {
                if (name.isBlank()) throw new IllegalArgumentException("execution_wait_request_key_invalid");
                var request = requests.path(name);
                requireElicitation(request);
                if (!"skillforge.runtime.tool.mcp-input-request.v1".equals(text(request.path("params"), "schemaId")))
                    throw new IllegalArgumentException("execution_wait_request_schema_unsupported");
                var params = json.readTree(text(request.path("params"), "canonicalJson"));
                if (params == null || !params.path("requestedSchema").isObject())
                    throw new IllegalArgumentException("execution_wait_request_schema_invalid");
                forms.add(Map.of("id", name, "message", text(params, "message"),
                        "requested_schema", params.get("requestedSchema")));
            }
            card = Map.of("type", "tool_input", "title", "工具需要补充信息", "requests", forms, "tool_wait", wait);
            content = "工具需要补充信息";
        }
        var message = new LinkedHashMap<>(base);
        message.put("message_id", saved.event().id().value());
        message.put("message_version", 1);
        message.put("author",agentAuthor(saved,value));
        message.put("role", "assistant");
        message.put("state", "waiting");
        message.put("content", content);
        message.put("card", card);
        message.put("references", List.of());
        sink.sendRuntime("message.card", message, saved.run().runId());
        var required = new LinkedHashMap<>(base);
        required.put("execution_id", saved.run().runId());
        required.put("run_revision", Long.toString(saved.revision()));
        required.put("interaction_type", human ? "user_input" : "tool_input");
        required.put("tool_wait", wait);
        required.put("message_id", saved.event().id().value());
        required.put("card", card);
        if (human) required.put("question", content);
        sink.sendRuntime("hitl.required", required, saved.run().runId());
        if (human) return;
        var process = new LinkedHashMap<>(base);
        process.put("process_id", "process:" + saved.run().runId() + ":tool:" + wait.get("action_id"));
        process.put("reference_id", wait.get("action_id"));
        process.put("type", "tool");
        process.put("ordinal", 0);
        process.put("actor_agent_id", text(value, "agentId"));
        process.put("status", "waiting");
        process.put("started_at", saved.occurredAt().toString());
        process.put("presentation", Map.of("title", "工具需要补充信息", "summary", content,
                "results", List.of(), "references", List.of()));
        sink.sendRuntime("process.delta", process, saved.run().runId());
    }

    /** 卡片来自真实SDK Human等待快照；所有回复路由字段都与Run Wait及Engine Wait同时公开。 */
    private void workflowHuman(ExecutionStore.SavedEvent saved, JsonNode value, JsonNode workflow, Map<String,Object> base,
            Map<String,String> wait, SessionOutboxChatEventSink sink) throws java.io.IOException {
        var card = json.readTree(text(workflow.path("card"),"canonicalJson"));
        if (!"application/json".equals(text(workflow.path("card"),"schemaId")) || !card.isObject()) throw new IllegalArgumentException("execution_workflow_card_invalid");
        String content = "工作流需要你的回复";
        for (var field : List.of("body","message","title")) if (card.path(field).isTextual() && !card.get(field).textValue().isBlank()) { content=card.get(field).textValue();break; }
        var message = new LinkedHashMap<>(base);message.put("message_id",saved.event().id().value());message.put("message_version",1);
        message.put("author",agentAuthor(saved,value));message.put("role","assistant");message.put("state","waiting");message.put("content",content);message.put("card",card);message.put("references",List.of());
        sink.sendRuntime("message.card",message,saved.run().runId());
        var required = new LinkedHashMap<>(base);required.put("execution_id",saved.run().runId());required.put("run_revision",Long.toString(saved.revision()));
        required.put("tool_wait",wait);required.put("interaction_type","workflow_input");required.put("message_id",saved.event().id().value());required.put("card",card);
        required.put("workflow_execution_id",text(workflow,"executionId"));required.put("suspend_ref",text(workflow,"suspendRef"));
        required.put("execution_revision",decimal(workflow,"executionRevision"));required.put("resume_action_key",text(workflow,"actionKey"));
        sink.sendRuntime("hitl.required",required,saved.run().runId());
    }

    /** 等待真实子Run只展示过程，不制造用户表单或工具InputRequired。 */
    private void dependencyProcess(ExecutionStore.SavedEvent saved, JsonNode value, Map<String, Object> base,
            SessionOutboxChatEventSink sink, boolean finished) {
        var process = new LinkedHashMap<>(base); String action = text(value, "actionId");
        String kind = text(value, finished ? "dependencyKind" : "kind");
        String title = switch (kind) { case "delegation" -> "委派执行"; case "workflow" -> "工作流执行"; case "plan" -> "协作计划"; default -> throw new IllegalArgumentException("execution_dependency_kind_invalid"); };
        String summary = finished ? switch (kind) { case "delegation" -> "真实委派结果已交接"; case "workflow" -> "工作流响应已交接"; default -> "计划结果已交接"; }
                : switch (kind) { case "delegation" -> "等待成员执行结果"; case "workflow" -> "等待工作流继续条件"; default -> "等待计划推进结果"; };
        process.put("process_id", "process:" + saved.run().runId() + ":dependency:" + action);
        process.put("reference_id", action); process.put("type", "workflow".equals(kind) ? "workflow" : "delegation"); process.put("ordinal", 0);
        process.put("actor_agent_id", text(value, "agentId")); process.put("status", finished ? "succeeded" : "waiting");
        process.put("started_at", saved.occurredAt().toString()); if (finished) process.put("finished_at", saved.occurredAt().toString());
        process.put("presentation", Map.of("title", title, "summary", summary,
                "results", List.of(), "references", List.of()));
        sink.sendRuntime(finished ? "process.finished" : "process.delta", process, saved.run().runId());
    }

    /** 工具过程终态只来自实际结果公开事实，不以Provider正常返回推断业务成果。 */
    private void toolResult(ExecutionStore.SavedEvent saved, JsonNode value, Map<String, Object> base,
            SessionOutboxChatEventSink sink) {
        String summary = text(value, "summary");
        if (guard.capture().rejects(summary)) summary = PublicOutputGuard.SAFE_MESSAGE;
        if (!value.path("outputAccepted").isBoolean()) throw new IllegalArgumentException("execution_tool_result_acceptance_invalid");
        String status = switch (text(value, "status")) {
            case "SUCCEEDED" -> value.path("outputAccepted").booleanValue() && !value.path("businessFailed").asBoolean(false) ? "succeeded" : "failed";
            case "FAILED" -> "failed";
            case "UNKNOWN" -> "outcome_unknown";
            default -> throw new IllegalArgumentException("execution_tool_result_status_invalid");
        };
        var process = new LinkedHashMap<>(base);
        String action = text(value, "actionId");
        process.put("process_id", "process:" + saved.run().runId() + ":tool:" + action);
        process.put("reference_id", action); process.put("type", "tool"); process.put("ordinal", 0);
        process.put("actor_agent_id", text(value, "agentId")); process.put("status", status);
        process.put("started_at", saved.occurredAt().toString()); process.put("finished_at", saved.occurredAt().toString());
        process.put("presentation", Map.of("title", "工具调用", "summary", summary, "results", List.of(), "references", List.of()));
        sink.sendRuntime("process.finished", process, saved.run().runId());
    }

    /** Workflow 同一逻辑调用保持一个过程身份；挂起由现有交互事实表示，不伪造终态。 */
    private void workflowProcess(ExecutionStore.SavedEvent saved, JsonNode value, Map<String, Object> base,
            SessionOutboxChatEventSink sink, boolean finished) {
        var safe = AgentRuntimeExecutionPublicEvents.protectWorkflow(saved.event(), guard, json);
        try { value = json.readTree(safe.data().canonicalJson()); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("execution_workflow_event_invalid", invalid); }
        String status = finished ? switch (text(value, "status")) {
            case "COMPLETED" -> value.path("outputAccepted").booleanValue() ? "succeeded" : "failed";
            case "FAILED", "TERMINATED" -> "failed";
            case "CANCELLED" -> "cancelled";
            case "UNKNOWN" -> "outcome_unknown";
            default -> throw new IllegalArgumentException("execution_workflow_event_invalid");
        } : "running";
        var process = new LinkedHashMap<>(base); String action = text(value, "actionId");
        process.put("process_id", "process:" + saved.run().runId() + ":workflow:" + action);
        process.put("reference_id", action); process.put("type", "workflow"); process.put("ordinal", 0);
        process.put("actor_agent_id", text(value, "agentId")); process.put("status", status);
        process.put("started_at", saved.occurredAt().toString());
        if (finished) process.put("finished_at", saved.occurredAt().toString());
        process.put("presentation", Map.of("title", "工作流调用", "summary", text(value, "summary"), "results", List.of(), "references", List.of()));
        sink.sendRuntime(finished ? "process.finished" : "process.started", process, saved.run().runId());
    }

    /** 模型过程开始来自派发登记，结束来自真实结果；不把派发等同于首token或生成成功。 */
    private void modelProcess(ExecutionStore.SavedEvent saved, JsonNode value, Map<String, Object> base,
            SessionOutboxChatEventSink sink, boolean finished) {
        String action = text(value, "actionId");
        String status = "running";
        String summary = "模型调用已登记";
        String event = "process.started";
        if (finished) {
            status = switch (text(value, "status")) {
                case "SUCCEEDED" -> "succeeded";
                case "FAILED" -> "failed";
                case "UNKNOWN" -> "outcome_unknown";
                default -> throw new IllegalArgumentException("execution_model_result_status_invalid");
            };
            summary = text(value, "summary");
            if (guard.capture().rejects(summary)) summary = PublicOutputGuard.SAFE_MESSAGE;
            event = "process.finished";
        } else {
            if (!"DISPATCHED".equals(text(value, "status"))) throw new IllegalArgumentException("execution_model_start_status_invalid");
        }
        var process = new LinkedHashMap<>(base);
        process.put("process_id", "process:" + saved.run().runId() + ":model:" + action);
        process.put("reference_id", action); process.put("type", "reasoning"); process.put("ordinal", 0);
        process.put("actor_agent_id", text(value, "agentId")); process.put("status", status);
        process.put("started_at", saved.occurredAt().toString());
        if (finished) process.put("finished_at", saved.occurredAt().toString());
        process.put("presentation", Map.of("title", "模型调用", "summary", summary, "results", List.of(), "references", List.of()));
        sink.sendRuntime(event, process, saved.run().runId());
    }

    /** 事件类型和schema共同限定版本，不接受同名的不同正文协议。 */
    private static boolean matches(ExecutionStore.Event event, String type, String schema) {
        return type.equals(event.type()) && schema.equals(event.data().schemaId());
    }

    /** 本轮公开表单仅消费已批准的标准 Tool elicitation 方法。 */
    private static void requireElicitation(JsonNode request) {
        if (!"elicitation/create".equals(text(request, "method")))
            throw new IllegalArgumentException("execution_wait_method_unsupported");
    }

    /** 原等待仍是执行权威；公开拒绝不能伪造一次 Run 失败或泄露原表单。 */
    private void rejectedInteraction(ExecutionStore.SavedEvent saved, Map<String, Object> base,
            SessionOutboxChatEventSink sink) {
        var rejected = new LinkedHashMap<>(base);
        rejected.put("reason", PublicOutputGuard.REJECTION_CODE);
        rejected.put("message", PublicOutputGuard.SAFE_MESSAGE);
        sink.sendRuntime("interaction.rejected", rejected, saved.run().runId());
    }

    /** 不经过浮点数解析或宽松转换，保持高位等待代次准确。 */
    private static String decimal(JsonNode value, String field) {
        var result = text(value, field);
        if (!result.matches("[1-9][0-9]*")) throw new IllegalArgumentException("execution_wait_generation_invalid");
        return result;
    }

    /** 强制内部事实中的必填文本类型，不把未知或缺失值变成可公开默认值。 */
    private static String text(JsonNode value, String field) {
        var text = value.get(field);
        if (text == null || !text.isTextual() || text.textValue().isBlank()) throw new IllegalArgumentException("execution_public_event_invalid");
        return text.textValue();
    }
}
