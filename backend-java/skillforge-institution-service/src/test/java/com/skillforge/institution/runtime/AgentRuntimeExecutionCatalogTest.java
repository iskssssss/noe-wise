package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.core.knowledge.integration.runtime.knowledge.JdbcKnowledgeBindings;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.api.port.PlanPort;
import com.skillforge.core.agent.capabilitymount.CapabilityMountSetResult;
import com.skillforge.institution.team.TeamService;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.skillforge.core.capability.CapabilityMount;
import com.skillforge.core.capability.SkillActivationMode;
import com.skillforge.core.capability.SkillPackageDescriptorMapper;
import com.skillforge.core.capability.SkillPackageValidator;
import com.skillforge.runtime.session.spi.ExecutionStore.Material;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 不可变能力配置保存完整typed目录，恢复不依赖当前停用或路由状态。 */
class AgentRuntimeExecutionCatalogTest {
    @Test void freezesExactPlanSelectionAndDoesNotReadNewChoiceOnRestore() {
        var fixture = new Fixture();
        var owner = fixture.identity.owner();
        var selected = new com.skillforge.runtime.api.port.CommittedSessionEvent(owner, new InputId("plan-on"), Optional.empty(),
                Optional.empty(),Optional.empty(),Optional.empty(),new com.skillforge.runtime.api.EventId("plan-on"), 7,
                com.skillforge.runtime.session.SessionCollaboration.SELECT,"1",fixture.identity.trace(),
                new TypedPayload("skillforge.runtime.plan-selection.v1","{\"active\":true}"),Instant.EPOCH);
        when(fixture.log.readAfter(any(),org.mockito.ArgumentMatchers.<java.util.Set<String>>any())).thenReturn(List.of(selected));
        var frozen = fixture.freeze();
        when(fixture.log.readAfter(any(),org.mockito.ArgumentMatchers.<java.util.Set<String>>any())).thenReturn(List.of());
        var restored = fixture.subject.read(frozen,fixture.identity);
        assertThat(restored.planRequired()).isTrue();
        assertThat(restored.planSelection()).isEqualTo(new com.skillforge.runtime.session.SessionCollaboration.Selection(true,"plan-on",7));
        var event = com.skillforge.runtime.session.SessionCollaboration.admissionMode(fixture.identity,restored.planSelection());
        assertThat(event.type()).isEqualTo(com.skillforge.runtime.session.SessionCollaboration.MODE);
        assertThat(event.data().canonicalJson()).contains("\"sourceSequence\":\"7\"","\"active\":true");
        assertThat(fixture.subject.read(fixture.freeze(),fixture.identity).planRequired()).isFalse();
    }

