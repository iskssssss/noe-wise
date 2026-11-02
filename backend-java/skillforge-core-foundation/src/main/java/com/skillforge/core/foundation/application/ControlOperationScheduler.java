package com.skillforge.core.foundation.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantDO;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Open Platform ControlOperation accepted 扫描器。
 *
 * <p>扫描器只负责按租户建立 RLS 上下文并把 accepted 账本交给
 * {@link ControlOperationWorker}；资源副作用和状态 CAS 均留在 worker 内。
 */
@Component
public class ControlOperationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ControlOperationScheduler.class);
    private static final int SCAN_LIMIT = 100;

    private final TenantMapper tenantMapper;
    private final ControlOperationService operations;
    private final ControlOperationWorker worker;

    public ControlOperationScheduler(TenantMapper tenantMapper,
                                     ControlOperationService operations,
                                     ControlOperationWorker worker) {
        this.tenantMapper = tenantMapper;
        this.operations = operations;
        this.worker = worker;
    }

    /** 周期领取 accepted 操作；cron 可由 skillforge.control-operation-cron 覆盖。 */
    @Scheduled(cron = "${skillforge.control-operation-cron:*/5 * * * * *}", zone = "Asia/Shanghai")
    public void scanAccepted() {
        List<TenantDO> tenants = tenantMapper.selectList(new LambdaQueryWrapper<TenantDO>()
                .eq(TenantDO::getStatus, "active"));
        if (tenants == null) {
            return;
        }
        for (TenantDO tenant : tenants) {
            if (tenant == null || tenant.getId() == null) {
                continue;
            }
            String tenantId = String.valueOf(tenant.getId());
            TenantContext.setTenantId(tenantId);
            try {
                operations.listAccepted(tenantId, SCAN_LIMIT).forEach(operation -> {
                    try {
                        worker.processAccepted(tenantId, operation.integrationId(), operation.operationId());
                    } catch (RuntimeException exception) {
                        log.warn("ControlOperation 调度处理失败: tenantId={}, operationId={}",
                                tenantId, operation.operationId(), exception);
                    }
                });
            } finally {
                TenantContext.clear();
            }
        }
    }
}
