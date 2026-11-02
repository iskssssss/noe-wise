package com.skillforge.platform.config;

import com.skillforge.core.agent.systemagent.SystemAgentExecution;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 平台内部调试结果回收，不扫描或执行机构生产任务。 */
@Component
public class SystemAgentRecovery {
    private final SystemAgentExecution execution;
    public SystemAgentRecovery(SystemAgentExecution execution) { this.execution=execution; }
    @Scheduled(fixedDelayString="${skillforge.system-agents.recovery-interval:30000}")
    public void recover() { execution.recoverDebugRuns(); }
}
