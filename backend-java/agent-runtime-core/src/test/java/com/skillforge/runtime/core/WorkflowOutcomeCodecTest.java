package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.LoopTerminationReason;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.PortFailure;
import com.skillforge.runtime.api.port.PortalCallKind;
import com.skillforge.runtime.api.port.PortalOutcomeSnapshot;
import com.skillforge.runtime.api.port.PortalResolution;
import com.skillforge.runtime.api.port.RetryMode;
import com.skillforge.runtime.api.port.WorkflowExecutionState;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.InvocationBarrierProof;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowControlResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowResult;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowSnapshot;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowRecoveredResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 独立手写 wire fixture 覆盖全部 25 个 Workflow tuple，不把 codec 自身当成预期值生成器。 */
class WorkflowOutcomeCodecTest {
    private static final CodecLimits LIMITS = new CodecLimits(200000, 20000, 32);
    private static final String INVOKE_SCHEMA = "skillforge.runtime.portal.workflow-invoke-outcome.v2";
    private static final String RECOVER_SCHEMA = "skillforge.runtime.portal.workflow-recover-outcome.v2";
    private static final String CONTROL_SCHEMA = "skillforge.runtime.portal.workflow-control-outcome.v1";
    private static final TenantId TENANT = new TenantId("tenant");
    private static final ExecutionRef EXECUTION = new ExecutionRef(TENANT, "exec");
    private static final TypedPayload OUTPUT = new TypedPayload(
            "workflow.output.v1", "{\"answer\":\"你好🌍\"}");
    private static final String EXECUTION_JSON =
            "{\"executionId\":\"exec\",\"tenantId\":\"tenant\"}";
    private static final String OUTPUT_JSON =
            "{\"canonicalJson\":\"{\\\"answer\\\":\\\"你好🌍\\\"}\",\"schemaId\":\"workflow.output.v1\"}";
    private static final String FAILURE_NONE_JSON =
            "{\"reasonCode\":\"declined\",\"resultRef\":\"audit\",\"retryMode\":\"NONE\"}";
    private static final String ACCEPTED_JSON = "{\"executionRef\":" + EXECUTION_JSON
            + ",\"outcomeType\":\"Accepted\",\"revision\":\"1\"}";
    private static final String SUSPENDED_JSON = "{\"executionRef\":" + EXECUTION_JSON
            + ",\"outcomeType\":\"Suspended\",\"reason\":\"WORKFLOW_HITL\",\"resultRef\":\"suspended\",\"revision\":\"2\",\"suspendRef\":\"wait\"}";
    private static final String COMPLETED_JSON = "{\"executionRef\":" + EXECUTION_JSON
            + ",\"outcomeType\":\"Completed\",\"output\":" + OUTPUT_JSON
            + ",\"resultRef\":\"completed\",\"revision\":\"9007199254740993\"}";
    private static final String FAILED_EMPTY_JSON = "{\"executionRef\":" + EXECUTION_JSON
            + ",\"failure\":" + FAILURE_NONE_JSON
            + ",\"outcomeType\":\"Failed\",\"revision\":null}";
    private static final String FAILED_PRESENT_JSON = "{\"executionRef\":" + EXECUTION_JSON
            + ",\"failure\":" + FAILURE_NONE_JSON
            + ",\"outcomeType\":\"Failed\",\"revision\":\"7\"}";
    private static final String TERMINATED_JSON = "{\"executionRef\":" + EXECUTION_JSON
            + ",\"outcomeType\":\"Terminated\",\"reason\":\"WORKFLOW_MAX_STEPS_REACHED\","
            + "\"resultRef\":\"terminated\",\"revision\":\"9223372036854775807\"}";
    private static final String CANCELLED_JSON = "{\"executionRef\":" + EXECUTION_JSON
            + ",\"outcomeType\":\"Cancelled\",\"resultRef\":\"cancelled\",\"revision\":\"5\"}";
    private static final String UNKNOWN_JSON = "{\"executionRef\":" + EXECUTION_JSON
            + ",\"failure\":" + FAILURE_NONE_JSON
            + ",\"outcomeType\":\"OutcomeUnknown\",\"revision\":\"6\"}";
    private static final Instant UPDATED_AT = Instant.parse("2026-09-05T01:02:03.123456789Z");

