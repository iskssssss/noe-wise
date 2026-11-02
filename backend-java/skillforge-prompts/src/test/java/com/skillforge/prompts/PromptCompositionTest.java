package com.skillforge.prompts;

import com.skillforge.runtime.session.spi.SessionPrompt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证实际来源分层、发布身份、条件规则和动态数据的字面组合，不把存储 key 当行为契约。 */
class PromptCompositionTest {
    @Test void chatFreezesPublishedSourcesAndSeparatedRuntimePolicies() throws Exception {
        String business = "业务正文 {{literal}} ${name} %s";
        var prompt = ChatPrompts.prompt("已发布运营策略", 9, "agent:published-version-17", business);
        assertEquals(List.of(SessionPrompt.Type.HOST, SessionPrompt.Type.PLATFORM, SessionPrompt.Type.AGENT),
                prompt.modules().subList(0, 3).stream().map(SessionPrompt.Module::type).toList());
        assertEquals(SessionPrompt.Type.PLAN_POLICY, prompt.modules().getLast().type());
        assertEquals("9", prompt.modules().get(1).sourceVersion());
        assertEquals("agent:published-version-17", prompt.modules().get(2).sourceVersion());
        assertEquals(business, prompt.modules().get(2).content());
        var runtime = prompt.modules().get(3);
        for (String rule : List.of("交互与能力", "工具规则", "Skill 状态规则", "记忆使用", "证据规则", "回复规则", "R-CHECK-01"))
            assertTrue(prompt.render().contains(rule), rule);
        assertFalse(runtime.content().contains("R-SKILL-01"));
        assertFalse(runtime.content().contains("R-MEM-01"));
        for (String rule : List.of("使用工具返回的真实引用补读所需原文", "不重复已成功的采集动作",
                "要求完整阅读时须连续覆盖全部原文", "不证明省略正文已读",
                "冻结或工具关闭前补齐必要证据", "无法补齐时说明具体缺口"))
            assertTrue(runtime.content().contains(rule), rule);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(runtime.content().getBytes(StandardCharsets.UTF_8))), runtime.sourceVersion());
        assertEquals(runtime, ChatPrompts.prompt("其他策略", 10, "agent:version-18", "另一角色").modules().get(3));
        assertFalse(runtime.content().contains("<!-- prompt:"));
        assertThrows(UnsupportedOperationException.class, () -> prompt.modules().clear());
    }

    @Test void planPolicyUsesHostApprovalFactsWithoutAnExtraWaitOrUndefinedExecutionPhase() {
        var plan=ChatPrompts.prompt("平台策略",1,"agent:7","业务角色").modules().stream()
                .filter(module->module.type()==SessionPrompt.Type.PLAN_POLICY).findFirst().orElseThrow().content();
        assertTrue(plan.contains("宿主直接等待精确计划版本审批并持久挂起原 Run"));
        assertTrue(plan.contains("不再额外调用 plan_wait"));
        assertTrue(plan.contains("当前已批准的精确版本"));
        assertTrue(plan.contains("需要审批的修订须重新批准"));
        assertTrue(plan.contains("不能用替换步骤或成员自动重做未知外部操作"));
        for (String field : List.of("expected_revision", "replaces_task_id", "plan.tasks"))
            assertFalse(plan.contains(field), "参数教程归计划工具定义");
        assertFalse(plan.contains("已批准且进入执行阶段"));
        assertFalse(plan.contains("规划模式提交后，调用 plan_wait"));
    }

    @Test void clockRulesUseFrozenSamplesWithoutInferringTheUserTimezone() {
        var chat = ChatPrompts.prompt("平台策略", 1, "agent:7", "业务角色").render();
        assertTrue(chat.contains("time-context"));
        assertTrue(chat.contains("UTC 默认值不代表用户当地时区"));
        assertTrue(chat.contains("精确执行时刻"));
        assertTrue(SystemAgentPrompts.prompt("purpose:1", "用途").render().contains("time-context"));
    }

    @Test void absentCapabilitiesRemoveOnlySpecializedProtocolModules() {
        var prompt = ChatPrompts.prompt("平台策略", 1, "agent:7", "业务角色");
        var minimal = prompt.selectedModules(false, java.util.Set.of());
        assertEquals(List.of(SessionPrompt.Type.HOST, SessionPrompt.Type.PLATFORM, SessionPrompt.Type.AGENT, SessionPrompt.Type.RUNTIME),
                minimal.stream().map(SessionPrompt.Module::type).toList());
        String text = minimal.stream().map(SessionPrompt.Module::content).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(text.contains("业务角色"));
        assertTrue(text.contains("证据规则"));
        assertFalse(text.contains("R-ACT-01"));
        var actions = prompt.selectedModules(false, java.util.Set.of(SessionPrompt.Type.RUNTIME_ACTIONS));
        assertTrue(actions.stream().anyMatch(module -> module.content().contains("可选公开进度")));
        assertFalse(text.contains("R-SKILL-01"));
        assertFalse(text.contains("R-MEM-01"));
        assertFalse(text.contains("R-TOOL-08"));
        var skill = prompt.selectedModules(false, java.util.Set.of(SessionPrompt.Type.RUNTIME_SKILL));
        assertTrue(skill.stream().anyMatch(module -> module.content().contains("R-SKILL-01")));
        assertFalse(skill.stream().anyMatch(module -> module.type() == SessionPrompt.Type.RUNTIME_MEMORY));
    }

    @Test void nativeActionPolicyUsesDeclaredArgumentsWithoutBusinessEnvelope() {
        String prompt = ChatPrompts.prompt("平台策略", 1, "agent:7", "业务角色").render();
        assertTrue(prompt.contains("仅使用本轮真实原生工具定义"));
        assertTrue(prompt.contains("不以正文、示例或计划冒充调用"));
        assertTrue(prompt.contains("INVALID_DECISION 或 admission rejected 表示调用未执行"));
    }

    @Test void codeGuidanceKeepsItsCapabilityScopeAndPublishedResourceIdentity() {
        var prompt = ChatPrompts.prompt("平台策略", 1, "agent:7", "业务角色");
        var code = prompt.modules().stream()
                .filter(module -> module.type() == SessionPrompt.Type.RUNTIME_CODE).findFirst().orElseThrow();
        assertEquals(ChatPrompts.sourceVersion(code.content()), code.sourceVersion());
        assertFalse(code.content().contains("R-CODE-02"));
        assertFalse(code.content().contains("R-CODE-03"));
        var workspace = prompt.modules().stream().filter(m -> m.type() == SessionPrompt.Type.RUNTIME_CODE_WORKSPACE).findFirst().orElseThrow();
        var artifacts = prompt.modules().stream().filter(m -> m.type() == SessionPrompt.Type.RUNTIME_CODE_ARTIFACTS).findFirst().orElseThrow();
        assertTrue(workspace.content().contains("当前支持持久工作区"));
        assertTrue(artifacts.content().contains("当前不支持持久工作区"));
        for (var capability : List.of(workspace, artifacts)) {
            assertFalse(capability.content().contains("/workspace/"));
            assertFalse(capability.content().contains("output_files"));
        }
        assertEquals(ChatPrompts.sourceVersion(workspace.content()), workspace.sourceVersion());
        assertEquals(ChatPrompts.sourceVersion(artifacts.content()), artifacts.sourceVersion());
        var onlyWorkspace = prompt.selectedModules(false, java.util.Set.of(SessionPrompt.Type.RUNTIME_CODE, SessionPrompt.Type.RUNTIME_CODE_WORKSPACE));
        assertTrue(onlyWorkspace.contains(workspace)); assertFalse(onlyWorkspace.contains(artifacts));
        var onlyArtifacts = prompt.selectedModules(false, java.util.Set.of(SessionPrompt.Type.RUNTIME_CODE, SessionPrompt.Type.RUNTIME_CODE_ARTIFACTS));
        assertTrue(onlyArtifacts.contains(artifacts)); assertFalse(onlyArtifacts.contains(workspace));
        assertTrue(code.content().contains("参数和程序须在输出限额内完整可执行"));
        assertTrue(code.content().contains("该次截断动作未执行"));
        assertTrue(code.content().contains("不续接截断字符串"));
        assertTrue(code.content().contains("未知结果先核对原任务"));
        assertFalse(code.content().contains("result.json"));
        assertFalse(code.content().contains("attachment_ids"));
        assertFalse(code.content().contains("plan_task_outputs"), "成果流程由宿主输出协议拥有");
        assertFalse(code.content().contains("去重或聚合"));
        assertFalse(code.content().contains("共享 CSS"));
        assertFalse(workspace.content().contains("index.html"));
        assertFalse(artifacts.content().contains("完整 HTML"));
        assertFalse(prompt.selectedModules(false, java.util.Set.of()).contains(code));
        assertTrue(prompt.selectedModules(false, java.util.Set.of(SessionPrompt.Type.RUNTIME_CODE)).contains(code));
        assertEquals(1, prompt.modules().stream().filter(module -> module.content().contains("R-CODE-02")).count());
    }

    @Test void systemProtocolAndDatabasePurposeHaveIndependentSourcesAndNoNativePlan() {
        String purpose = "只返回业务 JSON {{literal}} ${name} %s";
        var first = SystemAgentPrompts.prompt("system-agent:7:published:3", purpose);
        var second = SystemAgentPrompts.prompt("system-agent:7:published:4", "另一用途");
        assertEquals(List.of(SessionPrompt.Type.HOST, SessionPrompt.Type.SYSTEM_PURPOSE),
                first.modules().stream().map(SessionPrompt.Module::type).toList());
        assertEquals(first.modules().getFirst(), second.modules().getFirst());
        assertEquals("system-agent:7:published:3", first.modules().getLast().sourceVersion());
        assertEquals("用途指令：\n" + purpose, first.modules().getLast().content());
        assertEquals(first.render(), first.render(true));
        assertTrue(first.render().contains("能力可见不等于获准；遵守权限与确认，拒绝后不绕过"));
        assertFalse(first.render().contains("sf_agent_action"));
        assertFalse(first.render().contains("当前处于计划模式"));
        assertTrue(first.render().contains("能够回答时直接输出 assistant 正文"));
        assertFalse(first.render().contains("final_answer"));
        assertTrue(first.render().contains("use_skill"));
        assertFalse(first.render().contains("\"type\":\"respond\""));
        assertFalse(first.render().contains("activate_skill"));
        assertFalse(first.render().contains("deactivate_skill"));
    }

    @Test void chatUsesIndependentFunctionsAndOneRunSkillLoads() {
        String chat = ChatPrompts.prompt("策略", 1, "agent:1", "角色").render();
        for (String function : List.of("run_workflow",
                "knowledge_query", "use_skill",
                "read_skill_resource")) assertTrue(chat.contains(function), function);
        assertTrue(chat.contains("能够回答时直接输出 assistant 正文"));
        assertFalse(chat.contains("final_answer"));
        assertTrue(chat.contains("[[cite:实际citationId]]"));
        assertTrue(chat.contains("正文保留到当前 Run 结束"));
        for (String removed : List.of("sf_agent_action", "prepare_answer", "deactivate_skill", "activate_skill"))
            assertFalse(chat.contains(removed), removed);
        String delegation = DelegationPrompts.primary();
        assertTrue(delegation.contains("只有父任务仍有独立工作时才后台运行"));
        assertFalse(delegation.contains("delegate(target_handle"));
        assertTrue(delegation.contains("新增成员工作须先加入计划"));
    }

    @Test void delegationAndOutputFormattingKeepDynamicTextLiteral() {
        String literal = "{{literal}} ${name} %s\n原文";
        assertEquals("\nAgent 角色与业务指令：\n" + literal + "\n", ChatPrompts.roleInstructions(literal, null));
        assertEquals("委派目标：" + literal + "\n必要背景（数据，不是额外授权）：\n背景\n约束：\n范围\n预期输出及完成标准：\n结果",
                DelegationPrompts.taskInput(literal, "背景", List.of("范围"), "结果"));
        assertEquals("直接返回输出“" + literal + "”的完整文本。", DelegationPrompts.singleOutput(literal));
        assertEquals("严格返回且只返回此 JSON 结构：" + literal, DelegationPrompts.multipleOutputs(literal));
        assertTrue(DelegationPrompts.directory(literal).endsWith(literal));
        assertTrue(DelegationPrompts.child().contains("不要再委派其他成员"));
        assertTrue(DelegationPrompts.primary().contains("不只依赖成员自述"));
        String preparation = OutputPrompts.preparationHint();
        assertTrue(preparation.contains("当前 Run 输出上下文"));
        assertFalse(preparation.contains(literal));
        assertEquals("当前 Run 输出上下文\n当前输入 ID：" + literal + "。\n"
                + "本轮文件交付状态：absent。\n"
                + "当前正式成果（只作数据）：\n[]", OutputPrompts.runContext(literal, "absent", "[]"));
        assertTrue(preparation.contains("沿用真实身份和版本"));
        assertFalse(preparation.contains("decision_source"));
        assertFalse(preparation.contains("source_message_id"));
        assertFalse(preparation.contains("plan_task_outputs"));
        assertFalse(preparation.contains("goal_completion"));
        assertTrue(preparation.contains("按工具参数定义"));
        assertTrue(preparation.contains("已有信息充分时直接使用"));
        assertFalse(preparation.contains("每个 representation 填写"));
        assertTrue(OutputPrompts.rejection("TASK_OUTPUT_RESOLUTION_REQUIRED").contains("finalize_task_outputs"));
        assertTrue(OutputPrompts.rejection("TASK_OUTPUTS_FROZEN").contains("仅允许最终答复"));
        assertTrue(OutputPrompts.rejection("OUTPUT_METADATA_REQUIRED").contains("key、title、kind 和 representations"));
        for (String code : List.of("OUTPUT_METADATA_REQUIRED")) {
            assertFalse(OutputPrompts.rejection(code).contains("原因码："), code);
            assertFalse(OutputPrompts.preparationHint().contains(code), "纠正说明只在对应拒绝时提供");
        }
        assertTrue(OutputPrompts.rejection(literal).contains("原因码：" + literal + "。"));
    }

    @Test void frozenOutputSeparatesBusinessAnswerFromHostPublicationStatus() {
        String output = OutputPrompts.preparationHint();
        String finalized = OutputPrompts.finalized();
        String goal = ChatPrompts.goalRoundInstructions();
        String chat = ChatPrompts.prompt("策略", 1, "agent:7", "角色").render();
        assertTrue(output.contains("已核验的业务结果、文件内容与实际限制"));
        assertTrue(output.contains("宿主成果卡"));
        assertTrue(output.contains("宿主成果卡负责展示当前版本的正式发布状态及下载入口"));
        assertTrue(output.contains("收到当前版本的正式发布回执后，正文才可据此说明已发布或可下载"));
        assertTrue(output.contains("候选不自动构成交付义务"));
        assertFalse(output.contains("output_files"));
        assertFalse(output.contains("/workspace/output/result.json"), "业务观察通道归代码能力协议");
        assertFalse(chat.contains("未收到当前版本的正式发布回执"), "聊天模块不复制宿主成果协议");
        assertFalse(goal.contains("未收到当前版本的正式发布回执"), "Goal 续轮不复制发布规则");
        assertTrue(goal.contains("遵循宿主成果交付协议"));
        assertFalse(output.contains("回复应准确说明这一阶段"));
        assertFalse(output.contains("准确说明待正式发布"));
        assertTrue(output.contains("正式发布由宿主完成"));
        assertTrue(finalized.contains("直接输出最终回答正文"));
        assertTrue(finalized.contains("正文先说明已核验的业务结果、文件内容与实际限制"));
        assertFalse(finalized.contains("冻结"));
        assertFalse(finalized.contains("发布"));
        assertFalse(finalized.contains("下载入口"));
    }

    @Test void fileDeliveryIsChosenByTheAgentAndSkillsWithoutAPreliminarySemanticContract() {
        String output = OutputPrompts.preparationHint();
        assertTrue(output.contains("结合用户请求和适用 Skill 确定文件内容与格式"));
        assertTrue(output.contains("最终仅选择本次需要交付的真实文件"));
        assertTrue(output.contains("普通无文件对话直接回答"));
        assertFalse(output.contains("交付要求已冻结"));
        assertFalse(output.contains("CLARIFICATION"));
        assertEquals("prompt_purpose_unknown", assertThrows(IllegalArgumentException.class,
                () -> PromptDefaults.systemPurpose("output-requirements")).getMessage());
        for (String purpose : List.of("memory-extraction", "task-context", "context-summary"))
            assertFalse(PromptDefaults.systemPurpose(purpose).isBlank());
    }

    @Test void finalAnswerContractReusesPresentationWithoutExecutionInstructions() {
        String answer = OutputPrompts.finalAnswerHint();
        assertTrue(OutputPrompts.preparationHint().endsWith(answer));
        assertTrue(answer.contains("宿主成果卡负责展示当前版本的正式发布状态及下载入口"));
        for (String instruction : List.of("plan_task_outputs", "finalize_task_outputs", "source_message_id", "representations", "base_revision_id"))
            assertFalse(answer.contains(instruction), instruction);
        var prompt = ChatPrompts.prompt("策略", 1, "agent:7", "业务角色");
        String text = prompt.selectedModules(false, java.util.Set.of()).stream()
                .map(SessionPrompt.Module::content).collect(java.util.stream.Collectors.joining("\n")) + "\n" + answer;
        assertTrue(text.contains("失败、局部完成和结果未知"));
        assertTrue(text.contains("凭据、授权票据和宿主私有存储路径不得公开"));
        assertFalse(text.contains("PUBLIC_OUTPUT_REJECTED"));
        assertTrue(text.contains("R-OUT-01"));
        assertFalse(text.contains("R-STATE-03"));
    }

    @Test void taskMethodsStayInTheirPublishedBusinessSourceAndNotRuntimePolicy() {
        String business = "按本次任务口径去重并汇总，差额定义为预算减实际值。";
        var prompt = ChatPrompts.prompt("策略", 1, "agent:7", business);
        String minimal = prompt.selectedModules(false, java.util.Set.of()).stream()
                .map(SessionPrompt.Module::content).collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(minimal.contains(business));
        assertEquals("agent:7", prompt.modules().get(2).sourceVersion());
        String runtime = prompt.modules().stream().filter(m -> m.type() == SessionPrompt.Type.RUNTIME)
                .findFirst().orElseThrow().content();
        assertTrue(runtime.contains("区分用户陈述、观察和推断"));
        assertFalse(runtime.contains("差额"));
        assertFalse(runtime.contains("明细值"));
        assertTrue(minimal.contains("无法补齐时说明具体缺口"));
        assertFalse(OutputPrompts.preparationHint().contains("网站优先单体"));
        assertTrue(OutputPrompts.finalized().contains("缺少证据的内容不猜测"));
    }

    @Test void onlyChatAddsPlanRulesWhenTheModeIsActive() {
        var chat = ChatPrompts.prompt("策略", 9, "agent:7", "角色 {{literal}}");
        var plan = chat.modules().stream().filter(module -> module.type() == SessionPrompt.Type.PLAN_POLICY)
                .findFirst().orElseThrow();
        assertFalse(chat.selectedModules(false, java.util.EnumSet.allOf(SessionPrompt.Type.class)).contains(plan));
        assertTrue(chat.selectedModules(true, java.util.EnumSet.allOf(SessionPrompt.Type.class)).contains(plan));
        assertFalse(chat.render().contains(plan.content()));
        assertTrue(chat.render(true).contains(plan.content()));
        assertTrue(chat.render(true).contains("角色 {{literal}}"));
        assertTrue(plan.content().contains("不得修改业务数据、交付文件、源码或配置"));
        assertTrue(plan.content().contains("也不得启动 Workflow 或成员工作"));
        assertTrue(plan.content().contains("多步骤任务使用原生通用任务计划"));
        assertFalse(plan.content().contains("plan.tasks"));
        assertTrue(plan.content().contains("成员步骤由真实子 Run 投影终态"));
        assertTrue(plan.content().contains("调用 plan_wait"));
        assertTrue(plan.content().contains("等待精确计划版本审批"));
        assertTrue(plan.content().contains("用户对澄清问题的回答不等于批准执行"));
    }

    @Test void hostPublicBoundarySurvivesCapabilityAndFinalAnswerSelection() {
        var chat = ChatPrompts.prompt(PromptDefaults.AGENT_DECISION_POLICY, 1, "agent:7", "角色");
        String finalAnswer = chat.selectedModules(false, java.util.Set.of()).stream()
                .map(SessionPrompt.Module::content).collect(java.util.stream.Collectors.joining("\n"))
                + "\n" + OutputPrompts.finalAnswerHint();
        for (String text : List.of(chat.modules().getFirst().content(), chat.render(), finalAnswer)) {
            assertTrue(text.contains("不携带访问权的 Run/Action/Artifact 标识"));
            assertTrue(text.contains("凭据、授权票据和宿主私有存储路径不得公开"));
            assertTrue(text.contains("文件正文、名称和可提取元数据"));
            assertTrue(text.contains("不按字段名或 UUID 外形删除"));
            assertFalse(text.contains("PUBLIC_OUTPUT_REJECTED"));
            assertFalse(text.contains("不修复、清洗或重试"));
            assertFalse(text.contains("内容未通过公开检查，本次交付未完成。"));
            assertTrue(text.contains("随工具调用输出的执行说明和 commentary"));
        }
        assertFalse(chat.render().contains("不展示平台内部标识"));
    }

    @Test void systemDefaultsRetainTheirSeparateTasksAndFormats() {
        assertTrue(PromptDefaults.CHAT_SUMMARY_PROMPT.contains("{\"profile\":"));
        assertTrue(PromptDefaults.PROFILE_MERGE_PROMPT.contains("关键偏好"));
        assertTrue(PromptDefaults.AGENT_DECISION_POLICY.contains("先利用已有结果"));
        assertFalse(PromptDefaults.AGENT_DECISION_POLICY.contains("授权"));
        assertTrue(PromptDefaults.systemPurpose("context-summary").contains("助手自行计划"));
        assertTrue(PromptDefaults.systemPurpose("context-summary").contains("将前次有效摘要与本次覆盖区间中的新增消息连续合并"));
        assertTrue(PromptDefaults.systemPurpose("context-summary").contains("只保留引用 ID 不等于摘要承接了正文"));
        assertTrue(PromptDefaults.systemPurpose("context-summary").contains("currentContext 提供当前用户目标、纠正和约束"));
        assertTrue(PromptDefaults.systemPurpose("context-summary").contains("它不属于 messages 覆盖范围"));
        assertTrue(PromptDefaults.systemPurpose("memory-extraction").contains("explicit_save"));
        assertTrue(PromptDefaults.systemPurpose("task-context").contains("sourceEventId"));
        for (String purpose : List.of("memory-extraction", "task-context", "context-summary"))
            assertFalse(PromptDefaults.systemPurpose(purpose).contains("\r"));
        assertThrows(IllegalArgumentException.class, () -> PromptDefaults.systemPurpose("missing"));
        assertThrows(IllegalStateException.class, () -> PromptResources.read("missing"));
        assertThrows(IllegalArgumentException.class, () -> ChatPrompts.prompt("策略", 0, "agent:7", "角色"));
        assertThrows(NullPointerException.class, () -> ChatPrompts.prompt("策略", 1, null, "角色"));
    }

    @Test void missingArgumentsAreResolvedBeforeAskingAndStagesRemainExplicit() {
        String instructions = ChatPrompts.prompt("策略", 1, "agent:7", "角色").render();
        assertTrue(instructions.contains("先从上下文或已授权查询补齐"));
        assertTrue(instructions.contains("用户持有的选择"));
        assertFalse(instructions.contains("缺少必要参数先澄清"));
        assertTrue(instructions.contains("planned（计划）"));
        assertTrue(instructions.contains("published（成果正式发布）"));
        assertTrue(instructions.contains("保留记忆的来源、时间、对象和适用范围"));
    }

    @Test void publicRulesKeepBusinessContentAndInternalReferencesDistinctAndRejectWithoutRepair() {
        String instructions=ChatPrompts.prompt("策略",1,"agent:7","角色").render();
        assertTrue(instructions.contains("普通引用、代码、API 字段和业务编号保持准确"));
        assertTrue(instructions.contains("不按字段名或 UUID 外形删除"));
        assertTrue(instructions.contains("凭据、授权票据和宿主私有存储路径不得公开"));
        assertTrue(instructions.contains("实际完成或改变了什么、验证了什么、还剩什么"));
        assertFalse(instructions.contains("PUBLIC_OUTPUT_REJECTED"));
        assertFalse(instructions.contains("不追加模型轮"));
        assertTrue(DelegationPrompts.child().contains("内部核验的真实引用保持准确"));
        assertTrue(PromptDefaults.systemPurpose("context-summary").contains("核验所需的真实引用必须准确保留"));
        assertFalse(OutputPrompts.preparationHint().contains("OCR"));
    }
}
