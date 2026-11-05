package com.skillforge.core.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.core.agent.dto.response.CapabilityMountResponse;
import com.skillforge.core.agent.mapper.AgentPublicationMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 稳定智能体身份的发布快照入口；Run 只读取本入口，不读取可变工作草稿。 */
@Service
public class AgentPublicationService {
    private final AgentService agents;
    private final AgentPublicationMapper publications;
    private final CapabilityMountPersistenceService mounts;
    private final ObjectMapper json;
    private final org.springframework.beans.factory.ObjectProvider<AgentExecutionPublicationResolver> executionResolvers;

    public AgentPublicationService(AgentService agents, AgentPublicationMapper publications,
            CapabilityMountPersistenceService mounts, ObjectMapper json,
            org.springframework.beans.factory.ObjectProvider<AgentExecutionPublicationResolver> executionResolvers) {
        this.agents = agents;
        this.publications = publications;
        this.mounts = mounts;
        this.json = json.copy();
        this.executionResolvers = executionResolvers;
    }

    /** 持有稳定身份锁后追加快照和原子切换指针；同一身份并发发布不会复用修订号。 */
    @Transactional(rollbackFor = Exception.class)
    public Publication publish(AgentDO agent) {
        return publish(agent, null);
    }

    /** 请求身份随成功快照一次写入；普通发布不写请求身份，原有语义保持。 */
    @Transactional(rollbackFor = Exception.class)
    public Publication publish(AgentDO agent, RequestIdentity request) {
        int revision = Math.addExact(agent.getRevision(), 1);
        long publicationId = SnowflakeIds.nextId();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        CapabilityMountSetResult mountSet = mounts.read(agent.getTenantId(), agent.getId());
        agent.setRevision(revision);
        agent.setPublishedRevisionId(publicationId);
        agent.setHasDraftChanges(false);
        agent.setStatus("published");
        agent.setPublishedAt(now);
        var snapshot = new Snapshot(agent, mountSet.generation(),
                mountSet.mounts().stream().map(CapabilityMountResponse::from).toList());
        int inserted = request == null
                ? publications.insert(publicationId, agent.getTenantId(), agent.getId(), revision, encode(snapshot), now)
                : publications.insertRequest(publicationId, agent.getTenantId(), agent.getId(), revision,
                        encode(snapshot), now, request.requestId(), request.fingerprint(), request.actor());
        if (inserted != 1 || !agents.updateById(agent))
            throw new IllegalStateException("Agent 发布快照写入失败");
        return new Publication(publicationId, revision, agent, mountSet, now);
    }

    /** 只读取原命令窄回执，不加载发布正文，不切换当前指针。 */
    public AgentPublicationMapper.RequestRow findRequest(long tenantId, long agentId, java.util.UUID requestId) {
        return publications.findRequest(tenantId, agentId, requestId);
    }

    /** 对原 Update 做局部规范编码；嵌套 Map 排序，但保留 null、空集合及数组顺序。 */
    public String requestFingerprint(long tenantId, long agentId, String actor,
            com.skillforge.core.agent.management.AgentManagementContracts.Update payload) {
        return requestFingerprint("agent.save-and-publish.v1", tenantId, agentId, actor, payload);
    }

    public String publishRequestFingerprint(long tenantId, long agentId, String actor) {
        return requestFingerprint("agent.publish.v1", tenantId, agentId, actor, Map.of());
    }

    private String requestFingerprint(String operation, long tenantId, long agentId, String actor, Object payload) {
        var mapper = json.copy().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var command = Map.of("operation", operation, "tenant", tenantId,
                "agent", agentId, "actor", actor, "payload", mapper.convertValue(payload, Object.class));
        try {
            byte[] bytes = mapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(command);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Agent 保存发布命令无法编码", failure);
        }
    }

    public record RequestIdentity(java.util.UUID requestId, String fingerprint, String actor) { }

    /** 新执行解析当前发布指针；停用和归档即时生效，草稿编辑不影响已发布配置。 */
    @Transactional(readOnly = true)
    public Publication requirePublished(long tenantId, long agentId) {
        AgentDO identity = agents.getOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentDO>()
                .eq(AgentDO::getTenantId, tenantId).eq(AgentDO::getId, agentId));
        if (identity == null || !"published".equals(identity.getStatus())
                || identity.getPublishedRevisionId() == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent 不存在或未发布");
        for (var resolver : executionResolvers.orderedStream().toList()) {
            var deployed = resolver.resolve(tenantId, agentId);
            if (deployed.isPresent()) return deployed.orElseThrow();
        }
        return decode(requireRow(publications.find(tenantId, agentId, identity.getPublishedRevisionId())),
                tenantId, agentId);
    }

