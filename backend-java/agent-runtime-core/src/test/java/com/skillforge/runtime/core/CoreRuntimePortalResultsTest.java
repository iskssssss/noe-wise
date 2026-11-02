package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.EvidenceBundle;
import com.skillforge.runtime.api.EvidenceItem;
import com.skillforge.runtime.api.EvidenceSufficiency;
import com.skillforge.runtime.api.KnowledgePlan;
import com.skillforge.runtime.api.KnowledgeOperatorType;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeOutcome;
import com.skillforge.runtime.api.port.ModelPort.ModelResult;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowControlResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowRecoveredResult;
import com.skillforge.runtime.codec.spi.PortalOutcomeCodec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** 装配测试只证明不同 registry 的路由和 artifact API，不冒充 Provider 授权或 Session 提交。 */
class CoreRuntimePortalResultsTest {
    private static final CodecLimits LIMITS = new CodecLimits(10000, 1000, 32);
    private static final TypedPayload MODEL = new TypedPayload("model.v1", "{}");
    private static final TypedPayload TOOL = new TypedPayload("tool.v1", "{}");
    private static final TypedPayload WORKFLOW = new TypedPayload("workflow.v1", "{}");
    private static final TypedPayload EVIDENCE = new TypedPayload("evidence.v1", "{}");

    /** 三类结果各自进入唯一业务 registry，模型/准备/投影 registry 不参与 artifact 编码。 */
    @Test
    void keepsRegistryOwnershipSeparateAcrossPublicAssembly() {
        int[] calls = {0, 0, 0};
        var results = runtime(LIMITS).portalResults((payload, limits) -> { assertEquals(TOOL, payload); assertEquals(LIMITS, limits); calls[0]++; },
                (payload, limits) -> { assertEquals(WORKFLOW, payload); assertEquals(LIMITS, limits); calls[1]++; },
                (payload, limits) -> { assertEquals(EVIDENCE, payload); assertEquals(LIMITS, limits); calls[2]++; });
        var tool = new ToolOutcome.Succeeded(TOOL, new ResultRef("tool"), List.of());
        assertEquals(tool, results.decodeTool(results.encodeTool(PortalCallKind.TOOL_INVOKE, tool), PortalCallKind.TOOL_INVOKE));
        assertArrayEquals(new int[]{2, 0, 0}, calls);
        var workflow = new WorkflowResult.Completed(new ExecutionRef(new TenantId("tenant"), "execution"), WORKFLOW, 1, new ResultRef("workflow"));
        assertEquals(workflow, results.decodeWorkflow(results.encodeWorkflow(PortalCallKind.WORKFLOW_DISPATCH, workflow), PortalCallKind.WORKFLOW_DISPATCH));
        var recovered = new WorkflowRecoveredResult.Completed(workflow);
        assertEquals(recovered, results.decodeWorkflowRecovery(results.encodeWorkflowRecovery(PortalCallKind.WORKFLOW_RECOVER_RESULT, recovered), PortalCallKind.WORKFLOW_RECOVER_RESULT));
        assertArrayEquals(new int[]{2, 4, 0}, calls);
        var knowledge = knowledge();
        var snapshot = results.encodeKnowledge(PortalCallKind.KNOWLEDGE_EXECUTE, knowledge);
        assertEquals(knowledge, results.decodeKnowledge(snapshot, PortalCallKind.KNOWLEDGE_EXECUTE));
        assertArrayEquals(new int[]{2, 4, 2}, calls);
        assertThrows(IllegalArgumentException.class, () -> results.decodeKnowledge(snapshot, PortalCallKind.TOOL_INVOKE));
        assertArrayEquals(new int[]{2, 4, 2}, calls);
    }

