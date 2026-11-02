package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.session.spi.SessionPreparations;

/**
 * 灵知 宿主最小 Runtime 装配使用的稳定引用。
 *
 * <p>Chat 入口、准备配置登记和 IAM 查询共用这些常量，避免再发明第二套语义。</p>
 */
public final class AgentRuntimeHostContracts {

    static final String CHAT_INTEGRATION_ID = "chat";
    static final String MODEL_BINDING_ID = "skillforge.host.chat";
    static final ResultRef TOKENIZER_PROFILE =
            new ResultRef("skillforge.host.tokenizer.v1");
    static final ResultRef PREPARATION_PROFILE =
            new ResultRef("skillforge.host.preparation.v22");
    static final String PREPARATION_VERSION =
            SessionPreparations.PROFILE_VERSION;
    static final String PREPARATION_RENDERER = SessionPreparations.AGENT_INPUT_RENDERER_VERSION;
    static final String TOKENIZER_VERSION = "jtokkit:1.1.0";
    static final String TOKENIZER_ENCODING = "cl100k_base";
    static final String MODEL_STRATEGY_ID = "chat";
    static final String MODEL_STRATEGY_VERSION = "v1";
    static final String CALLBACK_RESULT_SCHEMA = "skillforge.host.callback.result.v1";
    static final String CALLBACK_INBOUND_SCHEMA = "callback.v1";
    public static final String AGENT_HITL_RESPONSE_SCHEMA = "skillforge.runtime.session.agent-hitl-response.v1";
    static final String WORKFLOW_HITL_RESPONSE_SCHEMA = "skillforge.runtime.session.workflow-hitl-response.v1";
    static final String MEMORY_PREFERENCE_TYPE = "preference";
    static final String MEMORY_EPISODE_TYPE = "episode";
    public static final String MEMORY_SCHEMA_VERSION = "v2";
    public static final String MEMORY_PREFERENCE_PAYLOAD = "skillforge.memory.preference.v2";
    public static final String MEMORY_EPISODE_PAYLOAD = "skillforge.memory.episode.v2";
    static final ResultRef MEMORY_ACCESS_POLICY = new ResultRef("skillforge.host.memory-access.v2");
    static final ResultRef MEMORY_SOURCE_POLICY = new ResultRef("skillforge.host.memory-source.v2");
    static final ResultRef ASYNC_FEEDBACK_POLICY = new ResultRef("skillforge.host.async-feedback.v1");
    static final String WORKFLOW_OUTPUT_SCHEMA = "skillforge.runtime.workflow.engine-result.v1";

    private AgentRuntimeHostContracts() {
    }
}
