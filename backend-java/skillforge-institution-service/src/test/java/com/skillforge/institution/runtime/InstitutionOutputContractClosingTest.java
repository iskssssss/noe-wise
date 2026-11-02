package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.integration.publicread.PublicOutputGuard;
import com.skillforge.institution.artifact.ArtifactClosingBudget;
import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.institution.browser.BrowserClosingBudget;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import com.skillforge.runtime.session.spi.WorkflowInteractionView;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 以可信 Run 目录验证真实停止预留、成果组合与同名能力拒绝，不调用浏览器。 */
class InstitutionOutputContractClosingTest {
    /** 同次MODEL只消费一组当前成果事实；下一次重新读取，冻结后保持动作关闭及有限回复预算。 */
    @Test void modelPreparationUsesOneOwnedFactsReadAndKeepsOutputAndStopObligations() {
        var f=new Fixture();var actual=mock(ExecutionStore.Run.class);when(actual.identity()).thenReturn(f.run);
        var binding=mock(AgentRunBindings.Binding.class);when(binding.owner()).thenReturn(f.run.owner());when(binding.actor()).thenReturn(f.run.agentId());

        var stop=tool("browser_stop","browser_stop",ToolBinding.ProviderType.PLATFORM,Duration.ofSeconds(90),true,ToolConfirmationPolicy.NEVER);
        when(f.deliverables.prepareOutput(f.run)).thenReturn(
                new DeliverableService.OutputPreparation(Optional.empty(),List.of(),Optional.empty(),false),
                new DeliverableService.OutputPreparation(Optional.of("draft"),List.of(),Optional.empty(),true),
                new DeliverableService.OutputPreparation(Optional.of("finalizing"),List.of(),Optional.of("result-ref"),true),
                new DeliverableService.OutputPreparation(Optional.of("abandoned"),List.of(),Optional.of("abandoned-result-ref"),true));
        var ordinary=f.outputs.prepareOutput(actual,binding,List.of(stop));
        assertTrue(ordinary.respondRejection().isEmpty());assertTrue(ordinary.executionRejection().isEmpty());
        assertTrue(ordinary.hint().isEmpty(), "没有成果状态的对话不注入空成果协议");
        assertEquals(2,ordinary.closingSpec().modelCalls());assertEquals(1,ordinary.closingSpec().finalizationCalls());
        var required=f.outputs.prepareOutput(actual,binding,List.of(stop));
        assertTrue(required.hint().orElseThrow().runContext().contains("draft"));
        assertEquals("TASK_OUTPUT_RESOLUTION_REQUIRED",required.respondRejection().orElseThrow().code());
        assertTrue(required.executionRejection().isEmpty());assertEquals(3,required.closingSpec().modelCalls());assertEquals(2,required.closingSpec().finalizationCalls());
        var prepared=f.outputs.prepareOutput(actual,binding,List.of(stop));
        assertTrue(prepared.hint().orElseThrow().runContext().contains("finalizing"));
        assertTrue(prepared.respondRejection().isEmpty());assertEquals("TASK_OUTPUTS_FROZEN",prepared.executionRejection().orElseThrow().code());
        assertEquals(Optional.of("result-ref"),prepared.preparedResult());assertEquals(1,prepared.closingSpec().modelCalls());assertEquals(0,prepared.closingSpec().finalizationCalls());
        var activeGoal=new com.skillforge.runtime.session.SessionGoal.Snapshot("goal-report",3,"完成项目报告",
                com.skillforge.runtime.session.SessionGoal.State.ACTIVE,10,1,Optional.empty(),Optional.of(f.run.runRef()),Optional.empty());
        var goalStatus=mock(AgentRuntimeGoalDriver.Status.class);when(goalStatus.goal()).thenReturn(activeGoal);
        when(f.goals.read(f.run.owner())).thenReturn(Optional.of(goalStatus));
        var abandoned=f.outputs.prepareOutput(actual,binding,List.of(stop));
        assertTrue(abandoned.hint().orElseThrow().runContext().contains("本轮文件交付状态：abandoned"));
        assertEquals(List.of("持续 Goal 当前状态=ACTIVE；revision=3；目标=完成项目报告"),abandoned.closingSpec().facts());
        assertTrue(abandoned.respondRejection().isEmpty());
        var rejection=abandoned.executionRejection().orElseThrow();
        assertEquals("TASK_OUTPUTS_FROZEN",rejection.code());
        assertTrue(rejection.feedback().startsWith("本轮文件交付已放弃，"));
        assertEquals(Optional.of("abandoned-result-ref"),abandoned.preparedResult());
        assertEquals(1,abandoned.closingSpec().modelCalls());assertEquals(0,abandoned.closingSpec().finalizationCalls());
        assertEquals(com.skillforge.runtime.session.SessionGoal.State.ACTIVE,activeGoal.state());
        verify(f.deliverables,times(4)).prepareOutput(f.run);verifyNoMoreInteractions(f.deliverables);
        verify(f.goals,times(4)).read(f.run.owner());verifyNoMoreInteractions(f.goals);verifyNoInteractions(f.tools);
    }

