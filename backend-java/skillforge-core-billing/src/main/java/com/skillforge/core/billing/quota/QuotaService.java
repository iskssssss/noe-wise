package com.skillforge.core.billing.quota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.persistence.CostEventMapper;
import com.skillforge.core.billing.metering.CostType;
import com.skillforge.core.billing.constant.BillingConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.application.TenantBillingProfileService;
import com.skillforge.core.billing.persistence.PlanDO;
import java.time.Instant;
import java.time.Clock;
import com.skillforge.common.time.BusinessTime;
import java.time.temporal.TemporalAdjusters;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 配额服务。
 *
 * <p>以 plans 表为唯一事实源，按自然月统计 API 调用量并校验租户配额；
 * 超限时抛出 429。计数口径：{@code usage_events} 的 {@code request} 根事件
 * （每条用户请求一条，无 LLM 调用的 Plan Cache Hit 同样计入），
 * token/金额统计不参与配额计数（分别见统计 API）。
 */
@Service
public class QuotaService {

    /** 机构套餐配置读取边界。 */
    private final TenantBillingProfileService tenants;

    /** 套餐表映射器。 */
    private final PlanMapper planMapper;

    /** 用量事件表映射器。 */
    private final CostEventMapper costEventMapper;
    private final Clock clock;

    /**
     * 构造配额服务。
     *
     * @param tenants    机构套餐配置读取边界
     * @param planMapper      套餐表映射器
     * @param costEventMapper 用量事件表映射器
     */
    @org.springframework.beans.factory.annotation.Autowired
    public QuotaService(
            TenantBillingProfileService tenants,
            PlanMapper planMapper,
            CostEventMapper costEventMapper) {
        this(tenants, planMapper, costEventMapper, BusinessTime.clock());
    }

    public QuotaService(TenantBillingProfileService tenants, PlanMapper planMapper, CostEventMapper costEventMapper, Clock clock) {
        this.clock = clock;
        this.tenants = tenants;
        this.planMapper = planMapper;
        this.costEventMapper = costEventMapper;
    }

    /**
     * 检查租户月配额。
     *
     * @param tenantId 租户 ID
     */
    public void check(String tenantId) {
        var tenant = tenants.quotaProfile(Long.parseLong(tenantId));
        if (tenant == null) {
            return;
        }
        PlanDO plan = planMapper.selectById(tenant.planId());
        Integer effective = tenant.overrideQuotaApiCallsPerMonth() != null
                ? tenant.overrideQuotaApiCallsPerMonth()
                : (plan == null ? null : plan.getQuotaApiCallsPerMonth());
        if (effective == null) {
            return;
        }
        Instant now = clock.instant();
        long usage = countMonthlyUsage(tenantId, now);
        if (usage >= effective) {
            throw new QuotaExceededException(now, monthStart(now).atZone(BusinessTime.ZONE).plusMonths(1).toInstant());
        }
    }

    /**
     * 统计当月 API 调用次数（request 根事件计数，北京时间月窗）。
     *
     * @param tenantId 租户 ID
     * @return 当月调用次数
     */
    private long countMonthlyUsage(String tenantId, Instant now) {
        Instant monthStart = monthStart(now);
        Long count = costEventMapper.selectCount(
                new LambdaQueryWrapper<CostEventDO>()
                        .eq(CostEventDO::getTenantId, Long.parseLong(tenantId))
                        .eq(CostEventDO::getEventType, CostType.REQUEST.value())
                        .ge(CostEventDO::getOccurredAt, monthStart)
                        .lt(CostEventDO::getOccurredAt, monthStart.atZone(BusinessTime.ZONE).plusMonths(1).toInstant()));
        return count == null ? 0 : count;
    }

    /** 将任意时刻规范化为其所在 北京时间自然月的首日零点。 */
    static Instant monthStart(Instant now) {
        return now.atZone(BusinessTime.ZONE)
                .with(TemporalAdjusters.firstDayOfMonth())
                .toLocalDate()
                .atStartOfDay(BusinessTime.ZONE)
                .toInstant();
    }
}
