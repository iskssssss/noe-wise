package com.skillforge.runtime.session;

import com.skillforge.prompts.OutputPrompts;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.spi.SessionOutputContract.PreparationHint;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** HTTP 测试复用正式成果规则消息编码；仅测试源可访问包内构造，不增加生产 API。 */
public final class AgentExecutionOutputMessageFixture {
    private AgentExecutionOutputMessageFixture() { }

    /** 无成果义务的普通 Agent 请求仍包含宿主规则；实际位置由正式 messages 函数决定。 */
    public static Map<String, Object> wire(long ordinal) {
        var codec = new AgentExecutionCodec(new CodecLimits(262144, 4096, 32));
        var hint = new PreparationHint(OutputPrompts.preparationHint(), OutputPrompts.finalAnswerHint(),
                OutputPrompts.runContext("fixture-input", "absent", "[]"));
        var policy = new AgentExecutionOutputs.Policy(Optional.of(hint), Optional.empty(), Optional.empty(),
                Optional.empty(), new ExecutionBudgetPort.ClosingSpec(1, 0, 1, 0, List.of(), Set.of()), false);
        var directive = new AgentExecutionOutputs.Directive(policy, 0);
        var message = AgentExecutionOutputs.messages(List.of(), Optional.of(directive), ordinal, codec).getFirst();
        return Map.of("role", "system", "content", message.textContent());
    }
}
