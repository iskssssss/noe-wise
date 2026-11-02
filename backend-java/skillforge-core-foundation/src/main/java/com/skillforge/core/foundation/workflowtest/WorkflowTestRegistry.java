package com.skillforge.core.foundation.workflowtest;

import java.time.Instant;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** 测试资源目录的公开领域契约；跨模块调用者不读取表映射器或持久化实体。 */
@Service
public class WorkflowTestRegistry {
    private final WorkflowTestIsolationMapper mapper;
    public WorkflowTestRegistry(WorkflowTestIsolationMapper mapper) {this.mapper=mapper;}
    public record Source(String kind,long containerId,String workflowId,Long draftRevision,Long definitionVersionId,String hash) {}
    public record Owned(long containerId,long definitionVersionId,long capabilityDefinitionId,long capabilityVersionId,
            long instanceId,long installationRevision,long agentId,long endUserId,String sessionId) {}
    public record Limits(int maxBusinessModelCalls,int maxToolCalls,int maxKnowledgeCalls,long maxTotalTokens,long maxWallTimeSeconds) {}
    public record Registration(long id,long tenantId,long initiatorMemberId,String requestId,String requestFingerprint,
            Source source,Owned owned,String initialInputId,String initialMessage,Map<String,Object> parameters,Limits limits,Instant expiresAt) {}
    public record Binding(long tenantId,long testSessionId,String slot,String capabilityType,long capabilityDefinitionId,
            long capabilityVersionId,Long toolDeploymentId,String toolDeploymentKey,Long toolDeploymentRevision,Long knowledgeRevision) {}
    /** 身份和输入均为不可变快照；读取不赋予当前派发或管理权限。 */
    public record Snapshot(Long id,
            Long tenantId,
            Long initiatorMemberId,
            String requestId,
            String requestFingerprint,
            String sourceKind,
            Long sourceContainerId,
            String sourceWorkflowId,
            Long sourceDraftRevision,
            Long sourceDefinitionVersionId,
            String sourceHash,
            Long containerId,
            Long definitionVersionId,
            Long capabilityDefinitionId,
            Long capabilityVersionId,
            Long instanceId,
            Long installationRevision,
            Long agentId,
            Long endUserId,
            String sessionId,
            String initialInputId,
            String initialMessage,
            Map<String,Object> parameters,
            Integer maxBusinessModelCalls,
            Integer maxToolCalls,
            Integer maxKnowledgeCalls,
            Long maxTotalTokens,
            Long maxWallTimeSeconds,
            String status,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt,
            Instant closedAt) {
        public Snapshot {parameters=freezeMap(parameters);}
        public Long getId() {return id;}
        public Long getTenantId() {return tenantId;}
        public Long getInitiatorMemberId() {return initiatorMemberId;}
        public String getRequestId() {return requestId;}
        public String getRequestFingerprint() {return requestFingerprint;}
        public String getSourceKind() {return sourceKind;}
        public Long getSourceContainerId() {return sourceContainerId;}
        public String getSourceWorkflowId() {return sourceWorkflowId;}
        public Long getSourceDraftRevision() {return sourceDraftRevision;}
        public Long getSourceDefinitionVersionId() {return sourceDefinitionVersionId;}
        public String getSourceHash() {return sourceHash;}
        public Long getContainerId() {return containerId;}
        public Long getDefinitionVersionId() {return definitionVersionId;}
        public Long getCapabilityDefinitionId() {return capabilityDefinitionId;}
        public Long getCapabilityVersionId() {return capabilityVersionId;}
        public Long getInstanceId() {return instanceId;}
        public Long getInstallationRevision() {return installationRevision;}
        public Long getAgentId() {return agentId;}
        public Long getEndUserId() {return endUserId;}
        public String getSessionId() {return sessionId;}
        public String getInitialInputId() {return initialInputId;}
        public String getInitialMessage() {return initialMessage;}
        public Map<String,Object> getParameters() {return parameters;}
        public Integer getMaxBusinessModelCalls() {return maxBusinessModelCalls;}
        public Integer getMaxToolCalls() {return maxToolCalls;}
        public Integer getMaxKnowledgeCalls() {return maxKnowledgeCalls;}
        public Long getMaxTotalTokens() {return maxTotalTokens;}
        public Long getMaxWallTimeSeconds() {return maxWallTimeSeconds;}
        public String getStatus() {return status;}
        public Instant getExpiresAt() {return expiresAt;}
        public Instant getCreatedAt() {return createdAt;}
        public Instant getUpdatedAt() {return updatedAt;}
        public Instant getClosedAt() {return closedAt;}
    }
    public Snapshot bySession(long tenant,String session) {return snapshot(mapper.bySession(tenant,session));}
    public Snapshot byRequest(long tenant,long member,String request) {return snapshot(mapper.byRequest(tenant,member,request));}
    public Snapshot byAgent(long tenant,long agent) {return snapshot(mapper.byAgent(tenant,agent));}
    public Snapshot byInstance(long tenant,long instance) {return snapshot(mapper.byInstance(tenant,instance));}
    public Snapshot byEndUser(long tenant,long user) {return snapshot(mapper.byEndUser(tenant,user));}
    public Snapshot find(long tenant,long id) {
        return snapshot(mapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowTestSessionDO>()
                .eq(WorkflowTestSessionDO::getTenantId,tenant).eq(WorkflowTestSessionDO::getId,id)));
    }
    public boolean ownedResource(long tenant,String kind,String id) {return mapper.ownedResource(tenant,kind,id);}
    /** 管理目录批量排除测试 Agent；只返回当前租户候选集合内的持久测试归属。 */
    public Set<Long> ownedAgentIds(long tenant,List<Long> candidateIds) {
        if(candidateIds.isEmpty())return Set.of();
        return Set.copyOf(mapper.ownedAgentIds(tenant,candidateIds.stream().distinct().toList()));
    }
    public void lockRequest(String key) {mapper.lockRequest(key);}
    public List<Binding> bindings(long tenant,long id) {return mapper.bindings(tenant,id).stream().map(value->new Binding(value.tenantId(),value.testSessionId(),value.slot(),value.capabilityType(),value.capabilityDefinitionId(),value.capabilityVersionId(),value.toolDeploymentId(),value.toolDeploymentKey(),value.toolDeploymentRevision(),value.knowledgeRevision())).toList();}
    /** 所有资源及原请求回执必须加入同一宿主创建事务，任何失败全部回滚。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Snapshot register(Registration value,List<Binding> bindings) {
        var row=new WorkflowTestSessionDO();var source=value.source();var owned=value.owned();var limits=value.limits();
        row.setId(value.id());row.setTenantId(value.tenantId());row.setInitiatorMemberId(value.initiatorMemberId());row.setRequestId(value.requestId());row.setRequestFingerprint(value.requestFingerprint());
        row.setSourceKind(source.kind());row.setSourceContainerId(source.containerId());row.setSourceWorkflowId(source.workflowId());row.setSourceDraftRevision(source.draftRevision());row.setSourceDefinitionVersionId(source.definitionVersionId());row.setSourceHash(source.hash());
        row.setContainerId(owned.containerId());row.setDefinitionVersionId(owned.definitionVersionId());row.setCapabilityDefinitionId(owned.capabilityDefinitionId());row.setCapabilityVersionId(owned.capabilityVersionId());
        row.setInstanceId(owned.instanceId());row.setInstallationRevision(owned.installationRevision());row.setAgentId(owned.agentId());row.setEndUserId(owned.endUserId());row.setSessionId(owned.sessionId());
        row.setInitialInputId(value.initialInputId());row.setInitialMessage(value.initialMessage());row.setParameters(value.parameters());
        row.setMaxBusinessModelCalls(limits.maxBusinessModelCalls());row.setMaxToolCalls(limits.maxToolCalls());row.setMaxKnowledgeCalls(limits.maxKnowledgeCalls());row.setMaxTotalTokens(limits.maxTotalTokens());row.setMaxWallTimeSeconds(limits.maxWallTimeSeconds());
        row.setStatus("ready");Instant now=Instant.now();row.setCreatedAt(now);row.setUpdatedAt(now);row.setExpiresAt(value.expiresAt());
        if(mapper.insert(row)!=1)throw new IllegalStateException("workflow_test_identity_insert_failed");
        for(var binding:bindings) {
            if(binding.tenantId()!=value.tenantId()||binding.testSessionId()!=value.id())throw new IllegalArgumentException("workflow_test_binding_owner_mismatch");
            if(mapper.insertBinding(new WorkflowTestIsolationMapper.Binding(binding.tenantId(),binding.testSessionId(),binding.slot(),binding.capabilityType(),binding.capabilityDefinitionId(),binding.capabilityVersionId(),binding.toolDeploymentId(),binding.toolDeploymentKey(),binding.toolDeploymentRevision(),binding.knowledgeRevision()))!=1)
                throw new IllegalStateException("workflow_test_binding_insert_failed");
        }
        return snapshot(row);
    }
    public Snapshot beginClosing(long tenant,long id) {mapper.closing(tenant,id);return find(tenant,id);}
    public Snapshot finishClosing(long tenant,long id) {mapper.closed(tenant,id);return find(tenant,id);}
    public boolean quiet(long tenant,String session) {return mapper.quiet(tenant,session);}
    public List<Snapshot> expiring(long tenant) {return mapper.expiring(tenant).stream().map(WorkflowTestRegistry::snapshot).toList();}
    /** Store借用同一SqlSession读取与锁定，归属查询仍由Foundation内部Mapper拥有。 */
    public Snapshot session(SqlSession sql,long tenant,String session) {return snapshot(sql.getMapper(WorkflowTestIsolationMapper.class).bySession(tenant,session));}
    public Snapshot lockSession(SqlSession sql,long tenant,String session) {return snapshot(sql.getMapper(WorkflowTestIsolationMapper.class).lockBySession(tenant,session));}
    public Snapshot agent(SqlSession sql,long tenant,long agent) {return snapshot(sql.getMapper(WorkflowTestIsolationMapper.class).byAgent(tenant,agent));}
    public static void configure(org.apache.ibatis.session.Configuration configuration) {
        if(configuration.hasMapper(WorkflowTestIsolationMapper.class))return;
        String resource="mapper/foundation/WorkflowTestIsolationMapper.xml";
        try(var source=WorkflowTestIsolationMapper.class.getClassLoader().getResourceAsStream(resource)) {
            if(source==null)throw new IllegalStateException("workflow_test_mapper_missing");
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(source,configuration,resource,configuration.getSqlFragments()).parse();
        }catch(java.io.IOException invalid){throw new IllegalStateException("workflow_test_mapper_invalid",invalid);}
    }
    private static Snapshot snapshot(WorkflowTestSessionDO row) {return row==null?null:new Snapshot(row.getId(),
                row.getTenantId(),
                row.getInitiatorMemberId(),
                row.getRequestId(),
                row.getRequestFingerprint(),
                row.getSourceKind(),
                row.getSourceContainerId(),
                row.getSourceWorkflowId(),
                row.getSourceDraftRevision(),
                row.getSourceDefinitionVersionId(),
                row.getSourceHash(),
                row.getContainerId(),
                row.getDefinitionVersionId(),
                row.getCapabilityDefinitionId(),
                row.getCapabilityVersionId(),
                row.getInstanceId(),
                row.getInstallationRevision(),
                row.getAgentId(),
                row.getEndUserId(),
                row.getSessionId(),
                row.getInitialInputId(),
                row.getInitialMessage(),
                row.getParameters(),
                row.getMaxBusinessModelCalls(),
                row.getMaxToolCalls(),
                row.getMaxKnowledgeCalls(),
                row.getMaxTotalTokens(),
                row.getMaxWallTimeSeconds(),
                row.getStatus(),
                row.getExpiresAt(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getClosedAt());}
    private static Map<String,Object> freezeMap(Map<String,Object> value) {
        if(value==null)throw new IllegalArgumentException("workflow_test_parameters_required");
        var result=new LinkedHashMap<String,Object>();value.forEach((key,item)->result.put(key,freeze(item)));return Collections.unmodifiableMap(result);
    }
    private static Object freeze(Object value) {
        if(value instanceof Map<?,?> map) {var result=new LinkedHashMap<String,Object>();map.forEach((key,item)->result.put((String)key,freeze(item)));return Collections.unmodifiableMap(result);}
        if(value instanceof List<?> list) {var result=new ArrayList<Object>();list.forEach(item->result.add(freeze(item)));return Collections.unmodifiableList(result);}
        return value;
    }
}
