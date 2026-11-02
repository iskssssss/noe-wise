package com.skillforge.core.billing;

import com.skillforge.core.billing.application.BillingStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 欠费状态机定时调度器。
 *
 * <p>每日凌晨执行一次租户欠费状态扫描，替代 Python 侧的 Celery beat；
 * cron 表达式可通过 {@code skillforge.billing-state-cron} 覆盖。
 */
@Component
public class BillingScheduler {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);

    /** 欠费状态机服务。 */
    private final BillingStateService billingStateService;

    /**
     * 构造定时调度器。
     *
     * @param billingStateService 欠费状态机服务
     */
    public BillingScheduler(BillingStateService billingStateService) {
        this.billingStateService = billingStateService;
    }

    /**
     * 每日凌晨扫描欠费状态。
     */
    @Scheduled(cron = "${skillforge.billing-state-cron:0 0 2 * * *}", zone = "Asia/Shanghai")
    public void runDailyStateScan() {
        try {
            log.info("定时欠费状态扫描开始");
            billingStateService.processStates();
        } catch (Exception e) {
            log.error("定时欠费状态扫描失败", e);
        }
    }
}
