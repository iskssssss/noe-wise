package com.skillforge.core.billing.export;

/** 系统用途的已受理 Run 与派发前冻结业务任务；事实修订不从当前策略重新归因。 */
public record PlatformUsageSystemSource(long systemAgentRunId, long agentId, String configurationRef,
        String invocationRef, String purpose, boolean debug, Long responsibilityAgentId, Long sourceAuthorId,
        String integrationId, Long groupBindingId, String originSessionId, String originEventId, String rootInputId, String businessScopeId) { }