    @Test void publicInvocationFreezesOnlyTheExactAuthorizedWorkflowWithoutModelSelection() {
        var f=new Fixture();
        var target=new com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget(
                new com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle("workflow:13:catalog-input"),"93",94,
                new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.WORKFLOW,"91","92"),Duration.ofSeconds(37),95);
        when(f.capabilities.authorizedWorkflows(f.identity,f.binding)).thenReturn(List.of(target));
        when(f.capabilities.workflowInputSchema(f.identity,target)).thenReturn(new TypedPayload("json-schema","{}"));
        var parameters=new TypedPayload("application/json","{\"accepted\":false,\"count\":0}");
        var material=f.subject.freezeWorkflow(f.identity,f.binding,new AgentRuntimeExecutionGateway.WorkflowInvocation("13","91","92",parameters));
        clearInvocations(f.capabilities);
        var restored=f.subject.read(material,f.identity);
        assertThat(restored.workflows()).hasSize(1);
        assertThat(restored.workflows().getFirst().parameters()).isEqualTo(parameters);
        assertThat(restored.initialIntent()).contains(new com.skillforge.runtime.session.AgentExecutionProgram.AuthorizedWorkflowInvocation(
                "open-platform-workflow:catalog-input",target));
        assertThat(restored.tools()).isEmpty();assertThat(restored.initialPlanState()).isEqualTo(PlanPort.State.absent());
        verifyNoInteractions(f.capabilities,f.planHost);
    }

    @Test void publicInvocationValidatesRequiredTypeAndRangeUsingTheRuntimeSchemaValidator() {
        var f=new Fixture();
        var target=new com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget(
                new com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle("workflow:13:catalog-input"),"93",94,
                new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.WORKFLOW,"91","92"),Duration.ofSeconds(37),95);
        when(f.capabilities.authorizedWorkflows(f.identity,f.binding)).thenReturn(List.of(target));
        when(f.capabilities.workflowInputSchema(f.identity,target)).thenReturn(new TypedPayload("json-schema",
                "{\"type\":\"object\",\"required\":[\"count\"],\"properties\":{\"count\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3}},\"additionalProperties\":false}"));
        for(String invalid:List.of("{}","{\"count\":\"1\"}","{\"count\":-1}","{\"count\":4}")) {
            var failure=org.junit.jupiter.api.Assertions.assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                    ()->f.subject.freezeWorkflow(f.identity,f.binding,new AgentRuntimeExecutionGateway.WorkflowInvocation(
                            "13","91","92",new TypedPayload("application/json",invalid))));
            assertThat(failure.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(failure.getCode()).isEqualTo("workflow_invocation_input_invalid");
        }
        var valid=new TypedPayload("application/json","{\"count\":0}");
        var frozen=f.subject.freezeWorkflow(f.identity,f.binding,new AgentRuntimeExecutionGateway.WorkflowInvocation("13","91","92",valid));
        assertThat(f.subject.read(frozen,f.identity).workflows().getFirst().parameters()).isEqualTo(valid);
    }

    @Test void publicInvocationRejectsMissingWrongVersionOrAmbiguousAuthorizedTargets() {
        var f=new Fixture();
        var target=new com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget(
                new com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle("workflow:13:catalog-input"),"93",94,
                new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.WORKFLOW,"91","92"),Duration.ofSeconds(37),95);
        var invocation=new AgentRuntimeExecutionGateway.WorkflowInvocation("13","91","92",new TypedPayload("application/json","{}"));
        for(var targets:List.of(List.<com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget>of(),List.of(target,target))) {
            when(f.capabilities.authorizedWorkflows(f.identity,f.binding)).thenReturn(targets);
            assertThatThrownBy(()->f.subject.freezeWorkflow(f.identity,f.binding,invocation))
                    .isInstanceOf(com.skillforge.core.shared.exception.ApiException.class)
                    .extracting("code").isEqualTo("workflow_invocation_not_authorized");
        }
        when(f.capabilities.authorizedWorkflows(f.identity,f.binding)).thenReturn(List.of(target));
        assertThatThrownBy(()->f.subject.freezeWorkflow(f.identity,f.binding,new AgentRuntimeExecutionGateway.WorkflowInvocation(
                "13","91","99",invocation.parameters()))).isInstanceOf(com.skillforge.core.shared.exception.ApiException.class);
    }

    /** base状态属于准入快照；后来的业务计划状态不能改变恢复第一个MODEL时的准入计算。 */
    @Test
    void originalPlanStateRestoresWithoutReadingCurrentBusinessState() {
        var fixture = new Fixture(); var original = new PlanPort.State(true, false, false, Optional.empty(), false);
        when(fixture.planHost.loadedState(fixture.identity, fixture.binding)).thenReturn(original);
        var frozen = fixture.freeze(); clearInvocations(fixture.planHost, fixture.log);
        when(fixture.planHost.loadedState(any(), any())).thenThrow(new IllegalStateException("current_plan_changed"));
        var restored = fixture.subject.read(frozen, fixture.identity);
        assertThat(restored.initialPlanState()).isEqualTo(original); assertThat(restored.planRequired()).isFalse();
        verifyNoInteractions(fixture.planHost, fixture.log);
    }

    /** Workflow目标、受理文本和可信业务上下文原样保存，恢复不重新发布实例或采用模型参数。 */
    @Test
    void frozenWorkflowTargetAndParametersSurviveCurrentCatalogChanges() throws Exception {
        var fixture = new Fixture();
        var reference = new com.skillforge.core.capability.CapabilityRef(com.skillforge.core.capability.CapabilityType.WORKFLOW, "91", "92");
        var mount = new CapabilityMount("13", 1L, 2L, reference, new CapabilityMount.WorkflowMountConfig("93", 94, 95), 1, 1, true);
        var target = new com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget(
                new com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle("workflow:13:catalog-input"), "93", 94,
                new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.WORKFLOW, "91", "92"), Duration.ofSeconds(37), 95);
        when(fixture.member.mounts()).thenReturn(new CapabilityMountSetResult(1L, 2L, 1, List.of(mount)));
        when(fixture.capabilities.workflows(fixture.identity, fixture.binding)).thenReturn(List.of(target));
        var inbound = new TypedPayload("input.v1", "{\"content\":[{\"type\":\"text\",\"text\":\"原始任务\"},{\"type\":\"text\",\"text\":\"后续要求\"}],\"extraParameters\":{\"customerId\":\"42\",\"user_input\":\"不能覆盖真实任务\",\"input_references\":[]}}");
        var frozen = fixture.freeze(inbound); clearInvocations(fixture.capabilities, fixture.knowledge);
        when(fixture.capabilities.workflows(any(), any())).thenThrow(new IllegalStateException("current_workflow_disabled"));
        var restored = fixture.subject.read(frozen, fixture.identity);
        assertThat(restored.workflows()).hasSize(1); var workflow = restored.workflows().getFirst();
        assertThat(workflow.target()).isEqualTo(target); assertThat(workflow.evidenceRefs()).isEmpty();
        var parameters = fixture.json.readTree(workflow.parameters().canonicalJson());
        assertThat(parameters.path("user_input").textValue()).isEqualTo("原始任务\n后续要求");
        assertThat(parameters.path("extraParameters")).isEqualTo(fixture.json.readTree(inbound.canonicalJson()).path("extraParameters"));
        assertThat(restored.availableCapabilities()).containsExactly(target.capability());
        verifyNoInteractions(fixture.capabilities, fixture.knowledge);
    }

    /** 实际标准包与完整可用挂载集合冻结后，恢复不读取新包正文或丢失Skill依赖身份。 */
    @Test
    void exactSkillPackageAndAvailableCapabilitiesSurviveCurrentCatalogChanges() throws Exception {
        var fixture = new Fixture();
        var reference = new com.skillforge.core.capability.CapabilityRef(com.skillforge.core.capability.CapabilityType.SKILL, "31", "41");
        var dependency = new com.skillforge.core.capability.CapabilityRef(com.skillforge.core.capability.CapabilityType.KNOWLEDGE_SOURCE, "61", "71");
        var skill = new CapabilityMount("11", 1L, 2L, reference,
                new CapabilityMount.SkillMountConfig(SkillActivationMode.ON_DEMAND, 4_096), 2, 3, true);
        var knowledgeMount = new CapabilityMount("12", 1L, 2L, dependency,
                new CapabilityMount.KnowledgeMountConfig(CapabilityMount.KnowledgeMountConfig.BindingMode.PINNED_REVISION, "81"), 1, 1, true);
        var original = "---\nname: frozen-skill\ndescription: Frozen skill instructions\n---\n原入口正文😀";
        var skillPackage = new SkillPackageValidator().fromDirectory(Map.of("SKILL.md", original.getBytes(StandardCharsets.UTF_8),
                "references/guide.md", "原版参考资源".getBytes(StandardCharsets.UTF_8)));
        var descriptor = SkillPackageDescriptorMapper.descriptor(skillPackage, true, false, SkillActivationMode.ON_DEMAND, null, List.of(dependency));
        var fact = new AgentRuntimeExecutionMounts.SkillFact(skill, new TypedPayload("skillforge.runtime.capability.descriptor.v1", fixture.json.writeValueAsString(descriptor)));
        when(fixture.member.mounts()).thenReturn(new CapabilityMountSetResult(1L, 2L, 1, List.of(skill, knowledgeMount)));
        when(fixture.capabilities.skills(fixture.identity, fixture.binding)).thenReturn(List.of(fact));
        var frozen = fixture.freeze(); clearInvocations(fixture.capabilities, fixture.knowledge);
        when(fixture.capabilities.skills(any(), any())).thenThrow(new IllegalStateException("skill_version_no_longer_current"));
        var restored = fixture.subject.read(frozen, fixture.identity);
        assertThat(restored.skills()).hasSize(1);
        var selected = restored.skills().getFirst();
        assertThat(selected.handle()).isEqualTo("skill:11"); assertThat(selected.tokenBudget()).isEqualTo(4_096);
        assertThat(selected.descriptor().entrypoint().content()).isEqualTo(original);
        assertThat(selected.descriptor().resource("references/guide.md").orElseThrow().content()).isEqualTo("原版参考资源");
        assertThat(restored.availableCapabilities()).containsExactlyInAnyOrder(
                new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.SKILL, "31", "41"),
                new com.skillforge.runtime.api.CapabilityRef(com.skillforge.runtime.api.CapabilityType.KNOWLEDGE_SOURCE, "61", "71"));
        verifyNoInteractions(fixture.capabilities, fixture.knowledge);
    }

    /** 真实host控制目录完整record往返，read不调用当前目录或知识发布。 */
    @Test
    void frozenCatalogRestoresExactTypedToolsWithoutConsultingCurrentSources() {
        var fixture = new Fixture(); var material = fixture.freeze();
        clearInvocations(fixture.capabilities, fixture.knowledge);
        when(fixture.capabilities.list(any(RunIdentity.class), any(AgentRunBindings.Binding.class)))
                .thenThrow(new IllegalStateException("current_deployment_disabled"));
        var restored = fixture.subject.read(material, fixture.identity);
        assertThat(restored.tools()).isEqualTo(fixture.tools);
        assertThat(restored.identities()).isEqualTo(fixture.identities);
        assertThat(restored.knowledge()).isEmpty(); assertThat(restored.identity()).isEqualTo(fixture.identity);
        verifyNoInteractions(fixture.capabilities, fixture.knowledge);
    }

    /** 配置材料引用和原Run归属必须一致，不能复用另一个Run的目录。 */
    @Test
    void rejectsWrongRunReferenceOrSchema() {
        var fixture = new Fixture(); var material = fixture.freeze();
        assertThatThrownBy(() -> fixture.subject.read(new Material(new ResultRef("other"), material.content()), fixture.identity))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixture.subject.read(new Material(material.reference(), new TypedPayload("application/json", material.content().canonicalJson())), fixture.identity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 缺失、额外、重复字段和尾随正文不能通过record默认值或宽松JSON转换获得合法目录。 */
    @Test
    void rejectsIncompleteUnknownDuplicateOrTrailingCatalogFields() throws Exception {
        var fixture = new Fixture(); var material = fixture.freeze();
        var extra = (ObjectNode) fixture.json.readTree(material.content().canonicalJson()); extra.put("unexpected", true);
        var missing = (ObjectNode) fixture.json.readTree(material.content().canonicalJson()); missing.remove("tools");
        for (String invalid : List.of(fixture.json.writeValueAsString(extra), fixture.json.writeValueAsString(missing),
                "{\"tools\":[]," + material.content().canonicalJson().substring(1), material.content().canonicalJson() + "{}")) {
            var altered = new Material(material.reference(), new TypedPayload(AgentRuntimeExecutionCatalog.SCHEMA, invalid));
            assertThatThrownBy(() -> fixture.subject.read(altered, fixture.identity)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 独立fixture只提供真实host目录和合法Run，不模拟Provider调用或数据库授权。 */
    private static final class Fixture {
        private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        private final AgentRuntimePublishedCapabilities capabilities = mock(AgentRuntimePublishedCapabilities.class);
        private final JdbcKnowledgeBindings knowledge = mock(JdbcKnowledgeBindings.class);
        private final AgentRunBindings.Binding binding = mock(AgentRunBindings.Binding.class);
        private final TeamService.FrozenMember member = mock(TeamService.FrozenMember.class);
        private final InstitutionPlanHost planHost = mock(InstitutionPlanHost.class);
        @SuppressWarnings("unchecked") private final ObjectProvider<InstitutionPlanHost> plans = mock(ObjectProvider.class);
        private final SessionLogPort log = mock(SessionLogPort.class);
        private final List<SessionToolDirectory.Entry> tools = OutputControlTools.append(new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()), List.of());
        private final Map<String,String> identities = tools.stream().collect(java.util.stream.Collectors.toMap(
                entry -> entry.target().handle().value(), entry -> entry.target().handle().value()));
        private final RunIdentity identity;
        private final AgentRuntimeExecutionCatalog subject;

        /** JSON配额测试使用独立有界校验；生产装配沿用Runtime纯JSON配额实现。 */
        private Fixture() {
            var tenant = new TenantId("1"); var owner = SessionOwner.user(new SessionRef(tenant, "catalog-session"), new AgentId("2"), new EndUserId(3));
            var input = new InputId("catalog-input");
            identity = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "catalog-run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("catalog-identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            when(capabilities.list(identity, binding)).thenReturn(tools);
            when(capabilities.modelIdentities(identity, tools, binding)).thenReturn(identities);
            when(capabilities.knowledge(identity, binding)).thenReturn(List.of());
            when(capabilities.skills(identity, binding)).thenReturn(List.of());
            when(capabilities.workflows(identity, binding)).thenReturn(List.of());
            when(binding.member()).thenReturn(member);
            when(member.mounts()).thenReturn(new CapabilityMountSetResult(1L, 2L, 1, List.of()));
            when(plans.getObject()).thenReturn(planHost); when(planHost.loadedState(identity, binding)).thenReturn(PlanPort.State.absent());
            subject = new AgentRuntimeExecutionCatalog(capabilities, knowledge, json, payload -> payload.canonicalJson().length() < 100_000,
                    new AgentRuntimeProperties(), plans, log, org.mockito.Mockito.mock(com.skillforge.institution.runtime.WorkflowTestRuntimePolicy.class));
        }

        /** 使用真实受理时点冻结，read原样保留而非恢复时重新取当前时间。 */
        private Material freeze() { return freeze(new TypedPayload("input.v1", "{\"content\":[{\"type\":\"text\",\"text\":\"原始输入\"}],\"extraParameters\":{}}")); }
        private Material freeze(TypedPayload inbound) { return subject.freeze(identity, binding, Instant.parse("2026-10-22T00:00:00Z"), inbound); }
    }
}
