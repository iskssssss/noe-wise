package com.skillforge.core.foundation.catalog.entitlements;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.catalog.CatalogScope;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.common.context.ServicePrincipalContext;
import java.util.Objects;
import java.util.function.Supplier;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Component;

/** 已明确获准的平台代理部署范围，临时绑定线程与同一事务连接后精确恢复。 */
@Component
public final class DirectDeploymentScope {
    private final PlatformManagementService management;
    private final ContextMapper mapper;
    private final InstitutionIntegrationService integrations;
    public DirectDeploymentScope(PlatformManagementService management,ContextMapper mapper,InstitutionIntegrationService integrations) {
        this.management=management;this.mapper=mapper;this.integrations=integrations;
    }
    /** 只能为平台当前获准目标申请Agent功能/容量，不形成其他资源管理权。 */
    public <T> T inTenant(String requestedTenantId,Supplier<T> work) {
        Objects.requireNonNull(work);
        String target=CatalogScope.id(requestedTenantId);
        var principal=ServicePrincipalContext.get();
        if(principal==null) {
            var delegated=com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService.currentTarget();
            if(delegated==null)management.requireTenant("platform.agent_deployments.create",Long.parseLong(target));
            else if(!target.equals(delegated.targetTenantId())||!target.equals(TenantContext.getTenantId())
                    ||!java.util.Set.of("tenant.agent_deployments.create","tenant.agent_deployments.bind","tenant.agent_deployments.switch_authorization").contains(delegated.action()))
                throw new SecurityException("delegated_deployment_tenant_mismatch");
        }
        else {
            integrations.requirePrincipal(principal,"resources.write");
            if(!target.equals(principal.tenantId()))throw new SecurityException("deployment_tenant_mismatch");
        }
        return bound(target,work);
    }
    /** 机构管理机器的直属开通专用容量范围；resources.write不获得此权限。 */
    public <T>T inSubInstitutionTenant(String requestedTenantId,Supplier<T> work) {
        String target=CatalogScope.id(requestedTenantId);
        var principal=ServicePrincipalContext.get();
        if(principal==null || !target.equals(principal.tenantId()) || !target.equals(TenantContext.getTenantId()))
            throw new SecurityException("sub_institution_principal_scope_mismatch");
        integrations.requirePrincipal(principal,"tenant.sub_institutions.provision");
        return bound(target,work);
    }
    private <T>T bound(String target,Supplier<T> work) {
        String previous=TenantContext.getTenantId();
        if(previous!=null&&!previous.equals(target))throw new SecurityException("database_tenant_scope_mismatch");
        var original=mapper.current();
        Throwable failure=null;
        try{TenantContext.setTenantId(target);mapper.bindTenant(target);return work.get();}
        catch(RuntimeException|Error rejected){failure=rejected;throw rejected;}
        finally{
            try{mapper.restore(original.tenant(),original.platformAdmin());}
            catch(RuntimeException|Error restore){if(failure!=null)failure.addSuppressed(restore);else throw restore;}
            finally{if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);}
        }
    }
    /** 仅保存和恢复连接安全上下文，不允许请求指定数据库权限。 */
    @Mapper
    public interface ContextMapper {
        /** 读取同一事务连接的真实GUC，不从请求声明安全上下文。 */
        State current();
        /** 仅绑定已验证目标租户并关闭平台读取权限，使用当前事务连接。 */
        String bindTenant(@Param("tenant") String tenant);
        /** 在成功与异常路径精确恢复原连接上下文，不改变授权事实。 */
        String restore(@Param("tenant")String tenant,@Param("platformAdmin")String platformAdmin);
    }
    /** 同一连接原上下文。 */
    public record State(String tenant,String platformAdmin) { }
}
