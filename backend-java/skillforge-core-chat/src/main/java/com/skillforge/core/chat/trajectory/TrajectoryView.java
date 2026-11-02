package com.skillforge.core.chat.trajectory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.chat.observation.SessionObservationView;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 两个后台共用的只读执行语义；正文只通过独立授权内容入口读取。 */
public final class TrajectoryView {
    private TrajectoryView() { }

    public enum Kind { ENTRY, AGENT, STEP, LLM, TOOL, RETRIEVAL, MEMORY, GUARDRAIL, COMPACTION, WORKFLOW }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("tenant_name") String tenantName,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("root_input_id") String rootInputId,
            @JsonProperty("agent_id") String agentId,
            @JsonProperty("agent_name") String agentName,
            String input, String output, String status,
            @JsonProperty("answer_status") String answerStatus,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("finished_at") Instant finishedAt,
            @JsonProperty("duration_ms") String durationMs,
            @JsonProperty("trace_ids") List<String> traceIds,
            List<String> models, List<String> tools,
            @JsonProperty("step_count") int stepCount,
            @JsonProperty("model_calls") int modelCalls,
            @JsonProperty("tool_calls") int toolCalls,
            SessionObservationView.TokenTotals totals,
            SessionObservationView.Coverage coverage) {
        public Summary { traceIds = List.copyOf(traceIds); models = List.copyOf(models); tools = List.copyOf(tools); }
    }

    public record ListPage(List<Summary> items, int page,
                           @JsonProperty("page_size") int pageSize, long total,
                           @JsonProperty("read_at") Instant readAt) {
        public ListPage { items = List.copyOf(items); }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(Summary summary, List<SessionObservationView.Message> messages,
                         SessionObservationView.Usage usage,
                         @JsonProperty("usage_summary") UsageSummary usageSummary, String snapshot,
                         @JsonProperty("read_at") Instant readAt, Termination termination) {
        public Detail { messages = List.copyOf(messages); }
    }

    /** 只展示同一主 Run 已保存的终态和预算拒绝，不从当前 Agent 配置重建历史。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Termination(@JsonProperty("run_id") String runId,
            @JsonProperty("reason_code") String reasonCode,
            @JsonProperty("business_model_attempts") String businessModelAttempts,
            @JsonProperty("tool_attempts") String toolAttempts,
            @JsonProperty("auxiliary_model_attempts") String auxiliaryModelAttempts,
            @JsonProperty("budget_failure") BudgetFailure budgetFailure) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BudgetFailure(@JsonProperty("schema_id") String schemaId, String code, String phase,
            List<BudgetShortfall> shortfalls,
            @JsonProperty("time_calculation") BudgetTimeCalculation timeCalculation) {
        public BudgetFailure { shortfalls = List.copyOf(shortfalls); }
    }

    public record BudgetShortfall(String scope, String dimension, String unit, String required,
            String available, String deficit, String consumed, String reserved, String limit) { }

    public record BudgetTimeCalculation(@JsonProperty("model_calls") String modelCalls,
            @JsonProperty("model_timeout_ms") String modelTimeoutMillis,
            @JsonProperty("model_window_ms") String modelWindowMillis,
            @JsonProperty("local_processing_ms") String localProcessingMillis,
            @JsonProperty("required_ms") String requiredMillis) { }

    /** 金额沿账本已确认人民币分精确传输，可含亚分和负调整；空字典不代表已知零。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UsageSummary(int calls, String tokens,
            @JsonProperty("prompt_tokens") String promptTokens,
            @JsonProperty("completion_tokens") String completionTokens,
            @JsonProperty("amount_by_currency") Map<String, String> amountByCurrency,
            @JsonProperty("unknown_usage_calls") int unknownUsageCalls,
            @JsonProperty("cost_coverage") CostCoverage costCoverage) {
        public UsageSummary { amountByCurrency = Map.copyOf(amountByCurrency); }
    }

    public enum CostStatus { NO_COSTS, COMPLETE, PARTIAL, UNKNOWN }

    public record CostCoverage(CostStatus status,
            @JsonProperty("total_events") int totalEvents,
            @JsonProperty("priced_events") int pricedEvents,
            @JsonProperty("unconfirmed_events") int unconfirmedEvents,
            @JsonProperty("missing_model_attempts") int missingModelAttempts) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ContentReference(String ref, String availability) {
        public static ContentReference missing() { return new ContentReference(null, "NOT_RECORDED"); }
        public static ContentReference restricted() { return new ContentReference(null, "REDACTED"); }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Attempt(
            @JsonProperty("attempt_id") String attemptId,
            String status, String provider, String model,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("finished_at") Instant finishedAt,
            @JsonProperty("duration_ms") String durationMs,
            @JsonProperty("report_status") String reportStatus,
            @JsonProperty("diagnostic_available") boolean diagnosticAvailable,
            SessionObservationView.TokenTotals totals,
            ContentReference input, ContentReference output) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ObservationNode(
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("parent_node_id") String parentNodeId,
            Kind kind, String name, String status,
            @JsonProperty("source_session_id") String sourceSessionId,
            @JsonProperty("root_input_id") String rootInputId,
            @JsonProperty("input_id") String inputId,
            @JsonProperty("run_id") String runId,
            @JsonProperty("action_id") String actionId,
            @JsonProperty("step_id") String stepId,
            String iteration,
            @JsonProperty("trace_id") String traceId,
            @JsonProperty("span_id") String spanId,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("finished_at") Instant finishedAt,
            @JsonProperty("duration_ms") String durationMs,
            String sequence, String summary,
            ContentReference input, ContentReference output,
            @JsonProperty("source_event_ids") List<String> sourceEventIds,
            List<Attempt> attempts) {
        public ObservationNode { sourceEventIds = List.copyOf(sourceEventIds); attempts = List.copyOf(attempts); }
    }

    public record ObservationPage(List<ObservationNode> items, List<ObservationNode> ancestors,
                                  @JsonProperty("next_cursor") String nextCursor,
                                  String snapshot, String coverage,
                                  @JsonProperty("read_at") Instant readAt) {
        public ObservationPage { items = List.copyOf(items); ancestors = List.copyOf(ancestors); }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Content(@JsonProperty("content_ref") String contentRef,
                          String availability, @JsonProperty("schema_id") String schemaId,
                          Object value) { }
}
