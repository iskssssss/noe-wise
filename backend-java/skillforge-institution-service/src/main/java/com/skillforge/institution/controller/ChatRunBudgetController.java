package com.skillforge.institution.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.runtime.AgentRuntimeRunControls;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

/** 只读原Run预算事实；不重新受理、恢复执行、读取当前配置或释放未知用量的预留。 */
@RestController
@RequestMapping("/api/v1/chat/runs")
public class ChatRunBudgetController {
    private final SessionService sessions;
    private final AgentRuntimeRunControls controls;
    private final ExecutionBudgetPort budgets;

    public ChatRunBudgetController(SessionService sessions, AgentRuntimeRunControls controls, ExecutionBudgetPort budgets) {
        this.sessions = sessions; this.controls = controls; this.budgets = budgets;
    }

    @Operation(summary = "读取运行冻结额度与用量", description = "返回原Root/Run限额、已用、预留及已确认活跃时长；未知预留保持原值，不推算余额")
    @RequirePermission("foundation.sessions.read")
    @GetMapping("/budget")
    public ApiResponse<BudgetResponse> budget(@RequestParam("session_id") String sessionId, @RequestParam("run_id") String runId) {
        String tenant = TenantIds.requireTenantId();
        var session = sessions.require(tenant, sessionId);
        try { controls.chatRunStatus(session, runId); }
        catch (SecurityException denied) { throw new ApiException(HttpStatus.FORBIDDEN, "没有运行访问权限", denied); }
        var reference = new RunRef(new TenantId(tenant), runId);
        var snapshot = budgets.snapshot(reference);
        if (!reference.equals(snapshot.registration().run())) throw new IllegalStateException("execution_budget_run_mismatch");
        return ApiResponses.wrap(BudgetResponse.from(snapshot));
    }

    public record BudgetResponse(@JsonProperty("run_id") String runId, @JsonProperty("root_run_id") String rootRunId,
            String scenario, String role, String phase, @JsonProperty("source_version") String sourceVersion,
            ScopeBudget root, ScopeBudget run, ClosingBudget closing) {
        static BudgetResponse from(ExecutionBudgetPort.Snapshot snapshot) {
            var registration = snapshot.registration(); var limits = registration.policy().requireEnforced();
            return new BudgetResponse(registration.run().runId(), registration.root().runId(), limits.scenario().name(),
                    registration.role().name(), snapshot.phase().name(), limits.sourceVersion(),
                    new ScopeBudget(Resources.from(limits.root()), Resources.from(snapshot.consumed()), Resources.from(snapshot.reserved()),
                            Long.toString(limits.rootActiveSeconds()), Long.toString(snapshot.activeMillis())),
                    new ScopeBudget(Resources.from(limits.run()), Resources.from(snapshot.runConsumed()), Resources.from(snapshot.runReserved()),
                            Long.toString(limits.runActiveSeconds()), Long.toString(snapshot.runActiveMillis())),
                    new ClosingBudget(Resources.from(snapshot.closing().resources()), Long.toString(snapshot.closing().activeSeconds())));
        }
    }

    /** 数值显式使用十进制字符串，客户端只呈现原值，不受JavaScript整数精度影响。 */
    public record ScopeBudget(Resources limits, Resources consumed, Resources reserved,
            @JsonProperty("active_seconds_limit") String activeSecondsLimit,
            @JsonProperty("confirmed_active_millis") String confirmedActiveMillis) { }

    public record ClosingBudget(Resources resources, @JsonProperty("active_seconds") String activeSeconds) { }

    public record Resources(@JsonProperty("business_model_calls") String businessModelCalls,
            @JsonProperty("auxiliary_model_calls") String auxiliaryModelCalls, @JsonProperty("tool_calls") String toolCalls,
            @JsonProperty("knowledge_calls") String knowledgeCalls, @JsonProperty("input_tokens") String inputTokens,
            @JsonProperty("output_tokens") String outputTokens, @JsonProperty("total_tokens") String totalTokens) {
        static Resources from(ExecutionBudgetPort.Resources resources) {
            return new Resources(Long.toString(resources.businessModels()), Long.toString(resources.auxiliaryModels()),
                    Long.toString(resources.tools()), Long.toString(resources.knowledge()), Long.toString(resources.inputTokens()),
                    Long.toString(resources.outputTokens()), Long.toString(resources.totalTokens()));
        }
    }
}
