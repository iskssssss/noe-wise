package com.skillforge.core.foundation.catalog.entitlements;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts.*;

/** 功能关系与现有 plans/tenants typed 配额的存储端口。 */
@Mapper
public interface EntitlementMapper {
    /** 功能目录。 */ List<Feature> features();
    /** 功能行锁串行化目录管理。 */ Feature lockFeature(@Param("code") String code);
    /** 新建目录项。 */ int insertFeature(@Param("command") SaveFeature command,@Param("actor") String actor);
    /** CAS 更新，全局停用立即影响新动作。 */ int updateFeature(@Param("command") SaveFeature command,@Param("actor") String actor);
    /** 现有套餐目录。 */ List<Plan> plans();
    /** 套餐行锁与资源受理共享写权。 */ Plan lockPlan(@Param("id") String id);
    /** 套餐功能关系。 */ List<Grant> planGrants(@Param("plan") String plan);
    /** 机构覆盖。 */ List<Grant> tenantGrants(@Param("tenant") String tenant);
    /** 新增套餐授权。 */ int insertPlanGrant(@Param("command") SavePlanGrant command,@Param("actor") String actor);
    /** 套餐授权 CAS。 */ int updatePlanGrant(@Param("command") SavePlanGrant command,@Param("actor") String actor);
    /** 新增租户授权覆盖。 */ int insertTenantGrant(@Param("command") SaveTenantGrant command,@Param("actor") String actor);
    /** 修改明确覆盖。 */ int updateTenantGrant(@Param("command") SaveTenantGrant command,@Param("actor") String actor);
    /** 删除覆盖，恢复当前套餐继承。 */ int deleteTenantGrant(@Param("command") SaveTenantGrant command);
    /** 即时授权来自全局目录、租户当前状态、当前套餐、计划关系和覆盖。 */ boolean featureAllowed(@Param("tenant") String tenant,@Param("code") String code);
    /** 当前有效功能目录。 */ List<String> effectiveFeatures(@Param("tenant") String tenant);
    /** 受理锁同时锁租户和计划，配额更新与同租户创建均不能穿越。 */ EffectiveQuota lockQuota(@Param("tenant") String tenant);
    /** 有效配额只读。 */ EffectiveQuota quota(@Param("tenant") String tenant);
    /** 现有套餐明细限额；稳定行身份区分全NULL限额与缺失行。 */ StoredLimits planLimits(@Param("plan") String plan);
    /** 机构显式覆盖值；全NULL仍是有效的完整继承配置。 */ StoredLimits tenantLimits(@Param("tenant") String tenant);
    /** CAS 修改 typed 套餐列。 */ int updatePlanQuota(@Param("command") SavePlanQuota command);
    /** CAS 修改 typed 机构覆盖列。 */ int updateTenantQuota(@Param("command") SaveTenantQuota command);
    /** 受控固定表真实计数/字节，必须在租户锁后调用。 */ long used(@Param("tenant") String tenant,@Param("kind") String kind);

    /** 内部查询投影；非空身份使MyBatis保留八项均NULL的真实行，不进入公开协议。 */
    record StoredLimits(String id,Long agents,Long workflows,Long knowledgeBases,Long members,Long channels,
            String fileBytes,Long adminTasks,Long subInstitutions) {
        public Limits limits(){return new Limits(agents,workflows,knowledgeBases,members,channels,fileBytes,adminTasks,subInstitutions);}
    }
}
