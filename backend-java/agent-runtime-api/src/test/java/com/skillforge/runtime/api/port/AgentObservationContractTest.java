package com.skillforge.runtime.api.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RuntimeReport;
import com.skillforge.runtime.api.TenantId;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 验证父 Loop 委派观察的精确 ABI、字段来源与四态构造矩阵。 */
class AgentObservationContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final DelegationRef DELEGATION = new DelegationRef(TENANT, "delegation-1");
    private static final RunRef CHILD_RUN = new RunRef(TENANT, "child-run-1");
    private static final RuntimeReport REPORT = new RuntimeReport(
            "child result", List.of(), List.of());
    private static final ResultRef RESULT = new ResultRef("result-1");

    /** 精确冻结顶层 record 的字段名称、顺序、完整泛型签名与四态枚举顺序。 */
    @Test
    void freezesExactObservationAbi() {
        assertEquals(
                List.of(
                        "delegationRef:java.util.Optional<com.skillforge.runtime.api.port.DelegationRef>",
                        "childRunRef:java.util.Optional<com.skillforge.runtime.api.RunRef>",
                        "status:com.skillforge.runtime.api.port.DelegationObservationStatus",
                        "ledgerRevision:long",
                        "report:java.util.Optional<com.skillforge.runtime.api.RuntimeReport>",
                        "reasonCode:java.util.Optional<java.lang.String>",
                        "resultRef:com.skillforge.runtime.api.ResultRef"),
                Arrays.stream(AgentObservation.class.getRecordComponents())
                        .map(AgentObservationContractTest::componentSignature)
                        .toList());
        assertEquals(
                List.of("REJECTED", "COMPLETED", "FAILED", "CANCELLED"),
                Arrays.stream(DelegationObservationStatus.values()).map(Enum::name).toList());
    }

    /** 验证 claim 前拒绝只投影 PortFailure 的稳定原因与结果事实引用。 */
    @Test
    void acceptsClaimTimeRejectionProjection() {
        PortFailure failure = new PortFailure("delegation_denied", RetryMode.NONE, RESULT);
        AgentObservation observation = new AgentObservation(
                Optional.empty(),
                Optional.empty(),
                DelegationObservationStatus.REJECTED,
                0,
                Optional.empty(),
                Optional.of(failure.reasonCode()),
                failure.resultRef());

        assertEquals(Optional.empty(), observation.delegationRef());
        assertEquals(Optional.empty(), observation.childRunRef());
        assertEquals(failure.reasonCode(), observation.reasonCode().orElseThrow());
        assertEquals(failure.resultRef(), observation.resultRef());
    }

    /** 验证三个 Ledger 终态逐字段投影各自允许的报告、原因与结果引用。 */
    @Test
    void acceptsTerminalOutcomeProjections() {
        AgentObservation completed = terminal(
                DelegationObservationStatus.COMPLETED,
                Optional.of(REPORT),
                Optional.empty());
        AgentObservation failedWithReport = terminal(
                DelegationObservationStatus.FAILED,
                Optional.of(REPORT),
                Optional.of("child_failed"));
        AgentObservation failedWithoutReport = terminal(
                DelegationObservationStatus.FAILED,
                Optional.empty(),
                Optional.of("child_admission_failed"));
        AgentObservation cancelled = terminal(
                DelegationObservationStatus.CANCELLED,
                Optional.empty(),
                Optional.empty());

        assertEquals(REPORT, completed.report().orElseThrow());
        assertEquals("child_failed", failedWithReport.reasonCode().orElseThrow());
        assertEquals(Optional.empty(), failedWithoutReport.report());
        assertEquals(Optional.empty(), cancelled.reasonCode());
    }

    /** 验证所有 Optional 本身、状态、结果引用以及存在的原因文本都不可为非法空值。 */
    @Test
    void rejectsNullOptionalsAndInvalidRequiredFields() {
        assertThrows(IllegalArgumentException.class,
                () -> observation(null, Optional.empty(),
                        DelegationObservationStatus.REJECTED, 0,
                        Optional.empty(), Optional.of("rejected"), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.empty(), null,
                        DelegationObservationStatus.REJECTED, 0,
                        Optional.empty(), Optional.of("rejected"), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.empty(), Optional.empty(), null, 0,
                        Optional.empty(), Optional.of("rejected"), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.empty(), Optional.empty(),
                        DelegationObservationStatus.REJECTED, 0,
                        null, Optional.of("rejected"), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.empty(), Optional.empty(),
                        DelegationObservationStatus.REJECTED, 0,
                        Optional.empty(), null, RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.empty(), Optional.empty(),
                        DelegationObservationStatus.REJECTED, 0,
                        Optional.empty(), Optional.of(" "), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.empty(), Optional.empty(),
                        DelegationObservationStatus.REJECTED, 0,
                        Optional.empty(), Optional.of("rejected"), null));
    }

    /** 验证拒绝态不能伪造任一引用、报告或 Ledger revision。 */
    @Test
    void rejectsInvalidClaimTimeRejectionMatrix() {
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.of(DELEGATION), Optional.empty(),
                        DelegationObservationStatus.REJECTED, 0,
                        Optional.empty(), Optional.of("rejected"), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.empty(), Optional.of(CHILD_RUN),
                        DelegationObservationStatus.REJECTED, 0,
                        Optional.empty(), Optional.of("rejected"), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.empty(), Optional.empty(),
                        DelegationObservationStatus.REJECTED, 1,
                        Optional.empty(), Optional.of("rejected"), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.empty(), Optional.empty(),
                        DelegationObservationStatus.REJECTED, 0,
                        Optional.of(REPORT), Optional.of("rejected"), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.empty(), Optional.empty(),
                        DelegationObservationStatus.REJECTED, 0,
                        Optional.empty(), Optional.empty(), RESULT));
    }

    /** 验证三个终态必须同时携带同租户 refs 与正 revision，不接受 partial identity。 */
    @Test
    void rejectsInvalidTerminalIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.empty(), Optional.of(CHILD_RUN),
                        DelegationObservationStatus.COMPLETED, 1,
                        Optional.of(REPORT), Optional.empty(), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.of(DELEGATION), Optional.empty(),
                        DelegationObservationStatus.COMPLETED, 1,
                        Optional.of(REPORT), Optional.empty(), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.of(DELEGATION), Optional.of(CHILD_RUN),
                        DelegationObservationStatus.COMPLETED, 0,
                        Optional.of(REPORT), Optional.empty(), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(Optional.of(DELEGATION), Optional.of(CHILD_RUN),
                        DelegationObservationStatus.COMPLETED, -1,
                        Optional.of(REPORT), Optional.empty(), RESULT));
        assertThrows(IllegalArgumentException.class,
                () -> observation(
                        Optional.of(DELEGATION),
                        Optional.of(new RunRef(new TenantId("tenant-2"), "child-run-2")),
                        DelegationObservationStatus.COMPLETED,
                        1,
                        Optional.of(REPORT),
                        Optional.empty(),
                        RESULT));
    }

    /** 验证终态报告与原因必须严格命中对应状态，不能靠字段缺失反推状态。 */
    @Test
    void rejectsInvalidTerminalPayloadMatrix() {
        assertThrows(IllegalArgumentException.class,
                () -> terminal(DelegationObservationStatus.COMPLETED,
                        Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> terminal(DelegationObservationStatus.COMPLETED,
                        Optional.of(REPORT), Optional.of("unexpected")));
        assertThrows(IllegalArgumentException.class,
                () -> terminal(DelegationObservationStatus.FAILED,
                        Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> terminal(DelegationObservationStatus.CANCELLED,
                        Optional.of(REPORT), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> terminal(DelegationObservationStatus.CANCELLED,
                        Optional.empty(), Optional.of("unexpected")));
    }

    /** 构造一条使用固定同租户身份和正 revision 的终态观察。 */
    private static AgentObservation terminal(
            DelegationObservationStatus status,
            Optional<RuntimeReport> report,
            Optional<String> reasonCode) {
        return observation(
                Optional.of(DELEGATION),
                Optional.of(CHILD_RUN),
                status,
                7,
                report,
                reasonCode,
                RESULT);
    }

    /** 统一调用公开构造器，使非法矩阵测试只突出被改变的字段。 */
    private static AgentObservation observation(
            Optional<DelegationRef> delegationRef,
            Optional<RunRef> childRunRef,
            DelegationObservationStatus status,
            long ledgerRevision,
            Optional<RuntimeReport> report,
            Optional<String> reasonCode,
            ResultRef resultRef) {
        return new AgentObservation(
                delegationRef,
                childRunRef,
                status,
                ledgerRevision,
                report,
                reasonCode,
                resultRef);
    }

    /** 返回 record component 的名称与完整反射泛型类型。 */
    private static String componentSignature(RecordComponent component) {
        return component.getName() + ":" + component.getGenericType().getTypeName();
    }
}
