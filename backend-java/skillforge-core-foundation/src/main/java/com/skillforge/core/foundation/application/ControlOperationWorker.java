package com.skillforge.core.foundation.application;

import com.skillforge.core.foundation.contract.ControlOperation;
import com.skillforge.core.foundation.contract.ControlOperationItem;
import com.skillforge.core.foundation.contract.ControlOperationStatus;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * ControlOperation 异步消费边界。
 *
 * <p>worker 先以 expectedStatus=accepted 抢占账本，再调用唯一资源适配器，最后以 processing CAS 写回
 * 逐项结果。没有适配器、适配器异常或适配器选择不明确时均失败关闭；不会绕过账本直接声称资源已完成。
 */
@Service
public class ControlOperationWorker {

    private static final Logger log = LoggerFactory.getLogger(ControlOperationWorker.class);
    private static final String NO_EXECUTOR = "NO_EXECUTOR";
    private static final String AMBIGUOUS_EXECUTOR = "AMBIGUOUS_EXECUTOR";
    private static final String EXECUTOR_ERROR = "EXECUTOR_ERROR";

    private final ControlOperationService operations;
    private final List<ControlOperationExecutor> executors;
    private final ControlOperationExecutionScope executionScope;

    public ControlOperationWorker(ControlOperationService operations,
                                  List<ControlOperationExecutor> executors,
                                  ControlOperationExecutionScope executionScope) {
        this.operations = operations;
        this.executionScope = java.util.Objects.requireNonNull(executionScope, "executionScope");
        this.executors = executors == null ? List.of() : List.copyOf(executors);
    }

    /**
     * 消费一条 accepted 操作；重复唤醒不会重复执行已处理的状态。
     *
     * @param tenantId       租户 ID
     * @param integrationId  集成 ID
     * @param operationId    操作 ID
     * @return 当前或最终账本状态
     */
    public ControlOperation processAccepted(String tenantId, String integrationId, String operationId) {
        ControlOperation processing;
        try {
            processing = operations.transition(tenantId, integrationId, operationId,
                    ControlOperationStatus.ACCEPTED, ControlOperationStatus.PROCESSING, null);
        } catch (IllegalStateException conflict) {
            // 另一个 worker 已抢占或已完成；返回账本当前状态，不重复执行外部副作用。
            ControlOperation current = operations.find(tenantId, integrationId, operationId);
            if (current != null) {
                return current;
            }
            throw conflict;
        }

        List<ControlOperationExecutor> matches;
        try {
            matches = executors.stream()
                    .filter(executor -> executor.supports(processing.integrationId(), processing.operationType()))
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("ControlOperation executor selection failed: operationId={}, integrationId={}",
                    operationId, integrationId, exception);
            return fail(tenantId, integrationId, operationId, processing, EXECUTOR_ERROR);
        }
        if (matches.isEmpty()) {
            return fail(tenantId, integrationId, operationId, processing, NO_EXECUTOR);
        }
        if (matches.size() > 1) {
            return fail(tenantId, integrationId, operationId, processing, AMBIGUOUS_EXECUTOR);
        }
        // 来源取自已抢占的持久账本，后台线程不继承查询请求的应用身份。
        try (ControlOperationExecutionScope.Scope scope = java.util.Objects.requireNonNull(
                executionScope.open(processing), "executionScope.open")) {
            ControlOperationExecutor.Execution execution = matches.getFirst().execute(processing);
            return operations.transition(tenantId, integrationId, operationId,
                    ControlOperationStatus.PROCESSING, execution.status(), execution.items());
        } catch (RuntimeException exception) {
            log.warn("ControlOperation executor failed: operationId={}, integrationId={}",
                    operationId, integrationId, exception);
            return fail(tenantId, integrationId, operationId, processing, EXECUTOR_ERROR);
        }
    }

    private ControlOperation fail(String tenantId, String integrationId, String operationId,
                                  ControlOperation processing, String errorCode) {
        List<ControlOperationItem> failures = new ArrayList<>();
        for (ControlOperationItem item : processing.items()) {
            failures.add(new ControlOperationItem(item.itemKey(), item.resourceRef(),
                    ControlOperationStatus.FAILED, null, errorCode, false));
        }
        return operations.transition(tenantId, integrationId, operationId,
                ControlOperationStatus.PROCESSING, ControlOperationStatus.FAILED, failures);
    }
}
