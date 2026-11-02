package com.skillforge.core.model.invocation;

import org.springframework.stereotype.Service;

/** 所有 LLM 入口共用的边界策略链。 */
@Service
public class DefaultModelInvocationPolicyChain implements ModelInvocationPolicyChain {

    private static final int MAX_SYSTEM_PROMPT_CHARS = 200_000;
    private static final int MAX_USER_INPUT_CHARS = 200_000;

    @Override
    public ModelPolicyDecision evaluate(ModelInvocationPolicyInput input) {
        if (input == null) {
            return ModelPolicyDecision.deny("policy_input_missing");
        }
        if (input.systemPromptChars() > MAX_SYSTEM_PROMPT_CHARS) {
            return ModelPolicyDecision.deny("system_prompt_budget_exceeded");
        }
        if (input.userInputChars() > MAX_USER_INPUT_CHARS) {
            return ModelPolicyDecision.deny("user_input_budget_exceeded");
        }
        if (input.historySize() > 1000) {
            return ModelPolicyDecision.deny("history_budget_exceeded");
        }
        return ModelPolicyDecision.allow("model_invocation_boundary_ready");
    }
}
