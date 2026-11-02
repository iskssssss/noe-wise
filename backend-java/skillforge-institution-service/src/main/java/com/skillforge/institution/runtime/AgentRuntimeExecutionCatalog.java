package com.skillforge.institution.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.runtime.api.KnowledgeBudget;
import com.skillforge.runtime.api.KnowledgeCapacity;
import com.skillforge.runtime.api.KnowledgeMode;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.PlanPort;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.session.AgentExecutionProgram.KnowledgeBinding;
import com.skillforge.runtime.session.AgentExecutionProgram.WorkflowBinding;
import com.skillforge.runtime.session.SessionCollaboration;
import com.skillforge.runtime.session.spi.ExecutionStore.Material;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.SkillActivation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 受理时的一份不可变能力配置材料；保留真实精确目标与模型目录，恢复不读取当前部署来补原路由。
 * 只在本存储边界按明确record编码，不存Run进度、回执、Provider许可或递归checkpoint。
 */
public final class AgentRuntimeExecutionCatalog {
    public static final String SCHEMA = "skillforge.runtime.execution.capabilities.v3";
    private final AgentRuntimePublishedCapabilities capabilities;
    private final JdbcKnowledgeBindings knowledge;
    private final ObjectMapper json;
    private final AgentRuntimePayloadValidation payloads;
    private final CodecLimits limits;
    private final ObjectProvider<InstitutionPlanHost> plans;
    private final SessionCollaboration collaboration;
    private final WorkflowTestRuntimePolicy tests;

