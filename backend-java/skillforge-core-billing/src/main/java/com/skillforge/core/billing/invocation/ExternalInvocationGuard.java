package com.skillforge.core.billing.invocation;

import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 外部调用的唯一守卫入口：先落开始事实、再执行、最后迁移终态。
 *
 * <p>该守卫不记录原始请求/响应，也不把失败伪装为成功。调用方如无法确定供应商是否收到请求，
 * 必须抛出 {@link OutcomeUnknownException}。守卫尝试将账本迁移至 UNRESOLVED；写入失败时
 * 保留原未知异常及账本错误，不能据此认定账本已迁移。</p>
 */
@Service
public class ExternalInvocationGuard {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExternalInvocationGuard.class);
    private final ExternalInvocationLedger ledger;
    private final com.skillforge.core.billing.budget.AuxiliaryModelBudgets modelBudgets;

    public ExternalInvocationGuard(ExternalInvocationLedger ledger, com.skillforge.core.billing.budget.AuxiliaryModelBudgets modelBudgets) {
        this.ledger = ledger;
        this.modelBudgets = modelBudgets;
    }

    /** 模型辅助调用先冻结类别和上界；HTTP 工具不使用此入口。 */
    public <T> T invokeModel(String tenantId, ExternalInvocationRequest request,
            com.skillforge.core.billing.budget.AuxiliaryModelBudgets.Request budgetRequest,
            ExternalCall<T> externalCall) throws Exception {
        try (var budget = modelBudgets.reserve(tenantId, request.invocationId(), request.attemptId(), budgetRequest)) {
            if (!ledger.startAuxiliary(tenantId, request, Instant.now())) {
                throw new DuplicateAttemptException(request.attemptId());
            }
            if (!budget.sent()) throw new DuplicateAttemptException(request.attemptId());
            try (ExternalInvocationScope ignored = ExternalInvocationScope.open(request)) {
                T result = externalCall.call();
                budget.resultSaved();
                ledger.finish(tenantId, request.attemptId(), ExternalInvocationStatus.SUCCEEDED, null, null, Instant.now());
                return result;
            } catch (OutcomeUnknownException unknown) {
                finishUnknown(tenantId, request, unknown);
                throw unknown;
            } catch (Exception knownFailure) {
                // 失败标签不能证明未收费；缺报预留继续保留。
                budget.resultSaved();
                ledger.finish(tenantId, request.attemptId(), ExternalInvocationStatus.FAILED, null, "EXTERNAL_CALL_FAILED", Instant.now());
                throw knownFailure;
            }
        }
    }

    public <T> T invoke(
            String tenantId, ExternalInvocationRequest request, ExternalCall<T> externalCall) throws Exception {
        Instant startedAt = Instant.now();
        if (!ledger.startAuxiliary(tenantId, request, startedAt)) {
            throw new DuplicateAttemptException(request.attemptId());
        }
        try (ExternalInvocationScope ignored = ExternalInvocationScope.open(request)) {
            T result = externalCall.call();
            ledger.finish(tenantId, request.attemptId(), ExternalInvocationStatus.SUCCEEDED,
                    null, null, Instant.now());
            return result;
        } catch (OutcomeUnknownException unknown) {
            finishUnknown(tenantId, request, unknown);
            throw unknown;
        } catch (Exception knownFailure) {
            ledger.finish(tenantId, request.attemptId(), ExternalInvocationStatus.FAILED,
                    null, "EXTERNAL_CALL_FAILED", Instant.now());
            throw knownFailure;
        }
    }

    private void finishUnknown(String tenantId, ExternalInvocationRequest request, OutcomeUnknownException unknown) {
        try {
            ledger.finish(tenantId, request.attemptId(), ExternalInvocationStatus.UNRESOLVED,
                    null, "EXTERNAL_OUTCOME_UNKNOWN", Instant.now());
        } catch (RuntimeException ledgerFailure) {
            unknown.addSuppressed(ledgerFailure);
            log.warn("外部调用未知结果账本写入未确认 tenant={} attempt={} errorType={}",
                    tenantId, request.attemptId(), ledgerFailure.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    public interface ExternalCall<T> {
        T call() throws Exception;
    }

    /** 同一真实 attempt 不允许被重放，调用方应创建新的 attemptId。 */
    public static class DuplicateAttemptException extends IllegalStateException {
        public DuplicateAttemptException(String attemptId) {
            super("External invocation attempt already exists: " + attemptId);
        }
    }

    /** 无法确定供应商是否收到请求或是否收费的错误标记。 */
    public static class OutcomeUnknownException extends Exception {
        public OutcomeUnknownException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
