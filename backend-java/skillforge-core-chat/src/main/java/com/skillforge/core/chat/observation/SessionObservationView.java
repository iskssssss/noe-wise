package com.skillforge.core.chat.observation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;

/** 平台会话只读观测的 HTTP 投影；字段名在组件上显式冻结为 snake_case。 */
public final class SessionObservationView {
    private SessionObservationView() {
    }

    public record SessionPage(
            List<SessionSummary> items,
            int page,
            @JsonProperty("page_size") int pageSize,
            long total,
            @JsonProperty("read_at") Instant readAt) {
        public SessionPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record SessionSummary(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("tenant_name") String tenantName,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("user_name") String userName,
            @JsonProperty("target_type") String targetType,
            @JsonProperty("agent_id") @JsonInclude(JsonInclude.Include.ALWAYS) String agentId,
            @JsonProperty("agent_name") @JsonInclude(JsonInclude.Include.ALWAYS) String agentName,
            @JsonProperty("team_id") @JsonInclude(JsonInclude.Include.ALWAYS) String teamId,
            @JsonProperty("team_name") @JsonInclude(JsonInclude.Include.ALWAYS) String teamName,
            @JsonProperty("channel_id") @JsonInclude(JsonInclude.Include.ALWAYS) String channelId,
            @JsonProperty("channel_type") @JsonInclude(JsonInclude.Include.ALWAYS) String channelType,
            @JsonProperty("channel_name") @JsonInclude(JsonInclude.Include.ALWAYS) String channelName,
            @JsonProperty("answer_status") String answerStatus,
            @JsonProperty("execution_status") String executionStatus,
            @JsonInclude(JsonInclude.Include.ALWAYS) String summary,
            @JsonProperty("message_count") int messageCount,
            @JsonProperty("root_input_count") int rootInputCount,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("last_activity_at") Instant lastActivityAt) {
    }

    public record SessionDetail(
            @JsonUnwrapped SessionSummary session,
            @JsonProperty("active_execution_id") @JsonInclude(JsonInclude.Include.ALWAYS) String activeExecutionId,
            @JsonProperty("expired_at") @JsonInclude(JsonInclude.Include.ALWAYS) Instant expiredAt,
            @JsonProperty("first_message_at") @JsonInclude(JsonInclude.Include.ALWAYS) Instant firstMessageAt,
            @JsonProperty("last_message_at") @JsonInclude(JsonInclude.Include.ALWAYS) Instant lastMessageAt,
            @JsonProperty("usage_snapshot") TokenTotals usageSnapshot) {
    }

    public record RoundPage(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("session_id") String sessionId,
            List<Round> items,
            @JsonProperty("next_cursor") @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor,
            @JsonProperty("read_at") Instant readAt) {
        public RoundPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record Round(
            @JsonProperty("root_input_id") String rootInputId,
            @JsonProperty("round_number") String roundNumber,
            @JsonProperty("first_sequence") String firstSequence,
            @JsonProperty("last_sequence") String lastSequence,
            @JsonProperty("answer_status") String answerStatus,
            @JsonProperty("execution_status") String executionStatus,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("run_count") int runCount,
            List<Run> runs) {
        public Round {
            runs = runs == null ? List.of() : List.copyOf(runs);
        }
    }

    public record Run(
            @JsonProperty("run_id") String runId,
            List<Loop> loops) {
        public Run {
            loops = loops == null ? List.of() : List.copyOf(loops);
        }
    }

    public record Loop(
            @JsonProperty("loop_id") @JsonInclude(JsonInclude.Include.ALWAYS) String loopId,
            @JsonInclude(JsonInclude.Include.ALWAYS) String iteration,
            @JsonProperty("association_status") String associationStatus,
            @JsonProperty("first_sequence") String firstSequence,
            @JsonProperty("last_sequence") String lastSequence,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("updated_at") Instant updatedAt) {
    }

    public record MessagePage(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("root_input_id") @JsonInclude(JsonInclude.Include.ALWAYS) String rootInputId,
            List<Message> items,
            @JsonProperty("next_cursor") @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor,
            @JsonProperty("read_at") Instant readAt) {
        public MessagePage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record Message(
            @JsonProperty("message_id") String messageId,
            String role,
            String content,
            @JsonProperty("root_input_id") @JsonInclude(JsonInclude.Include.ALWAYS) String rootInputId,
            @JsonProperty("run_id") @JsonInclude(JsonInclude.Include.ALWAYS) String runId,
            @JsonInclude(JsonInclude.Include.ALWAYS) String phase,
            @JsonInclude(JsonInclude.Include.ALWAYS) String state,
            @JsonProperty("message_version") String messageVersion,
            @JsonProperty("created_at") Instant createdAt) {
    }

    public record TracePage(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("root_input_id") String rootInputId,
            @JsonProperty("sequence_upper_bound") String sequenceUpperBound,
            @JsonProperty("next_cursor") @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor,
            List<TraceEvent> items,
            @JsonProperty("loop_groups") List<LoopGroup> loopGroups,
            @JsonProperty("unassociated_usage_summary") TraceUsageSummary unassociatedUsageSummary,
            @JsonProperty("read_at") Instant readAt) {
        public TracePage {
            items = items == null ? List.of() : List.copyOf(items);
            loopGroups = loopGroups == null ? List.of() : List.copyOf(loopGroups);
        }
    }

    public record TraceEvent(
            @JsonProperty("node_id") String nodeId,
            @JsonProperty("node_type") String nodeType,
            @JsonProperty("parent_node_id") @JsonInclude(JsonInclude.Include.ALWAYS) String parentNodeId,
            @JsonProperty("session_sequence") String sessionSequence,
            @JsonProperty("event_id") String eventId,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("run_id") @JsonInclude(JsonInclude.Include.ALWAYS) String runId,
            @JsonProperty("loop_id") @JsonInclude(JsonInclude.Include.ALWAYS) String loopId,
            @JsonInclude(JsonInclude.Include.ALWAYS) String iteration,
            @JsonProperty("association_status") String associationStatus,
            @JsonProperty("execution_id") @JsonInclude(JsonInclude.Include.ALWAYS) String executionId,
            @JsonProperty("action_id") @JsonInclude(JsonInclude.Include.ALWAYS) String actionId,
            @JsonProperty("trace_id") String traceId,
            @JsonProperty("caused_by_event_id") @JsonInclude(JsonInclude.Include.ALWAYS) String causedByEventId,
            @JsonProperty("occurred_at") Instant occurredAt,
            @JsonProperty("content_ref") String contentRef,
            @JsonProperty("content_availability") String contentAvailability,
            String status,
            @JsonInclude(JsonInclude.Include.ALWAYS) String detail,
            @JsonProperty("usage_summary") @JsonInclude(JsonInclude.Include.ALWAYS)
            TraceUsageSummary usageSummary) {
    }

    public record LoopGroup(
            @JsonProperty("run_id") @JsonInclude(JsonInclude.Include.ALWAYS) String runId,
            @JsonProperty("loop_id") @JsonInclude(JsonInclude.Include.ALWAYS) String loopId,
            @JsonInclude(JsonInclude.Include.ALWAYS) String iteration,
            @JsonProperty("association_status") String associationStatus,
            @JsonProperty("first_sequence") String firstSequence,
            @JsonProperty("last_sequence") String lastSequence,
            @JsonProperty("node_ids") List<String> nodeIds,
            @JsonProperty("usage_summary") TraceUsageSummary usageSummary) {
        public LoopGroup {
            nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
        }
    }

    /** 只汇总与节点精确关联的 attempt；未知或未关联事实不会推断挂到相邻执行轮。 */
    public record TraceUsageSummary(
            Coverage coverage,
            TokenTotals totals,
            CacheStats cache,
            @JsonProperty("attempt_ids") List<String> attemptIds) {
        public TraceUsageSummary {
            attemptIds = attemptIds == null ? List.of() : List.copyOf(attemptIds);
        }
    }

    public record Content(
            @JsonProperty("content_ref") String contentRef,
            String availability,
            @JsonProperty("schema_id") String schemaId,
            @JsonInclude(JsonInclude.Include.ALWAYS) Object value) {
    }

    public record Usage(
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("root_input_id") @JsonInclude(JsonInclude.Include.ALWAYS) String rootInputId,
            @JsonProperty("read_at") Instant readAt,
            Coverage coverage,
            TokenTotals totals,
            CacheStats cache,
            List<UsageBreakdown> breakdowns,
            List<UsageAttempt> attempts,
            @JsonProperty("next_cursor") @JsonInclude(JsonInclude.Include.ALWAYS) String nextCursor) {
        public Usage {
            breakdowns = breakdowns == null ? List.of() : List.copyOf(breakdowns);
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }
    }

    public record Coverage(
            String status,
            @JsonProperty("total_attempts") int totalAttempts,
            @JsonProperty("reported_attempts") int reportedAttempts,
            @JsonProperty("unknown_attempts") int unknownAttempts,
            @JsonProperty("partial_attempts") int partialAttempts,
            @JsonProperty("zero_usage_attempts") int zeroUsageAttempts) {
    }

    /** null 表示本快照没有该字段的 Provider 报告；所有已知值均以十进制字符串传输。 */
    public record TokenTotals(
            @JsonProperty("prompt_tokens") @JsonInclude(JsonInclude.Include.ALWAYS) String promptTokens,
            @JsonProperty("completion_tokens") @JsonInclude(JsonInclude.Include.ALWAYS) String completionTokens,
            @JsonProperty("cached_input_tokens") @JsonInclude(JsonInclude.Include.ALWAYS) String cachedInputTokens,
            @JsonProperty("cache_write_input_tokens") @JsonInclude(JsonInclude.Include.ALWAYS) String cacheWriteInputTokens,
            @JsonProperty("reasoning_output_tokens") @JsonInclude(JsonInclude.Include.ALWAYS) String reasoningOutputTokens) {
    }

    public record CacheStats(
            String availability,
            @JsonProperty("paired_attempt_count") int pairedAttemptCount,
            @JsonProperty("eligible_attempt_count") int eligibleAttemptCount,
            @JsonProperty("paired_input_tokens") @JsonInclude(JsonInclude.Include.ALWAYS) String pairedInputTokens,
            @JsonProperty("paired_cached_input_tokens") @JsonInclude(JsonInclude.Include.ALWAYS) String pairedCachedInputTokens,
            @JsonProperty("hit_ratio") @JsonInclude(JsonInclude.Include.ALWAYS) String hitRatio) {
    }

    public record UsageBreakdown(
            String provider,
            String model,
            @JsonProperty("call_point") @JsonInclude(JsonInclude.Include.ALWAYS) String callPoint,
            @JsonProperty("attempt_count") int attemptCount,
            TokenTotals totals) {
    }

    public record UsageAttempt(
            @JsonProperty("attempt_id") String attemptId,
            @JsonProperty("invocation_id") String invocationId,
            @JsonProperty("trace_node_id") @JsonInclude(JsonInclude.Include.ALWAYS) String traceNodeId,
            @JsonProperty("run_id") @JsonInclude(JsonInclude.Include.ALWAYS) String runId,
            String provider,
            String model,
            @JsonProperty("call_point") @JsonInclude(JsonInclude.Include.ALWAYS) String callPoint,
            @JsonProperty("report_status") String reportStatus,
            @JsonProperty("usage_source") @JsonInclude(JsonInclude.Include.ALWAYS) String usageSource,
            TokenTotals totals,
            @JsonProperty("ingested_at") @JsonInclude(JsonInclude.Include.ALWAYS) Instant ingestedAt,
            @JsonProperty("diagnostic_available") @JsonInclude(JsonInclude.Include.ALWAYS) boolean diagnosticAvailable,
            @JsonProperty("source_session_id") @JsonInclude(JsonInclude.Include.ALWAYS) String sourceSessionId,
            @JsonProperty("source_root_input_id") @JsonInclude(JsonInclude.Include.ALWAYS) String sourceRootInputId) {
    }
}