    /** 新原因保持Workflow专属语义，旧版本和旧枚举均明确拒绝。 */
    @Test
    void workflowLimitsUseCurrentReasonsAndRejectOldProtocol() {
        for (var reason : List.of(LoopTerminationReason.WORKFLOW_MAX_STEPS_REACHED,
                LoopTerminationReason.WORKFLOW_NODE_REVISIT)) {
            var original = new WorkflowResult.Terminated(EXECUTION, 3, reason, new ResultRef("limit-result"));
            var snapshot = codec().encodeInvoke(PortalCallKind.WORKFLOW_DISPATCH, original);
            assertEquals(original, codec().decodeInvoke(snapshot, PortalCallKind.WORKFLOW_DISPATCH));
            var recovered = new WorkflowRecoveredResult.Terminated(original);
            assertEquals(recovered, codec().decodeRecover(codec().encodeRecover(
                    PortalCallKind.WORKFLOW_RECOVER_RESULT, recovered), PortalCallKind.WORKFLOW_RECOVER_RESULT));
        }
        assertThrows(IllegalArgumentException.class, () -> new PortalOutcomeSnapshot(PortalCallKind.WORKFLOW_DISPATCH,
                PortalResolution.KNOWN_RESULT, "Terminated", new TypedPayload(
                        "skillforge.runtime.portal.workflow-invoke-outcome.v1", TERMINATED_JSON)));
        assertThrows(IllegalArgumentException.class, () -> new PortalOutcomeSnapshot(PortalCallKind.WORKFLOW_RECOVER_RESULT,
                PortalResolution.KNOWN_RESULT, "Terminated", new TypedPayload(
                        "skillforge.runtime.portal.workflow-recover-outcome.v1", "{\"outcomeType\":\"Terminated\",\"result\":" + TERMINATED_JSON + "}")));
        assertInvokeRejected(manualInvoke(PortalCallKind.WORKFLOW_DISPATCH, "Terminated",
                TERMINATED_JSON.replace("WORKFLOW_MAX_STEPS_REACHED", "MAX_ITERATIONS_REACHED")));
    }

    /** dispatch 与 resume 各覆盖七种 subtype，recover 六种、control 五种，共 25 个外层 tuple。 */
    @Test
    void exactGoldenForAllTwentyFiveTuples() {
        // invoke 的同一 body 在两个外层 kind 下各自形成独立 tuple。
        for (PortalCallKind kind : List.of(
                PortalCallKind.WORKFLOW_DISPATCH, PortalCallKind.WORKFLOW_RESUME)) {
            assertInvokeGolden(kind, accepted(), "Accepted", ACCEPTED_JSON);
            assertInvokeGolden(kind, suspended(), "Suspended", SUSPENDED_JSON);
            assertInvokeGolden(kind, completed(), "Completed", COMPLETED_JSON);
            assertInvokeGolden(kind, failed(OptionalLong.empty(), RetryMode.NONE),
                    "Failed", FAILED_EMPTY_JSON);
            assertInvokeGolden(kind, terminated(), "Terminated", TERMINATED_JSON);
            assertInvokeGolden(kind, cancelled(), "Cancelled", CANCELLED_JSON);
            assertInvokeGolden(kind, unknown(OptionalLong.of(6), RetryMode.NONE),
                    "OutcomeUnknown", UNKNOWN_JSON);
        }

        // recover 四个 wrapper 保留完整 result body，Absent 与 Rejected 使用各自结构。
        assertRecoverGolden(new WorkflowRecoveredResult.Suspended(suspended()), "Suspended",
                "{\"outcomeType\":\"Suspended\",\"result\":" + SUSPENDED_JSON + "}");
        assertRecoverGolden(new WorkflowRecoveredResult.Completed(completed()), "Completed",
                "{\"outcomeType\":\"Completed\",\"result\":" + COMPLETED_JSON + "}");
        assertRecoverGolden(new WorkflowRecoveredResult.Failed(
                        failed(OptionalLong.of(7), RetryMode.NONE)),
                "Failed", "{\"outcomeType\":\"Failed\",\"result\":" + FAILED_PRESENT_JSON + "}");
        assertRecoverGolden(new WorkflowRecoveredResult.Terminated(terminated()), "Terminated",
                "{\"outcomeType\":\"Terminated\",\"result\":" + TERMINATED_JSON + "}");
        assertRecoverGolden(new WorkflowRecoveredResult.Cancelled(cancelled()), "Cancelled",
                "{\"outcomeType\":\"Cancelled\",\"result\":" + CANCELLED_JSON + "}");
        assertRecoverGolden(absent(), "Absent", absentJson("tenant"));
        assertRecoverGolden(new WorkflowRecoveredResult.Rejected(failure(RetryMode.NONE)),
                "Rejected", "{\"failure\":" + FAILURE_NONE_JSON + ",\"outcomeType\":\"Rejected\"}");

        // control fixture 同时固定完整 target、Optional resultRef、Duration、Instant 与状态。
        assertControlGolden(new WorkflowControlResult.Applied(cancelledSnapshot(Optional.empty(), 9)),
                "Applied", "{\"execution\":" + snapshotJson("CANCELLED", "9", "null")
                        + ",\"outcomeType\":\"Applied\"}");
        assertControlGolden(new WorkflowControlResult.Replayed(
                        cancelledSnapshot(Optional.of(new ResultRef("cancel-result")), 10)),
                "Replayed", "{\"execution\":" + snapshotJson(
                        "CANCELLED", "10", "\"cancel-result\"")
                        + ",\"outcomeType\":\"Replayed\"}");
        assertControlGolden(new WorkflowControlResult.Conflict(
                        workflowSnapshot(WorkflowExecutionState.RUNNING, Optional.of(new ResultRef("current")), 11),
                        failure(RetryMode.NONE)),
                "Conflict", "{\"current\":" + snapshotJson("RUNNING", "11", "\"current\"")
                        + ",\"failure\":" + FAILURE_NONE_JSON
                        + ",\"outcomeType\":\"Conflict\"}");
        assertControlGolden(new WorkflowControlResult.Rejected(failure(RetryMode.COORDINATED)),
                "Rejected", controlFailureJson("Rejected", "COORDINATED"));
        assertControlGolden(new WorkflowControlResult.OutcomeUnknown(
                        failure(RetryMode.RESULT_QUERYABLE)),
                "OutcomeUnknown", controlFailureJson("OutcomeUnknown", "RESULT_QUERYABLE"));
    }

