package com.skillforge.core.model.invocation;

/** 所有 LLM 入口共用的调用前治理链。 */
public interface ModelInvocationPolicyChain {

    /** 在构造/发送模型请求前返回可审计决策。 */
    ModelPolicyDecision evaluate(ModelInvocationPolicyInput input);
}