    /** 真实动态消息在终答时保留三种已核验状态的区别，不把冻结待发布呈现为失败。 */
    @Test void finalAnswerProjectionExplainsPreparedStateWithoutInventingPublication() throws Exception {
        var f=new Fixture();var actual=mock(ExecutionStore.Run.class);when(actual.identity()).thenReturn(f.run);
        var binding=mock(AgentRunBindings.Binding.class);when(binding.owner()).thenReturn(f.run.owner());when(binding.actor()).thenReturn(f.run.agentId());
        var messages=com.skillforge.runtime.session.AgentExecutionOutputs.class.getDeclaredMethod("messages",List.class,
                Optional.class,long.class,com.skillforge.runtime.session.AgentExecutionCodec.class);
        messages.setAccessible(true);
        var codec=new com.skillforge.runtime.session.AgentExecutionCodec(new com.skillforge.runtime.json.CodecLimits(100000,10000,64));
        for (String state:List.of("finalizing","abandoned","finalized")) {
            when(f.deliverables.prepareOutput(f.run)).thenReturn(new DeliverableService.OutputPreparation(
                    Optional.of(state),List.of(),Optional.of("result-"+state),true));
            var policy=f.outputs.prepareOutput(actual,binding,List.of());
            @SuppressWarnings("unchecked") var projected=(List<com.skillforge.runtime.api.port.ModelPort.ModelMessage>)messages.invoke(null,List.of(),
                    Optional.of(new com.skillforge.runtime.session.AgentExecutionOutputs.Directive(policy,0)),7L,codec);
            var visible=projected.getLast().textContent();
            assertTrue(visible.contains("本轮文件交付状态："+state));
            assertTrue(visible.contains("TASK_OUTPUTS_FROZEN"));
            assertFalse(visible.contains("文件交付操作已关闭"));
            assertTrue(policy.respondRejection().isEmpty());assertTrue(policy.preparedResult().isPresent());
            switch(state) {
                case "finalizing" -> {assertTrue(visible.contains("等待本次答复结束且 Run 成功后由宿主正式发布"));assertFalse(visible.contains("已由宿主正式发布"));}
                case "abandoned" -> assertTrue(visible.contains("本轮文件交付已放弃"));
                case "finalized" -> assertTrue(visible.contains("本轮交付清单已由宿主正式发布"));
                default -> fail("unexpected state");
            }
        }
    }

    /** 无成果时通用入口也省略提示；真实状态出现后才携带宿主事实。 */
    @Test void outputHintAppearsOnlyWhenDeliverableFactsExist() {
        var f = new Fixture();
        assertTrue(f.outputs.preparationHint(f.run).isEmpty());
        when(f.deliverables.currentOutputPlanState(f.run)).thenReturn(Optional.of("finalizing"));
        assertTrue(f.outputs.preparationHint(f.run).orElseThrow().runContext().contains("finalizing"));
    }

    /** 复用事实不授予其他owner或Agent访问；binding漂移在读取成果前拒绝。 */
    @Test void modelPreparationRejectsBindingDriftBeforeReadingOutputFacts() {
        var f=new Fixture();var actual=mock(ExecutionStore.Run.class);when(actual.identity()).thenReturn(f.run);
        var binding=mock(AgentRunBindings.Binding.class);when(binding.owner()).thenReturn(f.run.owner());when(binding.actor()).thenReturn(new AgentId("999"));
        assertEquals("execution_output_binding_mismatch",assertThrows(IllegalStateException.class,
                ()->f.outputs.prepareOutput(actual,binding,List.of())).getMessage());
        verifyNoInteractions(f.deliverables,f.goals);
    }