    /** API 允许的 RetryMode 与 OptionalLong presence 全部逐项保留，不套用 Model 的 NONE 限制。 */
    @Test
    void preservesRetryAndPresenceMatrices() {
        for (RetryMode mode : RetryMode.values()) {
            // Workflow invoke Failed 对四种模式、空/有 revision 都无损往返。
            for (OptionalLong revision : List.of(OptionalLong.empty(), OptionalLong.of(17))) {
                WorkflowResult.Failed value = failed(revision, mode);
                assertEquals(value, codec().decodeInvoke(
                        codec().encodeInvoke(PortalCallKind.WORKFLOW_DISPATCH, value),
                        PortalCallKind.WORKFLOW_DISPATCH));
            }
            // recover Rejected 和 cancel Rejected 都保留所有公开 API 模式。
            WorkflowRecoveredResult.Rejected recover =
                    new WorkflowRecoveredResult.Rejected(failure(mode));
            assertEquals(recover, codec().decodeRecover(
                    codec().encodeRecover(PortalCallKind.WORKFLOW_RECOVER_RESULT, recover),
                    PortalCallKind.WORKFLOW_RECOVER_RESULT));
            WorkflowControlResult.Rejected control =
                    new WorkflowControlResult.Rejected(failure(mode));
            assertEquals(control, codec().decodeControl(
                    codec().encodeControl(PortalCallKind.WORKFLOW_CANCEL, control),
                    PortalCallKind.WORKFLOW_CANCEL));
        }

        // Unknown 只接受 API 冻结的 NONE/RESULT_QUERYABLE，但两种 revision presence 都有效。
        for (RetryMode mode : List.of(RetryMode.NONE, RetryMode.RESULT_QUERYABLE)) {
            for (OptionalLong revision : List.of(OptionalLong.empty(), OptionalLong.of(Long.MAX_VALUE))) {
                WorkflowResult.OutcomeUnknown value = unknown(revision, mode);
                assertEquals(value, codec().decodeInvoke(
                        codec().encodeInvoke(PortalCallKind.WORKFLOW_RESUME, value),
                        PortalCallKind.WORKFLOW_RESUME));
            }
            WorkflowControlResult.OutcomeUnknown control =
                    new WorkflowControlResult.OutcomeUnknown(failure(mode));
            assertEquals(control, codec().decodeControl(
                    codec().encodeControl(PortalCallKind.WORKFLOW_CANCEL, control),
                    PortalCallKind.WORKFLOW_CANCEL));
        }

        // 非法 unknown、recover Failed 与 Conflict 矩阵从手写 wire 解码时 fail closed。
        for (RetryMode mode : List.of(RetryMode.IDEMPOTENT_BY_STABLE_KEY, RetryMode.COORDINATED)) {
            assertInvokeRejected(manualInvoke(
                    PortalCallKind.WORKFLOW_DISPATCH,
                    "OutcomeUnknown",
                    failureResultJson("OutcomeUnknown", "null", mode.name())));
            assertControlRejected(manualControl(
                    "OutcomeUnknown", controlFailureJson("OutcomeUnknown", mode.name())));
        }
        assertRecoverRejected(manualRecover(
                "Failed", "{\"outcomeType\":\"Failed\",\"result\":" + FAILED_EMPTY_JSON + "}"));
        assertRecoverRejected(manualRecover(
                "Failed", "{\"outcomeType\":\"Failed\",\"result\":"
                        + failureResultJson("Failed", "\"7\"", "RESULT_QUERYABLE") + "}"));
        assertControlRejected(manualControl(
                "Conflict", "{\"current\":" + snapshotJson("RUNNING", "11", "\"current\"")
                        + ",\"failure\":" + failureJson("COORDINATED")
                        + ",\"outcomeType\":\"Conflict\"}"));
    }

    @Test
    void suspendedReasonRoundTripDistinguishesHitlFromExternalWaits() {
        var hitl = suspended();
        var external = new WorkflowResult.Suspended(
                EXECUTION, "wait", 2, new ResultRef("suspended"),
                com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_EXTERNAL);

        for (PortalCallKind kind : List.of(
                PortalCallKind.WORKFLOW_DISPATCH,
                PortalCallKind.WORKFLOW_RESUME)) {
            var hitlEncoded = codec().encodeInvoke(kind, hitl);
            var externalEncoded = codec().encodeInvoke(kind, external);
            assertEquals(hitl, codec().decodeInvoke(hitlEncoded, kind));
            assertEquals(external, codec().decodeInvoke(externalEncoded, kind));
            assertNotEquals(hitl, external);
            assertNotEquals(hitlEncoded, externalEncoded);
        }
    }

