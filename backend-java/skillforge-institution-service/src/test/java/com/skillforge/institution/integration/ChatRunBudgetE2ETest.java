package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.institution.runtime.AgentRuntimeExecutionConfiguration;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** 真实登录、Session权限、Run归属和PG原预算；受控Provider不访问云端。 */
@Tag("runtime-latency")
@Import({ChatRuntimeChannelE2ETest.LoopbackEvents.class, DeterministicModelCatalogConfiguration.class,
        AgentRuntimeExecutionConfiguration.class})
class ChatRunBudgetE2ETest extends ChatExecutionChainE2ETest {
    @Autowired private ExecutionBudgetPort budgets;
    @Autowired private TenantMapper budgetTenants;
    @Autowired private PlanMapper budgetPlans;

    @Test void readsOriginalRunBudgetAndRejectsUnauthenticatedCrossTenantSessionAndRunQueries() throws Exception {
        var checked = new java.util.concurrent.atomic.AtomicBoolean();
        ordinaryTemplateSamples("budget-read-contract", 0, 1, (actualRun, rendered) -> {
            assertTypedExecutionPersistence(actualRun, rendered);
            var reference = new RunRef(new TenantId(tenantId), actualRun);
            var original = snapshot(reference);
            var response = get(budgetPath(sessionId, actualRun));
            assertEquals(200, response.statusCode(), response.body());
            assertSnapshot(successData(response), original, snapshot(reference));
            assertEquals(original.registration(), snapshot(reference).registration(), "查询不能读取当前配置重建原额度");
            if (!checked.compareAndSet(false, true)) return;
            assertEquals(401, send(budgetPath(sessionId, actualRun), "GET", null, null).statusCode());
            assertEquals(404, get(budgetPath(sessionId, "missing-" + actualRun)).statusCode());
            var created = send("/api/v1/chat/sessions", "POST", Map.of("agent_id", agentId, "end_user_id", endUserId), accessToken);
            assertEquals(200, created.statusCode(), created.body());
            String otherSession = successData(created).path("session_id").asText();
            assertEquals(403, get(budgetPath(otherSession, actualRun)).statusCode(), "已授权的其他会话也不能读取原Run预算");
            String otherSlug = "budget-other-" + SnowflakeIds.nextId();
            String otherTenant = createTenant(budgetTenants, budgetPlans, otherSlug);
            TenantContext.runWithTenant(otherTenant, () -> createTestTenantAdministrator(otherTenant, "budget-other-admin", PASSWORD));
            var login = send("/api/v1/auth/login", "POST", Map.of("tenant_slug", otherSlug,
                    "username", "budget-other-admin", "password", PASSWORD), null);
            assertEquals(200, login.statusCode(), login.body());
            String otherToken = successData(login).path("access_token").asText();
            assertEquals(404, send(budgetPath(sessionId, actualRun), "GET", null, otherToken).statusCode());
            assertEquals(original.registration(), snapshot(reference).registration());
        });
    }

