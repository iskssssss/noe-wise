package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.institution.runtime.AgentRuntimeExecutionConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.annotation.Import;

/**
 * 显式装配新 Java 执行主链的独立 Spring 验收候选。
 *
 * <p>登录、发布、挂载、产品 HTTP/SSE、Provider 和 PostgreSQL 均复用真实夹具；
 * 新配置提供唯一 Primary ChatRuntimeGateway，不给生产增加新旧按租户路由。
 * 首次验收只选择本类声明的具体方法，不选择继承的旧链测试。</p>
 */
@Import({ChatRuntimeChannelE2ETest.LoopbackEvents.class, DeterministicModelCatalogConfiguration.class,
        AgentRuntimeExecutionConfiguration.class})
@ExtendWith(ChatExecutionChainE2ETest.DeclaredCandidateMethodsOnly.class)
class ChatExecutionChainE2ETest extends ChatRuntimeChannelE2ETest {
    @org.springframework.beans.factory.annotation.Autowired
    private com.skillforge.runtime.starter.AgentRuntimeProperties executionProperties;
    /** 复用夹具不继承旧链验收范围；完整类发现也只启用本类声明的新候选测试。 */
    static final class DeclaredCandidateMethodsOnly implements ExecutionCondition {
        /** 在 BeforeEach 前跳过继承测试，避免给新装配隐式执行旧主链或额外工作量。 */
        @Override public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            var method = context.getTestMethod();
            return method.isPresent() && method.orElseThrow().getDeclaringClass() != context.getRequiredTestClass()
                    ? ConditionEvaluationResult.disabled("旧链方法只在原测试类运行")
                    : ConditionEvaluationResult.enabled("仅运行本类声明的新候选方法");
        }
    }

    /** 一个完整最小首链：实际工具结果进入第二轮模型，最终事件持久化，重复输入不新增外调。 */
    @Tag("runtime-latency")
    @Test
    void newExecutionChainPersistsRealToolAndFinalSseWithoutOldGraph() throws Exception {
        ordinaryTemplateSamples("new-java-first-chain", 0, 1, this::assertTypedExecutionPersistence);
    }

    /** 与旧链基线严格同工作量和计时：两次预热、七个独立会话热样本，保存独立原始证据。 */
    @Tag("runtime-latency")
    @Test
    void newExecutionChainWarmLatencyWithSevenIndependentSessions() throws Exception {
        ordinaryTemplateSamples("new-java-hot-chain", 2, 7, this::assertTypedExecutionPersistence);
    }

    /** 新主链事实只存在于目标权威表，已采纳结果与安全最终事件先于客户端完成。 */
    protected void assertTypedExecutionPersistence(String actualRun, String expectedRendered) throws Exception {
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_runs "
                + "WHERE tenant_id=? AND run_id=? AND status='COMPLETED' AND phase='FINISHED' AND lease_claim_id IS NULL",
                tenantId, actualRun));
        assertEquals(2, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions "
                + "WHERE tenant_id=? AND run_id=? AND kind='MODEL' AND status='SUCCEEDED'", tenantId, actualRun));
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions "
                + "WHERE tenant_id=? AND run_id=? AND kind='TOOL' AND status='SUCCEEDED'", tenantId, actualRun));
        assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts "
                + "WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED' AND result_ref IS NOT NULL", tenantId, actualRun));
        assertEquals(3, countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        var modelRequests = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a "
                + "JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref "
                + "WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' ORDER BY a.action_ordinal", tenantId, actualRun);
        assertEquals(2, modelRequests.size());
        var modelCodec = new com.skillforge.runtime.session.AgentExecutionCodec(new com.skillforge.runtime.json.CodecLimits(
                executionProperties.getMaxPayloadCodeUnits(), executionProperties.getMaxPayloadNodes(), executionProperties.getMaxPayloadDepth()));
        for (var row : modelRequests) {
            assertEquals(com.skillforge.runtime.session.AgentExecutionCodec.MODEL_REQUEST_SCHEMA, row.get("schema_id"));
            var request = ModelRequestTestMaterials.decode(modelCodec, new com.skillforge.runtime.api.TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString()), jdbc, tenantId);
            assertTrue(request.transformations().isEmpty(), "纯文本调用不伪造图片来源或准备关系");
            assertFalse(request.request().messages().isEmpty());
        }
        assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_events "
                + "WHERE tenant_id=? AND run_id=? AND type='execution.completed'", tenantId, actualRun));
        var events = queryRows(jdbc, tenantId, "SELECT event_sequence,type,schema_id,canonical_json FROM agent_runtime_execution_events "
                + "WHERE tenant_id=? AND run_id=? ORDER BY event_sequence", tenantId, actualRun);
        assertFalse(events.isEmpty());
        long previous = 0;
        for (var event : events) {
            long sequence = ((Number) event.get("event_sequence")).longValue();
            assertTrue(sequence > previous, events.toString());
            previous = sequence;
        }
        var completed = events.stream().filter(event -> "execution.completed".equals(event.get("type"))).findFirst().orElseThrow();
        long completedSequence = ((Number) completed.get("event_sequence")).longValue();
        var trailing = events.stream().filter(event -> ((Number) event.get("event_sequence")).longValue() > completedSequence).toList();
        assertFalse(trailing.isEmpty(), "完成提交须保留真实最终输出检查事实");
        for (var event : trailing) {
            assertEquals(com.skillforge.runtime.session.ExecutionObservation.EVENT_TYPE, event.get("type"),
                    "完成后不得追加新的业务事件");
            var fact = com.skillforge.runtime.session.ExecutionObservation.decode(
                    new com.skillforge.runtime.api.TypedPayload(event.get("schema_id").toString(), event.get("canonical_json").toString()),
                    executionProperties.getMaxPayloadCodeUnits(), executionProperties.getMaxPayloadNodes(), executionProperties.getMaxPayloadDepth());
            assertEquals(actualRun, fact.runId());
            assertEquals(com.skillforge.runtime.session.ExecutionObservation.Kind.GUARDRAIL, fact.kind());
            assertEquals(com.skillforge.runtime.session.ExecutionObservation.Stage.AGENT_OUTPUT, fact.stage());
            assertEquals(com.skillforge.runtime.session.ExecutionObservation.Status.SUCCEEDED, fact.status());
        }
        assertTrue(events.stream().anyMatch(event -> event.get("canonical_json").toString().contains("runtime-workload-completed-")),
                "已提交公开事件必须保存本次真实模型完成文本");
        var results = queryRows(jdbc, tenantId, "SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a "
                + "JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.run_id=a.run_id "
                + "AND t.action_id=a.action_id AND t.attempt_id=a.selected_attempt "
                + "JOIN agent_runtime_execution_materials m ON m.tenant_id=t.tenant_id AND m.reference=t.result_ref "
                + "WHERE a.tenant_id=? AND a.run_id=? AND a.kind='TOOL' AND t.adopted", tenantId, actualRun);
        assertEquals(1, results.size(), results.toString());
        assertTrue(containsRenderedResult(objectMapper.readTree(results.getFirst().get("canonical_json").toString()), expectedRendered),
                "已选定原工具结果材料必须保存真实 template_render 的 success/rendered 对象");
        assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_actions WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        assertEquals(0, countRuntime("SELECT count(*) FROM agent_runtime_session_events WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        assertEquals(0, countRuntime("SELECT count(*) FROM nw_runtime_session_model_operations WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
        assertEquals(0, countRuntime("SELECT count(*) FROM nw_runtime_session_tool_operations WHERE tenant_id=? AND run_id=?", tenantId, actualRun));
    }

    /**
     * 检查选定 Attempt 指向的真实结果对象；TypedPayload 正文在明确 canonicalJson 字段处解码。
     * 不在用户文本或任意 SSE 字符串中搜索预期工具结果，也不据此生成执行事实。
     */
    private boolean containsRenderedResult(JsonNode node, String expected) throws Exception {
        if (node == null) return false;
        if (node.isObject() && node.path("success").isBoolean() && node.path("success").asBoolean()
                && expected.equals(node.path("rendered").asText())) return true;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if ("canonicalJson".equals(field.getKey()) && field.getValue().isTextual()) {
                    if (containsRenderedResult(objectMapper.readTree(field.getValue().textValue()), expected)) return true;
                } else if (containsRenderedResult(field.getValue(), expected)) return true;
            }
        } else if (node.isArray()) {
            for (var child : node) if (containsRenderedResult(child, expected)) return true;
        }
        return false;
    }
}
