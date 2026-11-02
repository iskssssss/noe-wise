package com.skillforge.core.billing.reconciliation;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.TenantDirectoryService;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 有 Provider 时按有限窗口遍历 active 租户；无 Provider 时每轮为空操作。 */
@Component
public class UsageReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(UsageReconciliationScheduler.class);

    private static final Duration WINDOW = Duration.ofHours(1);

    private final TenantDirectoryService tenants;
    private final UsageReconciliationService reconciliationService;

    public UsageReconciliationScheduler(TenantDirectoryService tenants,
                                        UsageReconciliationService reconciliationService) {
        this.tenants = tenants;
        this.reconciliationService = reconciliationService;
    }

    @Scheduled(cron = "${skillforge.usage-reconciliation-cron:0 */15 * * * *}", zone = "Asia/Shanghai")
    public void pullRecentUsage() {
        Instant to = Instant.now();
        Instant from = to.minus(WINDOW);
        for (String tenantId : tenants.activeTenantIds()) {
            TenantContext.setTenantId(tenantId);
            try {
                var result = reconciliationService.reconcileWindow(tenantId, from, to, null);
                if ("failed".equals(result.status())) {
                    log.warn("定时账单窗口对账未完成 tenant={} processed={} failed={}",
                            tenantId, result.processed(), result.failed());
                }
            } finally {
                TenantContext.clear();
            }
        }
    }
}
