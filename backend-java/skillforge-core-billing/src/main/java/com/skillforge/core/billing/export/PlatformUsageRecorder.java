package com.skillforge.core.billing.export;

import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.foundation.integration.IntegrationProvenanceService;
import com.skillforge.core.foundation.integration.IntegrationProvenanceMapper;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.Set;

/** 从持久执行来源确定计量归属，并在源事务中写入精确事实引用与可靠待发布状态。 */
@Service
public class PlatformUsageRecorder {
    public static final String SYSTEM_AGENT_SCOPE_TYPE = "system_agent_run";
    private static final Logger log=LoggerFactory.getLogger(PlatformUsageRecorder.class);
    private final PlatformUsageMapper mapper;
    private final IntegrationProvenanceService provenance;

    /** 注入可信来源和所属 SQL 入口，不接受 HTTP 上下文或模型元数据作为来源。 */
    public PlatformUsageRecorder(PlatformUsageMapper mapper,IntegrationProvenanceService provenance) {
        log.debug("平台用量归属组件装配进入");
        this.mapper=mapper; this.provenance=provenance;
        log.debug("平台用量归属组件装配退出");
    }

    /** 新事实仅解析冻结 Run；无 Run 的请求根读取已授权 Session，恢复不能查当前 Agent。 */
    public void attribute(CostEventDO event) {
        attribute(event, event.getRunId());
    }

    /** 冻结归属由可信调用点决定；效果事件保留实际 Run，消费方不重选来源。 */
    public void attribute(CostEventDO event, String attributionRunId) {
        log.debug("用量来源解析进入 tenant={} hasRun={}",event.getTenantId(),event.getRunId()!=null);
        // 成本确认/调整必须继承原事实，避免后来发布改变历史费用维度。
        if(event.getAdjustmentOfEventId()!=null) {
            event.setGrantChain(mapper.grantHops(Set.of(event.getTenantId()),List.of(event.getAdjustmentOfEventId()))
                    .stream().map(PlatformUsageGrantHopRow::toHop).toList());
            log.debug("用量来源沿用原事实退出 tenant={}",event.getTenantId()); return;
        }
        String tenant=event.getTenantId().toString();
        PlatformUsageSystemSource system = systemSource(tenant, event);
        if (system != null && event.getRunId() == null) {
            attributeSystem(event, system);
            return;
        }
        var source=event.getRunId()!=null ? provenance.resolveRun(tenant,java.util.Objects.requireNonNull(attributionRunId,"attributionRunId"))
                : event.getSessionId()!=null ? provenance.resolveSession(tenant,event.getSessionId())
                : "control_operation".equals(event.getScopeType()) && event.getScopeId()!=null
                    ? provenance.resolveControlOperation(tenant,event.getScopeId()) : null;
        if(source!=null) {
            applySource(event,source,mapper,attributionRunId,
                    event.getRunId()==null?null:mapper.executionSource(tenant,event.getRunId()));
        }
        else if(event.getRunId()!=null || event.getSessionId()!=null || "control_operation".equals(event.getScopeType())) {
            throw new IllegalStateException("usage_persistent_origin_missing");
        } else {
            // 无会话的内部调用保留真实调用点和费用机构，不虚构接入用户或 Agent。
            if(event.getCallPoint()==null || event.getCallPoint().isBlank())throw new IllegalStateException("usage_system_call_point_required");
            event.setOriginKind("system");
            event.setActorRef("call_point:"+event.getCallPoint());
            event.setResponsibilityRef("tenant:"+tenant);
        }
        if (system != null) applySystemReference(event, system);
        log.debug("用量来源解析退出 tenant={} origin={}",event.getTenantId(),event.getOriginKind());
    }

    /** 借用受理SqlSession，在Run插入前使用同次已冻结来源，不另开Spring事务。 */
    public void attributeRequest(SqlSession sql,CostEventDO event,String integrationId) {
        var source=sql.getMapper(IntegrationProvenanceMapper.class)
                .run(event.getTenantId().toString(),event.getRunId());
        if(source==null||!"external".equals(source.originKind())||!java.util.Objects.equals(integrationId,source.integrationId())
                ||!java.util.Objects.equals(event.getSessionId(),source.sessionId())||!java.util.Objects.equals(event.getAgentId(),source.agentId())
                ||!java.util.Objects.equals(event.getRootInputId(),source.rootInputId()))
            throw new IllegalStateException("usage_request_origin_mismatch");
        applySource(event,source,sql.getMapper(PlatformUsageMapper.class),event.getRunId(),
                new PlatformUsageExecutionSource(null,"END_USER","end_user:"+event.getEndUserId(),null,null));
    }

