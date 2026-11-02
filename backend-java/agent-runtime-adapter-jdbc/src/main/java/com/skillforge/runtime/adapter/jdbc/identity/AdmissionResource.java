package com.skillforge.runtime.adapter.jdbc.identity;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.InboundEnvelope;
import com.skillforge.runtime.api.RunTarget;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.SourceMessageRef;
import com.skillforge.runtime.api.RunOriginRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** JDBC 身份边界私有协议：完整核验 admission 资源，只向策略交付归属与入口。 */
public record AdmissionResource(SessionOwner owner, AgentId agentId, String channel, String source) {
    private static final String SCHEMA = "skillforge.runtime.session.admission-resource.v2";

    public AdmissionResource {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(agentId, "agentId");
        requireText(channel); requireText(source);
    }

    /** HMAC真实入口使用的完整入站资源；只编码原envelope，不创建执行或调度权。 */
    public static TypedPayload encode(InboundEnvelope value) {
        List<String> target = value.runTarget() instanceof RunTarget.ExistingRun existing
                ? List.of("existing", existing.runRef().tenantId().value(), existing.runRef().runId())
                : List.of("new");
        return new TypedPayload("skillforge.runtime.session.admission-resource.v2",
                json(List.of(value.sessionRef().tenantId().value(), value.sessionTarget().kind().name(),
                    value.sessionTarget().id(), value.agentId().value(), value.sessionRef().sessionId(),
                    OwnerPolicyFields.subject(new SessionOwner(value.sessionRef(),value.subject(),value.sessionTarget())), value.inputId().value(), value.eventId().value(),
                    value.kind().name(), target, value.channel(), value.source(), value.trace().traceId(),
                    value.trace().spanId(), value.trace().rootInputId().value(),
                    value.trace().causedByEventId().map(id -> List.of(id.value())).orElseGet(List::of),
                    value.payload().schemaId(), value.payload().canonicalJson(), value.receivedAt().toString(),
                    value.requester() instanceof RunRequester.EndUser user?"user:"+user.endUserId().value():"system:"+((RunRequester.System)value.requester()).principal().principalId(),
                    value.sourceMessageRef().map(source->source.sessionRef().sessionId()).orElse(""),
                    value.sourceMessageRef().map(SourceMessageRef::messageId).orElse(""),value.originRef().originId())));
    }


