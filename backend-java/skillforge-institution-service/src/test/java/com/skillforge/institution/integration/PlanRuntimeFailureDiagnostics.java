package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.institution.runtime.TeamRuntimeBridge;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionLogPort;
import java.util.*;

/** 仅失败时读取原 Run 持久事实；字段白名单避免模型请求、工具参数和供应商凭据进入断言日志。 */
final class PlanRuntimeFailureDiagnostics {
    private PlanRuntimeFailureDiagnostics() { }
    static String milestones(SessionLogPort log, SessionOwner owner, RunRef run) {
        return "; persisted milestones=" + log.readAfter(new SessionLogPort.ReadSessionLog(owner, 0, 1000)).stream()
                .filter(e -> e.runRef().equals(Optional.of(run)))
                .filter(e -> Set.of("plan.step.finished", "run.completed").contains(e.eventType()))
                .map(e -> e.sessionSequence() + "@" + e.occurredAt() + ":" + e.eventType()).toList();
    }
    static String describe(TeamRuntimeBridge runtime, SessionLogPort log, ObjectMapper json,
            SessionOwner owner, RunRef run) {
        try {
            String status = runtime.fact(owner, run).map(r -> r.status().name()).orElse("MISSING");
            var events = log.readAfter(new SessionLogPort.ReadSessionLog(owner, 0, 1000)).stream()
                    .filter(e -> e.runRef().equals(Optional.of(run))).toList();
            var recent = new ArrayList<String>();
            for (var event : events.subList(Math.max(0, events.size() - 35), events.size())) {
                var fields = new ArrayList<String>();
                collect(json.readTree(event.payload().canonicalJson()), "", fields);
                recent.add(event.sessionSequence() + "@" + event.occurredAt() + ":" + event.eventType() + fields);
            }
            return "; persisted run=" + run.runId() + " status=" + status + " events=" + recent;
        } catch (Exception failure) {
            return "; persisted diagnostics unavailable=" + failure.getClass().getSimpleName();
        }
    }
    private static void collect(JsonNode node, String path, List<String> values) {
        if (!node.isObject()) return;
        node.fields().forEachRemaining(field -> {
            var value = field.getValue();
            String key = field.getKey();
            if (Set.of("code", "reason", "reasonCode", "reason_code", "status", "outcome", "action", "task_id", "operation").contains(key)
                    && value.isValueNode()) values.add(path + key + "=" + value.asText());
            else if (value.isObject()) collect(value, path + key + ".", values);
        });
    }
}