    /** expectedKind 必须由调用方显式给出，dispatch/resume 也不能因共享 schema 相互读取。 */
    @Test
    void rejectsWrongOuterKindSchemaAndInnerSubtype() {
        PortalOutcomeSnapshot dispatch = manualInvoke(
                PortalCallKind.WORKFLOW_DISPATCH, "Accepted", ACCEPTED_JSON);
        assertInvokeRejectedWithKind(dispatch, PortalCallKind.WORKFLOW_RESUME);

        // 三类入口逐个拒绝所有不属于自己的 call kind。
        for (PortalCallKind kind : PortalCallKind.values()) {
            if (kind != PortalCallKind.WORKFLOW_DISPATCH
                    && kind != PortalCallKind.WORKFLOW_RESUME) {
                assertThrows(IllegalArgumentException.class,
                        () -> codec().encodeInvoke(kind, accepted()));
                assertInvokeRejectedWithKind(dispatch, kind);
            }
            if (kind != PortalCallKind.WORKFLOW_RECOVER_RESULT) {
                assertThrows(IllegalArgumentException.class,
                        () -> codec().encodeRecover(kind, absent()));
                assertRecoverRejectedWithKind(manualRecover("Absent", absentJson("tenant")), kind);
            }
            if (kind != PortalCallKind.WORKFLOW_CANCEL) {
                assertThrows(IllegalArgumentException.class,
                        () -> codec().encodeControl(
                                kind, new WorkflowControlResult.Rejected(failure(RetryMode.NONE))));
                assertControlRejectedWithKind(
                        manualControl("Rejected", controlFailureJson("Rejected", "NONE")), kind);
            }
        }

        // API 封套本身拒绝未知 schema；codec 仍拒绝其他合法 Portal schema。
        assertThrows(IllegalArgumentException.class, () -> new PortalOutcomeSnapshot(
                PortalCallKind.WORKFLOW_DISPATCH,
                PortalResolution.KNOWN_RESULT,
                "Accepted",
                new TypedPayload("skillforge.runtime.portal.workflow-invoke-outcome.v999", ACCEPTED_JSON)));
        assertInvokeRejectedWithKind(
                manualRecover("Completed", "{\"outcomeType\":\"Completed\",\"result\":"
                        + COMPLETED_JSON + "}"),
                PortalCallKind.WORKFLOW_DISPATCH);

        // recover wrapper 与内部 invoke discriminator 必须精确一致。
        assertRecoverRejected(manualRecover(
                "Completed", "{\"outcomeType\":\"Completed\",\"result\":"
                        + TERMINATED_JSON + "}"));
        assertRecoverRejected(manualRecover(
                "Completed", "{\"outcomeType\":\"Terminated\",\"result\":"
                        + COMPLETED_JSON + "}"));
        assertControlRejected(manualControl(
                "Applied", "{\"execution\":" + snapshotJson("CANCELLED", "9", "null")
                        + ",\"outcomeType\":\"Replayed\"}"));
    }

    /** 未知/缺失嵌套字段、非规范整数/时间和跨租户屏障在 registry 前统一脱敏拒绝。 */
    @Test
    void rejectsMalformedNestedFieldsAndNonCanonicalValues() {
        for (String body : List.of(
                " " + COMPLETED_JSON,
                COMPLETED_JSON + " ",
                COMPLETED_JSON.replace("你好", "\\u4f60\\u597d"),
                COMPLETED_JSON.replace("\"schemaId\":\"workflow.output.v1\"",
                        "\"extra\":\"SECRET\",\"schemaId\":\"workflow.output.v1\""),
                COMPLETED_JSON.replace("\"executionId\":\"exec\",", ""),
                COMPLETED_JSON.replace("\"revision\":\"9007199254740993\"",
                        "\"revision\":\"09007199254740993\""),
                COMPLETED_JSON.replace("\"revision\":\"9007199254740993\"",
                        "\"revision\":\"9223372036854775808\""),
                COMPLETED_JSON.replace("\"revision\":\"9007199254740993\"",
                        "\"revision\":\"-1\""),
                COMPLETED_JSON.replace("\"revision\":\"9007199254740993\"",
                        "\"revision\":\"0\""),
                COMPLETED_JSON.replace("\"revision\":\"9007199254740993\"",
                        "\"revision\":9007199254740993"),
                COMPLETED_JSON.replace("\"outcomeType\":\"Completed\"",
                        "\"outcomeType\":\"SECRET\""))) {
            assertInvokeRejected(manualInvoke(
                    PortalCallKind.WORKFLOW_DISPATCH, "Completed", body));
        }

        // Absent 必须保留完整证明且 ActionRef 与 ExecutionRef 同租户。
        assertRecoverRejected(manualRecover(
                "Absent", absentJson("other")));
        assertRecoverRejected(manualRecover(
                "Absent", absentJson("tenant").replace("\"invocationBarrierRef\":\"barrier\",", "")));
        assertRecoverRejected(manualRecover(
                "Absent", absentJson("tenant").replace(
                        "\"actionId\":\"action\"", "\"actionId\":\"action\",\"extra\":\"SECRET\"")));
        assertRecoverRejected(manualRecover(
                "Absent", absentJson("tenant").replace(
                        "\"blockedExecuteAttemptId\":\"execute-attempt\"",
                        "\"blockedExecuteAttemptId\":\"invocation\"")));

        // control 快照的状态、revision、Duration 与 Instant 必须重编码逐字节相同。
        String applied = "{\"execution\":" + snapshotJson("CANCELLED", "9", "null")
                + ",\"outcomeType\":\"Applied\"}";
        for (String body : List.of(
                applied.replace("\"state\":\"CANCELLED\"", "\"state\":\"RUNNING\""),
                applied.replace("\"revision\":\"9\"", "\"revision\":\"0\""),
                applied.replace("\"timeout\":\"PT1.234S\"", "\"timeout\":\"PT1.2340S\""),
                applied.replace("\"timeout\":\"PT1.234S\"", "\"timeout\":\"SECRET\""),
                applied.replace("2026-09-05T01:02:03.123456789Z",
                        "2026-09-05T01:02:03.123456789+00:00"),
                applied.replace("2026-09-05T01:02:03.123456789Z", "SECRET"),
                applied.replace("\"versionId\":\"v7\"",
                        "\"versionId\":\"v7\",\"extra\":\"SECRET\""),
                applied.replace("\"capabilityId\":\"workflow-capability\",", ""))) {
            assertControlRejected(manualControl("Applied", body));
        }
    }