    /** 真实HTTP响应在首段后中断，原UNKNOWN及用量预留不会因只读刷新被结算或重发。 */
    @Test @Timeout(180)
    void incompleteOriginalModelResultKeepsUnknownReservationsAcrossBudgetRefreshes() throws Exception {
        try (var model = new NativeChatOpenAiProvider(objectMapper, "budget-unknown-" + runId)) {
            model.holdStreamCompletion(); model.abortHeldStream(); model.start();
            TenantContext.runWithTenant(tenantId, () -> configureTenantModel(model.endpoint()));
            login();
            var accepted = send("/api/v1/chat", "POST", chatPayload("请直接回答当前请求"), accessToken);
            assertEquals(202, accepted.statusCode(), accepted.body());
            String actualRun = successData(accepted).path("execution_id").asText();
            var reference = new RunRef(new TenantId(tenantId), actualRun);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND phase='UNCERTAIN'",
                    tenantId, actualRun) != 1 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? AND phase='UNCERTAIN'", tenantId, actualRun));
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts "
                    + "WHERE tenant_id=? AND run_id=? AND result_kind='UNKNOWN'", tenantId, actualRun));
            var original = snapshot(reference);
            assertTrue(original.runReserved().totalTokens() > 0, "缺失usage仍保留原Token预留");
            for (int read = 0; read < 2; read++) {
                var response = get(budgetPath(sessionId, actualRun));
                var after = snapshot(reference);
                assertEquals(200, response.statusCode(), response.body()); assertSnapshot(successData(response), original, after);
                assertEquals(original.registration(), after.registration());
                assertEquals(original.reserved(), after.reserved()); assertEquals(original.runReserved(), after.runReserved());
            }
            assertEquals(1, model.requestCount());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
            assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, actualRun));
        }
    }

    private ExecutionBudgetPort.Snapshot snapshot(RunRef run) {
        return TenantContext.callWithTenant(run.tenantId().value(), () -> budgets.snapshot(run));
    }

    private static String budgetPath(String session, String run) {
        return "/api/v1/chat/runs/budget?session_id=" + session + "&run_id=" + run;
    }

    private void assertSnapshot(JsonNode actual, ExecutionBudgetPort.Snapshot expected, ExecutionBudgetPort.Snapshot after) {
        var registration = expected.registration(); var limits = registration.policy().requireEnforced();
        assertEquals(registration.run().runId(), actual.path("run_id").asText());
        assertEquals(registration.root().runId(), actual.path("root_run_id").asText());
        assertEquals(limits.scenario().name(), actual.path("scenario").asText());
        assertEquals(registration.role().name(), actual.path("role").asText());
        assertEquals(expected.phase().name(), actual.path("phase").asText());
        assertEquals(limits.sourceVersion(), actual.path("source_version").asText());
        assertResources(actual.path("root").path("limits"), limits.root());
        assertResources(actual.path("root").path("consumed"), expected.consumed());
        assertResources(actual.path("root").path("reserved"), expected.reserved());
        assertResources(actual.path("run").path("limits"), limits.run());
        assertResources(actual.path("run").path("consumed"), expected.runConsumed());
        assertResources(actual.path("run").path("reserved"), expected.runReserved());
        assertResources(actual.path("closing").path("resources"), expected.closing().resources());
        assertEquals(Long.toString(limits.rootActiveSeconds()), actual.path("root").path("active_seconds_limit").textValue());
        assertEquals(Long.toString(limits.runActiveSeconds()), actual.path("run").path("active_seconds_limit").textValue());
        long rootMillis = Long.parseLong(actual.path("root").path("confirmed_active_millis").textValue());
        long runMillis = Long.parseLong(actual.path("run").path("confirmed_active_millis").textValue());
        assertTrue(expected.activeMillis() <= rootMillis && rootMillis <= after.activeMillis(), "公开时间只能来自查询前后已持久确认的范围");
        assertTrue(expected.runActiveMillis() <= runMillis && runMillis <= after.runActiveMillis());
        assertEquals(Long.toString(expected.closing().activeSeconds()), actual.path("closing").path("active_seconds").textValue());
        assertFalse(actual.has("stage")); assertFalse(actual.has("remaining"));
    }

    private static void assertResources(JsonNode actual, ExecutionBudgetPort.Resources expected) {
        var fields = Map.of(ExecutionBudgetPort.Dimension.BUSINESS_MODELS, "business_model_calls",
                ExecutionBudgetPort.Dimension.AUXILIARY_MODELS, "auxiliary_model_calls", ExecutionBudgetPort.Dimension.TOOLS, "tool_calls",
                ExecutionBudgetPort.Dimension.KNOWLEDGE, "knowledge_calls", ExecutionBudgetPort.Dimension.INPUT_TOKENS, "input_tokens",
                ExecutionBudgetPort.Dimension.OUTPUT_TOKENS, "output_tokens", ExecutionBudgetPort.Dimension.TOTAL_TOKENS, "total_tokens");
        fields.forEach((dimension, field) -> assertEquals(Long.toString(expected.value(dimension)), actual.path(field).textValue(), field));
    }
}
