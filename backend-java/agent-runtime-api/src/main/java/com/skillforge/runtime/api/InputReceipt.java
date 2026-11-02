package com.skillforge.runtime.api;

import java.time.Instant;
import java.util.Optional;

/**
 * 表示 InputIdempotencyLedger 对首次有效 envelope 形成的稳定接受或拒绝事实。
 */
public record InputReceipt(
        SessionRef sessionRef,
        InputId inputId,
        InputReceiptStatus status,
        boolean replayed,
        long ledgerRevision,
        Optional<RunRef> runRef,
        Optional<String> rejectionReasonCode,
        Optional<ResultRef> resultRef,
        Instant recordedAt) {

    /** 规范化 Optional，并严格执行 ACCEPTED/REJECTED 字段矩阵与租户边界。 */
    public InputReceipt {
        sessionRef = ApiContracts.requireNonNull(sessionRef, "sessionRef");
        inputId = ApiContracts.requireNonNull(inputId, "inputId");
        status = ApiContracts.requireNonNull(status, "status");
        ledgerRevision = ApiContracts.requirePositive(ledgerRevision, "ledgerRevision");
        runRef = ApiContracts.normalize(runRef);
        rejectionReasonCode = ApiContracts.normalize(rejectionReasonCode)
                .map(value -> ApiContracts.requireText(value, "rejectionReasonCode"));
        resultRef = ApiContracts.normalize(resultRef);
        recordedAt = ApiContracts.requireNonNull(recordedAt, "recordedAt");

        // 任意 receipt 中出现的 RunRef 都必须属于 receipt 的 Session tenant。
        if (runRef.isPresent()) {
            ApiContracts.requireSameTenant(sessionRef, runRef.get());
        }

        // 接受事实只绑定 RunRef；拒绝原因和拒绝审计引用只属于 REJECTED。
        if (status == InputReceiptStatus.ACCEPTED) {
            if (runRef.isEmpty() || rejectionReasonCode.isPresent() || resultRef.isPresent()) {
                throw new IllegalArgumentException("ACCEPTED receipt requires runRef only");
            }
        } else if (rejectionReasonCode.isEmpty() || resultRef.isEmpty()) {
            throw new IllegalArgumentException("REJECTED receipt requires rejectionReasonCode and resultRef");
        }
    }
}
