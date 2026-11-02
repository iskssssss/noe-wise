package com.skillforge.runtime.api;

/** 表示接收输入并拥有 Session 的 Agent 身份。 */
public record AgentId(String value) {

    /** 拒绝缺失或空白的 Agent 身份。 */
    public AgentId {
        value = ApiContracts.requireText(value, "value");
    }
}
