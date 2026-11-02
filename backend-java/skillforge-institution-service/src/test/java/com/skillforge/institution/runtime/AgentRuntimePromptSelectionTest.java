package com.skillforge.institution.runtime;

import com.skillforge.prompts.ChatPrompts;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding.ProviderType;
import com.skillforge.runtime.session.spi.SessionPrompt;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 从冻结目录选择模型规则，不根据第三方工具名称授予平台专项语义。 */
class AgentRuntimePromptSelectionTest {
    @Test void plainChatKeepsCoreEvidenceOutputAndHumanInteractionWithoutUnavailableCapabilities() {
        var selected = AgentRuntimePromptSelection.select(prompt(), catalog(List.of()));
        assertEquals(List.of(SessionPrompt.Type.HOST, SessionPrompt.Type.PLATFORM, SessionPrompt.Type.AGENT,
                SessionPrompt.Type.RUNTIME, SessionPrompt.Type.RUNTIME_ACTIONS, SessionPrompt.Type.PLAN_POLICY), selected.modules().stream().map(SessionPrompt.Module::type).toList());
        String text = selected.render();
        for (String rule : List.of("业务角色", "R-CORE-01", "R-EVID-01", "R-OUT-01", "向用户澄清", "R-STATE-03"))
            assertTrue(text.contains(rule), rule);
        for (String rule : List.of("R-CODE-01", "R-SKILL-01", "R-MEM-01", "R-WORKFLOW-01", "R-TOOL-08"))
            assertFalse(text.contains(rule), rule);
        assertEquals(selected, new SessionPrompt(selected.modules()));
    }

    @Test void codeGetsExactlyTheWorkspaceModeFromTrustedFrozenPlatformBindings() {
        var code = entry("code_execute", ProviderType.PLATFORM);
        var workspace = entry("workspace_files", ProviderType.PLATFORM);
        var standalone = AgentRuntimePromptSelection.select(prompt(), catalog(List.of(code))).render();
        assertTrue(standalone.contains("R-CODE-03"));assertFalse(standalone.contains("R-CODE-02"));
        var persistent = AgentRuntimePromptSelection.select(prompt(), catalog(List.of(code, workspace))).render();
        assertTrue(persistent.contains("R-CODE-02"));assertFalse(persistent.contains("R-CODE-03"));
        assertTrue(persistent.contains("R-TOOL-02"));assertFalse(persistent.contains("result.json"));
        var thirdParty = AgentRuntimePromptSelection.select(prompt(), catalog(List.of(code,entry("workspace_files", ProviderType.MCP)))).render();
        assertTrue(thirdParty.contains("R-CODE-03"));assertFalse(thirdParty.contains("R-CODE-02"));
    }

    @Test void selectorsRetainPublishedContentVersionsAndDoNotMutateSourcePrompt() {
        var source=prompt();var selected=AgentRuntimePromptSelection.select(source,catalog(List.of(entry("search_memory",ProviderType.PLATFORM))));
        assertTrue(selected.render().contains("R-MEM-01"));assertTrue(source.render().contains("R-CODE-02"));
        assertTrue(source.modules().containsAll(selected.modules()));
        assertFalse(AgentRuntimePromptSelection.select(source,catalog(List.of(entry("code_execute",ProviderType.MCP)))).render().contains("R-CODE-01"));
    }

    @Test void freezesPlanPolicyForInitialPlanningAndLaterSkillActivationWithoutShowingItInPlainChat() {
        var plain = AgentRuntimePromptSelection.select(prompt(), catalog(List.of()));
        assertFalse(plain.render().contains("当前任务处于规划阶段"));
        assertTrue(plain.render(true).contains("当前任务处于规划阶段"));
        var planning = catalog(List.of()); when(planning.planRequired()).thenReturn(true);
        var selected = AgentRuntimePromptSelection.select(prompt(), planning);
        assertEquals(plain.modules(), selected.modules());
        assertTrue(selected.modules().stream().anyMatch(module -> module.type() == SessionPrompt.Type.PLAN_POLICY));
    }

    private static SessionPrompt prompt() { return ChatPrompts.prompt("平台策略",1,"agent:1","业务角色"); }
    private static AgentRuntimeExecutionCatalog.FrozenCatalog catalog(List<SessionToolDirectory.Entry> tools) {
        var catalog=mock(AgentRuntimeExecutionCatalog.FrozenCatalog.class);
        when(catalog.tools()).thenReturn(tools);when(catalog.knowledge()).thenReturn(List.of());when(catalog.skills()).thenReturn(List.of());
        when(catalog.workflows()).thenReturn(List.of());when(catalog.initialPlanState()).thenReturn(com.skillforge.runtime.api.port.PlanPort.State.absent());
        return catalog;
    }
    private static SessionToolDirectory.Entry entry(String name,ProviderType provider) {
        var target=mock(com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget.class);when(target.modelInvocable()).thenReturn(true);
        var binding=new com.skillforge.runtime.api.port.ToolPortal.ToolBinding(provider,name,name);
        return new SessionToolDirectory.Entry(target,binding,new SessionToolDirectory.Discovery(binding.serverIdentity(),binding.serverIdentity()+"/"+name,name,Optional.empty(),"说明"));
    }
}
