package com.skillforge.runtime.core;

import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.PlanPort;
import com.skillforge.runtime.api.port.DelegationCommand;
import java.util.*;

/** 计划前置与步骤执行的纯准入；可信工具效果只来自原冻结路由。 */
public final class PlanActionAdmission {
    private PlanActionAdmission() { }
    public static Optional<String> rejection(boolean required, PlanPort.State state, AgentAction action, ToolCallRouting tools) {
        Objects.requireNonNull(tools); return rejection(required, state, action, tools.current());
    }
    /** 新执行链只提供已加载冻结工具资格，不为计划门禁重建旧上下文或来源图。 */
    public static Optional<String> rejection(boolean required, PlanPort.State state, AgentAction action, List<ToolCallRouting.Binding> tools) {
        Objects.requireNonNull(state); Objects.requireNonNull(action); tools = List.copyOf(tools);
        boolean readOnly = action instanceof AgentAction.KnowledgeAccess || action instanceof AgentAction.ActivateSkill
            || action instanceof AgentAction.ReadSkillResource || action instanceof AgentAction.AskUser || action instanceof AgentAction.Plan;
        if(action instanceof AgentAction.Delegate d) readOnly=Set.of("TARGETS","LIST","GET","WAIT","CANCEL").contains(d.command().operation().name());
        if(action instanceof AgentAction.ToolCall t) readOnly=tools.stream().anyMatch(b->b.target().handle().value().equals(t.toolHandle())
            && b.target().effect()==ToolEffect.READ_ONLY && !Set.of("code_execute","code_execution","run_code").contains(b.binding().toolName()));
        if(required && !state.exists() && !readOnly) return Optional.of("plan_required_before_execution");
        boolean finalizeOutputs = action instanceof AgentAction.ToolCall call && tools.stream().anyMatch(binding ->
                binding.target().handle().value().equals(call.toolHandle())
                && binding.binding().providerType() == com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType.PLATFORM
                && binding.binding().toolName().equals("finalize_task_outputs"));
        // 成果冻结属于计划步骤之后的终结控制，不能先冻结再阻断尚待完成的步骤。
        if (finalizeOutputs && state.exists() && !state.inherited()) {
            return state.complete() && state.executionApproved() && state.activeTaskId().isEmpty()
                    ? Optional.empty() : Optional.of("plan_steps_not_complete_before_finalization");
        }
        if(state.exists() && !state.inherited() && action instanceof AgentAction.Respond && !state.complete()) return Optional.of("plan_steps_not_complete");
        if(state.exists() && action instanceof AgentAction.Delegate d && d.command().operation()==DelegationCommand.Operation.START) return Optional.of("delegate_must_use_plan_step");
        if(state.exists() && !state.inherited() && !readOnly && !(action instanceof AgentAction.Respond) && (!state.executionApproved() || state.activeTaskId().isEmpty()))
            return Optional.of("plan_step_not_started_or_approved");
        return Optional.empty();
    }
}
