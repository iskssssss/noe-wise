package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.api.port.SessionLogPort.AppendSessionEvent;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 展示/诊断墓碑的私有 v2 全请求摘要，与数据库删除守卫使用相同长度前缀格式。
 * 每字段先写 UTF-8 字节数的网络序 int32，再写原字节；NULL 写 -1，空串写 0。
 * 固定顺序包含域、tenant/session/targetKind/targetId/endUser/agent/input/run/execution/action/delegation、
 * eventId/type/version/fingerprint、trace/span/rootInput/cause、payloadSchema/完整canonicalJson、UTC秒/纳秒/sequence。
 * 不使用反射类名或当前结果重建正文，逻辑原请求与原sequence的任何差异均不能重放。
 */
final class SessionHistoryDigest {
    static final String DOMAIN = "skillforge.runtime.session.event-tombstone.v2";
    private SessionHistoryDigest() { }

    /** 摘要输入必须是已经精确恢复的完整 Append，sequence 是原已确认序号。 */
    static String digest(AppendSessionEvent command, long sequence) {
        Objects.requireNonNull(command);
        if (sequence <= 0) throw new IllegalArgumentException("session_history_digest_sequence_invalid");
        var target = command.target();
        var event = command.event();
        var owner = target.owner();
        String[] fields = { DOMAIN, owner.sessionRef().tenantId().value(), owner.sessionRef().sessionId(),
                owner.target().kind().name(), owner.target().id(), Long.toString(owner.requireUser().value()),
                target.agentId().value(), target.inputId().value(), target.runRef().map(value -> value.runId()).orElse(null),
                target.executionRef().map(value -> value.executionId()).orElse(null),
                target.actionRef().map(value -> value.actionId()).orElse(null),
                target.delegationRef().map(value -> value.delegationId()).orElse(null), event.eventId().value(),
                event.eventType(), event.eventVersion(), event.eventFingerprint(), event.trace().traceId(),
                event.trace().spanId(), event.trace().rootInputId().value(),
                event.trace().causedByEventId().map(value -> value.value()).orElse(null), event.payload().schemaId(),
                event.payload().canonicalJson(), Long.toString(event.occurredAt().getEpochSecond()),
                Integer.toString(event.occurredAt().getNano()), Long.toString(sequence) };
        try {
            var buffer = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(buffer)) {
                for (String field : fields) {
                    if (field == null) { output.writeInt(-1); continue; }
                    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(bytes.length); output.write(bytes);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException failure) { throw new IllegalStateException("session_history_digest_failure", failure); }
    }
}
