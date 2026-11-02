package com.skillforge.core.billing.metering;

import com.skillforge.core.foundation.contract.ControlOperation;
import com.skillforge.core.foundation.application.ControlOperationExecutionScope;
import org.springframework.stereotype.Component;

/** 从控制操作账本建立隔离的计量来源，避免工作线程残留的请求身份污染异步用量。 */
@Component
public final class ControlOperationMeteringScope implements ControlOperationExecutionScope {
    @Override
    public Scope open(ControlOperation processing) {
        MeterScope scope = MeterScope.openIsolated(CostContext.builder()
                .requestId(processing.rootOperationId()).scopeId(processing.operationId())
                .scopeType("control_operation").callPoint("control.operation").build());
        return scope::close;
    }
}