    /** 单一 SPI façade 对六类结果执行严格双向 round-trip，并在 encode/decode 两侧拒绝错 kind。 */
    @Test
    void roundTripsEveryPortalFamilyAndRejectsWrongCallKinds() {
        PayloadValidator unused = (payload, limits) -> { };
        var runtime = new CoreRuntime(
                LIMITS,
                (payload, limits) -> assertEquals(MODEL, payload));
        PortalOutcomeCodec codec = runtime.portalResults(unused, unused, unused);

        var model = new ModelResult.Completed(
                MODEL,
                "model-1",
                new ModelUsage(OptionalLong.of(3), OptionalLong.of(5)),
                "stop",
                new ResultRef("model-result"));
        var modelSnapshot = codec.encodeModel(PortalCallKind.MODEL_GENERATE, model);
        assertEquals(model, codec.decodeModel(modelSnapshot, PortalCallKind.MODEL_GENERATE));
        assertEquals(modelSnapshot, codec.encodeModel(
                PortalCallKind.MODEL_GENERATE,
                codec.decodeModel(modelSnapshot, PortalCallKind.MODEL_GENERATE)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encodeModel(PortalCallKind.TOOL_INVOKE, model));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeModel(modelSnapshot, PortalCallKind.MODEL_STREAM));

        var tool = new ToolOutcome.Succeeded(TOOL, new ResultRef("tool-result"), List.of());
        var toolSnapshot = codec.encodeTool(PortalCallKind.TOOL_INVOKE, tool);
        assertEquals(tool, codec.decodeTool(toolSnapshot, PortalCallKind.TOOL_INVOKE));
        assertEquals(toolSnapshot, codec.encodeTool(
                PortalCallKind.TOOL_INVOKE,
                codec.decodeTool(toolSnapshot, PortalCallKind.TOOL_INVOKE)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encodeTool(PortalCallKind.MODEL_GENERATE, tool));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeTool(toolSnapshot, PortalCallKind.MODEL_GENERATE));

        var workflow = new WorkflowResult.Completed(
                new ExecutionRef(new TenantId("tenant"), "execution"),
                WORKFLOW,
                1,
                new ResultRef("workflow-result"));
        var workflowSnapshot = codec.encodeWorkflow(PortalCallKind.WORKFLOW_DISPATCH, workflow);
        assertEquals(workflow,
                codec.decodeWorkflow(workflowSnapshot, PortalCallKind.WORKFLOW_DISPATCH));
        assertEquals(workflowSnapshot, codec.encodeWorkflow(
                PortalCallKind.WORKFLOW_DISPATCH,
                codec.decodeWorkflow(workflowSnapshot, PortalCallKind.WORKFLOW_DISPATCH)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encodeWorkflow(PortalCallKind.WORKFLOW_RECOVER_RESULT, workflow));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeWorkflow(workflowSnapshot, PortalCallKind.WORKFLOW_RESUME));

        var recovery = new WorkflowRecoveredResult.Completed(workflow);
        var recoverySnapshot = codec.encodeWorkflowRecovery(
                PortalCallKind.WORKFLOW_RECOVER_RESULT, recovery);
        assertEquals(recovery, codec.decodeWorkflowRecovery(
                recoverySnapshot, PortalCallKind.WORKFLOW_RECOVER_RESULT));
        assertEquals(recoverySnapshot, codec.encodeWorkflowRecovery(
                PortalCallKind.WORKFLOW_RECOVER_RESULT,
                codec.decodeWorkflowRecovery(
                        recoverySnapshot, PortalCallKind.WORKFLOW_RECOVER_RESULT)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encodeWorkflowRecovery(PortalCallKind.WORKFLOW_DISPATCH, recovery));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeWorkflowRecovery(
                        recoverySnapshot, PortalCallKind.WORKFLOW_DISPATCH));

        var control = new WorkflowControlResult.Rejected(new PortFailure(
                "cancel_rejected", RetryMode.NONE, new ResultRef("control-result")));
        var controlSnapshot = codec.encodeWorkflowControl(
                PortalCallKind.WORKFLOW_CANCEL, control);
        assertEquals(control, codec.decodeWorkflowControl(
                controlSnapshot, PortalCallKind.WORKFLOW_CANCEL));
        assertEquals(controlSnapshot, codec.encodeWorkflowControl(
                PortalCallKind.WORKFLOW_CANCEL,
                codec.decodeWorkflowControl(controlSnapshot, PortalCallKind.WORKFLOW_CANCEL)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encodeWorkflowControl(PortalCallKind.WORKFLOW_DISPATCH, control));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeWorkflowControl(
                        controlSnapshot, PortalCallKind.WORKFLOW_RECOVER_RESULT));

        var knowledge = knowledge();
        var knowledgeSnapshot = codec.encodeKnowledge(
                PortalCallKind.KNOWLEDGE_EXECUTE, knowledge);
        assertEquals(knowledge, codec.decodeKnowledge(
                knowledgeSnapshot, PortalCallKind.KNOWLEDGE_EXECUTE));
        assertEquals(knowledgeSnapshot, codec.encodeKnowledge(
                PortalCallKind.KNOWLEDGE_EXECUTE,
                codec.decodeKnowledge(knowledgeSnapshot, PortalCallKind.KNOWLEDGE_EXECUTE)));
        assertThrows(IllegalArgumentException.class,
                () -> codec.encodeKnowledge(PortalCallKind.TOOL_INVOKE, knowledge));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decodeKnowledge(knowledgeSnapshot, PortalCallKind.TOOL_INVOKE));
    }

    /** 任何必需 registry 缺失都在装配时失败，不提供 allow-all fallback。 */
    @Test
    void refusesMissingRegistriesAndKeepsConfiguredResourceLimits() {
        PayloadValidator validator = (payload, limits) -> fail("must not reach registry");
        assertThrows(NullPointerException.class, () -> runtime(LIMITS).portalResults(null, validator, validator));
        assertThrows(NullPointerException.class, () -> runtime(LIMITS).portalResults(validator, null, validator));
        assertThrows(NullPointerException.class, () -> runtime(LIMITS).portalResults(validator, validator, null));
        var bounded = runtime(new CodecLimits(1, 1, 1)).portalResults(validator, validator, validator);
        assertThrows(IllegalArgumentException.class, () -> bounded.encodeTool(PortalCallKind.TOOL_INVOKE,
                new ToolOutcome.InputRequired(Map.of(), Optional.of("request-state"))));
        assertThrows(IllegalArgumentException.class, () -> bounded.encodeWorkflow(PortalCallKind.WORKFLOW_DISPATCH,
                new WorkflowResult.Accepted(new ExecutionRef(new TenantId("tenant"), "execution"), 1)));
        assertThrows(IllegalArgumentException.class, () -> bounded.encodeKnowledge(PortalCallKind.KNOWLEDGE_EXECUTE, knowledge()));
    }

    /** 装配层不捕获或重写技术故障，也不把错误改装为 Portal failure。 */
    @Test
    void preservesTechnicalFailureAtPublicBoundary() {
        var failure = new IllegalStateException("unavailable", new IllegalStateException("cause"));
        var results = runtime(LIMITS).portalResults((payload, limits) -> { throw failure; }, (payload, limits) -> { throw failure; },
                (payload, limits) -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> results.encodeTool(PortalCallKind.TOOL_INVOKE,
                new ToolOutcome.Succeeded(TOOL, new ResultRef("tool"), List.of()))));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> results.encodeWorkflow(PortalCallKind.WORKFLOW_RESUME,
                new WorkflowResult.Completed(new ExecutionRef(new TenantId("tenant"), "execution"), WORKFLOW, 1, new ResultRef("workflow")))));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> results.encodeKnowledge(PortalCallKind.KNOWLEDGE_EXECUTE, knowledge())));
    }

    /** 单项 bundle 只验证公开装配，复杂引用与排序由独立 codec 测试覆盖。 */
    private static KnowledgeOutcome.Retrieved knowledge() {
        var plan = new KnowledgePlan("plan", "request", List.of(), "enough", "v1");
        var item = new EvidenceItem("item", "source", "v1", KnowledgeOperatorType.KEYWORD, EVIDENCE,
                List.of("provenance"), List.of(), 0.5, "trusted");
        return new KnowledgeOutcome.Retrieved(new EvidenceBundle("bundle", "request", plan, List.of(item), List.of(),
                EvidenceSufficiency.SUFFICIENT, List.of(), 1, Instant.EPOCH, java.util.Optional.empty()), new ResultRef("knowledge"));
    }

    /** 其他模型相关依赖仅为禁止触达的断言替身，不参与本切片。 */
    private static CoreRuntime runtime(CodecLimits limits) {
        PayloadValidator unused = (payload, ignored) -> fail("unrelated registry");
        return new CoreRuntime(limits, unused);
    }
}