    private static void applySource(CostEventDO event,IntegrationProvenanceService.Source source,PlatformUsageMapper mapper,
            String attributionRunId,PlatformUsageExecutionSource execution) {
        String tenant=event.getTenantId().toString();
            event.setOriginKind(source.originKind());
            event.setActorRef(source.actorRef());
            event.setResponsibilityRef(source.responsibilityRef());
            event.setDeploymentId(source.deploymentId());
            event.setDeploymentRevision(source.deploymentRevision());
            event.setGrantId(source.grantId());
            event.setGrantRevision(source.grantRevision());
            if(source.rootInputId()!=null)event.setRootInputId(source.rootInputId());
            event.setIntegrationId(source.integrationId());
            event.setBusinessScopeId(source.businessScopeId());
            if(source.agentId()!=null)event.setAgentId(source.agentId());
            if(execution!=null) {
                event.setGroupBindingId(execution.groupBindingId());event.setRequesterKind(execution.requesterKind());
                event.setRequesterRef(execution.requesterRef());event.setOriginSessionId(execution.originSessionId());
                event.setOriginEventId(execution.originEventId());
            }
            if(event.getRunId()!=null && event.getAgentId()!=null) {
                var frozen=mapper.frozenSource(tenant,attributionRunId,event.getAgentId());
                if(frozen==null)throw new IllegalStateException("usage_frozen_agent_source_missing");
                event.setSourceOwnerKind(frozen.getSourceOwnerKind());
                event.setSourceOwnerTenantId(frozen.getSourceOwnerTenantId());
                event.setAgentDefinitionId(frozen.getAgentDefinitionId());
                event.setAgentDefinitionVersion(frozen.getAgentDefinitionVersion());
                event.setAgentRevisionId(frozen.getAgentRevisionId());
                event.setAgentRevision(frozen.getAgentRevision());
                if(!java.util.Objects.equals(source.deploymentId(),frozen.getDeploymentId())
                    || !java.util.Objects.equals(source.deploymentRevision(),frozen.getDeploymentRevision())
                    || !java.util.Objects.equals(source.grantId(),frozen.getGrantId())
                    || !java.util.Objects.equals(source.grantRevision(),frozen.getGrantRevision()))
                    throw new IllegalStateException("usage_frozen_authorization_mismatch");
                event.setGrantChain(mapper.frozenGrantHops(tenant,attributionRunId,event.getAgentId())
                        .stream().map(PlatformUsageGrantHopRow::toHop).toList());
                if(event.getDeploymentId()!=null && event.getGrantChain().isEmpty())
                    throw new IllegalStateException("usage_frozen_grant_chain_missing");
            }
    }

    private PlatformUsageSystemSource systemSource(String tenant, CostEventDO event) {
        if (!SYSTEM_AGENT_SCOPE_TYPE.equals(event.getScopeType())) return null;
        long id;
        try { id = Long.parseLong(event.getScopeId()); }
        catch (RuntimeException invalid) { throw new IllegalStateException("usage_system_run_reference_invalid", invalid); }
        if (id <= 0) throw new IllegalStateException("usage_system_run_reference_invalid");
        var source = mapper.systemSource(tenant, id);
        if (source == null || source.systemAgentRunId() != id)
            throw new IllegalStateException("usage_system_run_source_missing");
        return source;
    }

    private void attributeSystem(CostEventDO event, PlatformUsageSystemSource source) {
        if ("memory-extraction".equals(source.purpose()) && !source.debug()
                && (source.responsibilityAgentId() == null || source.sourceAuthorId() == null
                    || source.originSessionId() == null || source.originEventId() == null || source.rootInputId() == null))
            throw new IllegalStateException("usage_memory_extraction_source_missing");
        event.setOriginKind("system");
        event.setActorRef("system:system-agent:" + source.agentId());
        event.setResponsibilityRef(source.responsibilityAgentId() == null ? "tenant:" + event.getTenantId()
                : "agent:" + source.responsibilityAgentId());
        event.setIntegrationId(source.integrationId());
        event.setBusinessScopeId(source.businessScopeId());
        event.setGroupBindingId(source.groupBindingId());
        event.setResponsibilityAgentId(source.responsibilityAgentId());
        event.setOriginSessionId(source.originSessionId());
        event.setOriginEventId(source.originEventId());
        event.setRequesterKind("SYSTEM");
        event.setRequesterRef(event.getActorRef());
        // 原作者属于业务来源，独立后台提取的实际请求者仍是系统主体。
        if (source.sourceAuthorId() != null) event.setEndUserId(source.sourceAuthorId());
        if (source.rootInputId() != null) event.setRootInputId(source.rootInputId());
        event.setAgentId(source.agentId());
        event.setSourceOwnerKind("platform");
        event.setSourceOwnerTenantId(null);
        event.setAgentDefinitionVersion(source.configurationRef());
        applySystemReference(event, source);
    }

    private void applySystemReference(CostEventDO event, PlatformUsageSystemSource source) {
        event.setSystemAgentRunId(source.systemAgentRunId());
        event.setSystemAgentConfigurationRef(source.configurationRef());
        event.setSystemAgentInvocationRef(source.invocationRef());
    }

    /** 必须共享源事实事务；失败回滚原事实，不能先提交原事实再尽力写导出。 */
    public void enqueue(CostEventDO event) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("usage_requires_fact_transaction");
        if(!event.getGrantChain().isEmpty())mapper.insertGrantHops(event);
        mapper.insertPending(event);
    }

    /** 后续媒体观察沿用首次调用的授权链，不能按查询 Run 或当前授权树重建来源。 */
    public void inheritGrantChain(CostEventDO original, CostEventDO observation) {
        observation.setGrantChain(mapper.grantHops(Set.of(original.getTenantId()), List.of(original.getId()))
                .stream().map(PlatformUsageGrantHopRow::toHop).toList());
    }
}