    /** 原冻结目录挂载使启动前已保护停止及后续回复，不增大业务预算。 */
    @Test void mountedStopReservesToolReceiptAndNextModelBeforeAnyBrowserReceiptExists() {
        var f = new Fixture();
        when(f.tools.list(f.run)).thenReturn(List.of(tool("browser_stop", "browser_stop", ToolBinding.ProviderType.PLATFORM,
                Duration.ofMillis(90_001), true, ToolConfirmationPolicy.NEVER)));
        var plan = f.outputs.closingPlan(f.run);
        assertEquals(2, plan.modelCalls());
        assertEquals(1, plan.finalizationCalls());
        assertEquals(Duration.ofSeconds(92), plan.localProcessing());
        assertEquals(32_768, plan.receiptInputUpperBound());
        var resources = new ExecutionBudgetScope.ClosingSizing(ExecutionBudgetPort.Category.BUSINESS_MODEL,
                1_000, 500, Duration.ofSeconds(120)).size(new ExecutionBudgetPort.ClosingSpec(plan.modelCalls(),
                plan.finalizationCalls(), plan.localProcessing().toSeconds(), plan.receiptInputUpperBound(), plan.facts(), Set.of()));
        assertEquals(2, resources.resources().businessModels());
        assertEquals(1, resources.resources().tools());
        assertEquals(34_768, resources.resources().inputTokens());
        assertEquals(212, resources.activeSeconds());
        assertTrue(plan.facts().getLast().contains("成果最后冻结和最终答复之前"));
    }

    /** 两个收尾工具需要三轮模型，停止回执在后两轮各计一次。 */
    @Test void outputsAndStopReserveBothToolsAndRepeatedStopReceipt() {
        var f = new Fixture();
        when(f.deliverables.hasOutputs(f.run)).thenReturn(true);
        when(f.tools.list(f.run)).thenReturn(List.of(tool("browser_stop", "browser_stop", ToolBinding.ProviderType.PLATFORM,
                Duration.ofSeconds(90), true, ToolConfirmationPolicy.NEVER)));
        var plan = f.outputs.closingPlan(f.run);
        assertEquals(3, plan.modelCalls());
        assertEquals(2, plan.finalizationCalls());
        assertEquals(Duration.ofSeconds(210), plan.localProcessing());
        assertEquals(98_304, plan.receiptInputUpperBound());
        assertTrue(f.outputs.closingToolAllowed(f.run, new ToolBinding(ToolBinding.ProviderType.PLATFORM, "browser_stop", "browser_stop")));
        assertTrue(f.outputs.closingToolAllowed(f.run, new ToolBinding(ToolBinding.ProviderType.PLATFORM, "finalize_task_outputs", "finalize_task_outputs")));
    }

    /** 非浏览器、不可模型调用、需确认及同名伪装能力均不能额外取得停止预留。 */
    @Test void rejectsSameNamedProvidersAndDoesNotReserveWithoutInvocablePlatformStop() {
        var f = new Fixture();
        for (var entry : List.of(
                tool("browser_call", "browser_call", ToolBinding.ProviderType.PLATFORM, Duration.ofSeconds(90), true, ToolConfirmationPolicy.NEVER),
                tool("browser_start", "browser_start", ToolBinding.ProviderType.PLATFORM, Duration.ofSeconds(90), true, ToolConfirmationPolicy.NEVER),
                tool("browser_stop", "browser_stop", ToolBinding.ProviderType.MCP, Duration.ofSeconds(90), true, ToolConfirmationPolicy.NEVER),
                tool("other_handler", "browser_stop", ToolBinding.ProviderType.PLATFORM, Duration.ofSeconds(90), true, ToolConfirmationPolicy.NEVER),
                tool("browser_stop", "browser_stop", ToolBinding.ProviderType.PLATFORM, Duration.ofSeconds(90), false, ToolConfirmationPolicy.NEVER),
                tool("browser_stop", "browser_stop", ToolBinding.ProviderType.PLATFORM, Duration.ofSeconds(90), true, ToolConfirmationPolicy.ALWAYS))) {
            when(f.tools.list(f.run)).thenReturn(List.of(entry));
            assertEquals(1, f.outputs.closingPlan(f.run).modelCalls());
            assertEquals(0, f.outputs.closingPlan(f.run).finalizationCalls());
            if (entry.target().modelInvocable() && entry.target().confirmation() == ToolConfirmationPolicy.NEVER)
                assertFalse(f.outputs.closingToolAllowed(f.run, entry.binding()));
        }
        when(f.tools.list(f.run)).thenReturn(List.of());
        assertEquals(0, f.outputs.closingPlan(f.run).receiptInputUpperBound());
    }

