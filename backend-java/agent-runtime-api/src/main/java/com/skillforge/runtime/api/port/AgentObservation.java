package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RuntimeReport;
import java.util.Optional;

/**
 * 向父 Agent Loop 投影一次确定拒绝或已提交的委派终态。
 *
 * <p>{@link DelegationObservationStatus#REJECTED} 只携带稳定失败事实。其余状态携带同一终态来源的
 * 委派引用、child Run 与 revision，不暴露内部执行快照。
 */
public record AgentObservation(
        Optional<DelegationRef> delegationRef,
        Optional<RunRef> childRunRef,
        DelegationObservationStatus status,
        long ledgerRevision,
        Optional<RuntimeReport> report,
        Optional<String> reasonCode,
        ResultRef resultRef) {

    /** 拒绝 null Optional、跨租户引用以及不符合四态封闭矩阵的字段组合。 */
    public AgentObservation {
        delegationRef = PortContracts.requireOptional(delegationRef, "delegationRef");
        childRunRef = PortContracts.requireOptional(childRunRef, "childRunRef");
        status = PortContracts.requireNonNull(status, "status");
        report = PortContracts.requireOptional(report, "report");
        reasonCode = PortContracts.requireOptional(reasonCode, "reasonCode");
        reasonCode.ifPresent(reason -> PortContracts.requireText(reason, "reasonCode"));
        resultRef = PortContracts.requireNonNull(resultRef, "resultRef");

        if (status == DelegationObservationStatus.REJECTED) {
            requireRejectedMatrix(
                    delegationRef, childRunRef, ledgerRevision, report, reasonCode);
        } else {
            requireTerminalIdentity(delegationRef, childRunRef, ledgerRevision);
            boolean payloadMatches = switch (status) {
                case COMPLETED -> report.isPresent() && reasonCode.isEmpty();
                case FAILED -> reasonCode.isPresent();
                case CANCELLED -> report.isEmpty() && reasonCode.isEmpty();
                case REJECTED -> false;
            };
            if (!payloadMatches) {
                throw new IllegalArgumentException(
                        "report and reasonCode must match delegation observation status");
            }
        }
    }

    /** 校验 claim 前拒绝不伪造 Ledger、child identity 或报告，但保留稳定原因。 */
    private static void requireRejectedMatrix(
            Optional<DelegationRef> delegationRef,
            Optional<RunRef> childRunRef,
            long ledgerRevision,
            Optional<RuntimeReport> report,
            Optional<String> reasonCode) {
        if (delegationRef.isPresent()
                || childRunRef.isPresent()
                || ledgerRevision != 0
                || report.isPresent()
                || reasonCode.isEmpty()) {
            throw new IllegalArgumentException(
                    "rejected observation must contain only reasonCode and resultRef");
        }
    }

    /** 校验终态同时携带同租户委派/child 引用与正 Ledger revision。 */
    private static void requireTerminalIdentity(
            Optional<DelegationRef> delegationRef,
            Optional<RunRef> childRunRef,
            long ledgerRevision) {
        if (delegationRef.isEmpty() || childRunRef.isEmpty()) {
            throw new IllegalArgumentException(
                    "terminal observation must contain delegationRef and childRunRef");
        }
        PortContracts.requirePositive(ledgerRevision, "ledgerRevision");
        PortContracts.requireSameTenant(
                delegationRef.orElseThrow().tenantId(),
                childRunRef.orElseThrow().tenantId(),
                "childRunRef.tenantId");
    }
}
