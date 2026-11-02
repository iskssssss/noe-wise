package com.skillforge.core.model.invocation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 统一 LLM 调用策略链的边界测试。 */
class DefaultModelInvocationPolicyChainTest {

    private final DefaultModelInvocationPolicyChain chain = new DefaultModelInvocationPolicyChain();

    @Test
    void allowsAWellFormedMultiProviderInvocation() {
        ModelPolicyDecision decision = chain.evaluate(
                new ModelInvocationPolicyInput("event-1", "7", "openai_compatible", "gpt-4o",
                        "agent.summary", 20, 100, 200));

        assertEquals(ModelPolicyDecision.Outcome.ALLOW, decision.outcome());
        assertEquals("model_invocation_boundary_ready", decision.reasonCode());
    }

    @Test
    void deniesOversizedPromptsAndHistoryBeforeNetwork() {
        assertEquals("system_prompt_budget_exceeded", chain.evaluate(input(200_001, 1, 1)).reasonCode());
        assertEquals("user_input_budget_exceeded", chain.evaluate(input(1, 200_001, 1)).reasonCode());
        assertEquals("history_budget_exceeded", chain.evaluate(input(1, 1, 1001)).reasonCode());
    }

    @Test
    void deniesMissingPolicyInput() {
        assertEquals("policy_input_missing", chain.evaluate(null).reasonCode());
    }

    private ModelInvocationPolicyInput input(int systemChars, int userChars, int history) {
        return new ModelInvocationPolicyInput("event-1", "7", "dashscope", "qwen-max",
                "extract.prompt", history, systemChars, userChars);
    }
}
