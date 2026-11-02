package com.skillforge.systemagent.runtime;

import com.skillforge.runtime.api.ResultRef;

/** 平台通用系统智能体宿主的稳定内部身份和协议引用。 */
final class SystemAgentRuntimeContracts {
    static final String TENANT_ID = "900000000000000001";
    static final long END_USER_ID = 1L;
    static final String INTEGRATION_ID = "system-agents";
    static final String SYSTEM_PRINCIPAL_ID = "platform-system-agent";
    static final String CHANNEL = "system-agent";
    static final String SOURCE = "platform-debug";
    static final String PERSONA_VERSION = "system-agent-v1";
    static final String STRATEGY_ID = "platform-system-agent";
    static final String STRATEGY_VERSION = "v1";
    static final ResultRef ACTION_CALL_POLICY = new ResultRef("platform-system-agent.action-call.v1");

    private SystemAgentRuntimeContracts() {}
}
