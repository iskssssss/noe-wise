package com.skillforge.runtime.api.port;
import com.skillforge.runtime.api.PrincipalRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
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
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 冻结Provider原派发上下文、八类outcome注册表及Workflow/Knowledge结果边界。 */
class ProviderOutcomeContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final TenantId OTHER_TENANT = new TenantId("tenant-2");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final SessionOwner OWNER = SessionOwner.user(
            SESSION, new AgentId("agent-1"), new EndUserId(7));
    private static final InputId INPUT = new InputId("input-1");
    private static final ExecutionRef EXECUTION = new ExecutionRef(TENANT, "execution-1");
    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final String OPAQUE_PROVIDER_OUTCOME_BODY =
            "\"opaque-core-owned-provider-outcome-body\"";

    /** 穷举 §8.2 为八种 PortalCallKind 登记的 schema、resolution 与 subtype 正例。 */
    @Test
    void acceptsEveryRegisteredPortalOutcomeTuple() {
        for (PortalCallKind callKind : PortalCallKind.values()) {
            for (PortalResolution resolution : PortalResolution.values()) {
                Set<String> allowedTypes = allowedOutcomeTypes(callKind, resolution);
                for (String outcomeType : allowedTypes) {
                    PortalOutcomeSnapshot snapshot = new PortalOutcomeSnapshot(
                            callKind,
                            resolution,
                            outcomeType,
                            payload(schemaId(callKind)));
                    assertEquals(callKind, snapshot.callKind());
                    assertEquals(resolution, snapshot.resolution());
                    assertEquals(outcomeType, snapshot.outcomeType());
                    assertEquals(schemaId(callKind), snapshot.outcome().schemaId());
                }
            }
        }
    }

    /** 对每种调用拒绝错 schema、错确定性与未登记 subtype，防止 wrapper 名称偷换语义。 */
    @Test
    void rejectsUnregisteredPortalOutcomeTuples() {
        Set<String> allTypes = Set.of(
                "Completed", "Failed", "OutcomeUnknown", "Succeeded", "InputRequired", "Accepted",
                "Suspended", "Terminated", "Cancelled", "Absent", "Rejected",
                "Applied", "Replayed", "Conflict", "Retrieved");
        for (PortalCallKind callKind : PortalCallKind.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PortalOutcomeSnapshot(
                            callKind,
                            PortalResolution.KNOWN_RESULT,
                            firstAllowed(callKind, PortalResolution.KNOWN_RESULT),
                            payload("skillforge.runtime.portal.wrong-outcome.v1")),
                    callKind + " wrong schema");

            // 每个 resolution 都拒绝同一 call kind 下没有登记的所有 wrapper 名称。
            for (PortalResolution resolution : PortalResolution.values()) {
                Set<String> allowed = allowedOutcomeTypes(callKind, resolution);
                for (String candidate : allTypes) {
                    if (!allowed.contains(candidate)) {
                        assertThrows(IllegalArgumentException.class,
                                () -> new PortalOutcomeSnapshot(
                                        callKind,
                                        resolution,
                                        candidate,
                                        payload(schemaId(callKind))),
                                callKind + " / " + resolution + " / " + candidate);
                    }
                }
            }
        }
        assertTrue(allowedOutcomeTypes(
                PortalCallKind.WORKFLOW_RECOVER_RESULT,
                PortalResolution.OUTCOME_UNKNOWN).isEmpty());
    }

    /** 验证 39 个 tuple 的 schema 与公开 result class 封套登记双向完整。 */
    @Test
    void freezesProviderOutcomeEnvelopeFixtures() {
        Set<ProviderOutcomeCodecFixtures.TupleKey> expectedKeys = new HashSet<>();
        for (PortalCallKind callKind : PortalCallKind.values()) {
            for (PortalResolution resolution : PortalResolution.values()) {
                for (String outcomeType : allowedOutcomeTypes(callKind, resolution)) {
                    expectedKeys.add(new ProviderOutcomeCodecFixtures.TupleKey(
                            callKind, resolution, outcomeType));
                }
            }
        }
        assertEquals(39, expectedKeys.size());
        assertEquals(expectedKeys, ProviderOutcomeCodecFixtures.tupleKeys());
        assertEquals(expectedKeys.size(), ProviderOutcomeCodecFixtures.fixtures().size());

        for (ProviderOutcomeCodecFixtures.Fixture fixture
                : ProviderOutcomeCodecFixtures.fixtures()) {
            ProviderOutcomeCodecFixtures.TupleKey key = fixture.tupleKey();
            assertEquals(schemaId(key.callKind()), fixture.schemaId());
            assertEquals(publicOutcomeRecordType(key), fixture.resultClass());
            assertEquals(fixture,
                    ProviderOutcomeCodecFixtures.require(
                            key.callKind(), key.resolution(), key.outcomeType()));

            // API 测试只验证 snapshot 封套；body 是明确的 opaque 占位，结构与 canonical
            // 互操作由 Core registry 独占验证，本断言不证明任何 outcome body wire 格式。
            TypedPayload opaqueOutcome =
                    new TypedPayload(fixture.schemaId(), OPAQUE_PROVIDER_OUTCOME_BODY);
            PortalOutcomeSnapshot snapshot = new PortalOutcomeSnapshot(
                    key.callKind(), key.resolution(), key.outcomeType(),
                    opaqueOutcome);
            assertEquals(opaqueOutcome, snapshot.outcome());
        }
        assertThrows(IllegalArgumentException.class,
                () -> ProviderOutcomeCodecFixtures.require(
                        PortalCallKind.WORKFLOW_RECOVER_RESULT,
                        PortalResolution.OUTCOME_UNKNOWN,
                        "OutcomeUnknown"));
    }

    /** 返回 tuple 对应的公开 result record，固定 fixture 与 SDK subtype 的直接映射。 */
    private static Class<? extends Record> publicOutcomeRecordType(
            ProviderOutcomeCodecFixtures.TupleKey key) {
        return switch (key.callKind()) {
            case MODEL_GENERATE, MODEL_STREAM -> switch (key.outcomeType()) {
                case "Completed" -> ModelPort.ModelResult.Completed.class;
                case "Failed" -> ModelPort.ModelResult.Failed.class;
                case "OutcomeUnknown" -> ModelPort.ModelResult.OutcomeUnknown.class;
                default -> throw new IllegalArgumentException("unknown Model outcome fixture");
            };
            case TOOL_INVOKE -> switch (key.outcomeType()) {
                case "Succeeded" -> ToolPortal.ToolOutcome.Succeeded.class;
                case "InputRequired" -> ToolPortal.ToolOutcome.InputRequired.class;
                case "Failed" -> ToolPortal.ToolOutcome.Failed.class;
                case "OutcomeUnknown" -> ToolPortal.ToolOutcome.OutcomeUnknown.class;
                default -> throw new IllegalArgumentException("unknown Tool outcome fixture");
            };
            case WORKFLOW_DISPATCH, WORKFLOW_RESUME -> switch (key.outcomeType()) {
                case "Accepted" -> WorkflowPortal.WorkflowResult.Accepted.class;
                case "Suspended" -> WorkflowPortal.WorkflowResult.Suspended.class;
                case "Completed" -> WorkflowPortal.WorkflowResult.Completed.class;
                case "Failed" -> WorkflowPortal.WorkflowResult.Failed.class;
                case "Terminated" -> WorkflowPortal.WorkflowResult.Terminated.class;
                case "Cancelled" -> WorkflowPortal.WorkflowResult.Cancelled.class;
                case "OutcomeUnknown" -> WorkflowPortal.WorkflowResult.OutcomeUnknown.class;
                default -> throw new IllegalArgumentException("unknown Workflow outcome fixture");
            };
            case WORKFLOW_RECOVER_RESULT -> switch (key.outcomeType()) {
                case "Suspended" -> WorkflowPortal.WorkflowRecoveredResult.Suspended.class;
                case "Completed" -> WorkflowPortal.WorkflowRecoveredResult.Completed.class;
                case "Failed" -> WorkflowPortal.WorkflowRecoveredResult.Failed.class;
                case "Terminated" -> WorkflowPortal.WorkflowRecoveredResult.Terminated.class;
                case "Cancelled" -> WorkflowPortal.WorkflowRecoveredResult.Cancelled.class;
                case "Absent" -> WorkflowPortal.WorkflowRecoveredResult.Absent.class;
                case "Rejected" -> WorkflowPortal.WorkflowRecoveredResult.Rejected.class;
                default -> throw new IllegalArgumentException("unknown recover outcome fixture");
            };
            case WORKFLOW_CANCEL -> switch (key.outcomeType()) {
                case "Applied" -> WorkflowPortal.WorkflowControlResult.Applied.class;
                case "Replayed" -> WorkflowPortal.WorkflowControlResult.Replayed.class;
                case "Conflict" -> WorkflowPortal.WorkflowControlResult.Conflict.class;
                case "Rejected" -> WorkflowPortal.WorkflowControlResult.Rejected.class;
                case "OutcomeUnknown" -> WorkflowPortal.WorkflowControlResult.OutcomeUnknown.class;
                default -> throw new IllegalArgumentException("unknown control outcome fixture");
            };
            case KNOWLEDGE_EXECUTE -> switch (key.outcomeType()) {
                case "Retrieved" -> KnowledgePortal.KnowledgeOutcome.Retrieved.class;
                case "Failed" -> KnowledgePortal.KnowledgeOutcome.Failed.class;
                case "OutcomeUnknown" -> KnowledgePortal.KnowledgeOutcome.OutcomeUnknown.class;
                default -> throw new IllegalArgumentException("unknown Knowledge outcome fixture");
            };
        };
    }

    /** 冻结 PortalCallKind、PortalResolution 与 PortalOutcomeSnapshot 的公开形状。 */
    @Test
    void freezesProviderOutcomeAbi() {
        assertEquals(List.of(
                        "MODEL_GENERATE",
                        "MODEL_STREAM",
                        "TOOL_INVOKE",
                        "WORKFLOW_DISPATCH",
                        "WORKFLOW_RESUME",
                        "WORKFLOW_RECOVER_RESULT",
                        "WORKFLOW_CANCEL",
                        "KNOWLEDGE_EXECUTE"),
                Arrays.stream(PortalCallKind.values()).map(Enum::name).toList());
        assertEquals(List.of("KNOWN_RESULT", "INPUT_REQUIRED", "KNOWN_FAILURE", "OUTCOME_UNKNOWN"),
                Arrays.stream(PortalResolution.values()).map(Enum::name).toList());
        assertEquals(List.of(
                        "callKind:PortalCallKind",
                        "resolution:PortalResolution",
                        "outcomeType:String",
                        "outcome:TypedPayload"),
                Arrays.stream(PortalOutcomeSnapshot.class.getRecordComponents())
                        .map(component -> component.getName() + ":"
                                + component.getType().getSimpleName())
                        .toList());
        assertEquals(List.of(
                        "callContext:PortCallContext",
                        "callKind:PortalCallKind",
                        "dispatch:ProviderDispatch",
                        "dispatched:ProviderCallContext"),
                Arrays.stream(ProviderCallContext.class.getDeclaredMethods())
                        .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())
                                && List.of("callContext", "callKind", "dispatch", "dispatched").contains(method.getName()))
                        .map(method -> method.getName() + ":" + method.getReturnType().getSimpleName())
                        .sorted()
                        .toList());
    }

    /** 验证Provider上下文只接受与当前Run和Action租户闭合的原派发事实。 */
    @Test
    void validatesProviderCallContextIdentityClosure() {
        PortCallContext context = callContext(TENANT, "run-1");
        ProviderCallContext provider = ProviderDispatchTestFixtures.providerContext(
                context, PortalCallKind.MODEL_GENERATE);
        assertEquals(context, provider.callContext());
        assertEquals(context.runIdentity().runRef(), provider.dispatch().run());
        assertEquals(context.runIdentity().runRef().tenantId(), provider.dispatch().action().tenantId());

        PortCallContext otherContext = callContext(OTHER_TENANT, "run-2");
        ProviderDispatch foreignDispatch =
                ProviderDispatchTestFixtures.providerContext(
                        otherContext, PortalCallKind.MODEL_GENERATE).dispatch();
        assertThrows(IllegalArgumentException.class,
                () -> ProviderCallContext.dispatched(context, foreignDispatch));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderCallContext.dispatched(context, null));
    }

    /** 验证 ProviderInvocationException 只暴露稳定原因与审计引用，不携带底层 cause/secret。 */
    @Test
    void validatesProviderInvocationExceptionContract() {
        ResultRef auditRef = new ResultRef("audit-1");
        ProviderInvocationException exception =
                new ProviderInvocationException("permit_not_consumed", auditRef);
        assertEquals("permit_not_consumed", exception.reasonCode());
        assertEquals(auditRef, exception.auditRef());
        assertEquals("permit_not_consumed", exception.getMessage());
        assertEquals(null, exception.getCause());
        assertFalse(exception.getMessage().contains("secret"));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderInvocationException(" ", auditRef));
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderInvocationException("permit_not_consumed", null));

        Constructor<?>[] constructors = ProviderInvocationException.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPublic(constructors[0].getModifiers()));
        assertEquals(List.of(String.class, ResultRef.class),
                Arrays.asList(constructors[0].getParameterTypes()));
    }

    /** 验证 Workflow Absent 只能携带 attempt 级线性化屏障且身份不可互相冒充。 */
    @Test
    void validatesWorkflowInvocationBarrierProof() {
        WorkflowPortal.InvocationBarrierProof proof = barrierProof(TENANT);
        WorkflowPortal.WorkflowRecoveredResult.Absent absent =
                new WorkflowPortal.WorkflowRecoveredResult.Absent(EXECUTION, proof);
        assertEquals(EXECUTION, absent.executionRef());
        assertEquals("execute-attempt-1", absent.barrierProof().blockedExecuteAttemptId());
        assertNotEquals(
                absent.barrierProof().invocationId(),
                absent.barrierProof().blockedExecuteAttemptId());

        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.InvocationBarrierProof(
                        new ActionRef(TENANT, "action-1"),
                        "same-id",
                        "same-id",
                        "request-fingerprint-1",
                        new ResultRef("reconciliation-1"),
                        new ResultRef("barrier-1")));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowRecoveredResult.Absent(
                        EXECUTION, barrierProof(OTHER_TENANT)));
    }

    /** 验证 recover 终态、revision 与 cancel control 的封闭结果规则。 */
    @Test
    void validatesWorkflowRecoverRevisionAndControlResults() {
        PortFailure noneFailure = failure(RetryMode.NONE);
        WorkflowPortal.WorkflowResult.Failed recoveredFailure =
                new WorkflowPortal.WorkflowResult.Failed(
                        EXECUTION, OptionalLong.of(3), noneFailure);
        assertEquals(recoveredFailure,
                new WorkflowPortal.WorkflowRecoveredResult.Failed(recoveredFailure).result());
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowRecoveredResult.Failed(
                        new WorkflowPortal.WorkflowResult.Failed(
                                EXECUTION, OptionalLong.empty(), noneFailure)));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowRecoveredResult.Failed(
                        new WorkflowPortal.WorkflowResult.Failed(
                                EXECUTION,
                                OptionalLong.of(3),
                                failure(RetryMode.RESULT_QUERYABLE))));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowResult.Failed(
                        EXECUTION, OptionalLong.of(0), noneFailure));

        WorkflowPortal.WorkflowSnapshot cancelled = workflowSnapshot(
                WorkflowExecutionState.CANCELLED, 4);
        assertEquals(cancelled,
                new WorkflowPortal.WorkflowControlResult.Applied(cancelled).execution());
        assertEquals(cancelled,
                new WorkflowPortal.WorkflowControlResult.Replayed(cancelled).execution());
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowControlResult.Applied(
                        workflowSnapshot(WorkflowExecutionState.RUNNING, 4)));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowControlResult.Replayed(
                        workflowSnapshot(WorkflowExecutionState.RUNNING, 4)));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowControlResult.Conflict(
                        workflowSnapshot(WorkflowExecutionState.RUNNING, 4),
                        failure(RetryMode.RESULT_QUERYABLE)));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowControlResult.OutcomeUnknown(
                        failure(RetryMode.IDEMPOTENT_BY_STABLE_KEY)));
    }

    /** 验证 Workflow resume/cancel 必须携带正 expectedRevision 和对应冻结上下文。 */
    @Test
    void validatesWorkflowCommandRevisionBoundary() {
        ProviderCallContext resumeContext = ProviderDispatchTestFixtures.providerContext(
                callContext(TENANT, "run-1"), PortalCallKind.WORKFLOW_RESUME);
        CapabilitySnapshotRef snapshot = capabilitySnapshot();
        WorkflowPortal.FrozenWorkflowTarget target = workflowTarget();
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowResume(
                        resumeContext,
                        snapshot,
                        target,
                        EXECUTION,
                        "suspend-1",
                        "action-key-1",
                        payload("skillforge.runtime.workflow-response.v1"),
                        0,
                        new IdempotencyKey("resume-1")));

        ProviderCallContext cancelContext = ProviderDispatchTestFixtures.providerContext(
                callContext(TENANT, "run-1"), PortalCallKind.WORKFLOW_CANCEL);
        assertThrows(IllegalArgumentException.class,
                () -> new WorkflowPortal.WorkflowCancel(
                        cancelContext,
                        snapshot,
                        target,
                        EXECUTION,
                        "action-key-1",
                        0,
                        "cancelled_by_user",
                        new IdempotencyKey("cancel-1")));
    }

    /** 验证 Knowledge 禁用模式不进入 Portal，未知结果也只能显式 NONE 停止自动动作。 */
    @Test
    void enforcesKnowledgeNoneOnlyFailureSemantics() {
        assertEquals(RetryMode.NONE,
                new KnowledgePortal.KnowledgeOutcome.OutcomeUnknown(
                        failure(RetryMode.NONE)).failure().retryMode());
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgePortal.KnowledgeOutcome.OutcomeUnknown(
                        failure(RetryMode.RESULT_QUERYABLE)));
        assertThrows(IllegalArgumentException.class,
                () -> new KnowledgePortal.KnowledgeOutcome.OutcomeUnknown(
                        failure(RetryMode.IDEMPOTENT_BY_STABLE_KEY)));
        assertThrows(IllegalArgumentException.class,
                () -> knowledgeCall(KnowledgeMode.NONE));
        assertEquals(KnowledgeMode.GROUNDING,
                knowledgeCall(KnowledgeMode.GROUNDING).request().mode());
    }

    /** 返回 §8.2 中某一 call kind/resolution 的完整 subtype 集合。 */
    private static Set<String> allowedOutcomeTypes(
            PortalCallKind callKind, PortalResolution resolution) {
        return switch (callKind) {
            case MODEL_GENERATE, MODEL_STREAM -> switch (resolution) {
                case KNOWN_RESULT -> Set.of("Completed");
                case INPUT_REQUIRED -> Set.of();
                case KNOWN_FAILURE -> Set.of("Failed");
                case OUTCOME_UNKNOWN -> Set.of("OutcomeUnknown");
            };
            case TOOL_INVOKE -> switch (resolution) {
                case KNOWN_RESULT -> Set.of("Succeeded");
                case INPUT_REQUIRED -> Set.of("InputRequired");
                case KNOWN_FAILURE -> Set.of("Failed");
                case OUTCOME_UNKNOWN -> Set.of("OutcomeUnknown");
            };
            case WORKFLOW_DISPATCH, WORKFLOW_RESUME -> switch (resolution) {
                case KNOWN_RESULT -> Set.of(
                        "Accepted", "Suspended", "Completed", "Terminated", "Cancelled");
                case INPUT_REQUIRED -> Set.of();
                case KNOWN_FAILURE -> Set.of("Failed");
                case OUTCOME_UNKNOWN -> Set.of("OutcomeUnknown");
            };
            case WORKFLOW_RECOVER_RESULT -> switch (resolution) {
                case KNOWN_RESULT -> Set.of("Suspended", "Completed", "Terminated", "Cancelled", "Absent");
                case INPUT_REQUIRED -> Set.of();
                case KNOWN_FAILURE -> Set.of("Failed", "Rejected");
                case OUTCOME_UNKNOWN -> Set.of();
            };
            case WORKFLOW_CANCEL -> switch (resolution) {
                case KNOWN_RESULT -> Set.of("Applied", "Replayed");
                case INPUT_REQUIRED -> Set.of();
                case KNOWN_FAILURE -> Set.of("Conflict", "Rejected");
                case OUTCOME_UNKNOWN -> Set.of("OutcomeUnknown");
            };
            case KNOWLEDGE_EXECUTE -> switch (resolution) {
                case KNOWN_RESULT -> Set.of("Retrieved");
                case INPUT_REQUIRED -> Set.of();
                case KNOWN_FAILURE -> Set.of("Failed");
                case OUTCOME_UNKNOWN -> Set.of("OutcomeUnknown");
            };
        };
    }

    /** 返回一个已登记 subtype，供 schema 错配负例使用。 */
    private static String firstAllowed(
            PortalCallKind callKind, PortalResolution resolution) {
        return allowedOutcomeTypes(callKind, resolution).iterator().next();
    }

    /** 返回 §8.2 为 call kind 登记的唯一私有 schema identity。 */
    private static String schemaId(PortalCallKind callKind) {
        return switch (callKind) {
            case MODEL_GENERATE, MODEL_STREAM -> "skillforge.runtime.portal.model-outcome.v5";
            case TOOL_INVOKE -> "skillforge.runtime.portal.tool-outcome.v2";
            case WORKFLOW_DISPATCH, WORKFLOW_RESUME ->
                    "skillforge.runtime.portal.workflow-invoke-outcome.v2";
            case WORKFLOW_RECOVER_RESULT ->
                    "skillforge.runtime.portal.workflow-recover-outcome.v2";
            case WORKFLOW_CANCEL -> "skillforge.runtime.portal.workflow-control-outcome.v1";
            case KNOWLEDGE_EXECUTE -> "skillforge.runtime.portal.knowledge-outcome.v2";
        };
    }

    /** 构造与指定 tenant 闭合的普通调用上下文。 */
    private static PortCallContext callContext(TenantId tenantId, String runId) {
        SessionOwner owner = tenantId.equals(TENANT)
                ? OWNER
                : SessionOwner.user(
                        new SessionRef(tenantId, "session-2"),
                        new AgentId("agent-2"),
                        new EndUserId(8));
        InputId inputId = tenantId.equals(TENANT) ? INPUT : new InputId("input-2");
        TraceEnvelope trace = new TraceEnvelope(
                "trace-" + runId,
                "span-" + runId,
                inputId,
                Optional.of(new EventId("event-" + runId)));
        RunIdentity runIdentity = new RunIdentity(owner, owner.target().requireAgent(), inputId, new RunRef(tenantId, runId), trace, new ResultRef("causation-" + runId), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), inputId.value()));
        VerifiedIdentity identity = new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(tenantId, owner.requireUser()),
                owner,
                Set.of("runtime:invoke"),
                1,
                new ResultRef("verification-" + runId));
        return new PortCallContext(
                runIdentity,
                identity,
                new InvocationControl(NOW.plusSeconds(30), () -> false));
    }

    /** 构造 current Session/Input 的能力快照引用。 */
    private static CapabilitySnapshotRef capabilitySnapshot() {
        return new CapabilitySnapshotRef(SESSION, INPUT, "snapshot-1", 1);
    }

    /** 构造冻结 Workflow 目标。 */
    private static WorkflowPortal.FrozenWorkflowTarget workflowTarget() {
        return new WorkflowPortal.FrozenWorkflowTarget(
                new WorkflowPortal.WorkflowHandle("workflow:mount-1"),
                "instance-1",
                1,
                new CapabilityRef(CapabilityType.WORKFLOW, "workflow-1", "version-1"),
                Duration.ofSeconds(30), 1L);
    }

    /** 构造 Workflow 当前快照。 */
    private static WorkflowPortal.WorkflowSnapshot workflowSnapshot(
            WorkflowExecutionState state, long revision) {
        return new WorkflowPortal.WorkflowSnapshot(
                EXECUTION,
                workflowTarget(),
                state,
                revision,
                state == WorkflowExecutionState.CANCELLED
                        ? Optional.of(new ResultRef("result-1"))
                        : Optional.empty(),
                NOW);
    }

    /** 构造与 invocation/attempt/fingerprint 逐项绑定的 Workflow barrier proof。 */
    private static WorkflowPortal.InvocationBarrierProof barrierProof(TenantId tenantId) {
        return new WorkflowPortal.InvocationBarrierProof(
                new ActionRef(tenantId, "action-1"),
                "invocation-1",
                "execute-attempt-1",
                "request-fingerprint-1",
                new ResultRef("reconciliation-1"),
                new ResultRef("barrier-1"));
    }

    /** 构造 Knowledge 调用，固定 target/source/operator 并只切换请求 mode。 */
    private static KnowledgePortal.KnowledgeCall knowledgeCall(KnowledgeMode mode) {
        PortCallContext callContext = callContext(TENANT, "run-1");
        KnowledgeSourceVersion sourceVersion = new KnowledgeSourceVersion("source-1", "v1");
        KnowledgePortal.FrozenKnowledgeTarget target =
                new KnowledgePortal.FrozenKnowledgeTarget(
                        new KnowledgePortal.KnowledgeHandle("knowledge:mount-1"),
                        new CapabilityRef(
                                CapabilityType.KNOWLEDGE_SOURCE,
                                "knowledge-1",
                                "version-1"),
                        "descriptor-1",
                        1,
                        Set.of(sourceVersion),
                        Set.of(KnowledgeOperatorType.VECTOR),
                        Duration.ofSeconds(10));
        KnowledgeRequest request = new KnowledgeRequest("knowledge-request-1", TENANT, new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), OWNER.target().requireAgent(), SESSION, callContext.runIdentity().trace(), mode, "answer", new com.skillforge.runtime.api.KnowledgeOperation.Search("query"), Set.of("source-1"), Set.of(sourceVersion), Set.of(KnowledgeOperatorType.VECTOR), new KnowledgeBudget(1, 3, 100, 1000, 1000), NOW, Optional.empty(), true);
        return new KnowledgePortal.KnowledgeCall(
                ProviderDispatchTestFixtures.providerContext(
                        callContext, PortalCallKind.KNOWLEDGE_EXECUTE),
                capabilitySnapshot(),
                target,
                request,
                new EventId("knowledge-source-event-1"),
                new IdempotencyKey("knowledge-key-1"));
    }

    /** 构造稳定 Port failure。 */
    private static PortFailure failure(RetryMode retryMode) {
        return new PortFailure("provider_failure", retryMode, new ResultRef("failure-1"));
    }

    /** 构造不可变动态载荷。 */
    private static TypedPayload payload(String schemaId) {
        return new TypedPayload(schemaId, "{}");
    }
}
