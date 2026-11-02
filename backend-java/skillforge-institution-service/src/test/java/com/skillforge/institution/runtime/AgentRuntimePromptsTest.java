package com.skillforge.institution.runtime;

import com.skillforge.prompts.ChatPrompts;
import com.skillforge.prompts.PromptDefaults;
import com.skillforge.prompts.OutputPrompts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 完整请求模板中的主 system 正文按真实指令拼装校验，不将占位符说明发送给模型。 */
class AgentRuntimePromptsTest {
    @Test
    void defaultPolicyAndAgentPlaceholdersRenderReviewedSystemMessageSnapshot() throws IOException {
        // 与 HostProvisioner 的 Agent 配置拼接及 Materializer 的能力目录后缀保持一致。
        String agentInstructions = ChatPrompts.roleInstructions("{{agent.system_prompt}}", "{{agent.role_description}}");
        var prompt = ChatPrompts.prompt(PromptDefaults.AGENT_DECISION_POLICY, 1,
                "agent:published-fixture", agentInstructions);
        String systemMessage = prompt.render() + "\n\n可用能力：\n{{runtime.capabilities_json}}";
        try (var input = getClass().getResourceAsStream("/prompts/agent-runtime.expected.txt")) {
            assertNotNull(input, "提示词快照缺失");
            String template = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            String startMarker = "[messages.instructions.content]\n";
            String endMarker = "\n[/messages.instructions.content]";
            int start = template.indexOf(startMarker);
            int end = template.indexOf(endMarker);
            assertTrue(start >= 0 && end > start, "完整请求模板必须标记主 system 正文边界");
            assertEquals(systemMessage, template.substring(start + startMarker.length(), end));
            String outputStartMarker = "[messages.output_policy.preparation_instructions]\n";
            String outputEndMarker = "\n[/messages.output_policy.preparation_instructions]";
            int outputStart = template.indexOf(outputStartMarker);
            int outputEnd = template.indexOf(outputEndMarker);
            assertTrue(outputStart > end && outputEnd > outputStart, "成果指引应独立于首条 system");
            assertEquals(OutputPrompts.preparationHint(), template.substring(outputStart + outputStartMarker.length(), outputEnd));
            assertTrue(template.indexOf("{{run.output_policy_message_json}}") > template.indexOf("{{run.observation_messages_json}}"));
            assertTrue(template.indexOf("{{run.time_context_message_json}}") > template.indexOf("{{run.output_policy_message_json}}"));
        }
    }

    @Test
    void changingRoleOrPlatformPolicyKeepsRuntimeSectionsStableAndIsolated() {
        var first = ChatPrompts.prompt("平台规则 A", 2, "agent:1", "角色 A：仅输出 JSON 数组");
        var second = ChatPrompts.prompt("平台规则 B", 3, "agent:2", "角色 B：使用自然语言");
        var noRole = ChatPrompts.prompt("平台规则 A", 2, null, null);
        assertEquals(first.modules().getFirst(), second.modules().getFirst());
        assertEquals(first.modules().getFirst(), noRole.modules().getFirst());
        assertEquals(first.modules().subList(3, first.modules().size()),
                second.modules().subList(3, second.modules().size()));
        assertEquals(first.modules().subList(3, first.modules().size()),
                noRole.modules().subList(2, noRole.modules().size()));
        assertNotEquals(first.modules().get(2).sourceVersion(), second.modules().get(2).sourceVersion());
        assertFalse(second.render().contains("角色 A"));
        assertFalse(noRole.render().contains("角色 A"));
        assertThrows(UnsupportedOperationException.class, () -> first.modules().clear());
    }

    @Test
    void planPolicyIsIncludedOnlyWhenPlanRenderingIsRequested() {
        var prompt = ChatPrompts.prompt("平台规则", 1, "agent:1", "角色正文 {{agent.role_description}}");
        var plan=prompt.modules().stream().filter(module -> module.type()
                == com.skillforge.runtime.session.spi.SessionPrompt.Type.PLAN_POLICY).findFirst().orElseThrow();
        assertFalse(prompt.render().contains(plan.content()));
        assertTrue(prompt.render(true).contains(plan.content()));
        assertTrue(prompt.render(true).contains("{{agent.role_description}}"));
        assertTrue(plan.content().contains("不得修改业务数据、交付文件、源码或配置"));
        assertTrue(plan.content().contains("等待精确计划版本审批"));
        assertTrue(plan.content().contains("用户对澄清问题的回答不等于批准执行"));
        assertTrue(plan.content().contains("成员步骤由真实子 Run 投影终态"));
        assertFalse(plan.content().contains("expected_revision"));
    }

    @Test
    void finalAnswerKeepsBusinessAndPublicRulesWithoutExecutionGuidance() {
        var prompt = ChatPrompts.prompt(PromptDefaults.AGENT_DECISION_POLICY, 1,
                "agent:published-fixture", ChatPrompts.roleInstructions("业务正文", "公开角色简介"));
        String answer = prompt.selectedModules(false, java.util.Set.of()).stream()
                .map(com.skillforge.runtime.session.spi.SessionPrompt.Module::content)
                .collect(java.util.stream.Collectors.joining("\n\n")) + "\n\n" + OutputPrompts.finalAnswerHint();
        assertTrue(answer.contains("业务正文"));
        assertTrue(answer.contains("公开角色简介"));
        assertTrue(answer.contains("能够回答时直接输出 assistant 正文"));
        assertFalse(answer.contains("final_answer"));
        assertTrue(answer.contains("宿主成果卡负责展示"));
        assertFalse(answer.contains("PUBLIC_OUTPUT_REJECTED"));
        assertTrue(answer.contains("凭据、授权票据和宿主私有存储路径不得公开"));
        assertFalse(answer.contains("R-ACT-01"));
        assertFalse(answer.contains("plan_task_outputs"));
        assertFalse(answer.contains("finalize_task_outputs"));
    }
}