    /** 成果已经冻结时物化器只允许最终答复，不再预留无法调用的停止。 */
    @Test void preparedOutputsKeepOnlyTheFinalAnswerPlan() {
        var f = new Fixture();
        when(f.deliverables.preparedResult(f.run)).thenReturn(Optional.of("result-ref"));
        assertEquals(1, f.outputs.closingPlan(f.run).modelCalls());
        assertEquals(0, f.outputs.closingPlan(f.run).finalizationCalls());
        verifyNoInteractions(f.tools);
    }

    /** 新通用上界精确容纳停止、成果冻结和最终回复，仍拒绝第四模型和第三工具。 */
    @Test void closingContractsKeepFiniteGenericBounds() {
        assertDoesNotThrow(() -> new com.skillforge.runtime.session.spi.SessionOutputContract.ClosingPlan(3, 2, Duration.ofSeconds(1), 1, List.of()));
        assertDoesNotThrow(() -> new ExecutionBudgetPort.ClosingSpec(3, 2, 1, 1, List.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new com.skillforge.runtime.session.spi.SessionOutputContract.ClosingPlan(4, 2, Duration.ZERO, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new com.skillforge.runtime.session.spi.SessionOutputContract.ClosingPlan(3, 3, Duration.ZERO, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionBudgetPort.ClosingSpec(4, 2, 1, 0, List.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionBudgetPort.ClosingSpec(3, 3, 1, 0, List.of(), Set.of()));
    }

    /** 夹具只构造目录契约，不替代生产目录的 Run 授权核验。 */
    private static SessionToolDirectory.Entry tool(String providerRef, String name, ToolBinding.ProviderType provider,
            Duration timeout, boolean invocable, ToolConfirmationPolicy confirmation) {
        var binding = new ToolBinding(provider, providerRef, name);
        var schema = new TypedPayload("json-schema", "{}");
        var target = new FrozenToolTarget(new ToolHandle("tool:closing:" + name),
                new CapabilityRef(CapabilityType.TOOL, "capability:" + name, "builtin:5"), "deployment:" + name, 1,
                schema, schema, ToolEffect.IDEMPOTENT_WRITE, true, timeout, invocable, confirmation, Set.of(), Set.of(), true);
        return new SessionToolDirectory.Entry(target, binding, new SessionToolDirectory.Discovery(binding.serverIdentity(),
                binding.serverIdentity() + "/" + name, name, Optional.empty(), "受控冻结测试条目"));
    }

    /** 每例独立的身份、目录和成果服务，保留原 Run 的可信作用域。 */
    private static final class Fixture {
        private final DeliverableService deliverables = mock(DeliverableService.class);
        private final SessionToolDirectory tools = mock(SessionToolDirectory.class);
        private final AgentRuntimeGoalDriver goals = mock(AgentRuntimeGoalDriver.class);
        private final RunIdentity run;
        private final InstitutionOutputContract outputs;

        /** 构造没有浏览器回执的合法 Run，验证预留发生于启动前。 */
        private Fixture() {
            var tenant = new TenantId("7");
            var input = new InputId("closing-input");
            var owner = SessionOwner.user(new SessionRef(tenant, "closing-session"), new AgentId("101"), new EndUserId(8));
            run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "closing-run"), new TraceEnvelope("closing-trace", "closing-span", input, Optional.empty()), new ResultRef("closing-identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
            outputs = new InstitutionOutputContract(deliverables, goals, mock(PublicOutputGuard.class),
                    mock(SessionLogPort.class), new ObjectMapper(), mock(WorkflowInteractionView.class),
                    new ArtifactClosingBudget(), new BrowserClosingBudget(tools));
        }
    }
}
