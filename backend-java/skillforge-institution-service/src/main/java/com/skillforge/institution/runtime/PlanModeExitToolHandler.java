package com.skillforge.institution.runtime;

import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.connector.PlatformInteractiveToolHandler;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolInteractionResult;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputRequest;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import com.skillforge.runtime.session.SessionCollaboration;
import com.skillforge.runtime.session.SessionRuntimePayloads;
import com.skillforge.institution.runtime.AgentRuntimeInboundPayloads.PlanReviewBody;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 通过 durable Tool input wait 提交计划评审，批准后只记录下一模型步骤的退出选择。 */
@Component
public final class PlanModeExitToolHandler implements PlatformInteractiveToolHandler {
    static final String REQUEST_ID = "plan-review";
    static final String REQUEST_SCHEMA = "skillforge.runtime.plan-review-request.v1";
    static final String RESPONSE_SCHEMA = SessionRuntimePayloads.PLAN_REVIEW_RESPONSE_SCHEMA;
    private static final Pattern HEADING = Pattern.compile("^#\\s+(.+?)\\s*$");
    private final SessionCollaboration collaboration;
    private final PublicOutputGuard publicOutputs;
    private final ObjectMapper json = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public PlanModeExitToolHandler(SessionLogPort log, PublicOutputGuard publicOutputs) {
        this.collaboration = new SessionCollaboration(log);
        this.publicOutputs = publicOutputs;
    }

    @Override public String toolName() { return "exit_plan_mode"; }

    /** 不具备 Tool continuation 的宿主得到符合本工具 output schema 的确定失败。 */
    @Override
    public Map<String, Object> execute(
            Map<String, Object> arguments,
            PlatformToolExecutionContext context) {
        return Map.of(
                "success", false,
                "approved", false,
                "error", "plan_review_channel_unavailable");
    }

    @Override
    public PlatformToolInteractionResult invoke(
            Map<String, Object> arguments,
            PlatformToolExecutionContext context) throws JsonProcessingException {
        if (!available(context)) return rejected("plan_review_cancelled", "");
        Plan plan = plan(arguments);
        if (plan == null) return rejected("invalid_plan", "");
        if (publicOutputs.capture(context.run()).rejects(plan.markdown()))
            return rejected(PublicOutputGuard.REJECTION_CODE, "");
        if (!collaboration.read(context.run().owner()).active()) {
            return rejected("plan_mode_inactive", "");
        }
        var params = new TreeMap<String, Object>();
        params.put("options", List.of("approve", "keep_planning"));
        params.put("plan", plan.markdown());
        params.put("title", plan.title());
        return new PlatformToolInteractionResult.InputRequired(
                Map.of(REQUEST_ID, new ToolInputRequest(ToolInputMethod.ELICITATION_CREATE,
                        new TypedPayload(REQUEST_SCHEMA, json.writeValueAsString(params)))),
                Optional.of(state(plan)));
    }

    @Override
    public PlatformToolInteractionResult resume(
            Map<String, Object> arguments,
            Map<String, ToolInputResponse> inputResponses,
            Optional<String> requestState,
            PlatformToolExecutionContext context) throws JsonProcessingException {
        if (!available(context)) return rejected("plan_review_cancelled", "");
        Plan plan = plan(arguments);
        if (plan == null || !matchesState(plan, requestState)) {
            return rejected("plan_review_state_invalid", "");
        }
        if (!collaboration.read(context.run().owner()).active()) {
            return rejected("plan_mode_inactive", "");
        }
        PlanReviewBody review = review(inputResponses);
        if (review == null) return rejected("plan_review_response_invalid", "");
        if ("keep_planning".equals(review.decision())) {
            return rejected("plan_review_keep_planning", review.feedback());
        }
        collaboration.requestPlanExit(context.run(), context.invocationId(), Instant.now());
        return new PlatformToolInteractionResult.Completed(Map.of(
                "success", true,
                "approved", true));
    }

    private String state(Plan plan) throws JsonProcessingException {
        return json.writeValueAsString(new TreeMap<>(Map.of(
                "plan", plan.markdown(),
                "title", plan.title())));
    }

    private boolean matchesState(Plan plan, Optional<String> requestState) {
        if (requestState == null || requestState.isEmpty()) return false;
        try {
            JsonNode value = json.readTree(requestState.orElseThrow());
            return value.isObject() && value.size() == 2
                    && value.path("plan").isTextual()
                    && plan.markdown().equals(value.path("plan").textValue())
                    && value.path("title").isTextual()
                    && plan.title().equals(value.path("title").textValue());
        } catch (RuntimeException | JsonProcessingException invalid) {
            return false;
        }
    }

    private PlanReviewBody review(Map<String, ToolInputResponse> responses) {
        if (responses == null || responses.size() != 1 || !responses.containsKey(REQUEST_ID)) return null;
        ToolInputResponse response = responses.get(REQUEST_ID);
        if (response == null || response.method() != ToolInputMethod.ELICITATION_CREATE
                || !RESPONSE_SCHEMA.equals(response.result().schemaId())) return null;
        try {
            return AgentRuntimeInboundPayloads.decode(response.result().canonicalJson(),PlanReviewBody.class);
        } catch (RuntimeException | IOException invalid) {
            return null;
        }
    }

    private static Plan plan(Map<String, Object> arguments) {
        if (arguments == null || !arguments.keySet().equals(Set.of("plan"))
                || !(arguments.get("plan") instanceof String markdown)) return null;
        String normalized = markdown.trim();
        String first = normalized.lines().findFirst().orElse("").stripTrailing();
        var match = HEADING.matcher(first);
        if (!match.matches() || match.group(1).isBlank()) return null;
        return new Plan(markdown, match.group(1));
    }

    private static boolean available(PlatformToolExecutionContext context) {
        try {
            return !context.cancellationSignal().isCancellationRequested()
                    && Instant.now().isBefore(context.deadlineAt());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static PlatformToolInteractionResult.Rejected rejected(String error, String feedback) {
        var result = new TreeMap<String, Object>();
        result.put("approved", false);
        result.put("error", error);
        if (!feedback.isEmpty()) result.put("feedback", feedback);
        result.put("success", false);
        return new PlatformToolInteractionResult.Rejected(result);
    }

    private record Plan(String markdown, String title) { }
}