    /** exact code-unit/node/depth 配额同时适用于预检与解析，任一配额减一都必须失败。 */
    @Test
    void enforcesExactResourceBoundariesBeforeAllocation() {
        // invoke Completed 固定 19 个节点、三层深度；三类配额各减一均在 registry 前拒绝。
        WorkflowOutcomeCodec exactInvoke = new WorkflowOutcomeCodec(
                new CodecLimits(COMPLETED_JSON.length(), 19, 3),
                (payload, limits) -> assertEquals(OUTPUT, payload));
        PortalOutcomeSnapshot invoke = exactInvoke.encodeInvoke(
                PortalCallKind.WORKFLOW_DISPATCH, completed());
        assertEquals(COMPLETED_JSON, invoke.outcome().canonicalJson());
        assertEquals(completed(), exactInvoke.decodeInvoke(
                invoke, PortalCallKind.WORKFLOW_DISPATCH));
        for (CodecLimits bounded : List.of(
                new CodecLimits(COMPLETED_JSON.length() - 1, 19, 3),
                new CodecLimits(COMPLETED_JSON.length(), 18, 3),
                new CodecLimits(COMPLETED_JSON.length(), 19, 2))) {
            WorkflowOutcomeCodec codec = new WorkflowOutcomeCodec(
                    bounded, (payload, limits) -> fail("must reject before registry"));
            assertThrows(IllegalArgumentException.class,
                    () -> codec.encodeInvoke(PortalCallKind.WORKFLOW_DISPATCH, completed()));
            assertThrows(IllegalArgumentException.class,
                    () -> codec.decodeInvoke(
                            manualInvoke(PortalCallKind.WORKFLOW_DISPATCH,
                                    "Completed", COMPLETED_JSON),
                            PortalCallKind.WORKFLOW_DISPATCH));
        }

        // recover wrapper 必须在同一预算中累计内部 body：共 23 节点、四层深度。
        String recoverGolden = "{\"outcomeType\":\"Completed\",\"result\":"
                + COMPLETED_JSON + "}";
        WorkflowRecoveredResult.Completed recoverValue =
                new WorkflowRecoveredResult.Completed(completed());
        WorkflowOutcomeCodec exactRecover = new WorkflowOutcomeCodec(
                new CodecLimits(recoverGolden.length(), 23, 4),
                (payload, limits) -> assertEquals(OUTPUT, payload));
        PortalOutcomeSnapshot recover = exactRecover.encodeRecover(
                PortalCallKind.WORKFLOW_RECOVER_RESULT, recoverValue);
        assertEquals(recoverGolden, recover.outcome().canonicalJson());
        assertEquals(recoverValue, exactRecover.decodeRecover(
                recover, PortalCallKind.WORKFLOW_RECOVER_RESULT));
        for (CodecLimits bounded : List.of(
                new CodecLimits(recoverGolden.length() - 1, 23, 4),
                new CodecLimits(recoverGolden.length(), 22, 4),
                new CodecLimits(recoverGolden.length(), 23, 3))) {
            WorkflowOutcomeCodec codec = new WorkflowOutcomeCodec(
                    bounded, (payload, limits) -> fail("must reject before registry"));
            assertThrows(IllegalArgumentException.class,
                    () -> codec.encodeRecover(
                            PortalCallKind.WORKFLOW_RECOVER_RESULT, recoverValue));
            assertThrows(IllegalArgumentException.class,
                    () -> codec.decodeRecover(
                            manualRecover("Completed", recoverGolden),
                            PortalCallKind.WORKFLOW_RECOVER_RESULT));
        }

        // control 的最深合法结构固定为 39 节点、五层深度。
        WorkflowControlResult.Applied value =
                new WorkflowControlResult.Applied(cancelledSnapshot(Optional.empty(), 9));
        String golden = "{\"execution\":" + snapshotJson("CANCELLED", "9", "null")
                + ",\"outcomeType\":\"Applied\"}";
        CodecLimits exactLimits = new CodecLimits(golden.length(), 39, 5);
        WorkflowOutcomeCodec exact = new WorkflowOutcomeCodec(exactLimits, (payload, limits) -> fail());
        PortalOutcomeSnapshot encoded = exact.encodeControl(PortalCallKind.WORKFLOW_CANCEL, value);
        assertEquals(golden, encoded.outcome().canonicalJson());
        assertEquals(value, exact.decodeControl(encoded, PortalCallKind.WORKFLOW_CANCEL));

        for (CodecLimits bounded : List.of(
                new CodecLimits(golden.length() - 1, 39, 5),
                new CodecLimits(golden.length(), 38, 5),
                new CodecLimits(golden.length(), 39, 4))) {
            WorkflowOutcomeCodec codec = new WorkflowOutcomeCodec(
                    bounded, (payload, limits) -> fail("control has no output"));
            assertThrows(IllegalArgumentException.class,
                    () -> codec.encodeControl(PortalCallKind.WORKFLOW_CANCEL, value));
            assertThrows(IllegalArgumentException.class,
                    () -> codec.decodeControl(
                            manualControl("Applied", golden), PortalCallKind.WORKFLOW_CANCEL));
        }
    }

