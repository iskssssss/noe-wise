package com.skillforge.core.foundation.application;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.common.context.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 机构账户拥有的套餐绑定和对账配置存取边界；不向计费模块泄漏机构持久实体。 */
@Service
public final class TenantBillingProfileService {
    private static final String ACTIVE = "active";
    private static final String WARNING = "warning";
    private final TenantMapper tenants;

    public TenantBillingProfileService(TenantMapper tenants) {
        this.tenants = java.util.Objects.requireNonNull(tenants, "tenants");
    }

    /** 不存在的机构返回 null，配额调用方保持原缺失处理语义。 */
    public QuotaProfile quotaProfile(long tenantId) {
        var tenant = tenants.selectById(tenantId);
        return tenant == null ? null : new QuotaProfile(tenant.getPlanId(), tenant.getOverrideQuotaApiCallsPerMonth());
    }

    /** 仅返回允许计费状态机处理的机构快照；全局扫描授权继续由调用入口拥有。 */
    public List<BillingCandidate> billingCandidates() {
        return List.copyOf(tenants.selectBillingCandidates());
    }

    /**
     * 在已绑定机构上下文内仅允许 active 与 warning 互转，并与观察到的状态做 CAS。
     * 并发停用、终止或其他状态变化返回 0，不能被计费扫描重新激活。
     */
    public int transitionBillingState(long tenantId, String expectedStatus, String targetStatus) {
        if (!Long.toString(tenantId).equals(TenantContext.getTenantId())) {
            throw new SecurityException("billing_state_tenant_context_mismatch");
        }
        boolean warn = ACTIVE.equals(expectedStatus) && WARNING.equals(targetStatus);
        boolean recover = WARNING.equals(expectedStatus) && ACTIVE.equals(targetStatus);
        if (!warn && !recover) throw new IllegalArgumentException("billing_state_transition_invalid");
        return tenants.transitionBillingState(tenantId, expectedStatus, targetStatus);
    }

    /** 仅供受控服务读取；API 响应必须由计费领域脱敏，不得直接返回加密凭据。 */
    public ReconciliationProfile reconciliationProfile(long tenantId) {
        var tenant = tenants.selectById(tenantId);
        return tenant == null ? null : new ReconciliationProfile(tenant.getUsageReconciliationProvider(),
                tenant.getUsageReconciliationEndpoint(), tenant.getUsageReconciliationApiKeyEncrypted(),
                tenant.getUsageReconciliationApiKeyHeader(), tenant.getUsageReconciliationResponseSchema());
    }

    /** 替换完整对账配置；null 字段显式清空数据库值，且不写其他机构字段。 */
    public void saveReconciliationProfile(long tenantId, ReconciliationProfile profile) {
        java.util.Objects.requireNonNull(profile, "profile");
        if (tenants.updateUsageReconciliation(tenantId, profile) != 1) {
            throw new ApiException(HttpStatus.NOT_FOUND, "租户不存在");
        }
    }

    public record QuotaProfile(Long planId, Integer overrideQuotaApiCallsPerMonth) { }

    /** API 月配额的机构覆盖为 null 时由计费领域继承套餐；状态仅作为 CAS 观察值。 */
    public record BillingCandidate(long tenantId, Long planId, Integer overrideQuotaApiCallsPerMonth, String status) { }

    public record ReconciliationProfile(String provider, String endpoint, String apiKeyEncrypted,
                                        String apiKeyHeader, Map<String, Object> responseSchema) {
        public ReconciliationProfile {
            responseSchema = responseSchema == null ? null : Map.copyOf(responseSchema);
        }
    }
}
