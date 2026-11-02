package com.skillforge.core.billing.application;

import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.constant.BillingConstants;
import com.skillforge.core.billing.BillingStateProcessResponse;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.application.TenantBillingProfileService;
import com.skillforge.core.foundation.application.TenantBillingProfileService.BillingCandidate;
import com.skillforge.core.billing.persistence.PlanDO;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 欠费状态机服务。
 *
 * <p>扫描全部租户，按月度 API 用量与套餐配额比对，将超限租户切换为
 * warning，用量回落后恢复 active；供手动接口与定时调度共用。
 * 计数口径：{@code usage_events} 的 {@code request} 根事件（N28 最小单元化）。
 */
@Service
public class BillingStateService {

    private final java.time.Clock clock;

    /** 机构领域提供计费候选与受限状态转换。 */
    private final TenantBillingProfileService tenantProfiles;

    /** 套餐表映射器。 */
    private final PlanMapper planMapper;

    /** 用量事件表映射器。 */
    private final CostEventMapper costEventMapper;

    /**
     * 构造欠费状态机服务。
     *
     * @param tenantProfiles  机构计费资料边界
     * @param planMapper      套餐表映射器
     * @param costEventMapper 用量事件表映射器
     */
    @org.springframework.beans.factory.annotation.Autowired
    public BillingStateService(
            TenantBillingProfileService tenantProfiles,
            PlanMapper planMapper,
            CostEventMapper costEventMapper) {
        this(tenantProfiles, planMapper, costEventMapper, com.skillforge.common.time.BusinessTime.clock());
    }

    public BillingStateService(TenantBillingProfileService tenantProfiles, PlanMapper planMapper,
            CostEventMapper costEventMapper, java.time.Clock clock) {
        this.clock = clock;
        this.tenantProfiles = tenantProfiles;
        this.planMapper = planMapper;
        this.costEventMapper = costEventMapper;
    }

    /**
     * 执行欠费状态扫描。
     *
     * @return 扫描统计
     */
    @org.springframework.transaction.annotation.Transactional
    public BillingStateProcessResponse processStates() {
        if (com.skillforge.common.context.TenantContext.getTenantId() != null)
            throw new IllegalStateException("billing_scan_requires_global_scope");
        List<BillingCandidate> tenants = tenantProfiles.billingCandidates();
        int warning = 0;
        int active = 0;
        Instant from = monthStart();
        Instant to = from.atZone(com.skillforge.common.time.BusinessTime.ZONE).plusMonths(1).toInstant();
        for (BillingCandidate tenant : tenants) {
            var changes = com.skillforge.core.foundation.config.TenantRlsInterceptor.withBillingScanTarget(tenant.tenantId(), () -> {
                Integer quota = tenant.overrideQuotaApiCallsPerMonth();
                if (quota == null) {
                    PlanDO plan = tenant.planId() == null ? null : planMapper.selectById(tenant.planId());
                    quota = plan == null ? null : plan.getQuotaApiCallsPerMonth();
                }
                if (quota == null) return new BillingStateProcessResponse(1, 0, 0);
                long usage = costEventMapper.countRequestsForBilling(tenant.tenantId(), from, to);
                if (usage >= quota && !BillingConstants.TENANT_STATUS_WARNING.equals(tenant.status()))
                    return new BillingStateProcessResponse(1, transition(tenant, BillingConstants.TENANT_STATUS_WARNING), 0);
                if (usage < quota && BillingConstants.TENANT_STATUS_WARNING.equals(tenant.status()))
                    return new BillingStateProcessResponse(1, 0, transition(tenant, BillingConstants.TENANT_STATUS_ACTIVE));
                return new BillingStateProcessResponse(1, 0, 0);
            });
            warning += changes.warning();
            active += changes.active();
        }
        return new BillingStateProcessResponse(tenants.size(), warning, active);
    }

    /** 只修改所见状态；并发停用机构时不得被账单扫描重新激活或预警。 */
    private int transition(BillingCandidate tenant, String target) {
        return tenantProfiles.transitionBillingState(tenant.tenantId(), tenant.status(), target);
    }

    /**
     * 计算当月第一天（北京时间）。
     *
     * @return 当月起始时间
     */
    private Instant monthStart() {
        return com.skillforge.common.time.BusinessTime.startOfMonth(Instant.now(clock));
    }
}
