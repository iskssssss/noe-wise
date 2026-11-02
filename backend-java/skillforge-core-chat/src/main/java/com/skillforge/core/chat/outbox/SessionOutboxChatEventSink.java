package com.skillforge.core.chat.outbox;

import com.skillforge.core.chat.contract.ChatEventSink;
import com.skillforge.core.chat.constant.ChatConstants;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionOutboxOutput;
import com.skillforge.core.shared.tracing.TraceContext;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 将单轮可见事件冻结到 Session Outbox，订阅者只从已提交 Outbox 按游标读取。
 *
 * <p>同一输出的事件身份由已提交源事件与投影身份确定。输入被重放时，Outbox 的
 * tenant+eventId 幂等约束会复用原事件；在线 sink 仅传递连接与临时错误等控制事件，
 * 不另走持久输出的推送路径。</p>
 */
public class SessionOutboxChatEventSink implements ChatEventSink {

    private final Consumer<SessionOutboxOutput> outputs;
    private final String tenantId;
    private final String sessionId;
    private final TraceContext trace;
    private final ChatEventSink liveSink;

    public SessionOutboxChatEventSink(
            SessionMailboxService mailbox,
            String tenantId,
            String sessionId,
            TraceContext trace,
            ChatEventSink liveSink) {
        this(output -> {
            if (output.runtimeRunId() == null) {
                mailbox.appendOutput(tenantId, sessionId, output.eventId(), output.eventType(), output.payload(), output.trace());
            } else {
                mailbox.appendOutput(tenantId, sessionId, output.eventId(), output.eventType(), output.payload(), output.trace(), output.runtimeRunId());
            }
        }, tenantId, sessionId, trace, liveSink);
    }

    /** 投影只产生本次事务的命令，不返回持久回执；真正写入由 Mailbox 统一执行。 */
    public static SessionOutboxChatEventSink collecting(String tenantId, String sessionId, TraceContext trace,
            Consumer<SessionOutboxOutput> outputs) {
        return new SessionOutboxChatEventSink(outputs, tenantId, sessionId, trace, null);
    }

    /** 普通单条与投影批次共用事件身份和信封生成，区别仅在显式命令接收方。 */
    private SessionOutboxChatEventSink(Consumer<SessionOutboxOutput> outputs, String tenantId, String sessionId,
            TraceContext trace, ChatEventSink liveSink) {
        this.outputs = Objects.requireNonNull(outputs);
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.trace = trace;
        this.liveSink = liveSink;
    }

    /** 保持同一会话和在线出口，仅用已提交源事件的 trace 持久化后续投影。 */
    public SessionOutboxChatEventSink sourceBound(TraceContext sourceTrace) {
        return new SessionOutboxChatEventSink(outputs, tenantId, sessionId,
                sourceTrace, liveSink);
    }

    @Override
    public void send(String event, Map<String, Object> data) {
        if (ChatConstants.EVENT_CONNECTED.equals(event) || ChatConstants.EVENT_ERROR.equals(event)
                || "collaboration.updated".equals(event) || "history.retired".equals(event)) {
            // 连接状态和可重新查询的快照不是执行事实，不消费 Outbox 序号。
            if (liveSink != null) liveSink.send(event, data);
            return;
        }
        persist(event, data, null);
    }

    /** Runtime 投影已核验 Run 归属，持久化时直接写入关联列。 */
    @Override
    public void sendRuntime(String event, Map<String, Object> data, String runtimeRunId) {
        if (runtimeRunId == null || runtimeRunId.isBlank()) {
            throw new IllegalArgumentException("Runtime output must include runtimeRunId");
        }
        persist(event, data, runtimeRunId);
    }

    private void persist(String event, Map<String, Object> data, String runtimeRunId) {
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("Session output event must not be blank");
        }
        Map<String, Object> outputData = new LinkedHashMap<>();
        if (data != null) {
            outputData.putAll(data);
        }
        outputData.putIfAbsent(ChatConstants.SESSION_ID, sessionId);
        // 由源事实和投影身份派生，重启/重放不会依赖本地计数器。
        String source = text(outputData.get("runtime_event_id"));
        if (source.isBlank()) source = runtimeRunId == null ? trace.inputId() : runtimeRunId + ":" + trace.inputId();
        String identity = sessionId + "\n" + source + "\n" + event + "\n"
                + text(outputData.get("process_id")) + "\n" + text(outputData.get("message_id"));
        String eventId = "output:" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        if (runtimeRunId != null) outputData.put("run_id", runtimeRunId);
        outputs.accept(new SessionOutboxOutput(eventId, event, Map.of("event", event, "data", outputData), trace, runtimeRunId));
    }

    @Override
    public void error(String message, String errorCode, boolean retryable) {
        send(ChatConstants.EVENT_ERROR, Map.of(
                "error_code", errorCode == null || errorCode.isBlank()
                        ? "CHAT_EXECUTION_FAILED" : errorCode,
                "message", message == null ? "对话执行失败" : message,
                "retryable", retryable));
        complete();
    }

    @Override
    public void complete() {
        if (liveSink != null) {
            liveSink.complete();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
