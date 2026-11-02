package com.skillforge.institution.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityDescriptorCodec;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.KnowledgeSourceDescriptor;
import com.skillforge.core.capability.SkillDescriptor;
import com.skillforge.core.capability.WorkflowDescriptor;
import com.skillforge.core.capability.ToolVersion;
import com.skillforge.core.capability.tool.ToolCatalog;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.capability.tool.ToolResolver;
import com.skillforge.institution.team.TeamService;
import com.skillforge.core.agent.integration.runtime.session.JdbcSessionCapabilities;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.KnowledgeCapacity;
import com.skillforge.runtime.api.KnowledgeOperatorType;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeHandle;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.time.Duration;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

/** 新派发事务的精确挂载读取；仅读取原冻结成员和当前版本/部署，不重建Session来源图。 */
final class AgentRuntimeExecutionMounts {
    private final ObjectMapper json;
    private final CapabilityDescriptorCodec descriptors;

    /** 与原Run绑定复用相同MountConfig联合类型与宿主JSON配置。 */
    AgentRuntimeExecutionMounts(ObjectMapper json) {
        this.json = Objects.requireNonNull(json).copy().addMixIn(CapabilityMount.MountConfig.class, MountConfigJson.class);
        descriptors = new CapabilityDescriptorCodec(json);
    }