    /** 成功 output 每个入口只校验一次；外部 registry 的异常实例与原因链原样传播。 */
    @Test
    void invokesRegistryOnceAndPreservesExternalExceptions() {
        AtomicInteger calls = new AtomicInteger();
        WorkflowOutcomeCodec codec = new WorkflowOutcomeCodec(LIMITS, (payload, limits) -> {
            assertEquals(OUTPUT, payload);
            assertEquals(LIMITS, limits);
            calls.incrementAndGet();
        });
        PortalOutcomeSnapshot invoke = codec.encodeInvoke(
                PortalCallKind.WORKFLOW_DISPATCH, completed());
        assertEquals(1, calls.get());
        codec.decodeInvoke(invoke, PortalCallKind.WORKFLOW_DISPATCH);
        assertEquals(2, calls.get());
        PortalOutcomeSnapshot recover = codec.encodeRecover(
                PortalCallKind.WORKFLOW_RECOVER_RESULT,
                new WorkflowRecoveredResult.Completed(completed()));
        assertEquals(3, calls.get());
        codec.decodeRecover(recover, PortalCallKind.WORKFLOW_RECOVER_RESULT);
        assertEquals(4, calls.get());

        // 非成功分支和所有 control 分支不调用 output registry。
        codec.encodeInvoke(PortalCallKind.WORKFLOW_RESUME, accepted());
        codec.encodeRecover(PortalCallKind.WORKFLOW_RECOVER_RESULT, absent());
        codec.encodeControl(PortalCallKind.WORKFLOW_CANCEL,
                new WorkflowControlResult.Rejected(failure(RetryMode.NONE)));
        assertEquals(4, calls.get());

        for (RuntimeException exception : List.of(
                new IllegalStateException("unavailable", new IllegalStateException("cause")),
                new IllegalArgumentException("external schema rejection"))) {
            WorkflowOutcomeCodec broken = new WorkflowOutcomeCodec(
                    LIMITS, (payload, limits) -> { throw exception; });
            assertSame(exception, assertThrows(RuntimeException.class,
                    () -> broken.encodeInvoke(PortalCallKind.WORKFLOW_DISPATCH, completed())));
            assertSame(exception, assertThrows(RuntimeException.class,
                    () -> broken.decodeInvoke(invoke, PortalCallKind.WORKFLOW_DISPATCH)));
            assertSame(exception, assertThrows(RuntimeException.class,
                    () -> broken.encodeRecover(
                            PortalCallKind.WORKFLOW_RECOVER_RESULT,
                            new WorkflowRecoveredResult.Completed(completed()))));
            assertSame(exception, assertThrows(RuntimeException.class,
                    () -> broken.decodeRecover(recover, PortalCallKind.WORKFLOW_RECOVER_RESULT)));
        }
    }

    /** invoke golden 同时固定外层 resolution/schema/type 与手写 canonical body。 */
    private static void assertInvokeGolden(
            PortalCallKind kind, WorkflowResult value, String type, String golden) {
        PortalOutcomeSnapshot encoded = codec().encodeInvoke(kind, value);
        assertEquals(manualInvoke(kind, type, golden), encoded);
        assertEquals(value, codec().decodeInvoke(manualInvoke(kind, type, golden), kind));
    }

    /** recover golden 不借用生产结构构造器生成预期 bytes。 */
    private static void assertRecoverGolden(
            WorkflowRecoveredResult value, String type, String golden) {
        PortalOutcomeSnapshot encoded = codec().encodeRecover(
                PortalCallKind.WORKFLOW_RECOVER_RESULT, value);
        assertEquals(manualRecover(type, golden), encoded);
        assertEquals(value, codec().decodeRecover(
                manualRecover(type, golden), PortalCallKind.WORKFLOW_RECOVER_RESULT));
    }

    /** control golden 固定五种注册 resolution 与完整 body。 */
    private static void assertControlGolden(
            WorkflowControlResult value, String type, String golden) {
        PortalOutcomeSnapshot encoded = codec().encodeControl(PortalCallKind.WORKFLOW_CANCEL, value);
        assertEquals(manualControl(type, golden), encoded);
        assertEquals(value, codec().decodeControl(
                manualControl(type, golden), PortalCallKind.WORKFLOW_CANCEL));
    }

