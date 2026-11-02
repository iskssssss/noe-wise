package com.skillforge.core.agent.systemagent;

import com.skillforge.core.model.invocation.LlmResult;
import com.skillforge.core.agent.systemagent.SystemAgentTypes.FrozenRun;

/** 通用系统 Agent 的宿主执行端口，由 Runtime 装配实现；不拥有另一套决策循环。 */
public interface SystemAgentRunner {
    /** 已确认 Runtime 失败或取消，允许宿主记录失败终态。 */
    final class TerminalFailure extends RuntimeException {
        public TerminalFailure(String message) { super(message); }
    }

    /** Runtime 仍在执行，宿主保留 RUNNING 并交给恢复协调。 */
    final class Pending extends RuntimeException {
        public Pending(String message) { super(message); }
    }

    LlmResult execute(FrozenRun run);

    /** 仅查询原 Runtime 的终态，恢复协调不能借此重复提交模型或工具。 */
    java.util.Optional<LlmResult> completed(FrozenRun run);
}