    /** 非当前 schema、非规范编码、字段缺失或身份错配均失败关闭，不暴露原输入。 */
    public static Optional<AdmissionResource> parse(TypedPayload resource) {
        if (resource == null || !SCHEMA.equals(resource.schemaId())) return Optional.empty();
        try {
            var reader = new Reader(resource.canonicalJson());
            reader.need('[');
            List<String> fields = new ArrayList<>(23);
            List<String> target = null;
            List<String> cause = null;
            for (int index = 0; index < 23; index++) {
                if (index > 0) reader.need(',');
                if (index == 9) { target = reader.strings(3); fields.add(null); }
                else if (index == 15) { cause = reader.strings(1); fields.add(null); }
                else { fields.add(reader.string()); }
            }
            reader.need(']');
            if (!reader.finished()) throw invalid();
            for(int index=0;index<fields.size();index++) if(fields.get(index)!=null && index!=20 && index!=21)requireText(fields.get(index));
            var tenant = new TenantId(fields.get(0));
            var owner = OwnerPolicyFields.owner(tenant,fields.get(4),fields.get(1),fields.get(2),fields.get(5));
            var requester=RunPolicyFields.requester(tenant,fields.get(19));
            if(owner.subject() instanceof com.skillforge.runtime.api.SessionSubject.User user && requester instanceof RunRequester.EndUser author
                    && !user.endUserId().equals(author.endUserId()))throw invalid();
            if(fields.get(20).isEmpty()!=fields.get(21).isEmpty())throw invalid();
            if(owner.subject() instanceof com.skillforge.runtime.api.SessionSubject.User && !fields.get(20).isEmpty()&&!new SourceMessageRef(new SessionRef(tenant,fields.get(20)),fields.get(21)).sessionRef().equals(owner.sessionRef()))throw invalid();
            new RunOriginRef(tenant,fields.get(22));
            var agentId = new AgentId(fields.get(3));
            new InputId(fields.get(6)); new EventId(fields.get(7));
            InboundKind.valueOf(fields.get(8));
            if (target.size() == 1 && "new".equals(target.getFirst())) {
                // NewRun 不携带运行引用。
            } else if (target.size() == 3 && "existing".equals(target.getFirst())) {
                var run = new RunRef(new TenantId(target.get(1)), target.get(2));
                if (!run.tenantId().equals(tenant)) throw invalid();
            } else throw invalid();
            new TraceEnvelope(fields.get(12), fields.get(13), new InputId(fields.get(14)),
                    cause.isEmpty() ? Optional.empty() : Optional.of(new EventId(cause.getFirst())));
            // Payload 的 canonicalJson 在外层是原文字符串；业务 schema 校验归业务解码器。
            new TypedPayload(fields.get(16), fields.get(17));
            if (!Instant.parse(fields.get(18)).toString().equals(fields.get(18))) throw invalid();
            return Optional.of(new AdmissionResource(owner, agentId, fields.get(10), fields.get(11)));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static void requireText(String text) {
        if (text == null || text.isBlank()) throw invalid();
    }

    static List<String> flatStrings(String json, int size) {
        var reader = new Reader(json);
        var values = reader.strings(size);
        if (values.size() != size || !reader.finished()) throw invalid();
        return List.copyOf(values);
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid_admission_resource");
    }

    /** 只读取协议需要的字符串和一层定长数组，避免不可信输入递归或无限节点分配。 */
    private static final class Reader {
        private final String input;
        private int position;
        private Reader(String input) { this.input = Objects.requireNonNull(input); }
        private boolean finished() { return position == input.length(); }
        private boolean take(char value) {
            if (!finished() && input.charAt(position) == value) { position++; return true; }
            return false;
        }
        private void need(char value) { if (!take(value)) throw invalid(); }
        private List<String> strings(int maximum) {
            need('[');
            List<String> values = new ArrayList<>(maximum);
            if (take(']')) return values;
            do {
                if (values.size() == maximum) throw invalid();
                values.add(string());
            } while (take(','));
            need(']'); return values;
        }
        private String string() {
            need('"');
            var out = new StringBuilder();
            while (!finished()) {
                char value = input.charAt(position++);
                if (value == '"') return out.toString();
                if (value < 32 || Character.isLowSurrogate(value)) throw invalid();
                if (Character.isHighSurrogate(value)) {
                    if (finished() || !Character.isLowSurrogate(input.charAt(position))) throw invalid();
                    out.append(value).append(input.charAt(position++));
                } else if (value == '\\') {
                    if (finished()) throw invalid();
                    char escaped = input.charAt(position++);
                    int common = "btnfr".indexOf(escaped);
                    if (common >= 0) out.append("\b\t\n\f\r".charAt(common));
                    else if (escaped == '"' || escaped == '\\') out.append(escaped);
                    else if (escaped == 'u') {
                        // Session canonical writer 仅将无短转义的控制字符写为小写 \\u00xx。
                        if (position + 4 > input.length()) throw invalid();
                        String hex = input.substring(position, position + 4);
                        if (!hex.matches("00[01][0-9a-f]")) throw invalid();
                        char control = (char) Integer.parseInt(hex, 16);
                        if ("\b\t\n\f\r".indexOf(control) >= 0) throw invalid();
                        out.append(control); position += 4;
                    } else throw invalid();
                } else out.append(value);
            }
            throw invalid();
        }
    }
    private static String json(Object value) {
        if (value instanceof List<?> values) return values.stream().map(AdmissionResource::json).collect(Collectors.joining(",", "[", "]"));
        if (!(value instanceof String text)) throw new IllegalArgumentException("unsupported_policy_resource");
        var out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 32) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
