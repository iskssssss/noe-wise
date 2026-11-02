package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.foundation.persistence.TenantDO;
import com.skillforge.core.foundation.application.TenantBillingProfileService.ReconciliationProfile;
import com.skillforge.core.foundation.application.TenantBillingProfileService.BillingCandidate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 租户 MyBatis-Plus 映射接口。 */
public interface TenantMapper extends BaseMapper<TenantDO> {
    List<Long> selectTenantIds();

    List<Long> selectActiveTenantIds();

    List<BillingCandidate> selectBillingCandidates();

    int transitionBillingState(@Param("tenantId") long tenantId, @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus);

    /** 精确更新对账配置，显式绑定 null 以支持清除，不依赖实体全局更新策略。 */
    int updateUsageReconciliation(@Param("tenantId") long tenantId, @Param("profile") ReconciliationProfile profile);
}
