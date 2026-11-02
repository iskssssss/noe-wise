package com.skillforge.runtime.starter;

import com.skillforge.runtime.api.TypedPayload;

/** 宿主 Provider 使用与 Runtime 相同的 schema 和容量限制，不直接依赖 Core 实现。 */
@FunctionalInterface
public interface AgentRuntimePayloadValidation {
    boolean accepts(TypedPayload payload);

    /** 动态发布契约必须另经 JSON Schema 校验；未装配实现时拒绝。 */
    default boolean accepts(TypedPayload schema,TypedPayload payload) {return false;}
}
