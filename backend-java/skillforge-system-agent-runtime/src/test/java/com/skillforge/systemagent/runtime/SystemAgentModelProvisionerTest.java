package com.skillforge.systemagent.runtime;

import com.skillforge.core.agent.systemagent.SystemAgentTypes.Config;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.ModelPort.*;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.starter.AgentRuntimeModelConfiguration;
import com.skillforge.runtime.session.spi.SessionPreparations;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 通用系统 Agent 的动作协议、用途正文与冻结准备身份必须一起装配。 */
class SystemAgentModelProvisionerTest {
    @Test void originalFrozenSystemSourceWithOldStepConfigIsRejectedInsteadOfReinterpreted() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var source = new SystemAgentCapabilitySource(json, mock(org.springframework.beans.factory.ObjectProvider.class), mock(com.skillforge.core.capability.connector.PlatformToolCatalog.class));
        String actual = source.encode(source("system-agent:agent:published:1", "正文"));
        assertEquals(5, source.decode(actual).config().maxBusinessModelCalls());
        assertThrows(IllegalStateException.class, () -> source.decode(actual.replace("max_business_model_calls", "max_steps")));
    }
    @Test void purposeOrPublicationChangesInvalidatePreparationWhileKeepingJsonActionContract() {
        var models = mock(AgentRuntimeModelConfiguration.class);
        var catalog = mock(PlatformModelCatalogService.class);
        var store = mock(SystemAgentRuntimeStore.class);
        var sources = mock(SystemAgentCapabilitySource.class);
        var owner = SessionOwner.user(new SessionRef(new TenantId("1"), "session"),
                new AgentId("agent"), new EndUserId(1));
        var input = new InputId("input");
        var row = new SystemAgentRuntimeStore.SourceRow();
        row.sourceData = "frozen-source";
        when(store.source(owner, input)).thenReturn(Optional.of(row));
        when(sources.decode(row.sourceData)).thenReturn(
                source("system-agent:agent:published:1", "只返回业务 JSON 对象"),
                source("system-agent:agent:published:2", "只返回业务 JSON 数组"),
                source("system-agent:agent:published:3", "只返回业务 JSON 数组"));
        var selected = new PlatformModelConfig("10", 3, "chat", "LLM", true,
                "deepseek", "deepseek-api", "2026-09-13", "openai-chat-completions",
                "bearer-api-key", "deepseek-chat-v1", "https://api.deepseek.com", "deepseek-chat", "encrypted",
                8192, 64000, 4096, 0.5, "DISABLED", 90, null, null, null, null, null, "CNY");
        when(catalog.readRevision("10", 3)).thenReturn(selected);
        var target = mock(FrozenModelTarget.class);
        when(target.binding()).thenReturn(new ModelBindingRef(new TenantId("1"), "binding", 3));
        when(models.publishBinding(any(), anyString(), any(), any(), any())).thenReturn(target);
        when(models.registerPreparation(any())).thenAnswer(call ->
                call.<AgentRuntimeModelConfiguration.PreparationPlan>getArgument(0).reference());
        var provisioner = new SystemAgentModelProvisioner(models, catalog, store, sources);

        var first = provisioner.prepare(owner, input);
        assertEquals(5, first.budget().maxBusinessModelCalls());
        assertEquals(5, first.budget().maxToolCalls());
        var second = provisioner.prepare(owner, input);
        var third = provisioner.prepare(owner, input);
        var plans = ArgumentCaptor.forClass(AgentRuntimeModelConfiguration.PreparationPlan.class);
        verify(models, times(3)).registerPreparation(plans.capture());
        assertNotEquals(first.profile(), second.profile());
        assertNotEquals(second.profile(), third.profile());
        var before = plans.getAllValues().getFirst();
        var after = plans.getAllValues().get(1);
        var samePurposeNewPublication = plans.getAllValues().getLast();
        for (var plan : plans.getAllValues()) {
            assertEquals(SessionPreparations.PROFILE_VERSION, plan.version());
            assertEquals(SessionPreparations.AGENT_INPUT_RENDERER_VERSION, plan.rendererVersion());
            assertInstanceOf(ResponseFormat.NativeConversation.class, plan.response().format());
            String prompt = plan.systemPrompt().render();
            assertTrue(prompt.contains("能够回答时直接输出 assistant 正文"));
            assertFalse(prompt.contains("final_answer"));
            assertTrue(prompt.contains("有副作用且结果未知时先核实，不重复操作"));
            assertTrue(prompt.contains("历史待办不自动续做"));
            assertFalse(prompt.contains("sf_agent_action"));
            assertEquals(com.skillforge.runtime.session.spi.SessionPrompt.Type.HOST, plan.systemPrompt().modules().getFirst().type());
            assertEquals(com.skillforge.runtime.session.spi.SessionPrompt.Type.SYSTEM_PURPOSE, plan.systemPrompt().modules().getLast().type());
            assertEquals(2, plan.systemPrompt().modules().size());
        }
        assertEquals("system-agent:agent:published:1", before.systemPrompt().modules().getLast().sourceVersion());
        assertEquals("system-agent:agent:published:2", after.systemPrompt().modules().getLast().sourceVersion());
        assertEquals("system-agent:agent:published:3", samePurposeNewPublication.systemPrompt().modules().getLast().sourceVersion());
        assertEquals(after.systemPrompt().render(), samePurposeNewPublication.systemPrompt().render());
        assertEquals(before.systemPrompt().render().split("用途指令：", 2)[0],
                after.systemPrompt().render().split("用途指令：", 2)[0]);
        assertTrue(before.systemPrompt().render().endsWith("只返回业务 JSON 对象"));
        assertTrue(after.systemPrompt().render().endsWith("只返回业务 JSON 数组"));
    }

    private static SystemAgentCapabilitySource.SourceSnapshot source(String sourceRef, String prompt) {
        return new SystemAgentCapabilitySource.SourceSnapshot("run", sourceRef, 1,
                new Config("系统用途", "", prompt, "10", 30, 5, List.of()), 3, List.of(), SystemAgentCapabilitySource.inputFingerprint("input"));
    }
}