    /** 原目录解析仅在准入调用；decode只使用不可变材料与精确schema，不执行当前IAM。 */
    public AgentRuntimeExecutionCatalog(AgentRuntimePublishedCapabilities capabilities, JdbcKnowledgeBindings knowledge,
            ObjectMapper json, AgentRuntimePayloadValidation payloads, AgentRuntimeProperties properties,
            ObjectProvider<InstitutionPlanHost> plans, SessionLogPort log,WorkflowTestRuntimePolicy tests) {
        this.tests=Objects.requireNonNull(tests);
        this.capabilities = Objects.requireNonNull(capabilities); this.knowledge = Objects.requireNonNull(knowledge);
        this.payloads = Objects.requireNonNull(payloads);
        this.plans = Objects.requireNonNull(plans); collaboration = new SessionCollaboration(log);
        Objects.requireNonNull(properties);
        limits = new CodecLimits(properties.getMaxPayloadCodeUnits(), properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth());
        this.json = com.skillforge.institution.runtime.SessionSubjectJson.configure(Objects.requireNonNull(json).copy()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.json.setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    /** 准入解析一次实际目录与原业务输入并发布精确绑定；返回材料由Store.admit与Run/input同事务保存。 */
    public Material freeze(RunIdentity identity, AgentRunBindings.Binding binding, Instant acceptedAt, TypedPayload inbound) {
        Objects.requireNonNull(identity); Objects.requireNonNull(binding); Objects.requireNonNull(acceptedAt); Objects.requireNonNull(inbound);
        if(tests.session(identity.owner().sessionRef()).isPresent()) {
            var targets=capabilities.authorizedWorkflows(identity,binding);var intent=tests.initial(identity,targets);
            var workflows=targets.stream().map(target->new WorkflowBinding(target,tests.parameters(identity),List.of())).toList();
            return encode(new FrozenCatalog(identity,List.of(),Map.of(),List.of(),List.of(),
                    targets.stream().map(target->target.capability()).collect(java.util.stream.Collectors.toUnmodifiableSet()),workflows,
                    PlanPort.State.absent(),SessionCollaboration.Selection.inactive(),intent));
        }
        var tools = capabilities.list(identity, binding);
        var identities = capabilities.modelIdentities(identity, tools, binding);
        var policies = new ArrayList<KnowledgeBinding>();
        for (var selected : capabilities.knowledge(identity, binding)) {
            if (!selected.modelInvocable()) continue;
            var target = selected.target();
            var frozen = knowledge.find(identity.runRef().tenantId(), target)
                    .orElseGet(() -> knowledge.publish(identity.runRef().tenantId(), target));
            if (!frozen.target().equals(target)) throw new IllegalStateException("execution_knowledge_binding_mismatch");
            var budget = new KnowledgeBudget(KnowledgeCapacity.DEFAULT_MAX_QUERIES, KnowledgeCapacity.DEFAULT_MAX_ITEMS,
                    KnowledgeCapacity.DEFAULT_MAX_TOKENS, KnowledgeCapacity.DEFAULT_MAX_CHARACTERS, target.timeout().toMillis());
            policies.add(new KnowledgeBinding(target, KnowledgeMode.GROUNDING, "agent_knowledge_query", budget,
                    acceptedAt, Optional.empty(), selected.citationRequired()));
        }
        var skills = capabilities.skills(identity, binding).stream().map(fact -> {
            var mount = fact.mount(); var selected = (com.skillforge.core.capability.CapabilityMount.SkillMountConfig) mount.config();
            return new SkillActivation.Mount("skill:" + mount.mountId(), apiReference(mount.capability()),
                    SkillActivation.Mode.valueOf(selected.activationMode().name()), selected.tokenBudget(), SkillActivation.decode(fact.descriptor(), limits));
        }).toList();
        var available = binding.member().mounts().mounts().stream().filter(com.skillforge.core.capability.CapabilityMount::active)
                .map(mount -> apiReference(mount.capability())).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var targets = capabilities.workflows(identity, binding);
        tests.ordinaryTargets(identity,targets);
        var workflows = new ArrayList<WorkflowBinding>();
        if (!targets.isEmpty()) {
            var parameters = workflowParameters(inbound);
            // input.v1只携带已选输入材料；没有已提交Observation的EvidenceRef，不将artifact引用伪造成证据。
            for (var target : targets) workflows.add(new WorkflowBinding(target, parameters, List.of()));
        }
        var initialPlanState = plans.getObject().loadedState(identity, binding);
        var selection = identity.owner().target().kind() == com.skillforge.runtime.api.SessionTarget.Kind.GROUP
                ? SessionCollaboration.Selection.inactive() : collaboration.selected(identity.owner());
        return encode(new FrozenCatalog(identity, tools, identities, policies, skills, available, workflows, initialPlanState, selection,Optional.empty()));
    }

    /** 公开的明确调用只冻结一个当前user_invocable正式挂载，不让模型选择或替换目标。 */
    public Material freezeWorkflow(RunIdentity identity, AgentRunBindings.Binding binding,
            AgentRuntimeExecutionGateway.WorkflowInvocation invocation) {
        if(tests.session(identity.owner().sessionRef()).isPresent()) throw new SecurityException("workflow_invocation_test_session");
        validateJson(invocation.parameters().canonicalJson());
        var selected=capabilities.authorizedWorkflows(identity,binding).stream()
                .filter(target->target.handle().value().equals("workflow:"+invocation.mountId()+":"+identity.causationInputId().value())
                        &&target.capability().capabilityId().equals(invocation.capabilityId())
                        &&target.capability().versionId().equals(invocation.versionId())).toList();
        if(selected.size()!=1)throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                "workflow_invocation_not_authorized","当前会话没有唯一获授权的正式Workflow版本",false);
        var target=selected.getFirst();
        var schema=capabilities.workflowInputSchema(identity,target);
        try {com.skillforge.runtime.session.SessionRuntimePayloads.validateAgainstSchema(schema,invocation.parameters(),limits);}
        catch(IllegalArgumentException rejected) {
            throw new com.skillforge.core.shared.exception.ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "workflow_invocation_input_invalid","业务参数不符合正式Workflow input_schema",false);
        }
        return encode(new FrozenCatalog(identity,List.of(),Map.of(),List.of(),List.of(),Set.of(target.capability()),
                List.of(new WorkflowBinding(target,invocation.parameters(),List.of())),PlanPort.State.absent(),SessionCollaboration.Selection.inactive(),
                Optional.of(new com.skillforge.runtime.session.AgentExecutionProgram.AuthorizedWorkflowInvocation(
                        "open-platform-workflow:"+identity.causationInputId().value(),target))));
    }

    Optional<com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry.Snapshot> testSession(com.skillforge.runtime.api.SessionRef session) {return tests.session(session);}

    /** 只读原精确配置引用；不查询当前挂载、IAM、部署或active知识revision。 */
    public FrozenCatalog read(Material material, RunIdentity identity) {
        Objects.requireNonNull(material); Objects.requireNonNull(identity);
        if (!reference(identity.runRef()).equals(material.reference()) || !SCHEMA.equals(material.content().schemaId())) throw invalid();
        validateJson(material.content().canonicalJson());
        try {
            var frozen = json.readValue(material.content().canonicalJson(), FrozenCatalog.class);
            if (!frozen.identity().equals(identity)) throw invalid();
            return frozen;
        } catch (java.io.IOException malformed) { throw new IllegalArgumentException("execution_catalog_invalid", malformed); }
    }

    /** 配置材料精确引用由Run身份决定，不与输入、profile或公开事件引用混用。 */
    public static ResultRef reference(RunRef run) { return new ResultRef("execution-capabilities:" + Objects.requireNonNull(run).runId()); }

