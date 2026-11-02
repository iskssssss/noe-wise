package com.skillforge.core.chat.observation;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.*;

/** 授权候选只恢复typed已保存公开事件；没有旧事件来源树或RunOutcome重建。 */
final class SessionObservationPublicFacts {
    private SessionObservationPublicFacts() { }
    record SessionKey(long tenantId, String sessionId) { }
    record ReadScope(long tenantId, String sessionId, List<String> roots,
                     long eventUpperBound, long messageUpperBound) {
        ReadScope { roots = List.copyOf(roots); }
        SessionKey key() { return new SessionKey(tenantId, sessionId); }
    }
    record Facts(Optional<SessionOwner> owner, List<ExecutionStore.SavedEvent> events) {
        Facts {
            Objects.requireNonNull(owner); events = List.copyOf(events);
            if (owner.isEmpty() != events.isEmpty()) throw new IllegalArgumentException("public_answer_owner_missing");
        }
        static Facts empty() { return new Facts(Optional.empty(), List.of()); }
    }

    /** 保留原Run、提交版本、Session事件游标与纳秒时间；每个Session只接受同一完整owner。 */
    static Map<SessionKey, Facts> read(List<Map<String, Object>> rows) {
        Map<SessionKey, List<ExecutionStore.SavedEvent>> events = new LinkedHashMap<>();
        Map<SessionKey, SessionOwner> owners = new LinkedHashMap<>();
        for (var row : rows) {
            var tenant = new TenantId(text(row, "tenant_id"));
            var key = new SessionKey(Long.parseLong(tenant.value()), text(row, "session_id"));
            var owner = SessionOwner.user(new SessionRef(tenant, key.sessionId()),
                    new SessionTarget(SessionTarget.Kind.valueOf(text(row, "target_kind")), text(row, "target_id")),
                    new EndUserId(number(row, "end_user_id")));
            var prior = owners.putIfAbsent(key, owner);
            if (prior != null && !prior.equals(owner)) throw new IllegalArgumentException("public_answer_owner_mismatch");
            var event = new ExecutionStore.SavedEvent(new RunRef(tenant, text(row, "run_id")), number(row, "event_sequence"),
                    number(row, "revision"), Instant.ofEpochSecond(number(row, "occurred_second"), number(row, "occurred_nano")),
                    new ExecutionStore.Event(new EventId(text(row, "event_id")), text(row, "type"),
                            new TypedPayload(text(row, "schema_id"), text(row, "canonical_json"))));
            events.computeIfAbsent(key, ignored -> new ArrayList<>()).add(event);
        }
        Map<SessionKey, Facts> result = new LinkedHashMap<>();
        events.forEach((key, value) -> result.put(key, new Facts(Optional.of(owners.get(key)), value)));
        return Map.copyOf(result);
    }
    private static String text(Map<String, Object> row, String column) {
        Object value = row.get(column); if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("public_answer_source_incomplete");
        return value.toString();
    }
    private static long number(Map<String, Object> row, String column) {
        if (!(row.get(column) instanceof Number value)) throw new IllegalArgumentException("public_answer_source_incomplete");
        return value.longValue();
    }
}
