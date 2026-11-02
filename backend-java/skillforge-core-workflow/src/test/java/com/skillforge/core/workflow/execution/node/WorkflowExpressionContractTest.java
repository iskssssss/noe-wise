package com.skillforge.core.workflow.execution.node;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.workflow.execution.StateMachineEngine;
import com.skillforge.core.workflow.execution.WorkflowModels.WorkflowNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 锁定构建器必须遵循的真实变量路径与类型边界，不为界面补造新的 Runtime 语义。 */
class WorkflowExpressionContractTest {
    /** 嵌套对象支持点路径，数组索引和 input 域没有隐式解释。 */
    @Test void nestedMapPathsDoNotInventArrayAccessOrInputNamespace() {
        var state = new LinkedHashMap<String, Object>();
        state.put("payload", Map.of("customer", Map.of("name", "张三"), "items", List.of(Map.of("id", "one"))));
        state.put("ctx_order", Map.of("id", "order-1"));
        var node = context(null, state);
        assertEquals("张三", resolve("state.payload.customer.name", node));
        assertEquals("order-1", resolve("state.ctx_order.id", node));
        assertNull(resolve("state.payload.items.0.id", node));
        assertNull(resolve("state.payload.items[0].id", node));
        assertEquals("input.order.id", resolve("input.order.id", node), "未识别裸路径沿现有规则回退为字面量，不能假定它引用业务输入");
        assertNull(resolve("runtime.run_id", node));
    }

    /** 工具和脚本的显式参数只在完整占位符时保留类型，替换值不再递归解释。 */
    @Test void typedParameterInterpolationPreservesObjectsArraysZeroFalseAndNull() {
        var payload = Map.<String, Object>of("count", 0, "enabled", false, "literal", "${config.secret}");
        var state = new LinkedHashMap<String, Object>();
        state.put("payload", payload);
        state.put("items", List.of(0, false));
        state.put("zero", 0);
        state.put("disabled", false);
        var node = context(null, state);
        assertSame(payload, WorkflowExpressions.interpolateValue("${state.payload}", node));
        assertEquals(List.of(0, false), WorkflowExpressions.interpolateValue("${state.items}", node));
        assertEquals(0, WorkflowExpressions.interpolateValue("${state.zero}", node));
        assertEquals(false, WorkflowExpressions.interpolateValue("${state.disabled}", node));
        assertNull(WorkflowExpressions.interpolateValue("${state.missing}", node));
        @SuppressWarnings("unchecked")
        var nested = (Map<String, Object>) WorkflowExpressions.interpolateValue(
                Map.of("items", List.of("${state.payload}", "count=${state.zero}")), node);
        assertEquals(List.of(payload, "count=0"), nested.get("items"));
        assertEquals("${config.secret}", payload.get("literal"));
    }

    /** 卡片和渲染的文本重载不会把参数重载的对象保持规则推广到所有控件。 */
    @Test void cardAndRenderInterpolationProducesTextEvenForWholePlaceholder() {
        var payload = Map.<String, Object>of("count", 0);
        var node = context(null, new LinkedHashMap<>(Map.of("payload", payload)));
        assertEquals(payload.toString(), WorkflowExpressions.interpolateValue("${state.payload}",
                node.config(), node.state(), node.extracted(), node.extraVars()));
        assertEquals("secret-value", WorkflowExpressions.interpolate("${config.secret}",
                node.config(), node.state(), node.extracted(), node.extraVars()), "消息 prompt 等显式传配置的文本消费者仍能引用 config");
    }

    /** 赋值裸路径保留对象；完整占位符走文本分支；目标直接写 state 且覆盖旧值。 */
    @Test void assignUsesBareTypedReferencesAndTextPlaceholdersWithoutNodeIdWrapper() {
        var source = Map.<String, Object>of("count", 0, "enabled", false);
        var workflowNode = mock(WorkflowNode.class);
        when(workflowNode.args()).thenReturn(Map.of(
                "copied", "state.source", "as_text", "${state.source}",
                "items_copy", "state.items", "items_text", "${state.items}", "zero", 0, "disabled", false));
        var state = new LinkedHashMap<String, Object>();
        state.put("source", source);
        state.put("items", List.of(0, false));
        state.put("copied", "previous");
        var node = context(workflowNode, state);
        new AssignNodeExecutor().doExecute(node);
        assertSame(source, state.get("copied"));
        assertEquals(source.toString(), state.get("as_text"));
        assertEquals(List.of(0, false), state.get("items_copy"));
        assertEquals("[0, false]", state.get("items_text"));
        assertEquals(0L, state.get("zero"));
        assertEquals(false, state.get("disabled"));
        assertEquals(8, state.size());
    }

    /** 运行标识和用户投影只包含生产引擎实际注入的键，不展示不存在的 run_id。 */
    @Test void runtimeAndUserVariablesMatchActualWhitelistedProjection() {
        var vars = StateMachineEngine.buildExtraVars(Map.of(
                "end_user_id", 100L, "display_name", "成员", "email", "private@example.invalid"),
                "session", "execution", "instance", Map.of("ask", 2));
        assertEquals(Map.of("end_user_id", 100L, "display_name", "成员"), vars.get("user"));
        assertEquals(Map.of("session_id", "session", "execution_id", "execution", "workflow_instance_id", "instance",
                "action_visits", Map.of("ask", 2)), vars.get("runtime"));
        assertNull(WorkflowExpressions.resolve("runtime.run_id", Map.of(), Map.of(), Map.of(), vars));
        assertNull(WorkflowExpressions.resolve("user.email", Map.of(), Map.of(), Map.of(), vars));
    }