    /** 只编码明确record；容量先由共享纯JSON校验器限制，超限不产生部分配置。 */
    private Material encode(FrozenCatalog catalog) {
        try {
            String value = json.writeValueAsString(catalog); validateJson(value);
            return new Material(reference(catalog.identity().runRef()), new TypedPayload(SCHEMA, value));
        } catch (java.io.IOException malformed) { throw new IllegalArgumentException("execution_catalog_invalid", malformed); }
    }

    /** 使用Runtime已有结构配额；具体catalog schema始终由record与read的精确封套校验拥有。 */
    private void validateJson(String value) {
        if (!payloads.accepts(new TypedPayload("application/json", value))) throw invalid();
    }

    /** 只映射同一正式能力身份，不从名称或包声明补出新挂载。 */
    private static CapabilityRef apiReference(com.skillforge.core.capability.CapabilityRef reference) {
        return new CapabilityRef(com.skillforge.runtime.api.CapabilityType.valueOf(reference.type().name()), reference.capabilityId(), reference.versionId());
    }

    /** Workflow消费本次原受理文本与已冻结业务上下文，不能把模型参数变成入口或宿主身份。 */
    private TypedPayload workflowParameters(TypedPayload inbound) {
        if (!"input.v1".equals(inbound.schemaId())) throw invalid();
        validateJson(inbound.canonicalJson());
        try {
            var source = json.readTree(inbound.canonicalJson());
            if (!source.isObject() || !source.path("content").isArray() || !source.path("extraParameters").isObject()) throw invalid();
            var texts = new ArrayList<String>();
            for (var content : source.path("content")) {
                if (!content.isObject() || !content.path("type").isTextual()) throw invalid();
                switch (content.path("type").textValue()) {
                    case "text" -> { if (!content.path("text").isTextual()) throw invalid(); texts.add(content.path("text").textValue()); }
                    case "image" -> { /* 图片保持原输入媒体引用，不伪造图片文本。 */ }
                    default -> throw invalid();
                }
            }
            var result = json.createObjectNode(); result.put("user_input", String.join("\n", texts));
            result.set("extraParameters", source.path("extraParameters"));
            String value = json.writeValueAsString(result); validateJson(value);
            return new TypedPayload("application/json", value);
        } catch (java.io.IOException malformed) { throw new IllegalArgumentException("execution_workflow_input_invalid", malformed); }
    }

    /** @param identity 原Run归属与因果身份 @param tools 原真实工具目标/路由/发现定义 @param identities 原精确handle到稳定模型身份 @param knowledge 原已PINNED知识目标与受理策略 @param skills 原真实Skill包、激活模式及挂载预算 @param availableCapabilities 全部原active挂载能力，依赖满足不授予调用权 @param workflows 原精确Workflow目标及受理输入形成的参数和证据 */
    public record FrozenCatalog(RunIdentity identity, List<SessionToolDirectory.Entry> tools,
            Map<String, String> identities, List<KnowledgeBinding> knowledge, List<SkillActivation.Mount> skills, Set<CapabilityRef> availableCapabilities,
            List<WorkflowBinding> workflows, PlanPort.State initialPlanState, SessionCollaboration.Selection planSelection,
            Optional<com.skillforge.runtime.session.AgentExecutionProgram.AuthorizedWorkflowInvocation> initialIntent) {
        /** 固定不可变集合并拒绝目录漂移，不因配置存在授予当前派发权。 */
        public FrozenCatalog {
            Objects.requireNonNull(identity); tools = List.copyOf(tools); knowledge = List.copyOf(knowledge);
            skills = List.copyOf(skills); availableCapabilities = Set.copyOf(availableCapabilities);
            workflows = List.copyOf(workflows);
            Objects.requireNonNull(initialPlanState);Objects.requireNonNull(initialIntent);Objects.requireNonNull(planSelection);
            identities = SessionToolDirectory.requireModelIdentities(tools, identities);
            if (knowledge.stream().map(policy -> policy.target().handle()).distinct().count() != knowledge.size()) throw invalid();
            if (skills.stream().map(SkillActivation.Mount::handle).distinct().count() != skills.size()
                    || !availableCapabilities.containsAll(skills.stream().map(SkillActivation.Mount::capability).toList())) throw invalid();
            if (workflows.stream().map(value -> value.target().handle()).distinct().count() != workflows.size()
                    || !availableCapabilities.containsAll(workflows.stream().map(value -> value.target().capability()).toList())
                    || workflows.stream().flatMap(value -> value.evidenceRefs().stream())
                            .anyMatch(reference -> !reference.sessionRef().equals(identity.owner().sessionRef()))) throw invalid();
        }
        public boolean planRequired() { return planSelection.active(); }
        @Override public String toString() { return "ExecutionFrozenCatalog[REDACTED]"; }
    }

    /** 拒绝精确材料不完整，不打印配置正文。 */
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("execution_catalog_invalid"); }
}
