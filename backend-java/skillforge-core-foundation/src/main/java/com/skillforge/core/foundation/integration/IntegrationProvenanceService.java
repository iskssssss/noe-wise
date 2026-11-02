package com.skillforge.core.foundation.integration;

import com.skillforge.common.context.TenantContext;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 宿主来源只引用已受理 Session/Run/ControlOperation；不另建执行检查点或来源证明图。 */
@Service
public class IntegrationProvenanceService {
    private final IntegrationProvenanceMapper mapper;
    private final InstitutionIntegrationService integrations;

    /** 来源和当前接入权限分别属于持久事实与当前授权。 */
    public IntegrationProvenanceService(IntegrationProvenanceMapper mapper, InstitutionIntegrationService integrations) {
        this.mapper=mapper; this.integrations=integrations;
    }

    /** 与主 Run 的现有绑定同事务冻结入口及根；部署引用必须由同次已解析执行材料提供。 */
    @Transactional
    public void freezeRun(String tenantId,String runId,String sessionId,Long agentId,String rootInputId,
            Long deploymentId,Long deploymentRevision,Long grantId,Long grantRevision) {
        scoped(tenantId,()->{
            var session=mapper.session(tenantId,sessionId);
            if(session==null)throw new IllegalStateException("integration_session_source_missing");
            persist(tenantId,runId,null,new Source(session.originKind(),session.actorRef(),session.responsibilityRef(),
                    session.integrationId(),session.businessScopeId(),agentId,sessionId,rootInputId,
                    deploymentId,deploymentRevision,grantId,grantRevision));
            return null;
        });
    }

    /**
     * 群准入借用 Store 所有的租户事务，来源来自已锁定的 trigger 与本次冻结部署。
     * 不读取群 Session 创建者，不另开连接或提交；Run、槽和责任来源一起生效。
     */
    public void freezeGroupRun(org.apache.ibatis.session.SqlSession sql,String tenantId,String runId,Source source) {
        Objects.requireNonNull(sql);Objects.requireNonNull(source);
        if (!"external".equals(source.originKind()) || source.integrationId()==null
                || source.actorRef()==null || source.responsibilityRef()==null
                || source.sessionId()==null || source.rootInputId()==null || source.agentId()==null)
            throw new IllegalArgumentException("group_run_source_incomplete");
        var borrowed=sql.getMapper(IntegrationProvenanceMapper.class);
        borrowed.insert(tenantId,runId,null,source);
        if(!Objects.equals(source,borrowed.run(tenantId,runId)))throw new IllegalStateException("integration_run_source_conflict");
    }

    /** Store 构建工厂时安装所属映射；执行回调内不修改 Configuration。 */
    public static void configure(org.apache.ibatis.session.Configuration configuration) {
        String resource="mapper/IntegrationProvenanceMapper.xml";
        if(configuration.hasMapper(IntegrationProvenanceMapper.class))return;
        try(var input=IntegrationProvenanceService.class.getClassLoader().getResourceAsStream(resource)) {
            if(input==null)throw new IllegalStateException("integration_provenance_mapper_missing");
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();
        }catch(java.io.IOException failure){throw new IllegalStateException("integration_provenance_mapper_failed",failure);}
    }

    /** 委派继承原请求责任与根；实际执行实例和授权引用来自父运行已冻结的目标成员。 */
    @Transactional
    public void inheritRun(String tenantId,String runId,String parentRunId,Long agentId,
            Long deploymentId,Long deploymentRevision,Long grantId,Long grantRevision) {
        scoped(tenantId,()->{
            var parent=mapper.run(tenantId,parentRunId);
            if(parent==null)throw new IllegalStateException("integration_parent_source_missing");
            persist(tenantId,runId,parentRunId,new Source(parent.originKind(),parent.actorRef(),parent.responsibilityRef(),
                    parent.integrationId(),parent.businessScopeId(),agentId,parent.sessionId(),parent.rootInputId(),
                    deploymentId,deploymentRevision,grantId,grantRevision));
            return null;
        });
    }

    /** 无 Run 的会话入口仅读取该 Session 已有来源。 */
    @Transactional(readOnly=true)
    public Source resolveSession(String tenantId,String sessionId) { return scoped(tenantId,()->mapper.session(tenantId,sessionId)); }

    /** 只读精确机构 Run 来源或 typed Run 对应的原系统宿主快照，不读取当前部署反推历史。 */
    @Transactional(readOnly=true)
    public Source resolveRun(String tenantId,String runId) { return scoped(tenantId,()->mapper.run(tenantId,runId)); }

    /** 控制操作可以没有 Session、Run 或 Agent，其责任来源仍持久存在。 */
    @Transactional(readOnly=true)
    public Source resolveControlOperation(String tenantId,String operationId) { return scoped(tenantId,()->mapper.controlOperation(tenantId,operationId)); }

    /** 新派发/恢复边界复验当前机构和业务范围；原生来源不被强行转换为外部 integration。 */
    @Transactional(readOnly=true)
    public void requireCurrentSessionAccess(String tenantId,String sessionId) {
        scoped(tenantId,()->{
            var source=mapper.session(tenantId,sessionId);
            if(source==null)throw new SecurityException("integration_session_source_missing");
            if(source.integrationId()!=null) {
                integrations.requireIntegration(tenantId,source.integrationId());
                if(source.businessScopeId()!=null)
                    integrations.requireBusinessScopeById(tenantId,source.integrationId(),source.businessScopeId());
            }
            return null;
        });
    }

    /** 重投只接受相同不可变来源；同 Run 异参使受理事务整体回滚。 */
    private void persist(String tenantId,String runId,String parentRunId,Source source) {
        mapper.insert(tenantId,runId,parentRunId,source);
        if(!Objects.equals(source,mapper.run(tenantId,runId)))throw new IllegalStateException("integration_run_source_conflict");
    }

    /** 临时绑定所属 Tenant，并禁止调用线程跨越已有不同机构身份。 */
    private <T>T scoped(String tenantId,Supplier<T> work) {
        String previous=TenantContext.getTenantId();
        if(previous!=null&&!previous.equals(tenantId))throw new SecurityException("integration_source_tenant_mismatch");
        try { TenantContext.setTenantId(tenantId); return work.get(); }
        finally { if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous); }
    }

    /**
     * 已受理业务事实的来源引用；不是可由请求正文填充的授权凭据。
     * @param originKind external/native/system
     * @param actorRef 真实发起主体
     * @param responsibilityRef 原业务责任主体
     * @param integrationId 外部接入必填，原生/系统可空
     * @param businessScopeId 已授权业务范围，无范围为空
     * @param agentId 实际执行实例，无 Agent 的控制来源为空
     * @param sessionId 原产品会话，控制来源可空
     * @param rootInputId 原受理根，未产生 Run 时为空
     * @param deploymentId 受控部署身份，自有 Agent 为空
     * @param deploymentRevision 原冻结部署修订
     * @param grantId 原选授权，不自动换链
     * @param grantRevision 原冻结授权修订
     */
    public record Source(String originKind,String actorRef,String responsibilityRef,String integrationId,
            String businessScopeId,Long agentId,String sessionId,String rootInputId,Long deploymentId,
            Long deploymentRevision,Long grantId,Long grantRevision) { }
}