    /** 手写 invoke 封套只根据冻结注册表选择 resolution，不调用生产 codec。 */
    private static PortalOutcomeSnapshot manualInvoke(
            PortalCallKind kind, String type, String body) {
        PortalResolution resolution = switch (type) {
            case "Failed" -> PortalResolution.KNOWN_FAILURE;
            case "OutcomeUnknown" -> PortalResolution.OUTCOME_UNKNOWN;
            default -> PortalResolution.KNOWN_RESULT;
        };
        return new PortalOutcomeSnapshot(
                kind, resolution, type, new TypedPayload(INVOKE_SCHEMA, body));
    }

    /** 手写 recover 封套固定 Failed/Rejected 为 KNOWN_FAILURE。 */
    private static PortalOutcomeSnapshot manualRecover(String type, String body) {
        PortalResolution resolution = type.equals("Failed") || type.equals("Rejected")
                ? PortalResolution.KNOWN_FAILURE : PortalResolution.KNOWN_RESULT;
        return new PortalOutcomeSnapshot(
                PortalCallKind.WORKFLOW_RECOVER_RESULT,
                resolution,
                type,
                new TypedPayload(RECOVER_SCHEMA, body));
    }

    /** 手写 control 封套固定 success/failure/unknown 三类 resolution。 */
    private static PortalOutcomeSnapshot manualControl(String type, String body) {
        PortalResolution resolution = switch (type) {
            case "Applied", "Replayed" -> PortalResolution.KNOWN_RESULT;
            case "OutcomeUnknown" -> PortalResolution.OUTCOME_UNKNOWN;
            default -> PortalResolution.KNOWN_FAILURE;
        };
        return new PortalOutcomeSnapshot(
                PortalCallKind.WORKFLOW_CANCEL,
                resolution,
                type,
                new TypedPayload(CONTROL_SCHEMA, body));
    }

    /** 本地 invoke 协议错误不得泄露 SECRET 或底层 cause。 */
    private static void assertInvokeRejected(PortalOutcomeSnapshot snapshot) {
        assertInvokeRejectedWithKind(snapshot, snapshot.callKind());
    }

