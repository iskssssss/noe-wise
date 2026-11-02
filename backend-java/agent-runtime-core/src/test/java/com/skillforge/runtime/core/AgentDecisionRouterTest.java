package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.DelegationCommand;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import com.skillforge.runtime.core.AgentDecisionRouter.*;
import java.time.Duration;
import java.util.AbstractList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 七动作纯路由及全历史矩阵；候选选择不证明实际 Session 结果存在、Policy 授权或计数已持久提交。 */
class AgentDecisionRouterTest {
    @Test void repeatedSkillResourceCallRegistersCurrentOperationInsteadOfReusingHistoricalError() {
        var current = context(3);
        var action = new AgentAction.ReadSkillResource("skill:one", "scripts/build_delivery.py");
        for (var outcome : List.<AgentActionSnapshot.Outcome>of(
                new AgentActionSnapshot.Rejected(new ResultRef("RESOURCE_NOT_FOUND"), new EventId("old-resource")),
                completed(1), new AgentActionSnapshot.Pending())) {
            var historical = localEntry(current, 1, action, outcome);
            var selected = assertInstanceOf(Selected.class,
                    route(action, current, usage(3, 0, 0), List.of(historical), ABSENT));
            assertEquals(action, selected.action());
        }
        var pending = localEntry(current, 3, action, new AgentActionSnapshot.Pending());
        assertEquals(new Converge(pending), route(action, current, usage(3, 0, 0), List.of(pending), ABSENT));
        var rejected = localEntry(current, 3, action,
                new AgentActionSnapshot.Rejected(new ResultRef("RESOURCE_NOT_FOUND"), new EventId("current-resource")));
        assertEquals(new Reuse(rejected), route(action, current, usage(3, 0, 0), List.of(rejected), ABSENT),
                "同轮重放仍采用该原 call 的结果，不重新读取资源");
    }