    /** 人工交互卡的标题、正文、字段、选项、动作和元数据共用实际安装配置。 */
    @Test void humanCardUsesInstalledConfigThroughoutNestedValuesAndKeepsStyleRaw() {
        var template = cardTemplate();
        var node = displayNode("human_input", template);
        var context = context(node, installedConfig(), new LinkedHashMap<>());
        var outcome = new HumanInputNodeExecutor().doExecute(context);
        assertTrue(outcome.pending());
        assertCardConfigValues(outcome.card(), template.get("style"));
        assertEquals(List.of(Map.of("label", "灵知")), outcome.card().get("options"));
        assertEquals(Map.of("nested", List.of("灵知", Map.of("enabled", "false"))), outcome.card().get("meta"));
        assertMessageConfigValues(context);
    }

    /** 展示卡与人工卡使用相同配置来源，嵌套渲染中的字符串也参与文本插值。 */
    @Test void infoCardAndRenderUseInstalledConfigWithoutChangingTextTypeBoundary() {
        var template = cardTemplate();
        var node = displayNode("card", template);
        var context = context(node, installedConfig(), new LinkedHashMap<>());
        var outcome = new CardNodeExecutor().doExecute(context);
        assertFalse(outcome.pending());
        assertEquals("info", outcome.card().get("type"));
        assertCardConfigValues(outcome.card(), template.get("style"));
        assertMessageConfigValues(context);
    }

    /** 菜单选项标题与挂起消息、render 都能读取同一份配置。 */
    @Test void menuLabelsAndPendingRenderUseInstalledConfig() {
        var node = displayNode("menu", Map.of());
        when(node.actions()).thenReturn(List.of(Map.of("id", "confirm", "label", "${config.brand.name}")));
        var context = context(node, installedConfig(), new LinkedHashMap<>());
        var outcome = new MenuNodeExecutor().doExecute(context);
        assertTrue(outcome.pending());
        assertEquals(List.of(Map.of("id", "confirm", "label", "灵知")), outcome.card().get("actions"));
        assertMessageConfigValues(context);
    }

    private static Map<String, Object> installedConfig() {
        return Map.of("brand", Map.of("name", "灵知"), "count", 0, "enabled", false,
                "link", "https://example.invalid/detail", "accent", "blue");
    }

    private static Map<String, Object> cardTemplate() {
        return Map.of("title", "${config.brand.name}", "body", "数量 ${config.count}",
                "fields", List.of(Map.of("label", "${config.brand.name}", "value", "${config.count}",
                        "nested", List.of("${config.brand.name}", Map.of("enabled", "${config.enabled}")))),
                "actions", List.of(Map.of("id", "detail", "label", "${config.brand.name}", "url", "${config.link}")),
                "options", List.of(Map.of("label", "${config.brand.name}")),
                "meta", Map.of("nested", List.of("${config.brand.name}", Map.of("enabled", "${config.enabled}"))),
                "style", Map.of("accent", "${config.accent}"));
    }

    private static WorkflowNode displayNode(String type, Map<String, Object> template) {
        var node = mock(WorkflowNode.class);
        when(node.id()).thenReturn("display");
        when(node.type()).thenReturn(type);
        when(node.card()).thenReturn(template);
        when(node.prompt()).thenReturn("${config.brand.name}");
        when(node.message()).thenReturn("${config.brand.name}");
        when(node.render()).thenReturn(Map.of("nested", List.of("${config.brand.name}",
                Map.of("values", List.of("${config.count}", "${config.enabled}", 0, false)))));
        when(node.mergeMessageId()).thenReturn("message-${config.count}");
        return node;
    }

    private static void assertCardConfigValues(Map<String, Object> card, Object style) {
        assertEquals("灵知", card.get("title"));
        assertEquals("数量 0", card.get("body"));
        assertEquals(List.of(Map.of("label", "灵知", "value", "0",
                "nested", List.of("灵知", Map.of("enabled", "false")))), card.get("fields"));
        assertEquals(List.of(Map.of("id", "detail", "label", "灵知", "url", "https://example.invalid/detail")), card.get("actions"));
        assertSame(style, card.get("style"), "受控样式保持原样，不因变量修复扩大插值范围");
    }

    private static void assertMessageConfigValues(NodeExecutionContext context) {
        var message = context.messages().getFirst();
        assertEquals("灵知", message.get("content"));
        assertEquals("message-0", message.get("merge_message_id"));
        assertEquals(Map.of("nested", List.of("灵知", Map.of("values", List.of("0", "false", 0, false)))),
                message.get("render"));
    }

    private static Object resolve(String path, NodeExecutionContext node) {
        return WorkflowExpressions.resolve(path, node.config(), node.state(), node.extracted(), node.extraVars());
    }

    private static NodeExecutionContext context(WorkflowNode workflowNode, Map<String, Object> state) {
        return context(workflowNode, Map.of("secret", "secret-value"), state);
    }

    private static NodeExecutionContext context(WorkflowNode workflowNode, Map<String, Object> config, Map<String, Object> state) {
        return new NodeExecutionContext(null, workflowNode, config, state,
                new LinkedHashMap<>(), "input", new ArrayList<>(), "instance", "7", List.of(),
                "execution", null, "session", Map.of("runtime", Map.of("execution_id", "execution")), null, null);
    }
}
