package com.skillforge.core.foundation.catalog.entitlements;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.catalog.CatalogScope;
import com.skillforge.core.foundation.catalog.CatalogValidation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实资源写入受理配额；必须加入创建事务，锁持有到外层提交或回滚。 */
@Service
public class ResourceQuota {
    private final EntitlementMapper mapper; private final CatalogScope scope;
    private final DirectDeploymentScope deployments;
    /** 使用数据库真实计数而非可漂移的独立计数器。 */
    public ResourceQuota(EntitlementMapper mapper,CatalogScope scope,DirectDeploymentScope deployments) {this.mapper=mapper;this.scope=scope;this.deployments=deployments;}
    /** 在当前租户创建事务中申请正数容量；写入失败由外层事务回滚。 */
    public void requireCapacity(ResourceKind kind,long additional) { requireCapacity(scope.tenant(),kind,additional); }
    /** 平台受控目标重载；所有配额创建消费者必须在写入前同事务调用。 */
    public void requireCapacity(String tenantId,ResourceKind kind,long additional) {
        validateRequest(kind,additional);
        scope.inTenant(tenantId,()->{ requireLockedCapacity(tenantId,kind,additional); return null; });
    }
    /** 受控部署只能申请 Agent 容量，仍加入创建事务并锁定真实套餐和机构计数。 */
    public void requireDirectAgentCapacity(String tenantId,long additional) {
        validateRequest(ResourceKind.AGENT,additional);
        deployments.inTenant(tenantId,()->{ requireLockedCapacity(tenantId,ResourceKind.AGENT,additional); return null; });
    }
    /** 直属机构只使用当前可信HQ；固定机构机器必须持有当前精确开通scope。 */
    public void requireSubInstitutionCapacity(String tenantId,long additional) {
        validateRequest(ResourceKind.SUB_INSTITUTION,additional);
        if(com.skillforge.common.context.ServicePrincipalContext.get()==null) {
            requireCapacity(tenantId,ResourceKind.SUB_INSTITUTION,additional);
        } else deployments.inSubInstitutionTenant(tenantId,()->{
            requireLockedCapacity(tenantId,ResourceKind.SUB_INSTITUTION,additional);return null;
        });
    }
    /** 两类授权范围共享同一事务准入条件，不能在自动提交中检查容量。 */
    private static void validateRequest(ResourceKind kind,long additional) {
        if(kind==null || additional<=0) throw CatalogValidation.invalid("配额增量必须为正数");
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("资源配额检查必须加入资源创建事务");
    }
    /** 锁和真实计数同属外层创建事务，锁持有到提交或回滚。 */
    private void requireLockedCapacity(String tenantId,ResourceKind kind,long additional) {
        var quota=mapper.lockQuota(tenantId); if(quota==null) throw CatalogValidation.missing();
        if(!quota.planEnabled() || !("active".equals(quota.tenantStatus()) || "warning".equals(quota.tenantStatus()))) throw new ApiException(HttpStatus.FORBIDDEN,"tenant_resource_suspended","机构或套餐已停用",false);
        Long limit=quota.limit(kind); long used=mapper.used(tenantId,kind.name());
        // 减法判定避免 used+additional 溢出；0 是明确禁用，null 才表示不限。
        if(limit!=null && (used>limit || additional>limit-used)) throw new ApiException(HttpStatus.CONFLICT,"resource_quota_exceeded","资源配额不足："+kind.name(),false);
    }
}
