package com.skillforge.platform.sessionmodelattempt;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.chat.observation.SessionObservationView.UsageAttempt;
import java.time.Instant;
import java.util.List;

/** 管理员模型调用诊断协议；仅承载经 Runtime 精确归属验证的展示值。 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ModelAttemptDetail(
        @JsonProperty("tenant_id") String tenantId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("root_input_id") String rootInputId,
        @JsonProperty("attempt_id") String attemptId,
        @JsonProperty("invocation_id") String invocationId,
        @JsonProperty("run_id") String runId,
        @JsonProperty("action_id") String actionId,
        @JsonProperty("loop_id") String loopId,
        Integer iteration,
        String provider,
        String model,
        @JsonProperty("call_point") String callPoint,
        String status,
        @JsonProperty("finish_reason") String finishReason,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("finished_at") Instant finishedAt,
        @JsonProperty("read_at") Instant readAt,
        UsageAttempt usage,
        RecordedContent input,
        RecordedContent reasoning,
        @JsonProperty("reasoning_history_retention") ReasoningHistoryRetention reasoningHistoryRetention,
        RecordedContent output,
        RecordedContent decision,
        @JsonProperty("capability_calls_availability") String capabilityCallsAvailability,
        @JsonProperty("capability_calls") List<CapabilityCall> capabilityCalls) {

    /** 防止调用方修改同一快照中的能力调用列表。 */
    public ModelAttemptDetail {
        capabilityCalls = capabilityCalls == null ? List.of() : List.copyOf(capabilityCalls);
    }

    /** 避免通用日志意外调用 record.toString 输出私有正文。 */
    @Override public String toString() { return "ModelAttemptDetail[REDACTED]"; }

    /** FULL/PARTIAL是留存正文；UNKNOWN不推断原attempt是否有推理，NOT_RECORDED/NOT_RETURNED保持显式空值。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecordedContent(String availability, String source,
            @JsonProperty("schema_id") String schemaId, Object value) {
        /** 历史缺失不使用当前配置、公开回答或相邻事件重建。 */
        public static RecordedContent notRecorded(String source) {
            return new RecordedContent("NOT_RECORDED", source, null, null);
        }
        @Override public String toString() { return "RecordedContent[availability=" + availability + ",REDACTED]"; }
    }

    /**
     * 所属Session owner的普通推理历史退役说明，不声明本attempt曾返回或丢失推理。
     * @param scope 固定SESSION_OWNER，计数和说明只覆盖完整Session owner
     * @param retiredReasoningEventCount 该owner累计退休推理事件数，十进制字符串
     * @param description 日志来源不能完整还原的明确说明，不能用当前配置或其他attempt补全
     */
    public record ReasoningHistoryRetention(String scope,
            @JsonProperty("retired_reasoning_event_count") String retiredReasoningEventCount,String description) { }

    /** 实际能力调用与 input.tools 中可供模型选择的定义分别展示。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CapabilityCall(
            @JsonProperty("call_id") String callId,
            @JsonProperty("action_id") String actionId,
            @JsonProperty("attempt_id") String attemptId,
            String kind, String title, String status,
            RecordedContent input, RecordedContent output) {
        @Override public String toString() { return "CapabilityCall[status=" + status + ",REDACTED]"; }
    }
}
