package com.skillforge.runtime.api.port;
import com.skillforge.runtime.api.PrincipalRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.EvidenceRef;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.KnowledgeBudget;
import com.skillforge.runtime.api.KnowledgeMode;
import com.skillforge.runtime.api.KnowledgeOperatorType;
import com.skillforge.runtime.api.KnowledgeRequest;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 验证 Tool、Workflow 与 Knowledge Portal 的冻结目标、调用闭包和结果语义。 */
class CapabilityPortalContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");

    @Test
    void redactsMultiRoundToolInputValues() {
        var payload = new TypedPayload("private.v1", "{\"secret\":\"value\"}");
        var request = new ToolPortal.ToolInputRequest(
                ToolPortal.ToolInputMethod.ELICITATION_CREATE, payload);
        var response = new ToolPortal.ToolInputResponse(
                ToolPortal.ToolInputMethod.ELICITATION_CREATE, payload);
        var required = new ToolPortal.ToolOutcome.InputRequired(
                Map.of("private-request", request), Optional.of("private-state"));

        assertFalse(request.toString().contains("secret"));
        assertFalse(response.toString().contains("secret"));
        assertEquals("ToolInputRequired[REDACTED]", required.toString());
    }
    private static final TenantId OTHER_TENANT = new TenantId("tenant-2");
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final EndUserId USER = new EndUserId(7);
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final InputId INPUT = new InputId("input-1");
    private static final TraceEnvelope TRACE = new TraceEnvelope(
            "trace-1", "span-1", INPUT, Optional.of(new EventId("cause-1")));
    private static final PortCallContext CALL_CONTEXT = context();
    private static final ProviderCallContext TOOL_CONTEXT =
            ProviderDispatchTestFixtures.providerContext(CALL_CONTEXT, PortalCallKind.TOOL_INVOKE);
    private static final ProviderCallContext WORKFLOW_CONTEXT =
            ProviderDispatchTestFixtures.providerContext(CALL_CONTEXT, PortalCallKind.WORKFLOW_DISPATCH);
    private static final ProviderCallContext KNOWLEDGE_CONTEXT =
            ProviderDispatchTestFixtures.providerContext(CALL_CONTEXT, PortalCallKind.KNOWLEDGE_EXECUTE);
    private static final CapabilitySnapshotRef SNAPSHOT = new CapabilitySnapshotRef(
            SESSION, INPUT, "snapshot-1", 1);
    private static final TypedPayload PAYLOAD = new TypedPayload("schema-1", "{}");
    private static final KnowledgeSourceVersion SOURCE_VERSION = new KnowledgeSourceVersion(
            "source-1", "version-1");

    /** 验证三个 Portal 的公开方法集合和返回类型与事实源精确一致。 */
    @Test
    void exposesExactPortalMethods() throws NoSuchMethodException {
        assertEquals(Set.of("invoke", "resume"), declaredMethodNames(ToolPortal.class));
        assertEquals(ToolPortal.ToolOutcome.class,
                ToolPortal.class.getMethod("invoke", ToolPortal.ToolCall.class).getReturnType());
        assertEquals(ToolPortal.ToolOutcome.class,
                ToolPortal.class.getMethod("resume", ToolPortal.ToolContinuationCall.class)
                        .getReturnType());
        assertEquals(
                Set.of("dispatch", "resume", "recoverResult", "query", "cancel"),
                declaredMethodNames(WorkflowPortal.class));
        assertEquals(WorkflowPortal.WorkflowResult.class,
                WorkflowPortal.class.getMethod(
                        "dispatch", WorkflowPortal.WorkflowDispatch.class).getReturnType());
        assertEquals(WorkflowPortal.WorkflowResult.class,
                WorkflowPortal.class.getMethod(
                        "resume", WorkflowPortal.WorkflowResume.class).getReturnType());
        assertEquals(WorkflowPortal.WorkflowRecoveredResult.class,
                WorkflowPortal.class.getMethod(
                        "recoverResult", WorkflowPortal.WorkflowResultQuery.class).getReturnType());
        assertEquals(Optional.class,
                WorkflowPortal.class.getMethod(
                        "query", WorkflowPortal.WorkflowQuery.class).getReturnType());
        ParameterizedType queryReturn = (ParameterizedType) WorkflowPortal.class.getMethod(
                "query", WorkflowPortal.WorkflowQuery.class).getGenericReturnType();
        assertEquals(WorkflowPortal.WorkflowSnapshot.class, queryReturn.getActualTypeArguments()[0]);
        assertEquals(WorkflowPortal.WorkflowControlResult.class,
                WorkflowPortal.class.getMethod(
                        "cancel", WorkflowPortal.WorkflowCancel.class).getReturnType());
        assertEquals(Set.of("execute"), declaredMethodNames(KnowledgePortal.class));
        assertEquals(KnowledgePortal.KnowledgeOutcome.class,
                KnowledgePortal.class.getMethod(
                        "execute", KnowledgePortal.KnowledgeCall.class).getReturnType());
    }

    /** 验证三个 Portal 的所有 record 字段、顺序与泛型 ABI 精确匹配主文档。 */
    @Test
    void freezesPortalRecordAbi() {
        assertEquals(List.of("value:String"), componentSignatures(ToolPortal.ToolHandle.class));
        assertEquals(List.of(
                        "handle:ToolHandle", "capability:CapabilityRef", "deploymentId:String",
                        "deploymentRevision:long", "inputSchema:TypedPayload",
                        "outputSchema:TypedPayload", "effect:ToolEffect", "idempotent:boolean",
                        "timeout:java.time.Duration", "modelInvocable:boolean",
                        "confirmation:ToolConfirmationPolicy",
                        "sensitiveFields:java.util.Set<String>",
                        "providerRequirements:java.util.Set<String>",
                        "outputSchemaDeclared:boolean", "callIdentityPolicy:ToolCallIdentityPolicy"),
                componentSignatures(ToolPortal.FrozenToolTarget.class));
        assertEquals(List.of(
                        "context:ProviderCallContext", "binding:ToolBinding",
                        "target:FrozenToolTarget", "arguments:TypedPayload",
                        "idempotencyKey:IdempotencyKey"),
                componentSignatures(ToolPortal.ToolCall.class));
        assertEquals(List.of(
                        "observation:TypedPayload", "resultRef:ResultRef",
                        "usageRefs:java.util.List<ResultRef>"),
                componentSignatures(ToolPortal.ToolOutcome.Succeeded.class));
        assertEquals(List.of(
                        "inputRequests:java.util.Map<String, ToolInputRequest>",
                        "requestState:java.util.Optional<String>"),
                componentSignatures(ToolPortal.ToolOutcome.InputRequired.class));
        assertEquals(List.of("failure:PortFailure", "comparisonFact:java.util.Optional<TypedPayload>"),
                componentSignatures(ToolPortal.ToolOutcome.Failed.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(ToolPortal.ToolOutcome.OutcomeUnknown.class));

        assertEquals(List.of("value:String"),
                componentSignatures(WorkflowPortal.WorkflowHandle.class));
        assertEquals(List.of(
                        "handle:WorkflowHandle", "instanceId:String", "instanceRevision:long",
                        "capability:CapabilityRef", "timeout:java.time.Duration", "installationRevision:long"),
                componentSignatures(WorkflowPortal.FrozenWorkflowTarget.class));
        assertEquals(List.of(
                        "context:ProviderCallContext", "snapshot:CapabilitySnapshotRef",
                        "target:FrozenWorkflowTarget", "executionRef:ExecutionRef",
                        "parameters:TypedPayload", "evidenceRefs:java.util.List<EvidenceRef>",
                        "idempotencyKey:IdempotencyKey"),
                componentSignatures(WorkflowPortal.WorkflowDispatch.class));
        assertEquals(List.of(
                        "context:ProviderCallContext", "snapshot:CapabilitySnapshotRef",
                        "target:FrozenWorkflowTarget",
                        "executionRef:ExecutionRef", "suspendRef:String", "actionKey:String",
                        "response:TypedPayload", "expectedRevision:long",
                        "idempotencyKey:IdempotencyKey"),
                componentSignatures(WorkflowPortal.WorkflowResume.class));
        assertEquals(List.of(
                        "context:ProviderCallContext", "snapshot:CapabilitySnapshotRef",
                        "target:FrozenWorkflowTarget",
                        "executionRef:ExecutionRef"),
                componentSignatures(WorkflowPortal.WorkflowResultQuery.class));
        assertEquals(List.of("identity:VerifiedIdentity", "executionRef:ExecutionRef"),
                componentSignatures(WorkflowPortal.WorkflowQuery.class));
        assertEquals(List.of(
                        "actionRef:ActionRef", "invocationId:String",
                        "blockedExecuteAttemptId:String",
                        "blockedExecuteRequestFingerprint:String",
                        "reconciliationRef:ResultRef", "invocationBarrierRef:ResultRef"),
                componentSignatures(WorkflowPortal.InvocationBarrierProof.class));
        assertEquals(List.of(
                        "context:ProviderCallContext", "snapshot:CapabilitySnapshotRef",
                        "target:FrozenWorkflowTarget",
                        "executionRef:ExecutionRef", "actionKey:String", "expectedRevision:long",
                        "reasonCode:String", "idempotencyKey:IdempotencyKey"),
                componentSignatures(WorkflowPortal.WorkflowCancel.class));
        assertEquals(List.of("executionRef:ExecutionRef", "revision:long"),
                componentSignatures(WorkflowPortal.WorkflowResult.Accepted.class));
        assertEquals(List.of(
                        "executionRef:ExecutionRef", "suspendRef:String", "revision:long",
                        "resultRef:ResultRef", "reason:LoopSuspendReason"),
                componentSignatures(WorkflowPortal.WorkflowResult.Suspended.class));
        assertEquals(List.of(
                        "executionRef:ExecutionRef", "output:TypedPayload", "revision:long",
                        "resultRef:ResultRef"),
                componentSignatures(WorkflowPortal.WorkflowResult.Completed.class));
        assertEquals(List.of(
                        "executionRef:ExecutionRef", "revision:java.util.OptionalLong",
                        "failure:PortFailure"),
                componentSignatures(WorkflowPortal.WorkflowResult.Failed.class));
        assertEquals(List.of(
                        "executionRef:ExecutionRef", "revision:long",
                        "reason:LoopTerminationReason", "resultRef:ResultRef"),
                componentSignatures(WorkflowPortal.WorkflowResult.Terminated.class));
        assertEquals(List.of(
                        "executionRef:ExecutionRef", "revision:long", "resultRef:ResultRef"),
                componentSignatures(WorkflowPortal.WorkflowResult.Cancelled.class));
        assertEquals(List.of(
                        "executionRef:ExecutionRef", "revision:java.util.OptionalLong",
                        "failure:PortFailure"),
                componentSignatures(WorkflowPortal.WorkflowResult.OutcomeUnknown.class));
        assertEquals(List.of("result:WorkflowResult.Suspended"),
                componentSignatures(WorkflowPortal.WorkflowRecoveredResult.Suspended.class));
        assertEquals(List.of("result:WorkflowResult.Completed"),
                componentSignatures(WorkflowPortal.WorkflowRecoveredResult.Completed.class));
        assertEquals(List.of("result:WorkflowResult.Failed"),
                componentSignatures(WorkflowPortal.WorkflowRecoveredResult.Failed.class));
        assertEquals(List.of("result:WorkflowResult.Terminated"),
                componentSignatures(WorkflowPortal.WorkflowRecoveredResult.Terminated.class));
        assertEquals(List.of("result:WorkflowResult.Cancelled"),
                componentSignatures(WorkflowPortal.WorkflowRecoveredResult.Cancelled.class));
        assertEquals(List.of(
                        "executionRef:ExecutionRef", "barrierProof:InvocationBarrierProof"),
                componentSignatures(WorkflowPortal.WorkflowRecoveredResult.Absent.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(WorkflowPortal.WorkflowRecoveredResult.Rejected.class));
        assertEquals(List.of(
                        "executionRef:ExecutionRef", "target:FrozenWorkflowTarget",
                        "state:WorkflowExecutionState", "revision:long",
                        "resultRef:java.util.Optional<ResultRef>", "updatedAt:java.time.Instant"),
                componentSignatures(WorkflowPortal.WorkflowSnapshot.class));
        assertEquals(List.of("execution:WorkflowSnapshot"),
                componentSignatures(WorkflowPortal.WorkflowControlResult.Applied.class));
        assertEquals(List.of("execution:WorkflowSnapshot"),
                componentSignatures(WorkflowPortal.WorkflowControlResult.Replayed.class));
        assertEquals(List.of("current:WorkflowSnapshot", "failure:PortFailure"),
                componentSignatures(WorkflowPortal.WorkflowControlResult.Conflict.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(WorkflowPortal.WorkflowControlResult.Rejected.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(WorkflowPortal.WorkflowControlResult.OutcomeUnknown.class));

        assertEquals(List.of("value:String"),
                componentSignatures(KnowledgePortal.KnowledgeHandle.class));
        assertEquals(List.of(
                        "handle:KnowledgeHandle", "capability:CapabilityRef",
                        "descriptorId:String", "descriptorRevision:long",
                        "sourceVersions:java.util.Set<KnowledgeSourceVersion>",
                        "allowedOperators:java.util.Set<KnowledgeOperatorType>",
                        "timeout:java.time.Duration"),
                componentSignatures(KnowledgePortal.FrozenKnowledgeTarget.class));
        assertEquals(List.of(
                        "context:ProviderCallContext", "snapshot:CapabilitySnapshotRef",
                        "target:FrozenKnowledgeTarget", "request:KnowledgeRequest",
                        "sourceEventId:EventId", "idempotencyKey:IdempotencyKey"),
                componentSignatures(KnowledgePortal.KnowledgeCall.class));
        assertEquals(List.of("bundle:EvidenceBundle", "resultRef:ResultRef"),
                componentSignatures(KnowledgePortal.KnowledgeOutcome.Retrieved.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(KnowledgePortal.KnowledgeOutcome.Failed.class));
        assertEquals(List.of("failure:PortFailure"),
                componentSignatures(KnowledgePortal.KnowledgeOutcome.OutcomeUnknown.class));
        assertEquals(Set.of(
                        ToolPortal.ToolOutcome.Succeeded.class,
                        ToolPortal.ToolOutcome.InputRequired.class,
                        ToolPortal.ToolOutcome.Failed.class,
                        ToolPortal.ToolOutcome.OutcomeUnknown.class),
                Set.of(ToolPortal.ToolOutcome.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                        WorkflowPortal.WorkflowResult.Accepted.class,
                        WorkflowPortal.WorkflowResult.Suspended.class,
                        WorkflowPortal.WorkflowResult.Completed.class,
                        WorkflowPortal.WorkflowResult.Failed.class,
                        WorkflowPortal.WorkflowResult.Terminated.class,
                        WorkflowPortal.WorkflowResult.Cancelled.class,
                        WorkflowPortal.WorkflowResult.OutcomeUnknown.class),
                Set.of(WorkflowPortal.WorkflowResult.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                        WorkflowPortal.WorkflowRecoveredResult.Suspended.class,
                        WorkflowPortal.WorkflowRecoveredResult.Completed.class,
                        WorkflowPortal.WorkflowRecoveredResult.Failed.class,
                        WorkflowPortal.WorkflowRecoveredResult.Terminated.class,
                        WorkflowPortal.WorkflowRecoveredResult.Cancelled.class,
                        WorkflowPortal.WorkflowRecoveredResult.Absent.class,
                        WorkflowPortal.WorkflowRecoveredResult.Rejected.class),
                Set.of(WorkflowPortal.WorkflowRecoveredResult.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                        WorkflowPortal.WorkflowControlResult.Applied.class,
                        WorkflowPortal.WorkflowControlResult.Replayed.class,
                        WorkflowPortal.WorkflowControlResult.Conflict.class,
                        WorkflowPortal.WorkflowControlResult.Rejected.class,
                        WorkflowPortal.WorkflowControlResult.OutcomeUnknown.class),
                Set.of(WorkflowPortal.WorkflowControlResult.class.getPermittedSubclasses()));
        assertEquals(Set.of(
                        KnowledgePortal.KnowledgeOutcome.Retrieved.class,
                        KnowledgePortal.KnowledgeOutcome.Failed.class,
                        KnowledgePortal.KnowledgeOutcome.OutcomeUnknown.class),
                Set.of(KnowledgePortal.KnowledgeOutcome.class.getPermittedSubclasses()));
    }

    /** 验证句柄格式、能力类型、正超时和 Knowledge 清单防御复制。 */
    @Test
    void validatesFrozenCapabilityTargets() {
        assertEquals("tool:mount-1", toolTarget().handle().value());
        assertEquals("workflow:mount-1", workflowTarget().handle().value());
        assertEquals("knowledge:mount-1", knowledgeTarget().handle().value());
        assertThrows(IllegalArgumentException.class, () -> new ToolPortal.ToolHandle("workflow:m1"));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowPortal.WorkflowHandle("workflow:"));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgePortal.KnowledgeHandle("knowledge: "));
        assertThrows(IllegalArgumentException.class, () -> new ToolPortal.FrozenToolTarget(
                new ToolPortal.ToolHandle("tool:m1"),
                capability(CapabilityType.WORKFLOW),
                "deployment-1", 1, PAYLOAD, PAYLOAD, ToolEffect.READ_ONLY, true,
                Duration.ofSeconds(1), true, ToolPortal.ToolConfirmationPolicy.NEVER,
                Set.of(), Set.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowPortal.FrozenWorkflowTarget(
                new WorkflowPortal.WorkflowHandle("workflow:m1"),
                "instance-1", 1, capability(CapabilityType.WORKFLOW), Duration.ZERO, 1L));

        HashSet<KnowledgeSourceVersion> mutableSources = new HashSet<>(Set.of(SOURCE_VERSION));
        HashSet<KnowledgeOperatorType> mutableOperators = new HashSet<>(Set.of(KnowledgeOperatorType.VECTOR));
        KnowledgePortal.FrozenKnowledgeTarget target = new KnowledgePortal.FrozenKnowledgeTarget(
                new KnowledgePortal.KnowledgeHandle("knowledge:m1"),
                capability(CapabilityType.KNOWLEDGE_SOURCE),
                "descriptor-1", 1, mutableSources, mutableOperators, Duration.ofSeconds(2));
        mutableSources.add(new KnowledgeSourceVersion("source-2", "version-1"));
        mutableOperators.add(KnowledgeOperatorType.GRAPH);

        assertEquals(Set.of(SOURCE_VERSION), target.sourceVersions());
        assertEquals(Set.of(KnowledgeOperatorType.VECTOR), target.allowedOperators());
        assertNotSame(mutableSources, target.sourceVersions());
        assertThrows(UnsupportedOperationException.class,
                () -> target.sourceVersions().add(new KnowledgeSourceVersion("source-3", "version-1")));
    }

    /** 验证 Tool 绑定精确目标与参数，Workflow 绑定当前快照和证据引用。 */
    @Test
    void validatesToolAndWorkflowCallClosure() {
        ToolPortal.ToolCall toolCall = new ToolPortal.ToolCall(
                TOOL_CONTEXT, new ToolPortal.ToolBinding(
                        ToolPortal.ToolBinding.ProviderType.TEST_MOCK, "fixture", "lookup"),
                toolTarget(), PAYLOAD,
                new IdempotencyKey("idem-tool"));
        assertEquals(toolTarget(), toolCall.target());
        assertEquals(PAYLOAD, toolCall.arguments());
        assertEquals("ToolCall[REDACTED]", toolCall.toString());
        assertThrows(IllegalArgumentException.class, () -> new ToolPortal.ToolCall(
                TOOL_CONTEXT, toolCall.binding(), null, PAYLOAD, new IdempotencyKey("idem-tool")));

        ArrayList<EvidenceRef> evidenceRefs = new ArrayList<>(List.of(evidenceRef(SESSION)));
        WorkflowPortal.WorkflowDispatch dispatch = new WorkflowPortal.WorkflowDispatch(
                WORKFLOW_CONTEXT, SNAPSHOT, workflowTarget(),
                new ExecutionRef(TENANT, "execution-1"),
                PAYLOAD, evidenceRefs, new IdempotencyKey("idem-dispatch"));
        evidenceRefs.clear();
        assertEquals(1, dispatch.evidenceRefs().size());
        assertNotSame(evidenceRefs, dispatch.evidenceRefs());
        assertThrows(IllegalArgumentException.class, () -> new WorkflowPortal.WorkflowDispatch(
                WORKFLOW_CONTEXT, SNAPSHOT, workflowTarget(),
                new ExecutionRef(OTHER_TENANT, "execution-1"),
                PAYLOAD, List.of(), new IdempotencyKey("idem-dispatch")));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowPortal.WorkflowDispatch(
                WORKFLOW_CONTEXT, SNAPSHOT, workflowTarget(),
                new ExecutionRef(TENANT, "execution-1"),
                PAYLOAD, List.of(evidenceRef(new SessionRef(OTHER_TENANT, "session-2"))),
                new IdempotencyKey("idem-dispatch")));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowPortal.WorkflowDispatch(
                WORKFLOW_CONTEXT, SNAPSHOT, workflowTarget(),
                new ExecutionRef(TENANT, "execution-1"),
                PAYLOAD, List.of(evidenceRef(new SessionRef(TENANT, "session-2"))),
                new IdempotencyKey("idem-dispatch")));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowPortal.WorkflowDispatch(
                WORKFLOW_CONTEXT, SNAPSHOT, workflowTarget(),
                new ExecutionRef(TENANT, "execution-1"),
                PAYLOAD, List.of(evidenceRef(SESSION), evidenceRef(SESSION)),
                new IdempotencyKey("idem-dispatch")));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowPortal.WorkflowResume(
                WORKFLOW_CONTEXT, SNAPSHOT, workflowTarget(),
                new ExecutionRef(TENANT, "execution-1"),
                "suspend-1", "action-1", PAYLOAD, 0, new IdempotencyKey("idem-resume")));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowPortal.WorkflowQuery(
                CALL_CONTEXT.identity(), new ExecutionRef(OTHER_TENANT, "execution-1")));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowPortal.WorkflowCancel(
                WORKFLOW_CONTEXT, SNAPSHOT, workflowTarget(),
                new ExecutionRef(TENANT, "execution-1"),
                "action-1", 1, " ", new IdempotencyKey("idem-cancel")));
    }

    /** 验证 Knowledge 调用不接受 NONE、身份漂移或扩大冻结来源/算子/超时。 */
    @Test
    void validatesKnowledgeCallClosure() {
        KnowledgePortal.KnowledgeCall call = new KnowledgePortal.KnowledgeCall(
                KNOWLEDGE_CONTEXT, SNAPSHOT, knowledgeTarget(), knowledgeRequest(
                        TENANT, USER, AGENT, SESSION, TRACE, KnowledgeMode.GROUNDING,
                        Set.of("source-1"), Set.of(SOURCE_VERSION),
                        Set.of(KnowledgeOperatorType.VECTOR), 1_000),
                new EventId("source-event-1"), new IdempotencyKey("idem-knowledge"));
        assertEquals("knowledge-request-1", call.request().knowledgeRequestId());
        assertThrows(IllegalArgumentException.class, () -> new KnowledgePortal.KnowledgeCall(
                KNOWLEDGE_CONTEXT, SNAPSHOT, knowledgeTarget(), knowledgeRequest(
                        TENANT, USER, AGENT, SESSION, TRACE, KnowledgeMode.NONE,
                        Set.of("source-1"), Set.of(SOURCE_VERSION),
                        Set.of(KnowledgeOperatorType.VECTOR), 1_000),
                new EventId("source-event-1"), new IdempotencyKey("idem-knowledge")));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgePortal.KnowledgeCall(
                KNOWLEDGE_CONTEXT, SNAPSHOT, knowledgeTarget(), knowledgeRequest(
                        TENANT, new EndUserId(8), AGENT, SESSION, TRACE, KnowledgeMode.GROUNDING,
                        Set.of("source-1"), Set.of(SOURCE_VERSION),
                        Set.of(KnowledgeOperatorType.VECTOR), 1_000),
                new EventId("source-event-1"), new IdempotencyKey("idem-knowledge")));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgePortal.KnowledgeCall(
                KNOWLEDGE_CONTEXT, SNAPSHOT, knowledgeTarget(), knowledgeRequest(
                        TENANT, USER, AGENT, SESSION, TRACE, KnowledgeMode.GROUNDING,
                        Set.of("source-2"),
                        Set.of(new KnowledgeSourceVersion("source-2", "version-1")),
                        Set.of(KnowledgeOperatorType.GRAPH), 3_000),
                new EventId("source-event-1"), new IdempotencyKey("idem-knowledge")));
    }

    /** 穷举 Tool、Workflow 与 Knowledge 对 OutcomeUnknown 的完整 RetryMode 白名单。 */
    @Test
    void validatesOutcomeUnknownRetryModes() {
        Set<RetryMode> toolAllowed = Set.of(
                RetryMode.NONE, RetryMode.IDEMPOTENT_BY_STABLE_KEY);
        Set<RetryMode> workflowAllowed = Set.of(
                RetryMode.NONE, RetryMode.RESULT_QUERYABLE);
        Set<RetryMode> knowledgeAllowed = Set.of(RetryMode.NONE);

        for (RetryMode retryMode : RetryMode.values()) {
            if (toolAllowed.contains(retryMode)) {
                assertEquals(retryMode,
                        new ToolPortal.ToolOutcome.OutcomeUnknown(failure(retryMode))
                                .failure().retryMode());
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> new ToolPortal.ToolOutcome.OutcomeUnknown(failure(retryMode)),
                        "Tool / " + retryMode);
            }

            if (workflowAllowed.contains(retryMode)) {
                assertEquals(retryMode,
                        new WorkflowPortal.WorkflowResult.OutcomeUnknown(
                                new ExecutionRef(TENANT, "execution-1"), OptionalLong.empty(),
                                failure(retryMode)).failure().retryMode());
                assertEquals(retryMode,
                        new WorkflowPortal.WorkflowControlResult.OutcomeUnknown(
                                failure(retryMode)).failure().retryMode());
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> new WorkflowPortal.WorkflowResult.OutcomeUnknown(
                                new ExecutionRef(TENANT, "execution-1"), OptionalLong.empty(),
                                failure(retryMode)),
                        "Workflow / " + retryMode);
                assertThrows(IllegalArgumentException.class,
                        () -> new WorkflowPortal.WorkflowControlResult.OutcomeUnknown(
                                failure(retryMode)),
                        "Workflow control / " + retryMode);
            }

            if (knowledgeAllowed.contains(retryMode)) {
                assertEquals(retryMode,
                        new KnowledgePortal.KnowledgeOutcome.OutcomeUnknown(failure(retryMode))
                                .failure().retryMode());
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> new KnowledgePortal.KnowledgeOutcome.OutcomeUnknown(
                                failure(retryMode)),
                        "Knowledge / " + retryMode);
            }
        }
    }

    /** 验证 Workflow 结果、快照和控制结果拒绝非正 revision 或空 Optional。 */
    @Test
    void validatesWorkflowResultFacts() {
        ExecutionRef executionRef = new ExecutionRef(TENANT, "execution-1");
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowResult.Accepted(executionRef, 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkflowPortal.WorkflowSnapshot(
                executionRef, workflowTarget(), WorkflowExecutionState.RUNNING,
                1, null, Instant.parse("2026-09-05T00:00:00Z")));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowControlResult.Conflict(
                        null, failure(RetryMode.NONE)));
    }

    /** 收集接口直接声明的方法名，排除 nested 类型的私有校验方法。 */
    private static Set<String> declaredMethodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
    }

    /** 构造测试使用的完整 Port 调用上下文。 */
    private static PortCallContext context() {
        SessionOwner owner = SessionOwner.user(SESSION, AGENT, USER);
        RunIdentity runIdentity = new RunIdentity(owner, owner.target().requireAgent(), INPUT, new RunRef(TENANT, "run-1"), TRACE, new ResultRef("causation-identity-1"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), INPUT.value()));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(TENANT, USER), owner,
                Set.of("runtime:invoke"), 1, new ResultRef("verification-1"));
        return new PortCallContext(
                runIdentity, identity,
                new InvocationControl(
                        Instant.parse("2026-09-05T00:01:00Z"), () -> false));
    }

    /** 构造指定能力类型的发布引用。 */
    private static CapabilityRef capability(CapabilityType type) {
        return new CapabilityRef(type, "capability-1", "version-1");
    }

    /** 构造测试使用的冻结 Tool 目标。 */
    private static ToolPortal.FrozenToolTarget toolTarget() {
        return new ToolPortal.FrozenToolTarget(
                new ToolPortal.ToolHandle("tool:mount-1"), capability(CapabilityType.TOOL),
                "deployment-1", 1, PAYLOAD, PAYLOAD, ToolEffect.READ_ONLY, true,
                Duration.ofSeconds(2), true, ToolPortal.ToolConfirmationPolicy.NEVER,
                Set.of(), Set.of(), true);
    }

    /** 构造测试使用的冻结 Workflow 目标。 */
    private static WorkflowPortal.FrozenWorkflowTarget workflowTarget() {
        return new WorkflowPortal.FrozenWorkflowTarget(
                new WorkflowPortal.WorkflowHandle("workflow:mount-1"),
                "instance-1", 1, capability(CapabilityType.WORKFLOW), Duration.ofSeconds(2), 1L);
    }

    /** 构造测试使用的冻结 Knowledge 目标。 */
    private static KnowledgePortal.FrozenKnowledgeTarget knowledgeTarget() {
        return new KnowledgePortal.FrozenKnowledgeTarget(
                new KnowledgePortal.KnowledgeHandle("knowledge:mount-1"),
                capability(CapabilityType.KNOWLEDGE_SOURCE), "descriptor-1", 1,
                Set.of(SOURCE_VERSION), Set.of(KnowledgeOperatorType.VECTOR), Duration.ofSeconds(2));
    }

    /** 构造可按维度产生身份、来源、算子或超时漂移的 Knowledge 请求。 */
    private static KnowledgeRequest knowledgeRequest(
            TenantId tenantId,
            EndUserId endUserId,
            AgentId agentId,
            SessionRef sessionRef,
            TraceEnvelope trace,
            KnowledgeMode mode,
            Set<String> sourceIds,
            Set<KnowledgeSourceVersion> sourceVersions,
            Set<KnowledgeOperatorType> operators,
            long timeoutMs) {
        return new KnowledgeRequest("knowledge-request-1", tenantId, new com.skillforge.runtime.api.RunRequester.EndUser(endUserId), agentId, sessionRef, trace, mode, "answer", new com.skillforge.runtime.api.KnowledgeOperation.Search("query"), sourceIds, sourceVersions, operators, new KnowledgeBudget(1, 5, 100, 1_000, timeoutMs), Instant.parse("2026-09-05T00:00:00Z"), Optional.empty(), true);
    }

    /** 构造测试使用的已提交证据引用。 */
    private static EvidenceRef evidenceRef(SessionRef sessionRef) {
        return new EvidenceRef(
                sessionRef, "bundle-1", List.of("item-1"), 1,
                new EventId("evidence-event-1"));
    }

    /** 构造带指定 RetryMode 的稳定 Port 失败事实。 */
    private static PortFailure failure(RetryMode retryMode) {
        return new PortFailure("provider_failure", retryMode, new ResultRef("failure-1"));
    }

    /** 返回 record component 的名称与完整泛型类型，冻结字段顺序和 ABI。 */
    private static List<String> componentSignatures(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + stableTypeName(component))
                .toList();
    }

    /** 压缩稳定 SDK 与 java.lang 前缀，并保留集合与 Optional 泛型参数。 */
    private static String stableTypeName(RecordComponent component) {
        return component.getGenericType().getTypeName()
                .replace("com.skillforge.runtime.api.port.ToolPortal$", "")
                .replace("com.skillforge.runtime.api.port.WorkflowPortal$", "")
                .replace("com.skillforge.runtime.api.port.KnowledgePortal$", "")
                .replace("com.skillforge.runtime.api.port.", "")
                .replace("com.skillforge.runtime.api.", "")
                .replace("java.lang.", "")
                .replace('$', '.');
    }
}
