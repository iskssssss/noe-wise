package com.skillforge.core.foundation.integration.runtime.identity;

import com.skillforge.runtime.adapter.jdbc.identity.OwnerPolicyFields;
import com.skillforge.runtime.adapter.jdbc.identity.PolicyResourceFields;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.*;
import java.util.stream.Collectors;

/** façade 读取与会话控制授权资源的固定数组协议；完整 owner 来自已验证认证事实。 */
final class AgentRuntimeAccessResource {
    static final String SCHEMA = "skillforge.runtime.facade-access.v1";
    private static final Set<String> READ = Set.of(
            "runtime.run.query", "runtime.session.query", "runtime.events.read");
    private AgentRuntimeAccessResource() { }

    static Optional<SessionOwner> owner(String action, TypedPayload payload) {
        if ((!READ.contains(action) && !"runtime.cancel".equals(action) && !"runtime.session.human-assistance".equals(action) && !"runtime.session.close".equals(action) && !"runtime.plan.select".equals(action) && !"runtime.run.steer".equals(action))
                || payload == null || !SCHEMA.equals(payload.schemaId())) return Optional.empty();
        try {
            var fields = PolicyResourceFields.parse(payload.canonicalJson(), 10);
            if (!json(fields).equals(payload.canonicalJson()) || !fields.get(5).equals(action)) return Optional.empty();
            var tenant = new TenantId(fields.get(0));
            var target = new SessionTarget(SessionTarget.Kind.valueOf(fields.get(1)), fields.get(2));
            var owner = OwnerPolicyFields.owner(tenant,fields.get(3),fields.get(1),fields.get(2),fields.get(4));
            long cursor = Long.parseLong(fields.get(8));
            if (!Long.toString(cursor).equals(fields.get(8)) || cursor < 0) return Optional.empty();
            if ("runtime.run.query".equals(action) || "runtime.run.steer".equals(action)) {
                if (!"run".equals(fields.get(6)) || cursor != 0) return Optional.empty();
                new RunRef(tenant, fields.get(7)); new RequestId(fields.get(9));
            } else if (("runtime.session.query".equals(action) || "runtime.session.human-assistance".equals(action) || "runtime.session.close".equals(action) || "runtime.plan.select".equals(action))) {
                if (!"session".equals(fields.get(6)) || !owner.sessionRef().sessionId().equals(fields.get(7)) || cursor != 0)
                    return Optional.empty();
                new RequestId(fields.get(9));
            } else if ("runtime.events.read".equals(action)) {
                if (!"session".equals(fields.get(6)) || !owner.sessionRef().sessionId().equals(fields.get(7)))
                    return Optional.empty();
                new RequestId(fields.get(9));
            } else {
                if (!"run".equals(fields.get(6)) || cursor <= 0) return Optional.empty();
                new RunRef(tenant, fields.get(7)); new InputId(fields.get(9));
            }
            return Optional.of(owner);
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    static boolean readAction(String action) { return READ.contains(action); }
    private static String json(List<String> values) {
        return values.stream().map(AgentRuntimeAccessResource::quote)
                .collect(Collectors.joining(",", "[", "]"));
    }
    private static String quote(String value) {
        var out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b"); case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n"); case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