    /** 在JDBC工厂创建时注册宿主所属映射，不在运行事务内修改配置。 */
    static void configure(Configuration configuration) {
        String resource = "mapper/AgentRuntimeExecutionMountsMapper.xml";
        if (configuration.hasStatement(Mapper.class.getName() + ".member", false)) return;
        try (var xml = AgentRuntimeExecutionMounts.class.getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("execution_mount_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (java.io.IOException failure) { throw new IllegalStateException("execution_mount_mapper_invalid", failure); }
    }

    /** 非活动frame调用者只在传入SqlSession中加载原冻结成员，拒绝归属漂移。 */
    TeamService.FrozenMember member(SqlSession session, RunIdentity run) {
        String value = session.getMapper(Mapper.class).member(run.runRef().tenantId().value(), run.runRef().runId(),
                run.owner().sessionRef().sessionId(), run.owner().target().kind().name(), run.owner().target().id(),
                run.owner().subject() instanceof com.skillforge.runtime.api.SessionSubject.User user?user.endUserId().value():null, run.agentId().value());
        if (value == null) throw denied();
        try { return json.readValue(value, TeamService.FrozenMember.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("execution_frozen_member_invalid", failure); }
    }

    /**
     * 已加载挂载直接消费原typed事实；当前查询使用同连接、同租户SqlSession。
     * 精确版本、schema、策略和Provider仍由所属领域Codec及Resolved契约校验。
     */
    ToolCatalog.Entry current(SqlSession session, RunIdentity run, FrozenToolTarget target, TeamService.FrozenMember member) {
        var frozen = member.mounts(); long tenant = Long.parseLong(run.runRef().tenantId().value());
        if (!member.agentId().equals(run.agentId().value()) || !frozen.tenantId().equals(tenant)
                || !frozen.agentId().toString().equals(run.agentId().value())) throw denied();
        var input = new InputId("input:" + run.runRef().runId());
        var selected = frozen.mounts().stream().filter(mount -> mount.active() && mount.capability().type() == CapabilityType.TOOL
                && JdbcSessionCapabilities.toolHandle(mount.mountId(), input).equals(target.handle())).toList();
        if (selected.size() != 1) throw denied();
        var mount = selected.getFirst();
        if (!(mount.config() instanceof CapabilityMount.ToolMountConfig deployment)) throw denied();
        var row = session.getMapper(Mapper.class).tool(run.runRef().tenantId().value(), mount.capability().capabilityId(),
                mount.capability().versionId(), deployment.toolDeploymentId());
        if (row == null || row.deploymentRevision < deployment.toolDeploymentRevision()) throw denied();
        try {
            Map<String,Object> descriptor = json.readValue(row.descriptorJson, new TypeReference<>() {});
            var decoded = descriptors.decode(mount.capability(), row.versionTenant, row.name, row.description,
                    row.trustLevel, row.status, row.contentHash, descriptor);
            if (!(decoded instanceof ToolVersion version)) throw denied();
            var bound = new ToolDeployment(deployment.toolDeploymentId(), tenant, mount.capability(),
                    ToolDeployment.ProviderType.valueOf(row.providerType), row.providerRef, deployment.toolDeploymentRevision(),
                    ToolDeployment.Environment.valueOf(row.environment), true);
            var resolved = new ToolResolver.Resolved(mount, version, bound);
            var binding = new com.skillforge.runtime.api.port.ToolPortal.ToolBinding(
                    com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.valueOf(row.providerType), row.providerRef, version.definition().name());
            return new ToolCatalog.Entry(binding.serverIdentity(), binding.serverIdentity() + "/" + version.definition().name(), resolved);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("execution_tool_descriptor_invalid", failure); }
    }

    /** 首次加载仅解析原FrozenMember里已PINNED的知识版本，绝不重新选择active revision。 */
    List<KnowledgeTarget> knowledge(Mapper mapper, RunIdentity run, TeamService.FrozenMember member) {
        requireMember(run, member);
        var result = new ArrayList<KnowledgeTarget>();
        for (var mount : member.mounts().mounts()) {
            if (!mount.active() || mount.capability().type() != CapabilityType.KNOWLEDGE_SOURCE) continue;
            var pin = knowledgePin(mount);
            var row = mapper.frozenKnowledge(run.runRef().tenantId().value(), mount.capability().capabilityId(),
                    mount.capability().versionId(), pin.pinnedRevisionId());
            result.add(knowledgeTarget(mount, row));
        }
        return List.copyOf(result);
    }

    /** 准入只从原冻结Skill版本读取正式包；没有挂载不查询，不从当前默认版本或宿主路径补资源。 */
    List<SkillFact> skills(Mapper mapper, RunIdentity run, TeamService.FrozenMember member) {
        requireMember(run, member);
        var result = new ArrayList<SkillFact>();
        for (var mount : member.mounts().mounts()) {
            if (!mount.active() || mount.capability().type() != CapabilityType.SKILL) continue;
            if (!(mount.config() instanceof CapabilityMount.SkillMountConfig configuration)) throw denied();
            var row = mapper.skill(run.runRef().tenantId().value(), mount.capability().capabilityId(), mount.capability().versionId());
            if (row == null) throw denied();
            try {
                Map<String,Object> descriptor = json.readValue(row.descriptorJson, new TypeReference<>() {});
                var decoded = descriptors.decode(mount.capability(), row.versionTenant, row.name, row.description,
                        row.trustLevel, row.status, row.contentHash, descriptor);
                if (!(decoded instanceof SkillDescriptor skill) || skill.activationMode() != configuration.activationMode()) throw denied();
                result.add(new SkillFact(mount, new TypedPayload("skillforge.runtime.capability.descriptor.v1", row.descriptorJson)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                throw new IllegalStateException("execution_skill_descriptor_invalid", failure);
            }
        }
        return List.copyOf(result);
    }

    /** 准入使用原精确Workflow挂载及正式版本超时，不选择当前实例/安装/default指针。 */
    List<FrozenWorkflowTarget> workflows(Mapper mapper, RunIdentity run, TeamService.FrozenMember member) {
        return workflows(mapper,run,member,false);
    }
    /** 宿主已授权输入使用user_invocable契约，保留相同精确挂载、IAM与目标解析。 */
    List<FrozenWorkflowTarget> authorizedWorkflows(Mapper mapper,RunIdentity run,TeamService.FrozenMember member) {
        return workflows(mapper,run,member,true);
    }
    private List<FrozenWorkflowTarget> workflows(Mapper mapper,RunIdentity run,TeamService.FrozenMember member,boolean authorizedInput) {
        requireMember(run, member);
        var result = new ArrayList<FrozenWorkflowTarget>();
        for (var mount : member.mounts().mounts()) {
            if (!mount.active() || mount.capability().type() != CapabilityType.WORKFLOW) continue;
            var row = mapper.workflow(run.runRef().tenantId().value(), mount.capability().capabilityId(), mount.capability().versionId());
            workflowTarget(run, mount, row,authorizedInput).ifPresent(result::add);
        }
        return List.copyOf(result);
    }

    /** 新派发只确认原精确实例/定义/安装仍发布，绝不选择当前默认或最新安装。 */
    FrozenWorkflowTarget current(SqlSession session, RunIdentity run, FrozenWorkflowTarget target, TeamService.FrozenMember member) {
        return current(session,run,target,member,false);
    }
    FrozenWorkflowTarget currentAuthorized(SqlSession session,RunIdentity run,FrozenWorkflowTarget target,TeamService.FrozenMember member) {
        return current(session,run,target,member,true);
    }
    private FrozenWorkflowTarget current(SqlSession session,RunIdentity run,FrozenWorkflowTarget target,TeamService.FrozenMember member,boolean authorizedInput) {
        requireMember(run, member);
        var selected = member.mounts().mounts().stream().filter(mount -> mount.active() && mount.capability().type() == CapabilityType.WORKFLOW
                && ("workflow:" + mount.mountId() + ":" + run.causationInputId().value()).equals(target.handle().value())).toList();
        if (selected.size() != 1 || !(selected.getFirst().config() instanceof CapabilityMount.WorkflowMountConfig configuration)) throw denied();
        var mount = selected.getFirst();
        var row = session.getMapper(Mapper.class).currentWorkflow(run.runRef().tenantId().value(), mount.capability().capabilityId(),
                mount.capability().versionId(), configuration.workflowInstanceId(), configuration.workflowInstanceRevision(), configuration.installationRevision());
        var current = workflowTarget(run, mount, row,authorizedInput).orElseThrow(AgentRuntimeExecutionMounts::denied);
        if (!current.equals(target)) throw denied();
        return current;
    }

    /** 所有目标字段均来自原挂载与同一正式版本descriptor，modelInvocable单独控制模型目录。 */
    private java.util.Optional<FrozenWorkflowTarget> workflowTarget(RunIdentity run, CapabilityMount mount, WorkflowRow row,boolean authorizedInput) {
        if (row == null || !(mount.config() instanceof CapabilityMount.WorkflowMountConfig configuration)) throw denied();
        try {
            Map<String,Object> descriptor = json.readValue(row.descriptorJson, new TypeReference<>() { });
            var decoded = descriptors.decode(mount.capability(), row.versionTenant, row.name, row.description,
                    row.trustLevel, row.status, row.contentHash, descriptor);
            if (!(decoded instanceof WorkflowDescriptor workflow)) throw denied();
            if (!(authorizedInput?workflow.metadata().invocationPolicy().userInvocable():workflow.metadata().invocationPolicy().modelInvocable())) return java.util.Optional.empty();
            return java.util.Optional.of(new FrozenWorkflowTarget(new WorkflowHandle("workflow:" + mount.mountId() + ":" + run.causationInputId().value()),
                    configuration.workflowInstanceId(), configuration.workflowInstanceRevision(),
                    new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.WORKFLOW,
                            mount.capability().capabilityId(), mount.capability().versionId()), Duration.ofMillis(workflow.timeoutMs()), configuration.installationRevision()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("execution_workflow_descriptor_invalid", failure);
        }
    }

    /** 派发事务只读取当前精确pin、发布状态和来源清单；加载材料不授予当前执行权限。 */
    KnowledgeTarget current(SqlSession session, RunIdentity run, FrozenKnowledgeTarget target, TeamService.FrozenMember member) {
        requireMember(run, member);
        var selected = member.mounts().mounts().stream().filter(mount -> mount.active()
                && mount.capability().type() == CapabilityType.KNOWLEDGE_SOURCE
                && ("knowledge:" + mount.mountId()).equals(target.handle().value())).toList();
        if (selected.size() != 1) throw denied();
        var mount = selected.getFirst(); var pin = knowledgePin(mount);
        var row = session.getMapper(Mapper.class).knowledge(run.runRef().tenantId().value(),
                mount.capability().capabilityId(), mount.capability().versionId(), pin.pinnedRevisionId());
        var current = knowledgeTarget(mount, row);
        if (!current.target().equals(target)) throw denied();
        return current;
    }

    /** 原成员归属来自Run冻结配置，不能拿同租户其他Agent的挂载充当当前允许范围。 */
    private static void requireMember(RunIdentity run, TeamService.FrozenMember member) {
        var frozen = member.mounts();
        if (!member.agentId().equals(run.agentId().value()) || !frozen.tenantId().toString().equals(run.runRef().tenantId().value())
                || !frozen.agentId().toString().equals(run.agentId().value())) throw denied();
    }

    /** freezeAgent已将FOLLOW_ACTIVE转成精确PINNED；未解析状态不能在恢复路径重新解析。 */
    private static CapabilityMount.KnowledgeMountConfig knowledgePin(CapabilityMount mount) {
        if (!(mount.config() instanceof CapabilityMount.KnowledgeMountConfig pin)
                || pin.mode() != CapabilityMount.KnowledgeMountConfig.BindingMode.PINNED_REVISION) throw denied();
        return pin;
    }

    /** 同一领域descriptor codec校验正式字段；Link跟进扩展仍保留在Jdbc绑定原正文，不转成模型权限。 */
    private KnowledgeTarget knowledgeTarget(CapabilityMount mount, KnowledgeRow row) {
        if (row == null) throw denied();
        try {
            Map<String,Object> descriptor = json.readValue(row.descriptorJson, new TypeReference<>() {});
            Object link = descriptor.remove("link_follow_up");
            if (link != null && !(link instanceof Map<?, ?>)) throw denied();
            var decoded = descriptors.decode(mount.capability(), row.versionTenant, row.name, row.description,
                    row.trustLevel, row.status, row.contentHash, descriptor);
            if (!(decoded instanceof KnowledgeSourceDescriptor knowledge) || !knowledge.sourceId().equals(row.sourceId)) throw denied();
            var versions = new LinkedHashSet<String>();
            var original = json.readTree(row.sourceVersions);
            if (!original.isArray()) throw denied();
            for (var value : original) {
                if (!value.isTextual() || !versions.add(value.textValue())) throw denied();
            }
            if (!versions.equals(knowledge.sourceVersions())) throw denied();
            var targets = versions.stream().map(version -> new KnowledgeSourceVersion(row.sourceId, version))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var operators = knowledge.allowedOperators().stream().map(KnowledgeOperatorType::valueOf)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            var reference = new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.KNOWLEDGE_SOURCE,
                    mount.capability().capabilityId(), mount.capability().versionId());
            var target = new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:" + mount.mountId()), reference,
                    row.sourceId, row.revision, targets, operators, Duration.ofSeconds(KnowledgeCapacity.DEFAULT_TIMEOUT_SECONDS));
            return new KnowledgeTarget(target, knowledge.metadata().invocationPolicy().modelInvocable(), knowledge.citationRequired());
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("execution_knowledge_descriptor_invalid", failure);
        }
    }

    /** 当前权限或精确挂载不成立时失败关闭。 */
    private static IllegalArgumentException denied() { return new IllegalArgumentException("studio_tool_no_longer_authorized"); }

    /** 本边界只映射已发布版本与活动部署的必要字段，不复制Run或预算状态。 */
    public static final class ToolRow {
        public Long versionTenant;
        public String name, description, trustLevel, status, contentHash, descriptorJson, providerType, providerRef, environment;
        public long deploymentRevision;
    }

    /** 原精确知识revision和CapabilityVersion的必要字段，不保存新的执行快照。 */
    public static final class KnowledgeRow {
        /** 正式版本归属和descriptor元数据；来源版本为原revision的JSON清单。 */
        public String name, description, trustLevel, status, contentHash, descriptorJson, sourceId, sourceVersions;
        public Long versionTenant;
        /** 已发布知识revision号，不是当前active指针。 */
        public long revision;
    }

    /** 准入实际Skill版本元数据和完整包descriptor，正文随后只在不可变Catalog内保存一次。 */
    public static final class SkillRow {
        public Long versionTenant;
        public String name, description, trustLevel, status, contentHash, descriptorJson;
    }

    /** 仅准入读取原精确Workflow版本元数据，不携带当前发布指针。 */
    public static final class WorkflowRow {
        public Long versionTenant;
        public String name, description, trustLevel, status, contentHash, descriptorJson;
    }

    /** @param mount 原精确挂载配置 @param descriptor 通过正式domain codec核验的原包正文 */
    record SkillFact(CapabilityMount mount, TypedPayload descriptor) { }

    /** @param target 原精确知识目标 @param modelInvocable 模型调用开关 @param citationRequired 正式descriptor引用要求 */
    record KnowledgeTarget(FrozenKnowledgeTarget target, boolean modelInvocable, boolean citationRequired) { }

    /** SQL只读取当前事务已经绑定的租户，不包含set_config或独立连接。 */
    @org.apache.ibatis.annotations.Mapper
    public interface Mapper {
        String member(@Param("tenant") String tenant, @Param("run") String run, @Param("session") String session,
                @Param("kind") String kind, @Param("target") String target, @Param("user") Long user, @Param("agent") String agent);

        ToolRow tool(@Param("tenant") String tenant, @Param("capability") String capability,
                @Param("version") String version, @Param("deployment") String deployment);

        KnowledgeRow knowledge(@Param("tenant") String tenant, @Param("capability") String capability,
                @Param("version") String version, @Param("revision") String revision);

        KnowledgeRow frozenKnowledge(@Param("tenant") String tenant, @Param("capability") String capability,
                @Param("version") String version, @Param("revision") String revision);

        SkillRow skill(@Param("tenant") String tenant, @Param("capability") String capability, @Param("version") String version);

        WorkflowRow workflow(@Param("tenant") String tenant, @Param("capability") String capability, @Param("version") String version);

        WorkflowRow currentWorkflow(@Param("tenant") String tenant, @Param("capability") String capability, @Param("version") String version,
                @Param("instance") String instance, @Param("definition") long definition, @Param("installation") long installation);
    }

    /** 原成员JSON的封闭挂载配置，类型标签与AgentRunBindings的持久契约一致。 */
    @com.fasterxml.jackson.annotation.JsonTypeInfo(use=com.fasterxml.jackson.annotation.JsonTypeInfo.Id.NAME,property="type")
    @com.fasterxml.jackson.annotation.JsonSubTypes({
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=CapabilityMount.SkillMountConfig.class,name="SKILL"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=CapabilityMount.ToolMountConfig.class,name="TOOL"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=CapabilityMount.WorkflowMountConfig.class,name="WORKFLOW"),
        @com.fasterxml.jackson.annotation.JsonSubTypes.Type(value=CapabilityMount.KnowledgeMountConfig.class,name="KNOWLEDGE_SOURCE")})
    private abstract static class MountConfigJson { }
}
