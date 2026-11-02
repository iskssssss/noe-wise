package com.skillforge.core.workflow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.workflow.execution.ControlledWorkflowExecution.Boundary;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.runtime.adapter.jdbc.session.JdbcWorkflowPolicy;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionLookup;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.ExecutionRecord;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowQuery;
import com.skillforge.runtime.session.spi.WorkflowInteractionView;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.sql.DataSource;

/** 从 SDK Workflow 权威账本投影等待用户输入所需的公开卡片。 */
public final class SdkWorkflowInteractionView implements WorkflowInteractionView {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Predicate<WorkflowQuery> authorization;
    private final Function<ExecutionLookup, Optional<ExecutionRecord>> records;

    public SdkWorkflowInteractionView(DataSource source, JdbcWorkflowPolicy policy) {
        var ledger = new JdbcExecutionLedgerPort(Objects.requireNonNull(source));
        authorization = Objects.requireNonNull(policy)::authorizeQuery;
        records = ledger::find;
    }

    SdkWorkflowInteractionView(Predicate<WorkflowQuery> authorization,
            Function<ExecutionLookup, Optional<ExecutionRecord>> records) {
        this.authorization = Objects.requireNonNull(authorization);
        this.records = Objects.requireNonNull(records);
    }

    @Override public Optional<Pending> find(WorkflowQuery query) {
        Objects.requireNonNull(query);
        // IAM 与完整 Execution owner 检查必须先于账本内容读取。
        if (!authorization.test(query)) {
            return Optional.empty();
        }
        var lookup = new ExecutionLookup(query.identity(), query.executionRef());
        var first = records.apply(lookup);
        if (first.isEmpty()) {
            return Optional.empty();
        }
        var projected = project(query, first.orElseThrow());
        if (projected.isEmpty()) {
            return Optional.empty();
        }
        // 卡片读取期间发生 resume/cancel 时丢弃旧快照。
        return records.apply(lookup).equals(first) ? projected : Optional.empty();
    }

    private Optional<Pending> project(WorkflowQuery query, ExecutionRecord record) {
        if (!record.executionRef().equals(query.executionRef())
                || !record.owner().owner().equals(query.identity().owner())
                || !record.state().isWaiting()
                || record.cancelRequestId().isPresent()
                || record.waiting().isEmpty()) {
            return Optional.empty();
        }
        try {
            var state = WorkflowCheckpointCodec.decode(record.checkpoint());
            var engine = state.engine().orElse(null);
            if (engine == null || engine.boundary() != Boundary.WAITING) {
                return Optional.empty();
            }
            Object publicCard = record.state() == WorkflowExecutionState.WAITING_HITL
                    ? engine.card() : null;
            if (publicCard == null) return Optional.empty();
            var wait = record.waiting().orElseThrow();
            var card = new TypedPayload("application/json",
                    JSON.writeValueAsString(sorted(JSON.valueToTree(publicCard))));
            return Optional.of(new Pending(record.owner().owner(), record.owner().runRef(),
                    record.executionRef(), record.executionRevision(), wait.suspendRef(),
                    wait.actionKey(), card));
        } catch (Exception invalidCheckpoint) {
            return Optional.empty();
        }
    }

    private static JsonNode sorted(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            var names = new TreeSet<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.forEach(name -> result.set(name, sorted(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            value.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return value;
    }
}
