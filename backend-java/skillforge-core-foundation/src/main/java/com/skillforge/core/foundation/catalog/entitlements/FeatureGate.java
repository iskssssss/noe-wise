package com.skillforge.core.foundation.catalog.entitlements;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.catalog.CatalogScope;
import com.skillforge.core.foundation.catalog.CatalogValidation;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 租户功能即时授权入口；不冻结权限，也不改写已受理执行的历史配置。 */
@Service
public class FeatureGate {
    private final EntitlementMapper mapper; private final CatalogScope scope;
    private final DirectDeploymentScope deployments;
    /** 绑定唯一套餐授权事实源。 */
    public FeatureGate(EntitlementMapper mapper,CatalogScope scope,DirectDeploymentScope deployments) {this.mapper=mapper;this.scope=scope;this.deployments=deployments;}
    /** 当前机构功能受理检查，调用者仍须执行对应动作权限。 */
    public void require(String featureCode) { require(scope.tenant(),featureCode); }
    /** 平台受控目标或机构自己；无授权、停用或未注册功能明确拒绝。 */
    public void require(String tenantId,String featureCode) { if(!allowed(tenantId,featureCode)) throw new ApiException(HttpStatus.FORBIDDEN,"tenant_feature_disabled","机构未获授权使用该功能",false); }
    /** 当前机构即时授权状态，用于展示且不能替代真正写入边界 require。 */
    public boolean allowed(String featureCode) { return allowed(scope.tenant(),featureCode); }
    /** 内部显式目标查询，RLS 临时范围在结束后恢复。 */
    public boolean allowed(String tenantId,String featureCode) { CatalogValidation.code(featureCode);return scope.inTenant(tenantId,()->mapper.featureAllowed(tenantId,featureCode)); }
    /** 受控直管入口只受理已获准目标机构的 Agent 部署，不授予其他功能或成员动作权限。 */
    public void requireDirectDeployment(String tenantId) {
        if(!deployments.inTenant(tenantId,()->mapper.featureAllowed(tenantId,"agents")))
            throw new ApiException(HttpStatus.FORBIDDEN,"tenant_feature_disabled","机构未获授权使用该功能",false);
    }
}
