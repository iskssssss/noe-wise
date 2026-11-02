package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.CapabilityMountSnapshot;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.tool.ToolCatalog;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.core.agent.integration.runtime.session.JdbcSessionCapabilities;
import com.skillforge.core.capability.integration.runtime.tool.JdbcMcpBindings;
import com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.session.spi.SessionCapabilities;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import com.skillforge.runtime.starter.AgentRuntimeAdmissionConfiguration;
import com.skillforge.institution.browser.BrowserToolReadPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 从当前 Agent 挂载与已发布 JDBC binding 装载 NewRun 能力候选。
 *
 * <p>空挂载得到空候选是合法结果；存在 Tool/Workflow/Knowledge 挂载时必须能发布或读到
 * 与 Catalog 一致的冻结目标，缺 binding 失败关闭，不回退成固定空列表。</p>
 */
@Service
public class AgentRuntimePublishedCapabilities implements SessionToolDirectory {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentRuntimePublishedCapabilities.class);

    /** 冻结 schema 只忽略对象键顺序；独立解析器保留数字精度并拒绝重复键和多余正文。 */
    private static final ObjectReader FROZEN_SCHEMA_READER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readerFor(JsonNode.class);

    private final JdbcSessionCapabilities capabilities;
    private final JdbcMcpBindings tools;
    private final JdbcWorkflowBindings workflows;
    private final AgentRuntimeWorkflowBindingPublisher workflowPublisher;
    private final JdbcKnowledgeBindings knowledge;
    private final CapabilityMountPersistenceService mounts;
    private final CapabilityMountResolver mountResolver;
    private final ToolCatalog toolCatalog;
    private final ObjectMapper json;
    private final PlatformToolCatalog platformTools;
    private final AgentRunBindings studioRuns;
    private final AgentRuntimeExecutionMounts executionMounts;
    private final AgentRuntimeExecutionMounts.Mapper executionMapper;

    public AgentRuntimePublishedCapabilities(
            JdbcSessionCapabilities capabilities,
            JdbcMcpBindings tools,
            JdbcWorkflowBindings workflows,
            JdbcKnowledgeBindings knowledge,
            CapabilityMountPersistenceService mounts,
            CapabilityMountResolver mountResolver,
            ToolCatalog toolCatalog,
            ObjectMapper json, AgentRunBindings studioRuns,AgentRuntimeWorkflowBindingPublisher workflowPublisher,
            AgentRuntimeExecutionMounts.Mapper executionMapper, PlatformToolCatalog platformTools) {
        this.capabilities = Objects.requireNonNull(capabilities);
        this.tools = Objects.requireNonNull(tools);
        this.workflows = Objects.requireNonNull(workflows);
        this.workflowPublisher=Objects.requireNonNull(workflowPublisher);
        this.knowledge = Objects.requireNonNull(knowledge);
        this.mounts = Objects.requireNonNull(mounts);
        this.mountResolver = Objects.requireNonNull(mountResolver);
        this.toolCatalog = Objects.requireNonNull(toolCatalog);
        this.json = Objects.requireNonNull(json);
        this.studioRuns = Objects.requireNonNull(studioRuns);
        this.executionMounts = new AgentRuntimeExecutionMounts(json);
        this.executionMapper = Objects.requireNonNull(executionMapper);
        this.platformTools = Objects.requireNonNull(platformTools);
    }

    /** 仅按原冻结绑定分类当前读取，不授予调用权限或重试未决操作。 */
    @Override
    public boolean currentRead(SessionToolDirectory.Entry frozen, TypedPayload arguments) {
        var target = frozen.target();
        var binding = frozen.binding();
        return (binding.providerType() == ToolBinding.ProviderType.PLATFORM
                && binding.providerRef().equals("get_current_time") && binding.toolName().equals("get_current_time")
                && target.providerRequirements().equals(Set.of("platform_handler:get_current_time"))
                && target.effect() == com.skillforge.runtime.api.ToolEffect.READ_ONLY && target.idempotent()
                && target.callIdentityPolicy() == com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy.ARGUMENTS
                && target.confirmation() == com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.NEVER
                && target.modelInvocable())
                || BrowserToolReadPolicy.currentRead(frozen, arguments);
    }

    /**
     * 发布当前挂载对应的 Runtime binding，并返回 freeze 必须使用的同一批候选。
     */
    public AgentRuntimeAdmissionConfiguration.CapabilityTargets resolve(SessionOwner owner) {
        return resolve(owner, owner.target().requireAgent());
    }

    public AgentRuntimeAdmissionConfiguration.CapabilityTargets resolve(SessionOwner owner, AgentId actor) {
        return resolve(owner,actor,null,null);
    }

    /** 候选 Workflow handle 使用实际准入输入，与后续 capability freeze 的输入身份保持一致。 */
    public AgentRuntimeAdmissionConfiguration.CapabilityTargets resolve(SessionOwner owner, AgentId actor,
            com.skillforge.runtime.api.RunRef run, com.skillforge.runtime.api.InputId admissionInput) {
        Objects.requireNonNull(owner, "owner");
        if ((run == null) != (admissionInput == null)) {
            throw new IllegalArgumentException("capability admission run and input must be supplied together");
        }
        if (run != null && (owner.target().kind()==SessionTarget.Kind.TEAM || studioRuns.find(run).isPresent())) {
            var binding = studioRuns.find(run)
                    .orElseThrow(() -> new IllegalStateException("studio_run_binding_missing"));
            if (!binding.owner().equals(owner) || !binding.actor().equals(actor))
                throw new IllegalStateException("studio_run_binding_mismatch");
            studioRuns.authorize(binding);
            // Provider 地址、认证与协议也在首次受理冻结；后续准备和执行只复用同 Input binding。
            listCurrent(owner, actor, new com.skillforge.runtime.api.InputId("input:" + run.runId()), binding.member().mounts());
        }
        JdbcSessionCapabilities.Catalog catalog = run == null ? capabilities.catalog(owner, actor)
                : capabilities.catalog(owner, actor, admissionInput);
        var tenant = owner.sessionRef().tenantId();
        List<FrozenWorkflowTarget> workflowTargets = new ArrayList<>();
        for (FrozenWorkflowTarget target : catalog.workflows()) {
            workflowPublisher.publish(tenant, target);
            if (workflows.find(tenant, target).isEmpty()) {
                throw new IllegalStateException("agent_runtime_workflow_binding_missing");
            }
            workflowTargets.add(target);
        }
        List<FrozenKnowledgeTarget> knowledgeTargets = new ArrayList<>();
        for (FrozenKnowledgeTarget target : catalog.knowledge()) {
            knowledge.publish(tenant, target);
            if (knowledge.find(tenant, target).isEmpty()) {
                throw new IllegalStateException("agent_runtime_knowledge_binding_missing");
            }
            knowledgeTargets.add(target);
        }
        return new AgentRuntimeAdmissionConfiguration.CapabilityTargets(catalog.generation(),
                List.copyOf(workflowTargets), List.copyOf(knowledgeTargets));
    }

    /** 无运行上下文的管理发现读取当前发布目录；执行入口使用 list(RunIdentity)。 */
    @Override
    public List<SessionToolDirectory.Entry> list(SessionOwner owner, AgentId actualAgentId) {
        return OutputControlTools.append(platformTools, listCurrent(owner, actualAgentId, null, null).stream()
                .filter(entry -> !Set.of("submit_team_plan", "submit_task_result")
                        .contains(entry.discovery().name()))
                .toList());
    }

    private List<SessionToolDirectory.Entry> listCurrent(SessionOwner owner, AgentId actualAgentId,
            com.skillforge.runtime.api.InputId input,
            com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult frozenMounts) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(actualAgentId, "actualAgentId");
        if (owner.target().kind() == SessionTarget.Kind.AGENT && !owner.target().requireAgent().equals(actualAgentId))
            throw new IllegalArgumentException("agent_runtime_capability_owner_invalid");
        long tenantId = positive(owner.sessionRef().tenantId().value());
        long agentId = positive(actualAgentId.value());
        var mountSet = frozenMounts == null ? mounts.read(tenantId, agentId) : frozenMounts;
        var toolMounts = mountSet.mounts().stream()
                .filter(mount -> mount.capability().type() == CapabilityType.TOOL)
                .toList();
        String directoryInput = input == null ? "tool-directory:" + mountSet.generation() : input.value();
        CapabilityMountSnapshot snapshot = frozenMounts == null ? mountResolver.freeze(
                CapabilityMountSnapshot.snapshotIdForInput(directoryInput), directoryInput, tenantId, agentId,
                mountSet.generation(), toolMounts) : mountResolver.restore(new CapabilityMountSnapshot(
                CapabilityMountSnapshot.snapshotIdForInput(directoryInput), directoryInput, tenantId, agentId,
                mountSet.generation(), toolMounts, java.time.Instant.now()));
        List<ToolCatalog.Entry> directory = toolCatalog.list(snapshot);
        if (directory.stream().anyMatch(entry -> !Set.of(
                com.skillforge.core.capability.tool.ToolDeployment.ProviderType.MCP,
                com.skillforge.core.capability.tool.ToolDeployment.ProviderType.PLATFORM)
                .contains(entry.target().deployment().providerType()))) {
            throw new IllegalStateException("agent_runtime_tool_provider_unsupported");
        }
        List<SessionToolDirectory.Entry> toolTargets = new ArrayList<>();
        for (ToolCatalog.Entry entry : directory) {
            if (snapshot.findActive(entry.handle()).filter(entry.target().mount()::equals).isEmpty())
                throw new IllegalStateException("agent_runtime_tool_catalog_target_missing");
            var projected = project(entry, input);
            if (projected.binding().providerType() == ToolBinding.ProviderType.MCP) {
                var binding = tools.find(owner.sessionRef().tenantId(), projected.target());
                if (binding.isEmpty()) {
                    tools.publish(owner.sessionRef().tenantId(), projected.target(),
                            new JdbcMcpBindings.Profile(projected.binding().toolName(), JdbcMcpBindings.PROTOCOL_VERSION));
                    binding = tools.find(owner.sessionRef().tenantId(), projected.target());
                }
                requireMcpBinding(projected, projected.target(), binding);
            }
            toolTargets.add(projected);
        }
        return List.copyOf(toolTargets);
    }

    /** 全部 Run 使用受理时的冻结 Tool；实时资源停用和 IAM 撤权仍在执行前校验。 */
    @Override
    public List<SessionToolDirectory.Entry> list(RunIdentity run) {
        return list(run, requireRunBinding(run));
    }

    /** 新执行frame已读取原绑定；本次准备复用该材料，不重新查询归属或组装来源。 */
    List<SessionToolDirectory.Entry> list(RunIdentity run, AgentRunBindings.Binding binding) {
        requireLoadedBinding(run, binding);
        try {
            return OutputControlTools.append(platformTools, listCurrent(run.owner(), run.agentId(),
                    new com.skillforge.runtime.api.InputId("input:" + run.runRef().runId()), binding.member().mounts()).stream()
                    .filter(entry -> !Set.of("submit_team_plan", "submit_task_result").contains(entry.discovery().name()))
                    .toList());
        } catch (CapabilityMountResolver.InactiveToolDeploymentException inactive) {
            // Run 的原挂载不能改绑或过滤；已确认撤权交给现有 FAILED 提交/Inbox 收口链。
            var revoked = new com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException(
                    "tool_deployment_revoked");
            revoked.initCause(inactive);
            throw revoked;
        }
    }

    /**
     * 从本 Run 的不可变挂载批量投影稳定身份；同名工具用 mountId 区分。
     * 执行 handle、版本与部署仍与原条目精确绑定，未知目标或重复挂载失败关闭。
     */
    @Override
    public Map<String, String> modelIdentities(RunIdentity run, List<SessionToolDirectory.Entry> entries) {
        return modelIdentities(run, entries, requireRunBinding(run));
    }

    /** 已加载目录与挂载在本次frame内构造稳定模型身份，不再次读取Run绑定。 */
    Map<String,String> modelIdentities(RunIdentity run, List<SessionToolDirectory.Entry> entries, AgentRunBindings.Binding binding) {
        Objects.requireNonNull(run);
        Objects.requireNonNull(entries);
        requireLoadedBinding(run, binding);
        LOG.debug("冻结工具模型身份进入 toolCount={}", entries.size());
        try {
            var frozen = binding.member().mounts();
            long tenantId = positive(run.owner().sessionRef().tenantId().value());
            long agentId = positive(run.agentId().value());
            if (!frozen.tenantId().equals(tenantId) || !frozen.agentId().equals(agentId))
                throw new IllegalStateException("studio_run_binding_mismatch");
            var input = new com.skillforge.runtime.api.InputId("input:" + run.runRef().runId());
            var mounted = new LinkedHashMap<String, CapabilityMount>();
            for (var mount : frozen.mounts()) {
                if (!mount.active() || mount.capability().type() != CapabilityType.TOOL) continue;
                if (!mount.tenantId().equals(tenantId) || !mount.agentId().equals(agentId)
                        || mounted.putIfAbsent(JdbcSessionCapabilities.toolHandle(mount.mountId(), input).value(), mount) != null)
                    throw new IllegalStateException("agent_runtime_tool_model_identity_rejected");
            }
            // 固定宿主控制能力不来自业务挂载；仅接受宿主自身声明的完整条目。
            var controls = new LinkedHashMap<String, SessionToolDirectory.Entry>();
            for (var control : OutputControlTools.append(platformTools, List.of()))
                controls.put(control.target().handle().value(), control);
            var identities = new LinkedHashMap<String, String>();
            for (var entry : entries) {
                String handle = entry.target().handle().value();
                var mount = mounted.get(handle);
                final String identity;
                if (mount != null) {
                    var config = (CapabilityMount.ToolMountConfig) mount.config();
                    var target = entry.target();
                    if (!mount.capability().capabilityId().equals(target.capability().capabilityId())
                            || !mount.capability().versionId().equals(target.capability().versionId())
                            || !config.toolDeploymentId().equals(target.deploymentId())
                            || config.toolDeploymentRevision() != target.deploymentRevision())
                        throw new IllegalStateException("agent_runtime_tool_model_identity_rejected");
                    identity = JdbcSessionCapabilities.toolHandle(mount.mountId(), null).value();
                } else {
                    var control = controls.get(handle);
                    if (control == null || !sameFrozenTarget(control.target(), entry.target())
                            || !control.binding().equals(entry.binding())
                            || !control.discovery().equals(entry.discovery())
                            || control.parallelSafe() != entry.parallelSafe())
                        throw new IllegalStateException("agent_runtime_tool_model_identity_rejected");
                    identity = handle;
                }
                if (identities.putIfAbsent(handle, identity) != null)
                    throw new IllegalStateException("agent_runtime_tool_model_identity_rejected");
            }
            return SessionToolDirectory.requireModelIdentities(entries, identities);
        } finally {
            LOG.debug("冻结工具模型身份退出 toolCount={}", entries.size());
        }
    }

    /** 校验执行 Agent 和会话归属并保留执行前 IAM 校验，不用模型身份代替授权。 */
    private AgentRunBindings.Binding requireRunBinding(RunIdentity run) {
        Objects.requireNonNull(run);
        LOG.debug("读取工具冻结运行绑定进入");
        try {
            var binding = studioRuns.find(run.runRef())
                    .orElseThrow(() -> new IllegalStateException("studio_run_binding_missing"));
            if (!binding.actor().equals(run.agentId()) || !binding.owner().equals(run.owner()))
                throw new IllegalStateException("studio_run_binding_mismatch");
            studioRuns.authorize(binding);
            return binding;
        } finally {
            LOG.debug("读取工具冻结运行绑定退出");
        }
    }

    /** 绑定由同一frame从精确Run读取，消费前核对实际Agent与会话归属。 */
    private static void requireLoadedBinding(RunIdentity run, AgentRunBindings.Binding binding) {
        if (!binding.actor().equals(run.agentId()) || !binding.owner().equals(run.owner()))
            throw new IllegalStateException("studio_run_binding_mismatch");
    }

    /** 新执行Store创建SqlSessionFactory时注册宿主精确挂载映射。 */
    public static void configureExecution(org.apache.ibatis.session.Configuration configuration) {
        AgentRuntimeExecutionMounts.configure(configuration);
    }

    /** 首次材料加载直接消费原已冻结PINNED成员，知识版本不依赖当前active指针或旧Session快照。 */
    List<AgentRuntimeExecutionMounts.KnowledgeTarget> knowledge(RunIdentity run, AgentRunBindings.Binding binding) {
        requireLoadedBinding(run, binding);
        return executionMounts.knowledge(executionMapper, run, binding.member());
    }

    /** 准入解析原FrozenMember的真实Skill包；恢复由原Catalog承担，不再次查当前版本。 */
    List<AgentRuntimeExecutionMounts.SkillFact> skills(RunIdentity run, AgentRunBindings.Binding binding) {
        requireLoadedBinding(run, binding);
        return executionMounts.skills(executionMapper, run, binding.member());
    }

    /** 原精确成员只在准入解析Workflow目标并发布不可变安装闭包；恢复不调用本入口。 */
    List<FrozenWorkflowTarget> workflows(RunIdentity run, AgentRunBindings.Binding binding) {return workflows(run,binding,false);}
    List<FrozenWorkflowTarget> authorizedWorkflows(RunIdentity run,AgentRunBindings.Binding binding) {return workflows(run,binding,true);}
    private List<FrozenWorkflowTarget> workflows(RunIdentity run,AgentRunBindings.Binding binding,boolean authorizedInput) {
        requireLoadedBinding(run, binding);
        var targets = authorizedInput?executionMounts.authorizedWorkflows(executionMapper,run,binding.member()):executionMounts.workflows(executionMapper, run, binding.member());
        for (var target : targets) {
            workflowPublisher.publish(run.runRef().tenantId(), target);
            if (workflows.find(run.runRef().tenantId(), target).isEmpty()) throw new IllegalStateException("execution_workflow_binding_missing");
        }
        return targets;
    }

    /** schema只取准入已授权的同一精确正式版本，不采用输入携带的声明。 */
    TypedPayload workflowInputSchema(RunIdentity run,FrozenWorkflowTarget target) {
        var row=executionMapper.workflow(run.runRef().tenantId().value(),target.capability().capabilityId(),target.capability().versionId());
        if(row==null)throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                "workflow_invocation_not_authorized","正式Workflow版本已不可用",false);
        try {
            var schema=json.readTree(row.descriptorJson).get("input_schema");
            if(schema==null||!schema.isObject())throw new IllegalStateException("execution_workflow_input_schema_missing");
            return new TypedPayload("json-schema",json.writeValueAsString(schema));
        } catch(java.io.IOException malformed) {throw new IllegalStateException("execution_workflow_descriptor_invalid",malformed);}
    }

    /** 当前派发验证原精确Workflow实例/定义/安装，无新连接或当前版本重选。 */
    void requireCurrentWorkflow(org.apache.ibatis.session.SqlSession session, RunIdentity run,
            FrozenWorkflowTarget target, AgentRunBindings.Binding binding) {
        requireLoadedBinding(run, binding);
        executionMounts.current(session, run, target, binding.member());
    }

    void requireCurrentAuthorizedWorkflow(org.apache.ibatis.session.SqlSession session,RunIdentity run,FrozenWorkflowTarget target,AgentRunBindings.Binding binding) {
        requireLoadedBinding(run,binding);executionMounts.currentAuthorized(session,run,target,binding.member());
    }

    /** Knowledge新派发复用调用方SqlSession当前发布状态检查，不打开第二连接或重建来源图。 */
    void requireCurrentKnowledge(org.apache.ibatis.session.SqlSession session, RunIdentity run,
            FrozenKnowledgeTarget target, AgentRunBindings.Binding binding) {
        requireLoadedBinding(run, binding);
        if (!executionMounts.current(session, run, target, binding.member()).modelInvocable()) throw unauthorized();
    }

    /** 派发事务内读取原冻结成员与当前发布/部署，不打开另一连接或查询旧Session journal。 */
    public SessionToolDirectory.Entry requireCurrent(org.apache.ibatis.session.SqlSession session, RunIdentity run, FrozenToolTarget target) {
        return requireCurrent(session, run, target, executionMounts.member(session, run));
    }

    /** 已加载frame直接复用原成员；同事务只查询当前精确版本、活动部署和策略。 */
    SessionToolDirectory.Entry requireCurrent(org.apache.ibatis.session.SqlSession session, RunIdentity run,
            FrozenToolTarget target, AgentRunBindings.Binding binding) {
        requireLoadedBinding(run, binding);
        return requireCurrent(session, run, target, binding.member());
    }

    /** 控制能力使用宿主完整声明，普通工具继续复用唯一Catalog投影与目标全值检查。 */
    private SessionToolDirectory.Entry requireCurrent(org.apache.ibatis.session.SqlSession session, RunIdentity run,
            FrozenToolTarget target, com.skillforge.institution.team.TeamService.FrozenMember member) {
        for (var control : OutputControlTools.append(platformTools, List.of())) {
            if (control.target().handle().equals(target.handle())) {
                if (!sameFrozenTarget(control.target(), target)) throw unauthorized();
                return new SessionToolDirectory.Entry(target, control.binding(), control.discovery(), control.parallelSafe());
            }
        }
        var current = project(executionMounts.current(session, run, target, member), new com.skillforge.runtime.api.InputId("input:" + run.runRef().runId()));
        if (Set.of("submit_team_plan", "submit_task_result").contains(current.discovery().name())
                || OutputControlTools.control(current.discovery().name()) || !sameFrozenTarget(current.target(), target)) throw unauthorized();
        return new SessionToolDirectory.Entry(target, current.binding(), current.discovery(), current.parallelSafe());
    }

    /** 重新授权原冻结目标，只允许 schema 对象键顺序差异，执行仍使用原冻结字节与策略。 */
    @Override
    public SessionToolDirectory.Entry requireCurrent(RunIdentity run, FrozenToolTarget target) {
        Objects.requireNonNull(target);
        var frozen = requireRunBinding(run).member().mounts();
        long tenant = positive(run.owner().sessionRef().tenantId().value());
        long agent = positive(run.agentId().value());
        if (!frozen.tenantId().equals(tenant) || !frozen.agentId().equals(agent))
            throw new IllegalStateException("studio_run_binding_mismatch");
        for (var control : OutputControlTools.append(platformTools, List.of())) {
            if (control.target().handle().equals(target.handle())) {
                if (!sameFrozenTarget(control.target(), target)) throw unauthorized();
                return new SessionToolDirectory.Entry(target, control.binding(), control.discovery(), control.parallelSafe());
            }
        }
        var input = new com.skillforge.runtime.api.InputId("input:" + run.runRef().runId());
        var selected = frozen.mounts().stream()
                .filter(mount -> mount.active() && mount.capability().type() == CapabilityType.TOOL
                        && JdbcSessionCapabilities.toolHandle(mount.mountId(), input).equals(target.handle())).toList();
        if (selected.size() != 1) throw unauthorized();
        var mount = selected.getFirst();
        final CapabilityMountSnapshot snapshot;
        try {
            snapshot = mountResolver.restore(new CapabilityMountSnapshot(
                    CapabilityMountSnapshot.snapshotIdForInput(input.value()), input.value(), tenant, agent,
                    frozen.generation(), List.of(mount), java.time.Instant.now()));
        } catch (CapabilityMountResolver.InactiveToolDeploymentException inactive) {
            var revoked = new com.skillforge.runtime.session.spi.SessionExecutionAuthorizationRevokedException("tool_deployment_revoked");
            revoked.initCause(inactive);
            throw revoked;
        }
        var entry = toolCatalog.resolve(snapshot, mount.handle()).orElseThrow(AgentRuntimePublishedCapabilities::unauthorized);
        if (!entry.target().mount().equals(mount)) throw unauthorized();
        var current = project(entry, input);
        if (Set.of("submit_team_plan", "submit_task_result").contains(current.discovery().name())
                || OutputControlTools.control(current.discovery().name()) || !sameFrozenTarget(current.target(), target))
            throw unauthorized();
        if (current.binding().providerType() == ToolBinding.ProviderType.MCP)
            requireMcpBinding(current, target, tools.find(run.runRef().tenantId(), target));
        return new SessionToolDirectory.Entry(target, current.binding(), current.discovery(), current.parallelSafe());
    }

    /** 目录发现与精确执行共用唯一 ToolCatalog 投影，不重查或重新解析另一个工具目录。 */
    private SessionToolDirectory.Entry project(ToolCatalog.Entry entry, com.skillforge.runtime.api.InputId input) {
        var resolved = entry.target();
        var definition = resolved.version().definition();
        var policy = resolved.version().policy();
        var deployment = resolved.deployment();
        if (deployment.providerType() != com.skillforge.core.capability.tool.ToolDeployment.ProviderType.MCP
                && deployment.providerType() != com.skillforge.core.capability.tool.ToolDeployment.ProviderType.PLATFORM)
            throw new IllegalStateException("agent_runtime_tool_provider_unsupported");
        var binding = new ToolBinding(ToolBinding.ProviderType.valueOf(deployment.providerType().name()),
                deployment.providerRef(), definition.name());
        if (!entry.serverIdentity().equals(binding.serverIdentity())
                || binding.providerType() == ToolBinding.ProviderType.PLATFORM && !binding.providerRef().equals(definition.name()))
            throw new IllegalStateException("agent_runtime_tool_catalog_mismatch");
        var ref = resolved.mount().capability();
        var target = new FrozenToolTarget(JdbcSessionCapabilities.toolHandle(resolved.mount().mountId(), input),
                new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.TOOL,
                        ref.capabilityId(), ref.versionId()), deployment.deploymentId(), deployment.revision(),
                schema(definition.inputSchema()), schema(definition.outputSchema().orElse(Map.of())),
                com.skillforge.runtime.api.ToolEffect.valueOf(policy.effect().name()), policy.idempotent(),
                java.time.Duration.ofMillis(policy.timeoutMs()), resolved.version().metadata().invocationPolicy().modelInvocable(),
                com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.valueOf(policy.confirmation().name()),
                policy.sensitiveFields(), policy.providerRequirements(), definition.outputSchema().isPresent(), policy.callIdentityPolicy());
        var title = definition.title();
        if (title.isEmpty() && binding.providerType() == ToolBinding.ProviderType.PLATFORM)
            title = platformTools.entries().stream().filter(tool -> tool.name().equals(binding.toolName()))
                    .map(PlatformToolCatalog.Entry::title).findFirst();
        return new SessionToolDirectory.Entry(target, binding, new SessionToolDirectory.Discovery(entry.serverIdentity(),
                entry.qualifiedName(), definition.name(), title, definition.description()), policy.parallelSafe());
    }

    private TypedPayload schema(Map<String,Object> value) {
        try {
            return new TypedPayload("json-schema", json.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("agent_runtime_tool_schema_projection_failed", failure);
        }
    }

    /** 执行只接受原冻结路由；同 schema 含义不能替换已经冻结的 endpoint、认证与协议。 */
    private static void requireMcpBinding(SessionToolDirectory.Entry current, FrozenToolTarget original,
            java.util.Optional<JdbcMcpBindings.Binding> binding) {
        var actual = binding.orElseThrow(() -> new IllegalStateException("agent_runtime_tool_binding_missing"));
        if (!actual.target().equals(original) || !actual.providerRef().equals(current.binding().providerRef())
                || !actual.toolName().equals(current.binding().toolName()))
            throw new IllegalStateException("agent_runtime_tool_binding_mismatch");
    }

    private static IllegalArgumentException unauthorized() {
        return new IllegalArgumentException("studio_tool_no_longer_authorized");
    }

    /** 全部执行字段精确匹配；JSON 对象键顺序没有业务含义，数组顺序和各值仍必须一致。 */
    private static boolean sameFrozenTarget(FrozenToolTarget current, FrozenToolTarget frozen) {
        if (current == null || frozen == null) return false;
        return current.handle().equals(frozen.handle())
                && current.capability().equals(frozen.capability())
                && current.deploymentId().equals(frozen.deploymentId())
                && current.deploymentRevision() == frozen.deploymentRevision()
                && current.effect() == frozen.effect()
                && current.idempotent() == frozen.idempotent()
                && current.timeout().equals(frozen.timeout())
                && current.modelInvocable() == frozen.modelInvocable()
                && current.confirmation() == frozen.confirmation()
                && current.sensitiveFields().equals(frozen.sensitiveFields())
                && current.providerRequirements().equals(frozen.providerRequirements())
                && current.outputSchemaDeclared() == frozen.outputSchemaDeclared()
                && current.callIdentityPolicy() == frozen.callIdentityPolicy()
                && sameFrozenSchema(current.inputSchema(), frozen.inputSchema())
                && sameFrozenSchema(current.outputSchema(), frozen.outputSchema());
    }

    /** 相同 schemaId 下比较完整 JSON 树，不接受重复键、截断正文或尾随额外 JSON。 */
    private static boolean sameFrozenSchema(TypedPayload current, TypedPayload frozen) {
        if (!current.schemaId().equals(frozen.schemaId())) return false;
        try {
            JsonNode currentSchema = FROZEN_SCHEMA_READER.readValue(current.canonicalJson());
            JsonNode frozenSchema = FROZEN_SCHEMA_READER.readValue(frozen.canonicalJson());
            return currentSchema != null && currentSchema.equals(frozenSchema);
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    private static long positive(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0 || !Long.toString(parsed).equals(value)) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("agent_runtime_capability_owner_invalid");
        }
    }
}
