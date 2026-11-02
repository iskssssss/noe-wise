package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.prompts.ChatPrompts;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Goal 驱动只提供输入和进程级开关，执行继续经过唯一 Runtime。 */
@Configuration(proxyBeanMethods = false)
public class AgentRuntimeGoalConfiguration {
    @Bean
    AgentRuntimeGoalDriver.RoundPrompt goalRoundPrompt(ObjectMapper json) {
        return (goal, input) -> {
            try {
                return ChatPrompts.goalRoundInstructions() + "\n<goal_round>\n" + json.writeValueAsString(Map.of(
                        "objective", goal.objective(), "round", goal.roundsStarted() + 1,
                        "max_rounds", goal.maxRounds(), "goal_id", goal.goalId(), "input_id", input.value()))
                        + "\n</goal_round>";
            } catch (com.fasterxml.jackson.core.JsonProcessingException failed) {
                throw new IllegalStateException("goal_prompt_invalid", failed);
            }
        };
    }
}