    /** 显式新Run准备入口，与后续冻结共用宿主事务；历史Run恢复不在此追加部署。 */
    @Transactional(rollbackFor=Exception.class)
    public void prepareExecution(long tenantId,long agentId) {
        var log=org.slf4j.LoggerFactory.getLogger(AgentPublicationService.class);
        log.debug("Agent执行发布准备进入 tenantId={} agentId={}",tenantId,agentId);
        executionResolvers.orderedStream().forEach(resolver->resolver.prepareExecution(tenantId,agentId));
        log.debug("Agent执行发布准备退出 tenantId={} agentId={}",tenantId,agentId);
    }

    /** 渠道目录批量读取发布配置和挂载；不会暴露正在编辑的工作草稿。 */
    @Transactional(readOnly = true)
    public Map<Long, Publication> currentPublishedAll(long tenantId, List<Long> agentIds) {
        if (agentIds.isEmpty()) return Map.of();
        Map<Long, Publication> result = new LinkedHashMap<>();
        for (var row : publications.current(tenantId, agentIds.stream().distinct().toList())) {
            Publication value=decode(row,tenantId,row.agentId());
            for(var resolver:executionResolvers.orderedStream().toList()) {
                var deployed=resolver.resolve(tenantId,row.agentId());
                if(deployed.isPresent()) {value=deployed.orElseThrow();break;}
            }
            result.put(row.agentId(),value);
        }
        return Map.copyOf(result);
    }

    /** 获取特定历史快照用于追溯或恢复草稿，不能更改当前发布指针。 */
    public Publication revision(long tenantId, long agentId, int revision) {
        return decode(requireRow(publications.findRevision(tenantId, agentId, revision)), tenantId, agentId);
    }

    public List<AgentPublicationMapper.History> history(long tenantId, long agentId) {
        return publications.history(tenantId, agentId);
    }

    private AgentPublicationMapper.Row requireRow(AgentPublicationMapper.Row row) {
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "Agent 发布修订不存在");
        return row;
    }

    private String encode(Snapshot snapshot) {
        try { return json.writeValueAsString(snapshot); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent 发布快照编码失败", exception); }
    }

    private Publication decode(AgentPublicationMapper.Row row, long tenantId, long agentId) {
        try {
            Snapshot snapshot = json.readValue(row.snapshot(), Snapshot.class);
            AgentDO agent = snapshot.agent();
            if (agent == null || !Long.valueOf(tenantId).equals(agent.getTenantId())
                    || !Long.valueOf(agentId).equals(agent.getId()))
                throw new IllegalStateException("Agent 发布快照身份不匹配");
            var mountSet = new CapabilityMountSetResult(tenantId, agentId, snapshot.generation(),
                    snapshot.mounts().stream().map(mount -> toDomain(mount, tenantId, agentId)).toList());
            return new Publication(row.id(), row.revision(), agent, mountSet, row.publishedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 发布快照解码失败", exception);
        }
    }

    private CapabilityMount toDomain(CapabilityMountResponse value, long tenantId, long agentId) {
        CapabilityMount.MountConfig config = switch (value.capabilityType()) {
            case SKILL -> new CapabilityMount.SkillMountConfig(value.skillConfig().activationMode(), value.skillConfig().tokenBudget());
            case TOOL -> new CapabilityMount.ToolMountConfig(value.toolConfig().toolDeploymentId(), value.toolConfig().toolDeploymentRevision());
            case WORKFLOW -> new CapabilityMount.WorkflowMountConfig(value.workflowConfig().workflowInstanceId(),
                    value.workflowConfig().workflowInstanceRevision(), value.workflowConfig().installationRevision());
            case KNOWLEDGE_SOURCE -> new CapabilityMount.KnowledgeMountConfig(value.knowledgeConfig().mode(), value.knowledgeConfig().pinnedRevisionId());
        };
        return new CapabilityMount(value.mountId(), tenantId, agentId,
                new CapabilityRef(value.capabilityType(), value.capabilityDefinitionId().toString(),
                        value.capabilityVersionId().toString()), config, value.priority(), value.revision(), value.status().active());
    }

    public record Publication(Long revisionId, int revision, AgentDO agent,
            CapabilityMountSetResult mountSet, Instant publishedAt,String definitionVersion,Long definitionTemplateId) {
        /** 独立Agent发布的版本源是其自身发布修订，模板仅表示创建来源。 */
        public Publication(Long revisionId,int revision,AgentDO agent,CapabilityMountSetResult mountSet,Instant publishedAt) {
            this(revisionId,revision,agent,mountSet,publishedAt,"agent:"+revision,null);
        }
    }
    public record Snapshot(AgentDO agent, long generation, List<CapabilityMountResponse> mounts) { }
}