    @Test void onlyTrustedPlatformFinalizationRunsAfterAllPlanStepsWithoutAnActiveStep() {
        var c = context(3);
        var action = script("freeze actual deliverables");
        var completed = new com.skillforge.runtime.api.port.PlanPort.State(true, true, true, Optional.empty());
        var platform = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "finalize_task_outputs", ToolCallIdentityPolicy.HANDLE);
        assertTrue(PlanActionAdmission.rejection(true, completed, action,
                new ToolCallRouting(c, List.of(platform), List.of())).isEmpty());
        for (var state : List.of(
                new com.skillforge.runtime.api.port.PlanPort.State(true, true, false, Optional.of("step")),
                new com.skillforge.runtime.api.port.PlanPort.State(true, false, true, Optional.empty()))) {
            assertEquals(Optional.of("plan_steps_not_complete_before_finalization"), PlanActionAdmission.rejection(true,
                    state, action, new ToolCallRouting(c, List.of(platform), List.of())));
        }
        var mcp = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.MCP, "finalize_task_outputs", ToolCallIdentityPolicy.HANDLE);
        assertEquals(Optional.of("plan_step_not_started_or_approved"), PlanActionAdmission.rejection(true, completed,
                action, new ToolCallRouting(c, List.of(mcp), List.of())));
    }
    @Test void requiredPlanBlocksEveryExecutionBoundaryAndSuccessfulResponse() {
        var c=context(3);var absent=com.skillforge.runtime.api.port.PlanPort.State.absent();
        var routing=ToolCallRouting.none(c);
        for(var action:List.<AgentAction>of(script("execute"),new AgentAction.Dispatch("workflow"),delegate("work"),new AgentAction.Respond("done",List.of())))
            assertEquals(Optional.of("plan_required_before_execution"),PlanActionAdmission.rejection(true,absent,action,routing));
        for(var action:List.<AgentAction>of(new AgentAction.KnowledgeQuery("knowledge","query"),new AgentAction.AskUser("clarify"),new AgentAction.ActivateSkill("skill"),new AgentAction.ReadSkillResource("skill","file")))
            assertTrue(PlanActionAdmission.rejection(true,absent,action,routing).isEmpty());
        var approved=new com.skillforge.runtime.api.port.PlanPort.State(true,true,false,Optional.of("step"));
        assertTrue(PlanActionAdmission.rejection(true,approved,script("execute"),routing).isEmpty());
        assertEquals(Optional.of("plan_steps_not_complete"),PlanActionAdmission.rejection(true,approved,new AgentAction.Respond("done",List.of()),routing));
        assertEquals(Optional.of("delegate_must_use_plan_step"),PlanActionAdmission.rejection(false,approved,delegate("work"),routing));
        var inherited=new com.skillforge.runtime.api.port.PlanPort.State(true,true,false,Optional.empty(),true);
        assertTrue(PlanActionAdmission.rejection(true,inherited,script("member work"),routing).isEmpty());
        assertTrue(PlanActionAdmission.rejection(true,inherited,new AgentAction.Respond("member result",List.of()),routing).isEmpty());
    }
    @Test void onlyFrozenReadEffectPassesBeforePlanAndCodeNeverBorrowsReadQualification() {
        var c=context(3);var original=codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "read_file", ToolCallIdentityPolicy.HANDLE);
        var t=original.target();
        var readTarget=new com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget(t.handle(),t.capability(),t.deploymentId(),t.deploymentRevision(),t.inputSchema(),t.outputSchema(),ToolEffect.READ_ONLY,true,t.timeout(),t.modelInvocable(),t.confirmation(),t.sensitiveFields(),t.providerRequirements(),t.outputSchemaDeclared(),ToolCallIdentityPolicy.ARGUMENTS);
        var read=new ToolCallRouting.Binding(readTarget,original.binding(),true);
        assertTrue(PlanActionAdmission.rejection(true,com.skillforge.runtime.api.port.PlanPort.State.absent(),script("read"),new ToolCallRouting(c,List.of(read),List.of())).isEmpty());
        var code=new ToolCallRouting.Binding(readTarget,new com.skillforge.runtime.api.port.ToolPortal.ToolBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM,"platform_tools","code_execute"),true);
        assertEquals(Optional.of("plan_required_before_execution"),PlanActionAdmission.rejection(true,com.skillforge.runtime.api.port.PlanPort.State.absent(),script("code"),new ToolCallRouting(c,List.of(code),List.of())));
    }

    @Test void verifiedSchemaRejectionAllowsCorrectionButPreservesExactReuseAndToolBudget() {
        var c = context(3);
        var binding = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "finalize_task_outputs", ToolCallIdentityPolicy.HANDLE);
        var original = script("invalid schema");
        var history = codeHistory(c, original, new AgentActionSnapshot.Failed(new ResultRef("schema-failed"), Optional.of(new EventId("schema-event"))));
        var routing = new ToolCallRouting(c, List.of(binding), List.of(new ToolCallRouting.Historical(history.getFirst(), binding, true)));
        assertEquals(new Reuse(history.getFirst()), router().route(new AgentDecisionDecoder.Decoded(original), c,
                BUDGET, usage(3, 1, 0), Duration.ZERO, history, ABSENT, routing));
        assertInstanceOf(Selected.class, router().route(new AgentDecisionDecoder.Decoded(script("corrected schema")), c,
                BUDGET, usage(3, 1, 0), Duration.ZERO, history, ABSENT, routing));
        assertInstanceOf(BudgetDenied.class, router().route(new AgentDecisionDecoder.Decoded(script("corrected schema")), c,
                BUDGET, usage(3, 2, 0), Duration.ZERO, history, ABSENT, routing));
        // 没有经过来源证明的 Failed，保持原非幂等写守卫。
        assertEquals(new Rejected(Rejection.INVALID_HISTORY), routedCode(script("corrected schema"), c, history, binding));
    }

    @Test void schemaRejectionAndLaterSuccessCoexistWithoutReopeningExecutedWrite() {
        var c = context(3);
        var binding = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "finalize_task_outputs", ToolCallIdentityPolicy.HANDLE);
        var bad = codeHistory(c, script("invalid schema"), new AgentActionSnapshot.Failed(new ResultRef("schema-failed"), Optional.of(new EventId("schema-event")))).getFirst();
        var fixed = script("corrected schema");
        var success = new AgentActionSnapshot(new ModelPreparationIdentity(c.operation().runIdentity(), c.operation().loopId(), 2),
                c.contextScope(), c.capabilitySnapshot(), fixed, Optional.of(new ResultRef("corrected-request")), completed(2));
        var history = List.of(bad, success);
        var routing = new ToolCallRouting(c, List.of(binding), List.of(new ToolCallRouting.Historical(bad, binding, true), new ToolCallRouting.Historical(success, binding, false)));
        assertEquals(new Reuse(success), router().route(new AgentDecisionDecoder.Decoded(fixed), c,
                BUDGET, usage(3, 2, 0), Duration.ZERO, history, ABSENT, routing));
        for (var call : List.of(script("another write"), (AgentAction.ToolCall) bad.action()))
            assertEquals(new Rejected(Rejection.INVALID_HISTORY), router().route(new AgentDecisionDecoder.Decoded(call), c,
                    BUDGET, usage(3, 2, 0), Duration.ZERO, history, ABSENT, routing));
        for (var outcome : List.<AgentActionSnapshot.Outcome>of(completed(1), new AgentActionSnapshot.Pending(),
                new AgentActionSnapshot.OutcomeUnknown(new ResultRef("unknown")),
                new AgentActionSnapshot.Cancelled(new ResultRef("cancelled"), new EventId("cancelled-event")))) {
            var entry = codeHistory(c, (AgentAction.ToolCall) bad.action(), outcome).getFirst();
            assertThrows(IllegalArgumentException.class, () -> new ToolCallRouting.Historical(entry, binding, true));
        }
    }

    @Test void platformCodeAllowsDifferentCompletedScriptAndReusesIdenticalInvocation() {
        var c = context(3);
        var original = script("read attachments");
        var history = codeHistory(c, original, completed(1));
        for (String name : List.of("code_execute", "workspace_exec", "workspace_files")) {
            var binding = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, name, ToolCallIdentityPolicy.ARGUMENTS);
            assertFalse(binding.parallelSafe(), "代码按参数区分不意味着可并行");
            assertInstanceOf(Selected.class, routedCode(script("write report"), c, history, binding), name);
            assertEquals(new Reuse(history.getFirst()), routedCode(original, c, history, binding), name);
            var routing = new ToolCallRouting(c, List.of(binding), List.of(new ToolCallRouting.Historical(history.getFirst(), binding, false)));
            assertInstanceOf(BudgetDenied.class, router().route(new AgentDecisionDecoder.Decoded(script("write report")), c,
                    BUDGET, usage(3, 2, 0), Duration.ZERO, history, ABSENT, routing), name);
        }
    }

    /** 真实失败边界：激活技能、读取脚本后，下一轮应能提交不同命令实际执行该脚本。 */
    @Test void platformWorkspaceExecRunsPackagedScriptAfterDistinctResourceRead() {
        var c = context(3);
        var binding = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "workspace_exec", ToolCallIdentityPolicy.ARGUMENTS);
        var read = workspaceCommand("cat \"$SKILL_DIR/scripts/test.sh\"");
        var execute = workspaceCommand("bash \"$SKILL_DIR/scripts/test.sh\"");
        var activation = localEntry(c, 1, new AgentAction.ActivateSkill("skill:one"), completed(1));
        var original = new AgentActionSnapshot(new ModelPreparationIdentity(c.operation().runIdentity(), c.operation().loopId(), 2),
                c.contextScope(), c.capabilitySnapshot(), read, Optional.of(new ResultRef("read-command-request")), completed(2));
        var history = List.of(activation, original);
        var routing = new ToolCallRouting(c, List.of(binding), List.of(new ToolCallRouting.Historical(original, binding, false)));
        assertFalse(binding.parallelSafe());
        assertEquals(new Selected(execute, List.of()), router().route(new AgentDecisionDecoder.Decoded(execute), c,
                BUDGET, usage(3, 1, 0), Duration.ZERO, history, ABSENT, routing));
        assertEquals(new Reuse(original), router().route(new AgentDecisionDecoder.Decoded(read), c,
                BUDGET, usage(3, 1, 0), Duration.ZERO, history, ABSENT, routing));
    }

    /** 工作区文件操作保留其非幂等效果，列目录与读取文件仍是两个串行新动作。 */
    @Test void platformWorkspaceFilesRunsDifferentOperationsAndReusesTheExactOriginal() {
        var c = context(3);
        var binding = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "workspace_files", ToolCallIdentityPolicy.ARGUMENTS);
        var list = new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", "{\"op\":\"list\",\"path\":\".\"}"));
        var read = new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", "{\"op\":\"read\",\"path\":\"index.html\"}"));
        var history = codeHistory(c, list, completed(1));
        assertEquals(ToolEffect.NON_IDEMPOTENT_WRITE, binding.target().effect());
        assertFalse(binding.parallelSafe());
        assertInstanceOf(Selected.class, routedCode(read, c, history, binding));
        assertEquals(new Reuse(history.getFirst()), routedCode(list, c, history, binding));
    }

    @Test void unresolvedPlatformCodeNeverAllowsChangedArgumentsAndConvergesOriginal() {
        var c = context(3);
        var original = script("read attachments");
        for (String name : List.of("code_execute", "workspace_exec", "workspace_files")) {
            var binding = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, name, ToolCallIdentityPolicy.ARGUMENTS);
            for (var outcome : List.<AgentActionSnapshot.Outcome>of(new AgentActionSnapshot.Pending(),
                    new AgentActionSnapshot.OutcomeUnknown(new ResultRef("unknown")))) {
                var history = codeHistory(c, original, outcome);
                assertEquals(new Rejected(Rejection.INVALID_HISTORY), routedCode(script("write report"), c, history, binding), name);
                assertEquals(new Converge(history.getFirst()), routedCode(original, c, history, binding), name);
            }
        }
    }

    /** 同一浏览器任务的独立命令保留非幂等效果，导航之后仍可依次截图及点击。 */
    @Test void platformBrowserRoutesDistinctCommandsAndReusesExactCompletedInvocation() {
        var c=context(3);
        var binding=codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "browser_call", ToolCallIdentityPolicy.ARGUMENTS);
        var navigate=browserCommand("navigate","{\"url\":\"https://example.test/page\"}");
        var screenshot=browserCommand("screenshot","{\"tab_id\":7}");
        var click=browserCommand("click","{\"ref\":\"e1\",\"tab_id\":7}");
        assertEquals(ToolEffect.NON_IDEMPOTENT_WRITE,binding.target().effect());
        assertFalse(binding.target().idempotent());assertFalse(binding.parallelSafe());
        for(var outcome:List.<AgentActionSnapshot.Outcome>of(completed(1),failed(1))) {
            var history=codeHistory(c,navigate,outcome);
            assertEquals(new Selected(screenshot,List.of()),routedCode(screenshot,c,history,binding));
            assertEquals(new Selected(click,List.of()),routedCode(click,c,history,binding));
            assertEquals(new Reuse(history.getFirst()),routedCode(navigate,c,history,binding));
        }
        var navigation=codeHistory(c,navigate,completed(1)).getFirst();
        var captured=new AgentActionSnapshot(new ModelPreparationIdentity(c.operation().runIdentity(),c.operation().loopId(),2),
                c.contextScope(),c.capabilitySnapshot(),screenshot,Optional.of(new ResultRef("screenshot-request")),completed(2));
        var history=List.of(navigation,captured);
        var routing=new ToolCallRouting(c,List.of(binding),history.stream().map(action->new ToolCallRouting.Historical(action,binding,false)).toList());
        var budget=new LoopBudget.Enforced(5,4,2,100,100,150,Duration.ofSeconds(10));
        assertEquals(new Selected(click,List.of()),router().route(new AgentDecisionDecoder.Decoded(click),c,budget,usage(3,2,0),Duration.ZERO,history,ABSENT,routing));
        assertEquals(new Reuse(captured),router().route(new AgentDecisionDecoder.Decoded(screenshot),c,budget,usage(3,2,0),Duration.ZERO,history,ABSENT,routing));
    }

    /** 可信观察跨轮读取当前页面，完整历史允许多轮观察；同轮恢复仍复用原动作。 */
    @Test void currentReadAfterInteractionSelectsNewActionAndReusesCurrentOperation() {
        var c = context(3);
        var binding = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "browser_call", ToolCallIdentityPolicy.ARGUMENTS);
        var observe = browserCommand("observe", "{\"tab_id\":7}");
        var click = browserCommand("click", "{\"ref\":\"e1\",\"tab_id\":7}");
        var history = List.of(entry(c, 1, observe, completed(1)), entry(c, 2, click, completed(2)));
        var routing = currentReadRouting(c, history, binding, observe);
        var budget = new LoopBudget.Enforced(5, 4, 2, 100, 100, 150, Duration.ofSeconds(10));
        assertEquals(new Selected(observe, List.of()), router().route(new AgentDecisionDecoder.Decoded(observe), c,
                budget, usage(3, 2, 0), Duration.ZERO, history, ABSENT, routing));
        assertEquals(new Reuse(history.getLast()), router().route(new AgentDecisionDecoder.Decoded(click), c,
                budget, usage(3, 2, 0), Duration.ZERO, history, ABSENT, routing), "观察资格不允许相同点击重发");
        for (var outcome : List.<AgentActionSnapshot.Outcome>of(completed(3), new AgentActionSnapshot.Pending())) {
            var current = entry(c, 3, observe, outcome);
            var recovered = List.of(history.getFirst(), history.getLast(), current);
            var result = router().route(new AgentDecisionDecoder.Decoded(observe), c, budget, usage(3, 3, 0),
                    Duration.ZERO, recovered, ABSENT, currentReadRouting(c, recovered, binding, observe));
            assertEquals(outcome.resolved() ? new Reuse(current) : new Converge(current), result);
        }
        var next = context(4);
        var multipleReads = List.of(history.getFirst(), history.getLast(), entry(c, 3, observe, completed(3)));
        assertEquals(new Selected(observe, List.of()), router().route(new AgentDecisionDecoder.Decoded(observe), next,
                budget, usage(4, 3, 0), Duration.ZERO, multipleReads, ABSENT,
                currentReadRouting(next, multipleReads, binding, observe)));
        assertInstanceOf(BudgetDenied.class, router().route(new AgentDecisionDecoder.Decoded(observe), c,
                BUDGET, usage(3, 2, 0), Duration.ZERO, history, ABSENT, routing), "新鲜读取仍消耗新调用预算");
    }

    /** 新观察身份不能跳过原未决目标；相同请求收敛，改参或写后观察均拒绝。 */
    @Test void currentReadDoesNotBypassPendingOrUnknownTarget() {
        var c = context(3);
        var binding = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "browser_call", ToolCallIdentityPolicy.ARGUMENTS);
        var observe = browserCommand("observe", "{\"tab_id\":7}");
        var changed = browserCommand("observe", "{\"tab_id\":8}");
        var click = browserCommand("click", "{\"ref\":\"e1\",\"tab_id\":7}");
        for (var outcome : outcomes(2).stream().filter(value -> !value.resolved()).toList()) {
            var pending = entry(c, 2, observe, outcome);
            var history = List.of(entry(c, 1, observe, completed(1)), pending);
            var routing = new ToolCallRouting(c, List.of(binding), history.stream()
                    .map(value -> new ToolCallRouting.Historical(value, binding, false)).toList(), List.of(observe, changed));
            assertEquals(new Converge(pending), router().route(new AgentDecisionDecoder.Decoded(observe), c,
                    BUDGET, usage(3, 2, 0), Duration.ZERO, history, ABSENT, routing));
            assertEquals(new Rejected(Rejection.INVALID_HISTORY), router().route(new AgentDecisionDecoder.Decoded(changed), c,
                    BUDGET, usage(3, 2, 0), Duration.ZERO, history, ABSENT, routing));
            var unresolvedWrite = List.of(entry(c, 1, click, outcome));
            assertEquals(new Rejected(Rejection.INVALID_HISTORY), router().route(new AgentDecisionDecoder.Decoded(observe), c,
                    BUDGET, usage(3, 1, 0), Duration.ZERO, unresolvedWrite, ABSENT,
                    currentReadRouting(c, unresolvedWrite, binding, observe)));
        }
        var completedRead = codeHistory(c, observe, completed(1));
        assertEquals(new Reuse(completedRead.getFirst()), routedCode(observe, c, completedRead, binding),
                "没有可信当前读取资格时保持原精确复用行为");
    }

    /** 测试只模拟宿主已核证的完整观察值；Core 不从参数自行推断无副作用。 */
    private static ToolCallRouting currentReadRouting(FrozenContextSnapshot context, List<AgentActionSnapshot> history,
                                                     ToolCallRouting.Binding binding, AgentAction.ToolCall read) {
        return new ToolCallRouting(context, List.of(binding), history.stream()
                .map(value -> new ToolCallRouting.Historical(value, binding, false)).toList(), List.of(read));
    }

    /** 新命令资格不允许在原浏览器调用未决时改参，也不重新派发完全相同的调用。 */
    @Test void unresolvedBrowserCommandRejectsChangedArgumentsAndConvergesExactOriginal() {
        var c=context(3);
        var binding=codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "browser_call", ToolCallIdentityPolicy.ARGUMENTS);
        var original=browserCommand("navigate","{\"url\":\"https://example.test/page\"}");
        for(var outcome:List.<AgentActionSnapshot.Outcome>of(new AgentActionSnapshot.Pending(),new AgentActionSnapshot.OutcomeUnknown(new ResultRef("unknown-browser-result")))) {
            var history=codeHistory(c,original,outcome);
            assertEquals(new Rejected(Rejection.INVALID_HISTORY),routedCode(browserCommand("click","{\"ref\":\"e1\"}"),c,history,binding));
            assertEquals(new Converge(history.getFirst()),routedCode(original,c,history,binding));
        }
    }

    /** 同名MCP默认句柄身份；平台明确冻结参数策略后仍要求NEVER与modelInvocable。 */
    @Test void browserParameterIdentityRejectsMcpAndRestrictedFrozenInvocation() {
        var c=context(3);var original=browserCommand("navigate","{\"url\":\"https://example.test/page\"}");
        var changed=browserCommand("screenshot","{}");var history=codeHistory(c,original,completed(1));
        var mcp=codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.MCP, "browser_call", ToolCallIdentityPolicy.HANDLE);
        assertFalse(mcp.parameterized());assertEquals(new Rejected(Rejection.INVALID_HISTORY),routedCode(changed,c,history,mcp));
        var platform=codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "browser_call", ToolCallIdentityPolicy.ARGUMENTS);
        var target=platform.target();
        for(boolean invocable:List.of(true,false)) {
            var restricted=new com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget(target.handle(),target.capability(),target.deploymentId(),target.deploymentRevision(),
                    target.inputSchema(),target.outputSchema(),target.effect(),target.idempotent(),target.timeout(),invocable,
                    invocable?com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.ALWAYS:com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.NEVER,
                    target.sensitiveFields(),target.providerRequirements(),target.outputSchemaDeclared(),target.callIdentityPolicy());
            var binding=new ToolCallRouting.Binding(restricted,platform.binding(),false);
            assertFalse(binding.parameterized());assertEquals(new Rejected(Rejection.INVALID_HISTORY),routedCode(changed,c,history,binding));
        }
    }

    /** 固定同一任务身份，仅官方命令及参数变化；不构造新的浏览器或Runtime状态。 */
    private static AgentAction.ToolCall browserCommand(String operation,String parameters) {
        return new AgentAction.ToolCall("tool:one",new TypedPayload("application/json",
                "{\"command\":{\"operation\":\""+operation+"\",\"parameters\":"+parameters+"},\"task\":\"b6cb7447-34e2-4da3-b7dc-ecc4a12917c1\"}"));
    }

    @Test void handlePolicyNeverGainsParameterIdentityFromPlatformOrMcpToolNames() {
        var c = context(3);
        var history = codeHistory(c, script("read attachments"), completed(1));
        for (var binding : List.of(
                codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "write_record", ToolCallIdentityPolicy.HANDLE),
                codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.MCP, "code_execute", ToolCallIdentityPolicy.HANDLE),
                codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.MCP, "workspace_exec", ToolCallIdentityPolicy.HANDLE),
                codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.MCP, "workspace_files", ToolCallIdentityPolicy.HANDLE))) {
            assertEquals(new Rejected(Rejection.INVALID_HISTORY), routedCode(script("write report"), c, history, binding));
        }
    }

    /** 相同效果、名称和提供方只有冻结策略不同；任意合法名称都可由可信发布显式声明参数身份。 */
    @Test void frozenPolicyAloneControlsParameterIdentityForAnyRegisteredName() {
        var c = context(3);
        var original = script("first operation");
        var changed = script("second operation");
        for (var provider : List.of(
                com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM,
                com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.MCP)) {
            var handle = codeBinding(provider, "registered_custom_tool", ToolCallIdentityPolicy.HANDLE);
            var arguments = codeBinding(provider, "registered_custom_tool", ToolCallIdentityPolicy.ARGUMENTS);
            var history = codeHistory(c, original, completed(1));
            assertEquals(handle.binding(), arguments.binding());
            assertEquals(handle.target().effect(), arguments.target().effect());
            assertFalse(handle.parameterized());
            assertTrue(arguments.parameterized());
            assertEquals(new Rejected(Rejection.INVALID_HISTORY), routedCode(changed, c, history, handle));
            assertEquals(new Selected(changed, List.of()), routedCode(changed, c, history, arguments));
            assertEquals(new Reuse(history.getFirst()), routedCode(original, c, history, arguments));
            for (var outcome : List.<AgentActionSnapshot.Outcome>of(new AgentActionSnapshot.Pending(),
                    new AgentActionSnapshot.OutcomeUnknown(new ResultRef("custom-unknown")))) {
                var unresolved = codeHistory(c, original, outcome);
                assertEquals(new Rejected(Rejection.INVALID_HISTORY), routedCode(changed, c, unresolved, arguments));
                assertEquals(new Converge(unresolved.getFirst()), routedCode(original, c, unresolved, arguments));
            }
        }
    }

    /** 当前发布策略不能替换原journal的完整目标及调用身份。 */
    @Test void historicalIdentityRetainsOriginalFrozenPolicy() {
        var c = context(3);
        var original = script("original operation");
        var action = codeHistory(c, original, completed(1)).getFirst();
        var handle = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM,
                "registered_custom_tool", ToolCallIdentityPolicy.HANDLE);
        var arguments = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM,
                "registered_custom_tool", ToolCallIdentityPolicy.ARGUMENTS);
        assertFalse(handle.sameTarget(arguments));
        var nowArguments = new ToolCallRouting(c, List.of(arguments),
                List.of(new ToolCallRouting.Historical(action, handle, false)));
        assertEquals(original.toolHandle(), nowArguments.identity(action));
        assertNotEquals(nowArguments.identity(original), nowArguments.identity(action));
        var nowHandle = new ToolCallRouting(c, List.of(handle),
                List.of(new ToolCallRouting.Historical(action, arguments, false)));
        assertEquals(original.toolHandle(), nowHandle.identity(original));
        assertNotEquals(nowHandle.identity(original), nowHandle.identity(action));
    }

    /** 平台执行入口不能以参数身份绕过冻结的模型可调用与确认策略。 */
    @Test void platformCodeAndWorkspaceParameterIdentityRequiresFrozenInvocationQualification() {
        var c = context(3);
        var history = codeHistory(c, script("read attachments"), completed(1));
        for (String name : List.of("code_execute", "workspace_exec", "workspace_files")) {
            var source = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, name, ToolCallIdentityPolicy.ARGUMENTS);
            var target = source.target();
            for (boolean modelInvocable : List.of(true, false)) {
                var restricted = new com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget(target.handle(), target.capability(),
                        target.deploymentId(), target.deploymentRevision(), target.inputSchema(), target.outputSchema(), target.effect(),
                        target.idempotent(), target.timeout(), modelInvocable,
                        modelInvocable ? com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.ALWAYS
                                : com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.NEVER,
                        target.sensitiveFields(), target.providerRequirements(), target.outputSchemaDeclared(), target.callIdentityPolicy());
                var binding = new ToolCallRouting.Binding(restricted, source.binding(), false);
                assertFalse(binding.parameterized(), name);
                assertEquals(new Rejected(Rejection.INVALID_HISTORY), routedCode(script("write report"), c, history, binding), name);
            }
        }
    }

    @Test void frozenIdempotentPlanCanCorrectKnownFailureAndReuseIdenticalArguments() {
        var c = context(3);
        var original = plan("report");
        var history = codeHistory(c, original, completed(1));
        for (boolean parallelSafe : List.of(false,true)) {
            var binding = idempotentPlanBinding(parallelSafe, com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.NEVER);
            assertInstanceOf(Selected.class, routedCode(plan("document"), c, history, binding));
            assertEquals(new Reuse(history.getFirst()), routedCode(original, c, history, binding));
            var unknown = codeHistory(c, original, new AgentActionSnapshot.OutcomeUnknown(new ResultRef("original-unknown")));
            assertEquals(new Rejected(Rejection.INVALID_HISTORY), routedCode(plan("document"), c, unknown, binding));
            assertEquals(new Converge(unknown.getFirst()), routedCode(original, c, unknown, binding));
            var routing = new ToolCallRouting(c,List.of(binding),List.of(new ToolCallRouting.Historical(history.getFirst(),binding,false)));
            assertInstanceOf(BudgetDenied.class,router().route(new AgentDecisionDecoder.Decoded(plan("document")),c,
                    BUDGET,usage(3,2,0),Duration.ZERO,history,ABSENT,routing));
        }
    }

    @Test void idempotentParameterIdentityStillRequiresFrozenNoConfirmationQualification() {
        var c = context(3);
        var history = codeHistory(c, plan("report"), completed(1));
        for (var binding : List.of(
                idempotentPlanBinding(false, com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.ALWAYS),
                idempotentPlanBinding(true, com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.ALWAYS))) {
            assertEquals(new Rejected(Rejection.INVALID_HISTORY), routedCode(plan("document"), c, history, binding));
        }
    }

    /** 把首轮错误分类修正为合法 document；不改写已冻结的原调用。 */
    private static AgentAction.ToolCall plan(String kind) {
        return new AgentAction.ToolCall("tool:one", new TypedPayload("application/json",
                "{\"deliverables\":[{\"formats\":[{\"decision_source\":\"platform_default\",\"format\":\"md\",\"required\":true}],"
                        + "\"key\":\"report\",\"kind\":\"" + kind + "\",\"title\":\"Report\"}]}"));
    }

    /** 模拟计划工具真实的冻结幂等写声明；不据模型名称推断效果资格。 */
    private static ToolCallRouting.Binding idempotentPlanBinding(boolean parallelSafe,
            com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy confirmation) {
        var original = codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM, "finalize_task_outputs", ToolCallIdentityPolicy.HANDLE);
        var source = original.target();
        var target = new com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget(source.handle(), source.capability(),
                source.deploymentId(), source.deploymentRevision(), source.inputSchema(), source.outputSchema(),
                ToolEffect.IDEMPOTENT_WRITE, true, source.timeout(), true, confirmation,
                source.sensitiveFields(), source.providerRequirements(), source.outputSchemaDeclared(), ToolCallIdentityPolicy.ARGUMENTS);
        return new ToolCallRouting.Binding(target, original.binding(), parallelSafe);
    }

    /** 效果与身份分别冻结；测试明确声明策略，不根据名称或提供方推断。 */
    private static ToolCallRouting.Binding codeBinding(com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType provider, String name,
            ToolCallIdentityPolicy callIdentityPolicy) {
        var target = new com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget(
                new com.skillforge.runtime.api.port.ToolPortal.ToolHandle("tool:one"),
                new CapabilityRef(CapabilityType.TOOL, "code", "v1"), "deployment", 1,
                new TypedPayload("json-schema", "{\"type\":\"object\"}"),
                new TypedPayload("json-schema", "{\"type\":\"object\"}"), ToolEffect.NON_IDEMPOTENT_WRITE, false,
                Duration.ofSeconds(3), true, com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy.NEVER,
                Set.of(), Set.of(), true, callIdentityPolicy);
        return new ToolCallRouting.Binding(target, new com.skillforge.runtime.api.port.ToolPortal.ToolBinding(provider, "platform_tools", name), false);
    }

    /** 参数变化代表新的脚本动作，不复用上一动作的持久 operation。 */
    private static AgentAction.ToolCall script(String code) {
        return new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", "{\"code\":\"" + code + "\"}"));
    }

    /** 采用真实 workspace_exec 参数结构，脚本挂载来源仍由宿主按冻结包核验。 */
    private static AgentAction.ToolCall workspaceCommand(String command) {
        return new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", CanonicalJson.encode(CanonicalJson.object(List.of(
                new CanonicalJson.Field("command", CanonicalJson.string(command)),
                new CanonicalJson.Field("skill_handle", CanonicalJson.string("skill:one")))))));
    }

    private static List<AgentActionSnapshot> codeHistory(FrozenContextSnapshot c, AgentAction.ToolCall call, AgentActionSnapshot.Outcome outcome) {
        return List.of(new AgentActionSnapshot(new ModelPreparationIdentity(c.operation().runIdentity(), c.operation().loopId(), 1),
                c.contextScope(), c.capabilitySnapshot(), call, Optional.of(new ResultRef("original-request")), outcome));
    }

    private static Result routedCode(AgentAction.ToolCall call, FrozenContextSnapshot c, List<AgentActionSnapshot> history, ToolCallRouting.Binding binding) {
        var routing = new ToolCallRouting(c, List.of(binding), history.stream().map(action -> new ToolCallRouting.Historical(action, binding, false)).toList());
        return router().route(new AgentDecisionDecoder.Decoded(call), c, BUDGET, usage(3, 1, 0), Duration.ZERO, history, ABSENT, routing);
    }

    @Test void prepareAnswerSelectsExactEvidenceWithoutExternalCallBudgetOrARepeatKey() {
        var c = context(3);
        var prepare = new AgentAction.Respond("回答", List.of("item"));
        var result = assertInstanceOf(Selected.class, route(prepare, c, usage(3, 2, 2), List.of(), ABSENT));
        assertEquals(prepare, result.action());
        assertEquals(List.of("item"), result.evidence().stream().map(EvidenceBinding::itemId).toList());
        assertEquals(new Rejected(Rejection.EVIDENCE_NOT_UNIQUE),
                route(new AgentAction.Respond("回答", List.of("invented")), c, usage(3, 0, 0), List.of(), ABSENT));
    }
    private static final CodecLimits LIMITS = new CodecLimits(20000, 3000, 32);
    private static final AgentDecisionDecoder DECODER = new AgentDecisionDecoder(LIMITS);
    private static final LoopBudget.Enforced BUDGET = new LoopBudget.Enforced(5, 2, 2, 100, 100, 150, Duration.ofSeconds(10));
    private static final DelegationSelection ABSENT = new DelegationSelection.Absent();
    private static final List<String> HANDLES = List.of("tool:one", "tool:two", "workflow:one", "knowledge:one", "skill:one", "shared");

    /** 真实 decoder 的七种动作都进入同一 router，不需要 reactive/planned 两套规则或旧 DTO。 */
    @Test
    void routesSevenDecodedActionsWithoutChangingUsage() {
        var c = context(3); var usage = usage(3, 0, 0); var original = usage;
        var configured = new DelegationSelection.Configured(c.operation(), List.of("agent:one"));
        var actions = List.<AgentAction>of(new AgentAction.Respond("answer", List.of("item")), new AgentAction.Dispatch("workflow:one"),
                new AgentAction.ToolCall("tool:one", new com.skillforge.runtime.api.TypedPayload("application/json", "{}")), new AgentAction.KnowledgeQuery("knowledge:one", "question"), new AgentAction.ActivateSkill("skill:one"),
                new AgentAction.AskUser("confirm?"), delegate("task"));
        for (var action : actions) {
            var decision = DECODER.decodeWire(DECODER.encode(action).canonicalJson());
            var result = assertInstanceOf(Selected.class, router().route(decision, c, BUDGET, usage, Duration.ZERO, List.of(), configured));
            assertEquals(action, result.action());
            assertEquals(action instanceof AgentAction.Respond ? 1 : 0, result.evidence().size());
            assertEquals(original, usage);
            assertEquals(result, router().route(decision, c, BUDGET, usage, Duration.ZERO, List.of(), configured));
        }
    }

    /** 处理第 maxBusinessModelCalls 个已消费结果仍可回应；只有下一次模型 DECISION 才因迭代上限拒绝。 */
    @Test
    void handlesCurrentResultAtIterationLimitAndRejectsUsageDrift() {
        var c = context(5); var action = new AgentAction.Respond("done", List.of());
        assertInstanceOf(Selected.class, route(action, c, usage(5, 0, 0), List.of(), ABSENT));
        assertEquals(Optional.of(BudgetGuard.Limit.BUSINESS_MODEL_CALL_BUDGET_EXCEEDED), BudgetGuard.evaluate(BUDGET, usage(5, 0, 0), BudgetGuard.Operation.BUSINESS_MODEL_CALL, Duration.ZERO));
        assertEquals(new Rejected(Rejection.INVALID_USAGE), route(action, c, usage(4, 0, 0), List.of(), ABSENT));
        // 上限可在运行中下调；已完成的模型回答仍可收敛，历史计数不是非法漂移。
        assertInstanceOf(Selected.class, route(action, context(6), usage(6, 0, 0), List.of(), ABSENT));
    }

    /** 已解码的最终回答不因产生该结果的输入 token 越线而被丢弃；新的工具调用仍要拒绝。 */
    @Test
    void acceptsRespondAfterInputTokenCapAndStillDeniesFreshToolCall() {
        var c = context(3);
        var over = tallied(100, 0);
        assertInstanceOf(Selected.class, route(new AgentAction.Respond("listed", List.of()), c, over, List.of(), ABSENT));
        assertInstanceOf(Selected.class, route(new AgentAction.AskUser("confirm?"), c, over, List.of(), ABSENT));
        assertEquals(new BudgetDenied(BudgetGuard.Limit.INPUT_TOKEN_BUDGET_EXCEEDED),
                route(new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", "{}")),
                        c, over, List.of(), ABSENT));
        assertEquals(new BudgetDenied(BudgetGuard.Limit.INPUT_TOKEN_BUDGET_EXCEEDED),
                router().route(DECODER.decodeWire("invalid"), c, BUDGET, over, Duration.ZERO, List.of(), ABSENT));
    }

    /** 公共限制在解码反馈、拒绝和历史复用之前；缺报 token 下界也必须接受相同限制。 */
    @Test
    void enforcesCommonBudgetBeforeFeedbackOrReuse() {
        var c = context(3); var action = new AgentAction.ToolCall("tool:one", new com.skillforge.runtime.api.TypedPayload("application/json", "{}"));
        var entry = entry(c, 1, action, completed(1));
        var usages = List.of(tallied(100, 0), tallied(0, 100), tallied(80, 70));
        var reasons = List.of(BudgetGuard.Limit.INPUT_TOKEN_BUDGET_EXCEEDED, BudgetGuard.Limit.OUTPUT_TOKEN_BUDGET_EXCEEDED, BudgetGuard.Limit.TOTAL_TOKEN_BUDGET_EXCEEDED);
        for (int i = 0; i < usages.size(); i++) {
            for (var decision : List.of(DECODER.decodeWire("invalid"), new AgentDecisionDecoder.Decoded(action))) {
                assertEquals(new BudgetDenied(reasons.get(i)), router().route(decision, c, BUDGET, usages.get(i), Duration.ZERO, List.of(entry), ABSENT));
            }
        }
        assertEquals(new BudgetDenied(BudgetGuard.Limit.WALL_TIME_BUDGET_EXCEEDED), router().route(DECODER.decodeWire("invalid"), c, BUDGET,
                usages.getFirst(), BUDGET.maxWallTime(), forbiddenHistory(), ABSENT));
        assertEquals(new DecodeFeedback(AgentDecisionDecoder.Reason.INVALID_DECISION), router().route(DECODER.decodeWire("invalid"), c, BUDGET,
                usage(3, 0, 0), Duration.ZERO, forbiddenHistory(), ABSENT));
    }

    /** 同 scope 整段历史中的 Tool 复用完整原快照，不因中间其他动作而重选目标或请求。 */
    @Test
    void reusesNonConsecutiveToolFromItsOriginalFrozenSnapshot() {
        var c = context(3); var first = entry(c, 1, new AgentAction.ToolCall("tool:one", new com.skillforge.runtime.api.TypedPayload("application/json", "{}")), completed(1));
        var second = entry(c, 2, new AgentAction.Dispatch("workflow:one"), failed(2));
        assertSame(first, assertInstanceOf(Reuse.class,
                route(first.action(), c, usage(3, 1, 0), List.of(first, second), ABSENT)).original());
        assertEquals(new Rejected(Rejection.CAPABILITY_NOT_VISIBLE), route(new AgentAction.ToolCall("missing", new com.skillforge.runtime.api.TypedPayload("application/json", "{}")), c, usage(3, 0, 0), List.of(first), ABSENT));
    }

    /** 参数按规范 JSON 比较；同 scope/handle 改变参数是冻结请求冲突，不能视为 fresh 调用。 */
    @Test
    void reusesCanonicalArgumentsAndRejectsChangedArgumentsOnTheSameToolHandle() {
        var context = context(3);
        var firstArguments = DECODER.decodeToolArgumentsDocument(
                new TypedPayload("application/json", "{ \"query\":\"first\", \"page\":1 }"));
        var equivalentArguments = DECODER.decodeToolArgumentsDocument(
                new TypedPayload("application/json", "{\"page\":1,\"query\":\"first\"}"));
        var secondArguments = new TypedPayload("application/json", "{\"query\":\"second\"}");
        var first = entry(context, 1, new AgentAction.ToolCall("tool:one", firstArguments), completed(1));

        assertSame(first, assertInstanceOf(Reuse.class,
                route(new AgentAction.ToolCall("tool:one", equivalentArguments), context,
                        usage(3, 1, 0), List.of(first), ABSENT)).original());
        assertEquals(new Rejected(Rejection.INVALID_HISTORY),
                route(new AgentAction.ToolCall("tool:one", secondArguments), context,
                        usage(3, 1, 0), List.of(first), ABSENT));
        assertInstanceOf(Selected.class, route(new AgentAction.ToolCall("tool:two", secondArguments), context,
                usage(3, 1, 0), List.of(first), ABSENT));
    }

    /** B09 的追加说明可以变化；真实 decoder 分离的 commentary 不改变原 Tool 执行参数或失败引用。 */
    @Test
    void reusesFailedToolWhenOnlyModelCommentaryChanges() {
        var c = context(3);
        var firstDecision = assertInstanceOf(AgentDecisionDecoder.Decoded.class, DECODER.decodeWire(
                "{\"type\":\"tool_call\",\"tool_handle\":\"tool:one\",\"arguments\":{\"case_key\":\"owned\"},\"commentary\":\"首次查询\"}"));
        var repeatedDecision = assertInstanceOf(AgentDecisionDecoder.Decoded.class, DECODER.decodeWire(
                "{\"type\":\"tool_call\",\"tool_handle\":\"tool:one\",\"arguments\":{\"case_key\":\"owned\"},\"commentary\":\"工具不可用，再次查询\"}"));
        assertNotEquals(firstDecision.commentary(), repeatedDecision.commentary());
        var original = entry(c, 1, firstDecision.action(), failed(1));
        var result = router().route(repeatedDecision, c, BUDGET, usage(3, 2, 0), Duration.ZERO,
                List.of(original), ABSENT);
        assertSame(original, assertInstanceOf(Reuse.class, result).original());
    }

    /** 成功、确定失败保留原结果；pending/unknown 等只收敛原请求，能力计数到顶不授予新调用。 */
    @Test
    void reusesKnownToolResultsAndConvergesPendingOrUnknownBeforeFreshCallLimits() {
        var c = context(3);
        var action = new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", "{}"));
        var usage = usage(3, 2, 2);
        for (var outcome : List.<AgentActionSnapshot.Outcome>of(completed(1), failed(1),
                new AgentActionSnapshot.Rejected(new ResultRef("result-1"), new EventId("result-1")),
                new AgentActionSnapshot.Cancelled(new ResultRef("result-1"), new EventId("result-1")),
                new AgentActionSnapshot.Accepted(Optional.of(new ResultRef("pending-1"))),
                new AgentActionSnapshot.Pending(), new AgentActionSnapshot.OutcomeUnknown(new ResultRef("fact-1")))) {
            var original = entry(c, 1, action, outcome);
            var result = route(action, c, usage, List.of(original), ABSENT);
            if (outcome.resolved()) { assertSame(original, assertInstanceOf(Reuse.class, result).original()); }
            else { assertSame(original, assertInstanceOf(Converge.class, result).original()); }
            assertEquals(usage(3, 2, 2), usage);
        }
    }

    /** 复用命中不能掩盖之后的损坏条目；同句柄重复登记或当前 iteration 更换执行参数都失败关闭。 */
    @Test
    void rejectsDuplicateToolHandlesAndFrozenActionConflictsAcrossTheWholeHistory() {
        var c = context(3);
        var action = new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", "{}"));
        var changed = new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", "{\"page\":2}"));
        var first = entry(c, 1, action, failed(1));
        for (var duplicate : List.of(entry(c, 2, action, completed(2)), entry(c, 2, changed, completed(2)))) {
            assertEquals(new Rejected(Rejection.INVALID_HISTORY),
                    route(action, c, usage(3, 1, 0), List.of(first, duplicate), ABSENT));
        }
        var future = entry(c, 4, new AgentAction.ToolCall("tool:two", new TypedPayload("application/json", "{}")), completed(4));
        assertEquals(new Rejected(Rejection.INVALID_HISTORY),
                route(action, c, usage(3, 1, 0), List.of(first, future), ABSENT));
        assertEquals(new Rejected(Rejection.INVALID_HISTORY),
                route(changed, c, usage(3, 1, 0), List.of(entry(c, 3, action, failed(3))), ABSENT));
    }

    /** 新 Run/输入、Loop 或能力快照使用自己的合法历史，不被相同句柄的另一个 scope 全局去重。 */
    @Test
    void selectsFreshToolInIndependentScopesAndRejectsForeignHistory() {
        var c = context(3);
        var action = new AgentAction.ToolCall("tool:one", new TypedPayload("application/json", "{}"));
        var original = entry(c, 1, action, failed(1));
        var run = c.operation().runIdentity();
        var snapshot = c.capabilitySnapshot().orElseThrow();
        var nextRun = new RunIdentity(run.owner(), run.owner().target().requireAgent(), run.causationInputId(), new RunRef(run.runRef().tenantId(), "next-run"), run.trace(), run.causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(run.owner().requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(run.owner().sessionRef().tenantId(), run.causationInputId().value()));
        var nextInput = new RunIdentity(run.owner(), run.owner().target().requireAgent(), new InputId("next-input"), new RunRef(run.runRef().tenantId(), "next-input-run"), run.trace(), run.causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(run.owner().requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(run.owner().sessionRef().tenantId(), new InputId("next-input").value()));
        var independent = List.of(scope(c, nextRun, c.operation().loopId(), snapshot),
                scope(c, nextInput, c.operation().loopId(),
                        new CapabilitySnapshotRef(snapshot.sessionRef(), nextInput.causationInputId(), "next-input-snapshot", 1)),
                scope(c, run, "next-loop", snapshot),
                scope(c, run, c.operation().loopId(), new CapabilitySnapshotRef(snapshot.sessionRef(),
                        snapshot.inputId(), snapshot.snapshotId(), snapshot.generation() + 1)));
        var router = router();
        assertSame(original, assertInstanceOf(Reuse.class, router.route(new AgentDecisionDecoder.Decoded(action),
                c, BUDGET, usage(3, 1, 0), Duration.ZERO, List.of(original), ABSENT)).original());
        for (var next : independent) {
            assertInstanceOf(Selected.class, router.route(new AgentDecisionDecoder.Decoded(action), next,
                    BUDGET, usage(3, 1, 0), Duration.ZERO, List.of(), ABSENT));
            var ownHistory = entry(next, 1,
                    new AgentAction.ToolCall("tool:two", new TypedPayload("application/json", "{}")), completed(1));
            assertInstanceOf(Selected.class, router.route(new AgentDecisionDecoder.Decoded(action), next,
                    BUDGET, usage(3, 1, 0), Duration.ZERO, List.of(ownHistory), ABSENT));
            assertEquals(new Rejected(Rejection.INVALID_HISTORY), router.route(new AgentDecisionDecoder.Decoded(action),
                    next, BUDGET, usage(3, 1, 0), Duration.ZERO, List.of(original), ABSENT));
        }
    }

    /** 种类参与重复键；冻结可见性只是选择检查，具体目标种类仍由后续 Portal 准入核验。 */
    @Test
    void differentKnowledgeQueryCannotReusePreviousEvidence() {
        var c = context(3);
        var first = new AgentAction.KnowledgeQuery("knowledge:one", "预算");
        var previous = entry(c, 1, first, completed(1));
        assertInstanceOf(Reuse.class, route(first, c, usage(3, 0, 1), List.of(previous), ABSENT));
        assertInstanceOf(Selected.class, route(new AgentAction.KnowledgeQuery("knowledge:one", "截止日期"),
                c, usage(3, 0, 1), List.of(previous), ABSENT));
    }

    @Test
    void distinguishesSameHandleAcrossKinds() {
        var c = context(3); var original = entry(c, 1, new AgentAction.ToolCall("shared", new com.skillforge.runtime.api.TypedPayload("application/json", "{}")), completed(1));
        assertInstanceOf(Selected.class, route(new AgentAction.Dispatch("shared"), c, usage(3, 1, 0), List.of(original), ABSENT));
        assertInstanceOf(Selected.class, route(new AgentAction.KnowledgeQuery("shared", "question"), c, usage(3, 1, 0), List.of(original), ABSENT));
    }

    /** 确定态回用、未确定态收敛；两者达到能力次数上限仍不创建新调用或 child。 */
    @Test
    void resolvesEverySnapshotOutcomeBeforeFreshCountLimits() {
        var c = context(3);
        for (var action : List.<AgentAction>of(new AgentAction.Dispatch("workflow:one"),
                new AgentAction.KnowledgeQuery("knowledge:one", "question"))) {
            for (var outcome : outcomes(1)) {
                var entry = entry(c, 1, action, outcome);
                var result = route(action, c, usage(3, 2, 2), List.of(entry), ABSENT);
                if (outcome.resolved()) { assertSame(entry, assertInstanceOf(Reuse.class, result).original()); }
                else { assertSame(entry, assertInstanceOf(Converge.class, result).original()); }
            }
        }
        assertEquals(new BudgetDenied(BudgetGuard.Limit.TOOL_CALL_BUDGET_EXCEEDED), route(new AgentAction.ToolCall("tool:one", new com.skillforge.runtime.api.TypedPayload("application/json", "{}")), c, usage(3, 2, 0), List.of(), ABSENT));
        assertEquals(new BudgetDenied(BudgetGuard.Limit.TOOL_CALL_BUDGET_EXCEEDED), route(new AgentAction.Dispatch("workflow:one"), c, usage(3, 2, 0), List.of(), ABSENT));
        assertEquals(new BudgetDenied(BudgetGuard.Limit.KNOWLEDGE_CALL_BUDGET_EXCEEDED), route(new AgentAction.KnowledgeQuery("knowledge:one", "question"), c, usage(3, 0, 2), List.of(), ABSENT));
    }

    /** 无跨轮重复键的本地动作同样遵守每 iteration 一个动作，不能在恢复后重新生成 fresh 候选。 */
    @Test
    void reusesOrConvergesCurrentIterationLocalActions() {
        var c = context(3);
        for (var action : List.<AgentAction>of(new AgentAction.Respond("answer", List.of()),
                new AgentAction.ActivateSkill("skill:one"), new AgentAction.AskUser("question"))) {
            var pending = localEntry(c, 3, action, new AgentActionSnapshot.Pending());
            assertSame(pending, assertInstanceOf(Converge.class,
                    route(action, c, usage(3, 0, 0), List.of(pending), ABSENT)).original());
            var done = localEntry(c, 3, action, completed(3));
            assertSame(done, assertInstanceOf(Reuse.class,
                    route(action, c, usage(3, 0, 0), List.of(done), ABSENT)).original());
        }
    }

    /** 不相关的损坏历史也失败关闭；不能过滤跨 Run/Loop/快照/未来条目后声称是新请求。 */
    @Test
    void rejectsAllHistoryIdentityDrift() {
        var c = context(3); var action = new AgentAction.ToolCall("tool:one", new com.skillforge.runtime.api.TypedPayload("application/json", "{}")); var first = entry(c, 1, action, new AgentActionSnapshot.Pending());
        var run = first.operation().runIdentity();
        var alternateRuns = List.of(new RunIdentity(run.owner(), run.owner().target().requireAgent(), run.causationInputId(), new RunRef(run.runRef().tenantId(), "other-run"), run.trace(), run.causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(run.owner().requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(run.owner().sessionRef().tenantId(), run.causationInputId().value())),
                new RunIdentity(run.owner(), run.owner().target().requireAgent(), new InputId("other-input"), run.runRef(), run.trace(), run.causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(run.owner().requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(run.owner().sessionRef().tenantId(), new InputId("other-input").value())),
                new RunIdentity(run.owner(), run.owner().target().requireAgent(), run.causationInputId(), run.runRef(), run.trace(), new ResultRef("other-cause"), new com.skillforge.runtime.api.RunRequester.EndUser(run.owner().requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(run.owner().sessionRef().tenantId(), run.causationInputId().value())),
                new RunIdentity(run.owner(), run.owner().target().requireAgent(), run.causationInputId(), run.runRef(), new TraceEnvelope("other-trace", "span", run.trace().rootInputId(), Optional.empty()), run.causationIdentityRef(), new com.skillforge.runtime.api.RunRequester.EndUser(run.owner().requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(run.owner().sessionRef().tenantId(), run.causationInputId().value())));
        var originalSnapshot = first.capabilitySnapshot().orElseThrow();
        for (var changed : alternateRuns) {
            var changedSnapshot = new CapabilitySnapshotRef(changed.owner().sessionRef(), changed.causationInputId(),
                    originalSnapshot.snapshotId(), originalSnapshot.generation());
            assertInvalidHistory(c, List.of(new AgentActionSnapshot(new ModelPreparationIdentity(changed, "loop", 1),
                    first.contextScope(), Optional.of(changedSnapshot), first.action(), first.requestJournalRef(), first.outcome())));
        }
        for (var changed : List.of(new ModelPreparationIdentity(run, "other-loop", 1), new ModelPreparationIdentity(run, "loop", 4))) {
            assertInvalidHistory(c, List.of(new AgentActionSnapshot(changed, first.contextScope(), first.capabilitySnapshot(), first.action(), first.requestJournalRef(), first.outcome())));
        }
        var snap = first.capabilitySnapshot().orElseThrow();
        for (var changed : List.of(new CapabilitySnapshotRef(snap.sessionRef(), snap.inputId(), snap.snapshotId(), snap.generation() + 1),
                new CapabilitySnapshotRef(snap.sessionRef(), snap.inputId(), "other-snapshot", snap.generation()))) {
            assertInvalidHistory(c, List.of(new AgentActionSnapshot(first.operation(), first.contextScope(), Optional.of(changed), first.action(), first.requestJournalRef(), first.outcome())));
        }
    }

    /** 同 key、同迭代、乱序或当前迭代换动作均是损坏；结果 presence 不能掩盖未知状态。 */
    @Test
    void rejectsDuplicateKeysIterationsCurrentDecisionConflictAndInvalidPresence() {
        var c = context(3); var first = entry(c, 1, new AgentAction.Dispatch("workflow:one"), completed(1));
        assertInvalidHistory(c, List.of(first, entry(c, 2, first.action(), failed(2))));
        assertInvalidHistory(c, List.of(first, entry(c, 1, new AgentAction.Dispatch("shared"), new AgentActionSnapshot.Pending())));
        assertInvalidHistory(c, List.of(entry(c, 2, new AgentAction.Dispatch("shared"),
                new AgentActionSnapshot.Pending()), first));
        assertInvalidHistory(c, List.of(entry(c, 3, first.action(), completed(3))));
        assertInstanceOf(Reuse.class, route(first.action(), c, usage(3, 1, 0), List.of(entry(c, 3, first.action(), completed(3))), ABSENT));
        assertThrows(IllegalArgumentException.class, () -> new AgentActionSnapshot(first.operation(), first.contextScope(),
                first.capabilitySnapshot(), first.action(), Optional.empty(), first.outcome()));
        assertThrows(IllegalArgumentException.class, () -> new AgentActionSnapshot(first.operation(), first.contextScope(),
                first.capabilitySnapshot(), new AgentAction.AskUser("ask"), first.requestJournalRef(), new AgentActionSnapshot.Pending()));
    }

    /** Respond 引用逐项唯一绑定并保序；未请求的另一个歧义 item 不应误伤正常回答。 */
    @Test
    void bindsEvidenceInOrderAndRejectsOnlyRequestedAmbiguity() {
        var c = context(3); var session = c.operation().runIdentity().owner().sessionRef();
        var first = new EvidenceRef(session, "first", List.of("a", "ambiguous"), 1, new EventId("e1"));
        var second = new EvidenceRef(session, "second", List.of("b", "ambiguous"), 2, new EventId("e2"));
        c = evidence(c, List.of(first, second));
        var selected = assertInstanceOf(Selected.class, route(new AgentAction.Respond("answer", List.of("b", "a")), c, usage(3, 0, 0), List.of(), ABSENT));
        assertEquals(List.of(new EvidenceBinding("b", second), new EvidenceBinding("a", first)), selected.evidence());
        for (String invalid : List.of("missing", "ambiguous")) {
            assertEquals(new Rejected(Rejection.EVIDENCE_NOT_UNIQUE), route(new AgentAction.Respond("answer", List.of(invalid)), c, usage(3, 0, 0), List.of(), ABSENT));
        }
    }

    /** 受限 Workflow 只做局部 Respond/AskUser，不补造 snapshot，不访问会抛错的 Agent 历史。 */
    @Test
    void restrictedWorkflowNeverTraversesAgentHistoryOrDelegationVisibility() {
        var c = context(3);
        c = new FrozenContextSnapshot(c.operation(), FrozenContextSnapshot.ContextScope.RESTRICTED_WORKFLOW,
                Optional.of(new ExecutionRef(c.operation().runIdentity().runRef().tenantId(), "execution")), Optional.empty(), Optional.empty(), Optional.empty(),
                c.blocks(), List.of(), List.of(), c.modelRequest(), c.budget(), c.viewVersion(), c.createdAt());
        var wrongView = new DelegationSelection.Configured(context(1).operation(), List.of());
        for (var action : List.<AgentAction>of(new AgentAction.ToolCall("tool:one", new com.skillforge.runtime.api.TypedPayload("application/json", "{}")), new AgentAction.Dispatch("workflow:one"), new AgentAction.KnowledgeQuery("knowledge:one", "question"),
                new AgentAction.ActivateSkill("skill:one"), delegate("task"))) {
            assertEquals(new Rejected(Rejection.RESTRICTED_SCOPE), route(action, c, usage(3, 0, 0), forbiddenHistory(), wrongView));
        }
        assertInstanceOf(Selected.class, route(new AgentAction.Respond("answer", List.of()), c, usage(3, 0, 0), forbiddenHistory(), wrongView));
        assertInstanceOf(Selected.class, route(new AgentAction.AskUser("ask"), c, usage(3, 0, 0), forbiddenHistory(), wrongView));
        assertEquals(new Rejected(Rejection.EVIDENCE_NOT_UNIQUE), route(new AgentAction.Respond("answer", List.of("item")), c, usage(3, 0, 0), forbiddenHistory(), wrongView));
    }

    /** 无实现的新请求只得到待审计候选；不要求目标可见、不解析目标数据、不计次数。 */
    @Test
    void absentDelegationRequiresAuditButDoesNotResolveTargetOrChargeCalls() {
        var c = context(3);
        var action = delegate("private-task");
        var candidate = assertInstanceOf(MissingDelegation.class, route(action, c, usage(3, 2, 2), List.of(), ABSENT));
        assertSame(action, candidate.action()); assertFalse(candidate.toString().contains("private-task"));
        var original = entry(c, 1, action, new AgentActionSnapshot.Rejected(new ResultRef("result-1"), new EventId("result-1")));
        assertInstanceOf(MissingDelegation.class, route(action, c, usage(3, 2, 2), List.of(original), ABSENT));
    }

    /** 已装配目标目录独立于 mount；只限制 START 的目标句柄，控制操作由 Session 委派协调器鉴权。 */
    @Test
    void configuredDelegationChecksIndependentTargetsAndOperationScope() {
        var c = context(3);
        var configured = new DelegationSelection.Configured(c.operation(), List.of("agent:one"));
        var action = delegate("task");
        assertFalse(c.capabilityHandles().contains(action.command().targetHandle()));
        assertInstanceOf(Selected.class, route(action, c, usage(3, 0, 0), List.of(), configured));
        assertEquals(new BudgetDenied(BudgetGuard.Limit.TOOL_CALL_BUDGET_EXCEEDED), route(action, c, usage(3, 2, 0), List.of(), configured));
        assertEquals(new Rejected(Rejection.DELEGATION_SCOPE_MISMATCH), route(action, c, usage(3, 0, 0), List.of(),
                new DelegationSelection.Configured(context(2).operation(), List.of("agent:one"))));
        assertEquals(new Rejected(Rejection.TARGET_NOT_VISIBLE), route(action, c, usage(3, 0, 0), List.of(),
                new DelegationSelection.Configured(c.operation(), List.of())));
        var get = new AgentAction.Delegate(new DelegationCommand(DelegationCommand.Operation.GET, "", Optional.empty(), List.of("delegation-1"), "", true));
        assertInstanceOf(Selected.class, route(get, c, usage(3, 0, 0), List.of(),
                new DelegationSelection.Configured(c.operation(), List.of())));

    }

    /** 完整委派命令不跨轮复用；同轮已登记动作仍按原快照收敛。 */
    @Test
    void delegationCommandsDoNotReuseAcrossIterationsAndConvergeWithinIteration() {
        var c = context(3); var originalAction = delegate("task");
        var previous = entry(c, 1, originalAction, new AgentActionSnapshot.Accepted(Optional.of(new ResultRef("pending-1"))));
        var configured = new DelegationSelection.Configured(c.operation(), List.of("agent:one"));
        assertInstanceOf(Selected.class, route(originalAction, c, usage(3, 1, 0), List.of(previous), configured));
        assertInstanceOf(Selected.class, route(delegate("different-task"), c, usage(3, 1, 0), List.of(previous), configured));
        var get = new AgentAction.Delegate(new DelegationCommand(DelegationCommand.Operation.GET, "", Optional.empty(), List.of("delegation-1"), "", true));
        assertInstanceOf(Selected.class, route(get, c, usage(3, 1, 0), List.of(previous), configured));
        var current = entry(c, 3, originalAction, new AgentActionSnapshot.Accepted(Optional.of(new ResultRef("pending-3"))));
        assertSame(current, assertInstanceOf(Converge.class, route(originalAction, c, usage(3, 2, 0), List.of(previous, current), configured)).original());
        assertEquals(new Rejected(Rejection.TARGET_NOT_VISIBLE), route(originalAction, c, usage(3, 1, 0), List.of(previous),
                new DelegationSelection.Configured(c.operation(), List.of())));
    }

    /** 可见性先于读取历史，无权选择能力时不会遍历损坏的历史；历史超长在复制/分配前拒绝。 */
    @Test
    void refusesInvisibleActionsAndOversizedHistoryBeforeTraversal() {
        var c = context(3);
        assertEquals(new Rejected(Rejection.CAPABILITY_NOT_VISIBLE), route(new AgentAction.ActivateSkill("hidden"), c, usage(3, 0, 0), forbiddenHistory(), ABSENT));
        var oversized = new AbstractList<AgentActionSnapshot>() {
            /** size 已足够证明状态无效，任何元素访问都是不必要读取。 */
            @Override public int size() {
                return Math.toIntExact((long) c.operation().iteration() * com.skillforge.runtime.api.ToolCallLimits.READ_ONLY_BATCH + 1);
            }
            /** router 不得先复制再检查长度。 */
            @Override public AgentActionSnapshot get(int index) { throw new AssertionError("oversized history traversed"); }
        };
        assertEquals(new Rejected(Rejection.INVALID_HISTORY), route(new AgentAction.AskUser("ask"), c, usage(3, 0, 0), oversized, ABSENT));
    }

    /** 统一创建无状态路由器，每次运行不共享缓存或记录“已经调用”的进程内事实。 */
    private static AgentDecisionRouter router() { return new AgentDecisionRouter(LIMITS); }
    /** 测试直接构造的类型化动作依然只形成候选，不模拟可消费的持久决策 ticket。 */
    private static Result route(AgentAction action, FrozenContextSnapshot c, LoopUsage usage, List<AgentActionSnapshot> history, DelegationSelection selection) {
        return router().route(new AgentDecisionDecoder.Decoded(action), c, BUDGET, usage, Duration.ZERO, history, selection);
    }
    /** 使用可见 AskUser 请求检查无关历史，让损坏条目不能因 key 不匹配被忽略。 */
    private static void assertInvalidHistory(FrozenContextSnapshot c, List<AgentActionSnapshot> history) {
        assertEquals(new Rejected(Rejection.INVALID_HISTORY), route(new AgentAction.AskUser("ask"), c, usage(3, 0, 0), history, ABSENT));
    }
    /** 构造最终 usage 的已知零 token，不在 router 内再执行 nextBusinessModelCall。 */
    private static LoopUsage usage(int iterations, int tools, int knowledge) {
        return new LoopUsage(iterations, tools, knowledge, new LoopUsage.TokenTally(0, true), new LoopUsage.TokenTally(0, true));
    }
    /** 缺报分量保留下界，证明 incomplete 不是跳过预算检查的理由。 */
    private static LoopUsage tallied(long input, long output) {
        return new LoopUsage(3, 0, 0, new LoopUsage.TokenTally(input, false), new LoopUsage.TokenTally(output, false));
    }
    /** 最小完整有来源上下文仅替换 iteration 与可见目录，不重写已有 codec 或模型夹具。 */
    private static FrozenContextSnapshot context(int iteration) {
        var c = ContextSnapshotCodecTest.fixture();
        return new FrozenContextSnapshot(new ModelPreparationIdentity(c.operation().runIdentity(), c.operation().loopId(), iteration), c.contextScope(),
                c.executionRef(), c.capabilitySnapshot(), c.persona(), c.relationship(), c.blocks(), c.evidenceRefs(), HANDLES, c.modelRequest(), c.budget(), c.viewVersion(), c.createdAt());
    }
    /** 只替换证据列表，用于有意构造同 itemId 多个来源的合法结构。 */
    private static FrozenContextSnapshot evidence(FrozenContextSnapshot c, List<EvidenceRef> evidence) {
        return new FrozenContextSnapshot(c.operation(), c.contextScope(), c.executionRef(), c.capabilitySnapshot(), c.persona(), c.relationship(),
                c.blocks(), evidence, c.capabilityHandles(), c.modelRequest(), c.budget(), c.viewVersion(), c.createdAt());
    }
    /** 使用显式独立身份与能力快照创建新合法 scope；历史来源不随之改写或过滤。 */
    private static FrozenContextSnapshot scope(FrozenContextSnapshot c, RunIdentity run, String loop,
                                               CapabilitySnapshotRef snapshot) {
        return new FrozenContextSnapshot(new ModelPreparationIdentity(run, loop, c.operation().iteration()), c.contextScope(),
                c.executionRef(), Optional.of(snapshot), c.persona(), c.relationship(), c.blocks(), c.evidenceRefs(),
                c.capabilityHandles(), c.modelRequest(), c.budget(), c.viewVersion(), c.createdAt());
    }
    /** 历史指向独立原请求 journal；实际引用存在由后续读取 Gate 证明，不在夹具伪装为已提交实体。 */
    private static AgentActionSnapshot entry(FrozenContextSnapshot c, int iteration, AgentAction action, AgentActionSnapshot.Outcome outcome) {
        return new AgentActionSnapshot(new ModelPreparationIdentity(c.operation().runIdentity(), c.operation().loopId(), iteration),
                c.contextScope(), c.capabilitySnapshot(), action, Optional.of(new ResultRef("journal-" + iteration)), outcome);
    }
    /** 本地动作没有请求 journal；同轮去重不能依赖外部动作的 journal 形状。 */
    private static AgentActionSnapshot localEntry(FrozenContextSnapshot c, int iteration, AgentAction action,
                                                   AgentActionSnapshot.Outcome outcome) {
        return new AgentActionSnapshot(new ModelPreparationIdentity(c.operation().runIdentity(), c.operation().loopId(), iteration),
                c.contextScope(), c.capabilitySnapshot(), action, Optional.empty(), outcome);
    }
    /** 结果引用与事件独立构造，不由 router 的分类逻辑生成预期。 */
    private static AgentActionSnapshot.Completed completed(int iteration) {
        return new AgentActionSnapshot.Completed(new ResultRef("result-" + iteration), new EventId("result-" + iteration));
    }
    /** Knowledge 允许无结果事件；此处显式保留事件验证普通外部动作历史。 */
    private static AgentActionSnapshot.Failed failed(int iteration) {
        return new AgentActionSnapshot.Failed(new ResultRef("result-" + iteration), Optional.of(new EventId("result-" + iteration)));
    }
    /** 覆盖全部封闭结果类别；路由只按 resolved 与需收敛分类，不合并状态。 */
    private static List<AgentActionSnapshot.Outcome> outcomes(int iteration) {
        return List.of(completed(iteration), failed(iteration),
                new AgentActionSnapshot.Rejected(new ResultRef("result-" + iteration), new EventId("result-" + iteration)),
                new AgentActionSnapshot.Cancelled(new ResultRef("result-" + iteration), new EventId("result-" + iteration)),
                new AgentActionSnapshot.Accepted(Optional.of(new ResultRef("pending-" + iteration))),
                new AgentActionSnapshot.Suspended("suspend-" + iteration, new ResultRef("result-" + iteration),
                        com.skillforge.runtime.api.LoopSuspendReason.WORKFLOW_HITL),
                new AgentActionSnapshot.Pending(), new AgentActionSnapshot.OutcomeUnknown(new ResultRef("fact-" + iteration)));
    }
    /** START 命令只携带目标目录句柄与业务任务，不允许模型注入预算或私有配置。 */
    private static AgentAction.Delegate delegate(String objective) {
        return new AgentAction.Delegate(DelegationCommand.start("agent:one",
                new DelegationCommand.Task(objective, "context", List.of(), "report")));
    }
    /** 对禁止历史访问的分支设置强断言，避免空列表夹具让意外遍历假绿。 */
    private static List<AgentActionSnapshot> forbiddenHistory() {
        return new AbstractList<>() {
            /** scope/visibility/预算拒绝不得读取大小。 */
            @Override public int size() { throw new AssertionError("history accessed"); }
            /** 同样不能读取任意条目。 */
            @Override public AgentActionSnapshot get(int index) { throw new AssertionError("history traversed"); }
        };
    }
}