    /** expected kind 错配也必须使用稳定脱敏错误。 */
    private static void assertInvokeRejectedWithKind(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> rejectingCodec().decodeInvoke(snapshot, expectedKind));
        assertEquals("invalid workflow invoke outcome", error.getMessage());
        assertNull(error.getCause());
    }

    /** 本地 recover 协议错误不得进入 output registry。 */
    private static void assertRecoverRejected(PortalOutcomeSnapshot snapshot) {
        assertRecoverRejectedWithKind(snapshot, PortalCallKind.WORKFLOW_RECOVER_RESULT);
    }

    /** recover expected kind 错配使用固定、无 cause 的错误。 */
    private static void assertRecoverRejectedWithKind(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> rejectingCodec().decodeRecover(snapshot, expectedKind));
        assertEquals("invalid workflow recover outcome", error.getMessage());
        assertNull(error.getCause());
    }

    /** 本地 control 协议错误不得进入任何业务 registry。 */
    private static void assertControlRejected(PortalOutcomeSnapshot snapshot) {
        assertControlRejectedWithKind(snapshot, PortalCallKind.WORKFLOW_CANCEL);
    }

    /** control expected kind 错配使用固定、无 cause 的错误。 */
    private static void assertControlRejectedWithKind(
            PortalOutcomeSnapshot snapshot, PortalCallKind expectedKind) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> rejectingCodec().decodeControl(snapshot, expectedKind));
        assertEquals("invalid workflow control outcome", error.getMessage());
        assertNull(error.getCause());
    }

    /** 默认测试 codec 只允许唯一成功 fixture，避免生产代码获得 allow-all validator。 */
    private static WorkflowOutcomeCodec codec() {
        return new WorkflowOutcomeCodec(LIMITS, (payload, limits) -> assertEquals(OUTPUT, payload));
    }

    /** 拒绝测试若触发 registry 即立即失败，证明结构检查发生在外部调用之前。 */
    private static WorkflowOutcomeCodec rejectingCodec() {
        return new WorkflowOutcomeCodec(
                LIMITS, (payload, limits) -> fail("malformed wire must not reach registry"));
    }

    /** Workflow invoke Accepted fixture。 */
    private static WorkflowResult.Accepted accepted() {
        return new WorkflowResult.Accepted(EXECUTION, 1);
    }

    /** Workflow invoke Suspended fixture。 */
    private static WorkflowResult.Suspended suspended() {
        return new WorkflowResult.Suspended(EXECUTION, "wait", 2, new ResultRef("suspended"),
                com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL);
    }

    /** 成功 fixture 的 revision 大于 2^53，证明 wire 不使用 JSON number。 */
    private static WorkflowResult.Completed completed() {
        return new WorkflowResult.Completed(
                EXECUTION, OUTPUT, 9_007_199_254_740_993L, new ResultRef("completed"));
    }

    /** Failed fixture 显式参数化 OptionalLong 与全部 RetryMode。 */
    private static WorkflowResult.Failed failed(OptionalLong revision, RetryMode mode) {
        return new WorkflowResult.Failed(EXECUTION, revision, failure(mode));
    }

    /** Loop 终止 fixture 使用 Long.MAX_VALUE 锁定十进制字符串边界。 */
    private static WorkflowResult.Terminated terminated() {
        return new WorkflowResult.Terminated(
                EXECUTION,
                Long.MAX_VALUE,
                LoopTerminationReason.WORKFLOW_MAX_STEPS_REACHED,
                new ResultRef("terminated"));
    }

    /** Workflow invoke Cancelled fixture。 */
    private static WorkflowResult.Cancelled cancelled() {
        return new WorkflowResult.Cancelled(EXECUTION, 5, new ResultRef("cancelled"));
    }

    /** OutcomeUnknown fixture 只由调用方传入 API 允许的 retry mode。 */
    private static WorkflowResult.OutcomeUnknown unknown(
            OptionalLong revision, RetryMode mode) {
        return new WorkflowResult.OutcomeUnknown(EXECUTION, revision, failure(mode));
    }

    /** 统一失败事实使测试只改变目标 RetryMode。 */
    private static PortFailure failure(RetryMode mode) {
        return new PortFailure("declined", mode, new ResultRef("audit"));
    }

    /** Absent fixture 完整包含同租户 ActionRef 与六字段屏障证明。 */
    private static WorkflowRecoveredResult.Absent absent() {
        return new WorkflowRecoveredResult.Absent(
                EXECUTION,
                new InvocationBarrierProof(
                        new ActionRef(TENANT, "action"),
                        "invocation",
                        "execute-attempt",
                        "fingerprint",
                        new ResultRef("reconciliation"),
                        new ResultRef("barrier")));
    }

    /** 冻结目标 fixture 覆盖完整 capability 版本与非整数 Duration。 */
    private static FrozenWorkflowTarget target() {
        return new FrozenWorkflowTarget(
                new WorkflowHandle("workflow:mounted"),
                "instance",
                9_007_199_254_740_993L,
                new CapabilityRef(
                        CapabilityType.WORKFLOW, "workflow-capability", "v7"),
                Duration.ofMillis(1234), 1L);
    }

    /** 任意状态 WorkflowSnapshot fixture 用于 control Conflict。 */
    private static WorkflowSnapshot workflowSnapshot(
            WorkflowExecutionState state, Optional<ResultRef> resultRef, long revision) {
        return new WorkflowSnapshot(
                EXECUTION, target(), state, revision, resultRef, UPDATED_AT);
    }

    /** Applied/Replayed 只能由 API 构造器接受 CANCELLED 快照。 */
    private static WorkflowSnapshot cancelledSnapshot(
            Optional<ResultRef> resultRef, long revision) {
        return workflowSnapshot(WorkflowExecutionState.CANCELLED, resultRef, revision);
    }

    /** 手写 PortFailure JSON 只参数化 retryMode。 */
    private static String failureJson(String mode) {
        return "{\"reasonCode\":\"declined\",\"resultRef\":\"audit\",\"retryMode\":\""
                + mode + "\"}";
    }

    /** 手写 invoke 失败 body 参数化 discriminator、revision presence 与 retry mode。 */
    private static String failureResultJson(String type, String revision, String mode) {
        return "{\"executionRef\":" + EXECUTION_JSON + ",\"failure\":" + failureJson(mode)
                + ",\"outcomeType\":\"" + type + "\",\"revision\":" + revision + "}";
    }

    /** 手写 recover Absent JSON 可切换 ActionRef tenant 以验证同租户约束。 */
    private static String absentJson(String actionTenant) {
        return "{\"barrierProof\":{\"actionRef\":{\"actionId\":\"action\",\"tenantId\":\""
                + actionTenant + "\"},\"blockedExecuteAttemptId\":\"execute-attempt\","
                + "\"blockedExecuteRequestFingerprint\":\"fingerprint\","
                + "\"invocationBarrierRef\":\"barrier\",\"invocationId\":\"invocation\","
                + "\"reconciliationRef\":\"reconciliation\"},\"executionRef\":"
                + EXECUTION_JSON + ",\"outcomeType\":\"Absent\"}";
    }

    /** 手写 WorkflowSnapshot JSON 锁定字段排序、target 闭包与固定时间。 */
    private static String snapshotJson(String state, String revision, String resultRef) {
        String capability = "{\"capabilityId\":\"workflow-capability\",\"type\":\"WORKFLOW\",\"versionId\":\"v7\"}";
        String target = "{\"capability\":" + capability
                + ",\"handle\":\"workflow:mounted\",\"installationRevision\":\"1\","
                + "\"instanceId\":\"instance\","
                + "\"instanceRevision\":\"9007199254740993\",\"timeout\":\"PT1.234S\"}";
        return "{\"executionRef\":" + EXECUTION_JSON + ",\"resultRef\":" + resultRef
                + ",\"revision\":\"" + revision + "\",\"state\":\"" + state
                + "\",\"target\":" + target
                + ",\"updatedAt\":\"2026-09-05T01:02:03.123456789Z\"}";
    }

    /** 手写 control failure JSON 参数化 subtype 与 RetryMode。 */
    private static String controlFailureJson(String type, String mode) {
        return "{\"failure\":" + failureJson(mode)
                + ",\"outcomeType\":\"" + type + "\"}";
    }
}
