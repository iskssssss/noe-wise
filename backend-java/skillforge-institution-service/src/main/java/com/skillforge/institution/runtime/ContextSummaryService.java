package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.tracing.TraceContext;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.SessionContextSummary;
import java.util.ArrayDeque;
import org.springframework.stereotype.Service;

/** 机构宿主对接独立 context-summary 用途；只提交背景摘要，不写任务或业务事实。 */
@Service
public final class ContextSummaryService implements SessionContextSummary {
    private static final String PURPOSE = "context-summary";
    private final SystemAgentExecution execution;
    private final ObjectMapper json;

    public ContextSummaryService(SystemAgentExecution execution, ObjectMapper json) {
        this.execution = execution;
        this.json = json;
    }

    @Override
    public Outcome summarize(Request request) {
        if (request.cancelled().getAsBoolean()) return Outcome.unavailable("SUMMARY_CANCELLED_BEFORE_ACCEPTANCE");
        String runId = request.key().identity().runRef().runId();
        String operation = runId + ":" + request.key().loopId() + ":" + request.key().iteration();
        String key = "context-summary:" + operation + ":" + request.sourceDigest();
        // 同键同来源由系统用途受理边界复用原冻结输入与配置；变化中的 remaining 只裁剪执行窗口。
        // Duration 是严格正值，但亚毫秒值直接 toMillis 会冻结成 0，随后被受控用途错误拒绝。
        long remainingMillis = Math.max(1L, request.remaining().toMillis());
        ObjectNode input = json.createObjectNode()
                .put("sourceDigest", request.sourceDigest())
                .put("maxOutputTokens", request.maxOutputTokens())
                .put("remainingMillis", remainingMillis)
                .put("coveredMessages", 0)
                .put("root", "session-preparation:" + operation);
        // 当前目标/纠正为相关性数据，不能被 coveredMessages 消耗或成为新的执行授权。
        var currentContext = input.putArray("currentContext");
        for (ModelMessage message : request.currentContext())
            currentContext.addObject().put("id", message.messageId()).put("role", message.role().name())
                    .put("text", message.accountingText());
        var messages = input.putArray("messages");
        var pending = new ArrayDeque<String>();
        for (ModelMessage message : request.history()) {
            ObjectNode value = messages.addObject()
                    .put("id", message.messageId())
                    .put("role", message.role().name())
                    .put("text", message.accountingText());
            if (!pending.isEmpty()) {
                if (message.toolCallId().isEmpty()
                        || !pending.removeFirst().equals(message.toolCallId().orElseThrow())) {
                    throw new IllegalArgumentException("context_summary_history_invalid");
                }
            } else if (message.toolCallId().isPresent()) {
                throw new IllegalArgumentException("context_summary_history_invalid");
            }
            if (!message.toolCalls().isEmpty()) {
                var toolCalls = value.putArray("toolCalls");
                for (var call : message.toolCalls()) {
                    toolCalls.addObject().put("callId", call.callId()).put("wireName", call.wireName())
                            .put("arguments", call.arguments().canonicalJson());
                    pending.addLast(call.callId());
                }
            }
            value.put("closed", pending.isEmpty());
        }
        if (!pending.isEmpty()) throw new IllegalArgumentException("context_summary_history_invalid");
        String payload;
        try { payload = json.writeValueAsString(input); }
        catch (Exception invalid) { throw new IllegalArgumentException("context_summary_input_invalid", invalid); }
        var limits = new SystemAgentExecution.ExecutionLimits(request.maxOutputTokens(), request.remaining(),
                request.cancelled(), new ResultRef("session-preparation:" + operation));
        SystemAgentExecution.PurposeResult accepted;
        var identity = request.key().identity();
        var trace = identity.trace();
        var cost = CostContext.builder().eventId(key).requestId(identity.causationInputId().value())
                .rootInputId(trace.rootInputId().value()).runId(identity.runRef().runId())
                .sessionId(identity.owner().sessionRef().sessionId()).agentId(Long.parseLong(identity.agentId().value()))
                .endUserId(identity.requester() instanceof RunRequester.EndUser user ? user.endUserId().value() : null)
                .callPoint("context-summary")
                .traceContext(new TraceContext(identity.causationInputId().value(), trace.rootInputId().value(), trace.traceId(), trace.spanId(),
                        AgentExecutionProgram.admissionEvent(identity).id().value(), trace.causedByEventId().map(event -> event.value()).orElse(null))).build();
        try (var meter = MeterScope.openIsolated(cost)) {
            accepted = execution.executePurposeControlled(PURPOSE,
                    request.key().identity().runRef().tenantId().value(), key, payload, limits);
        } catch (ApiException unavailable) {
            // 受理拒绝还没有受信的配置/调用引用；不从异常文案推断配置或复制外部正文。
            return Outcome.unavailable(acceptanceReason(unavailable));
        } catch (RuntimeException failed) {
            return Outcome.failed("SUMMARY_INVOCATION_FAILED");
        }
        var run = accepted.run();
        if (request.cancelled().getAsBoolean()) return Outcome.failed("SUMMARY_CANCELLED",
                accepted.configurationRef(), accepted.invocationRef());
        if (!"COMPLETED".equals(run.status()) && !"FAILED".equals(run.status()))
            return Outcome.unavailable("SUMMARY_RESULT_PENDING", accepted.configurationRef(), accepted.invocationRef());
        if ("system_agent_outcome_unknown".equals(run.errorCode()))
            return Outcome.unavailable("SUMMARY_RESULT_UNKNOWN", accepted.configurationRef(), accepted.invocationRef());
        if ("FAILED".equals(run.status())) return Outcome.failed(failureReason(run.errorCode()),
                accepted.configurationRef(), accepted.invocationRef());
        if (run.output() == null || run.output().isBlank()) return Outcome.rejected("SUMMARY_OUTPUT_EMPTY",
                accepted.configurationRef(), accepted.invocationRef());
        try {
            JsonNode result = json.readTree(run.output());
            if (result == null || !result.isObject() || !result.path("content").isTextual()
                    || !result.path("coveredMessages").isIntegralNumber()
                    || !result.path("coveredMessages").canConvertToInt())
                return Outcome.rejected("SUMMARY_OUTPUT_INVALID", accepted.configurationRef(), accepted.invocationRef());
            String content = result.path("content").asText("");
            int covered = result.path("coveredMessages").asInt(0);
            if (content.isBlank()) return Outcome.rejected("SUMMARY_CONTENT_EMPTY",
                    accepted.configurationRef(), accepted.invocationRef());
            if (covered <= 0 || covered > request.history().size()) return Outcome.rejected("SUMMARY_COVERAGE_INVALID",
                    accepted.configurationRef(), accepted.invocationRef());
            return Outcome.available(new Result(accepted.configurationRef(), accepted.invocationRef(),
                    request.sourceDigest(), covered, content));
        } catch (Exception invalid) {
            return Outcome.rejected("SUMMARY_OUTPUT_INVALID", accepted.configurationRef(), accepted.invocationRef());
        }
    }

    private static String acceptanceReason(ApiException failure) {
        return switch (failure.getCode()) {
            case "NOT_FOUND" -> "SUMMARY_ACCEPTANCE_NOT_FOUND";
            case "CONFLICT" -> "SUMMARY_ACCEPTANCE_CONFLICT";
            case "FORBIDDEN", "UNAUTHORIZED" -> "SUMMARY_ACCEPTANCE_DENIED";
            case "RATE_LIMITED", "SERVICE_UNAVAILABLE" -> "SUMMARY_ACCEPTANCE_UNAVAILABLE";
            default -> "SUMMARY_ACCEPTANCE_REJECTED";
        };
    }

    private static String failureReason(String errorCode) {
        if ("system_agent_output_truncated".equals(errorCode)) return "SUMMARY_OUTPUT_TRUNCATED";
        if ("system_agent_output_empty".equals(errorCode)) return "SUMMARY_OUTPUT_EMPTY";
        return "SUMMARY_GENERATION_FAILED";
    }
}
