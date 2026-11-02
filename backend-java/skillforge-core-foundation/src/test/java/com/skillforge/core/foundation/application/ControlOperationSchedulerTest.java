package com.skillforge.core.foundation.application;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.contract.ControlOperation;
import com.skillforge.core.foundation.contract.ControlOperationItem;
import com.skillforge.core.foundation.contract.ControlOperationStatus;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantDO;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ControlOperationSchedulerTest {

    private final TenantMapper tenantMapper = mock(TenantMapper.class);
    private final ControlOperationService operations = mock(ControlOperationService.class);
    private final ControlOperationWorker worker = mock(ControlOperationWorker.class);
    private final ControlOperationScheduler scheduler = new ControlOperationScheduler(
            tenantMapper, operations, worker);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void scansEachActiveTenantAndRoutesAcceptedLedgerToWorker() {
        TenantDO active = new TenantDO();
        active.setId(7L);
        active.setStatus("active");
        TenantDO withoutId = new TenantDO();
        withoutId.setStatus("active");
        when(tenantMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(active, withoutId));
        ControlOperation operation = operation("op-1");
        when(operations.listAccepted("7", 100)).thenReturn(List.of(operation));

        scheduler.scanAccepted();

        verify(operations).listAccepted("7", 100);
        verify(worker).processAccepted("7", "integration-1", "op-1");
        assertNull(TenantContext.getTenantId());
    }

    @Test
    void nullTenantListIsNoopAndWorkerFailureDoesNotLeakTenantContext() {
        when(tenantMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(null);
        scheduler.scanAccepted();
        verify(operations, never()).listAccepted(any(), any(Integer.class));
        assertNull(TenantContext.getTenantId());

        TenantDO active = new TenantDO();
        active.setId(8L);
        when(tenantMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(active));
        when(operations.listAccepted("8", 100)).thenReturn(List.of(operation("op-2")));
        when(worker.processAccepted("8", "integration-1", "op-2"))
                .thenThrow(new IllegalStateException("temporary"));

        scheduler.scanAccepted();

        assertNull(TenantContext.getTenantId());
    }

    private ControlOperation operation(String operationId) {
        return new ControlOperation(operationId, operationId, "7", "integration-1", "resource.publish",
                ControlOperationStatus.ACCEPTED, "idem-" + operationId, "fp-" + operationId,
                List.of(new ControlOperationItem("item-1", "resource-1", ControlOperationStatus.ACCEPTED,
                        null, null, false)), Instant.now(), null);
    }
}
