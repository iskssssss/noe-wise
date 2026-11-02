package com.skillforge.institution.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 真实派发的可撤销正文预览；只写展示 Outbox，不写消息历史、模型结果或执行状态。 */
public final class AgentRuntimeBodyPreview {
    public static final String EVENT = "message.preview";
    private static final TypeReference<Map<String, Object>> PAYLOAD = new TypeReference<>() { };
    private final SessionMailboxService mailbox;
    private final PublicOutputGuard guard;
    private final ObjectMapper json;
    private final int maximumCharacters;
    private final long publicationIntervalNanos;
    private final LongSupplier monotonicTime;

    public AgentRuntimeBodyPreview(SessionMailboxService mailbox, PublicOutputGuard guard, ObjectMapper json, int maximumCharacters,
            Duration publicationInterval, LongSupplier monotonicTime) {
        this.mailbox = Objects.requireNonNull(mailbox); this.guard = Objects.requireNonNull(guard);
        this.json = Objects.requireNonNull(json);
        if (maximumCharacters <= 0) throw new IllegalArgumentException("body_preview_limit_invalid");
        this.maximumCharacters = maximumCharacters;
        if (Objects.requireNonNull(publicationInterval).isNegative() || publicationInterval.isZero())
            throw new IllegalArgumentException("body_preview_interval_invalid");
        this.publicationIntervalNanos = publicationInterval.toNanos();
        this.monotonicTime = Objects.requireNonNull(monotonicTime);
    }

    /** 仅由 Agent capability 的实际 Registered 调用建立，普通 Workflow 模型节点不进入此出口。 */
    public Accumulator begin(ExecutionStore.DispatchResult.Registered registered, ExecutionStore.Prepared prepared) {
        return new Accumulator(registered.run().identity(), registered.attempt().id(), prepared.ordinal());
    }

    public static String messageId(String runId) { return "assistant-preview:" + runId; }

    /** 固定公开形状。content 是本 attempt 的累计安全前缀，跨 attempt 必须整体替换。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Preview(String previewMessageId, String sessionId, String runId, String rootInputId,
            String inputId, String attemptId, long ordinal, long previewSequence,
            String role, String content, String state, boolean authoritative) { }
    private record Envelope(String event, Preview data) { }

    public final class Accumulator implements Consumer<String> {
        private final RunIdentity run;
        private final String attemptId;
        private final long ordinal;
        private PublicOutputGuard.Scope scope;
        private final StringBuilder accumulated = new StringBuilder();
        private long sequence;
        private int published;
        private boolean rejected;
        private long lastInspectionNanos;
        private int inspectedCharacters;
        private boolean inspected;

        private Accumulator(RunIdentity run, String attemptId, long ordinal) {
            this.run = run; this.attemptId = attemptId; this.ordinal = ordinal;
        }

        /** 只收束展示；持久投影不可用不覆盖已经取得的模型结果，也不取得重试权。 */
        public void unresolved(String state) {
            if (sequence == 0 || rejected) return;
            rejected = true;
            try { emit("", state); }
            catch (RuntimeException unavailable) {
                System.getLogger(AgentRuntimeBodyPreview.class.getName()).log(System.Logger.Level.WARNING,
                        "model_body_preview_close_unavailable attempt={0}", attemptId);
            }
        }

        @Override public void accept(String delta) {
            if (rejected) return;
            try {
                if (delta.length() > maximumCharacters - accumulated.length()) {
                    rejected = true;
                    emit("", "unavailable");
                    return;
                }
                accumulated.append(delta);
                long now = monotonicTime.getAsLong();
                if (!inspected || now - lastInspectionNanos >= publicationIntervalNanos) publishSafePrefix(now);
            } catch (RuntimeException unavailable) {
                // adapter 记录本次观察不可用；此处停止所有后续预览，包括模型成功后的末次 flush。
                rejected = true;
                throw unavailable;
            }
        }

        /** 模型真实成功后只补安全前缀，不释放安全尾部，不把候选晋升为正式回答。 */
        public void flush() {
            if (rejected || accumulated.length() == inspectedCharacters) return;
            try { publishSafePrefix(monotonicTime.getAsLong()); }
            catch (RuntimeException unavailable) {
                rejected = true;
                System.getLogger(AgentRuntimeBodyPreview.class.getName()).log(System.Logger.Level.WARNING,
                        "model_body_preview_flush_unavailable attempt={0}", attemptId);
            }
        }

        private void publishSafePrefix(long now) {
            // 只有披露点才扫描累计全文；合并期间没有任何未检查正文进入 Outbox。
            if (scope == null) scope = guard.capture(run);
            var checked = scope.streamingContent(accumulated.toString());
            inspected = true;
            lastInspectionNanos = now;
            inspectedCharacters = accumulated.length();
            if (checked.rejection().isPresent()) {
                rejected = true;
                emit("", "rejected");
                return;
            }
            int safe = checked.safePrefixCharacters();
            // 新 attempt 首个真实正文片段即发布空安全快照，避免继续显示上一轮候选。
            if (safe <= published && sequence != 0) return;
            emit(accumulated.substring(0, safe), "streaming");
            published = safe;
        }

        private void emit(String content, String state) {
            var owner = run.owner().sessionRef();
            var trace = run.trace();
            String eventId = "body-preview:" + run.runRef().runId() + ":" + attemptId + ":" + ++sequence;
            var data = new Preview(messageId(run.runRef().runId()), owner.sessionId(), run.runRef().runId(),
                    trace.rootInputId().value(), run.causationInputId().value(), attemptId, ordinal, sequence,
                    "assistant", content, state, false);
            var context = new TraceContext(run.causationInputId().value(), trace.rootInputId().value(), trace.traceId(), trace.spanId(),
                    eventId, trace.causedByEventId().map(value -> value.value()).orElse(null));
            TenantContext.callWithTenant(owner.tenantId().value(), () -> mailbox.appendOutput(owner.tenantId().value(),
                    owner.sessionId(), eventId, EVENT, json.convertValue(new Envelope(EVENT, data), PAYLOAD), context, run.runRef().runId()));
        }
    }
}
