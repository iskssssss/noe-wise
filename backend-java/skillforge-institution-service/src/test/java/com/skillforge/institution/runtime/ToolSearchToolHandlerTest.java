package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import com.skillforge.runtime.session.SessionToolNames;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 搜索只用完整冻结 Run 目录，模型结果不泄露精确输入绑定身份。 */
class ToolSearchToolHandlerTest {
    @Test
    void searchUsesFullRunDirectoryAndProjectsUniqueStableIdentitiesForSameNameMounts() {
        var directory = mock(SessionToolDirectory.class);
        var run = run("first");
        var tools = List.of(tool("tool:1:input:first", "search_tools", ToolBinding.ProviderType.PLATFORM),
                tool("tool:101:input:first", "search", ToolBinding.ProviderType.MCP),
                tool("tool:102:input:first", "search", ToolBinding.ProviderType.MCP));
        when(directory.list(run)).thenReturn(tools);
        when(directory.modelIdentities(run, tools)).thenReturn(Map.of(
                "tool:1:input:first", "tool:1", "tool:101:input:first", "tool:101", "tool:102:input:first", "tool:102"));

        var result = new ToolSearchToolHandler(directory, new ObjectMapper()).execute(Map.of("query", "documents", "limit", 2), context(run, false));

        assertEquals(true, result.get("success"));
        var matches = (List<?>) result.get("tools");
        assertEquals(2, matches.size());
        assertEquals(List.of(SessionToolNames.wireName("tool:101", "search"), SessionToolNames.wireName("tool:102", "search")), matches.stream().map(item -> ((Map<?, ?>) item).get("handle")).toList());
        assertEquals(List.of("mcp:server/search", "mcp:server/search"), matches.stream().map(item -> ((Map<?, ?>) item).get("name")).toList());
        assertFalse(result.toString().contains("input:first"));
        assertEquals(false, result.get("has_more"));
        verify(directory).list(run);
        verify(directory).modelIdentities(run, tools);
        verifyNoMoreInteractions(directory);
    }

    @Test
    void unchangedMountDiscoveryIsByteStableAcrossRunsWithDifferentInternalHandles() throws Exception {
        var directory = mock(SessionToolDirectory.class);
        var handler = new ToolSearchToolHandler(directory, new ObjectMapper());
        var first = run("first");
        var second = run("second");
        var firstTools = List.of(tool("tool:101:input:first", "search", ToolBinding.ProviderType.MCP));
        var secondTools = List.of(tool("tool:101:input:second", "search", ToolBinding.ProviderType.MCP));
        when(directory.list(first)).thenReturn(firstTools);
        when(directory.list(second)).thenReturn(secondTools);
        when(directory.modelIdentities(first, firstTools)).thenReturn(Map.of("tool:101:input:first", "tool:101"));
        when(directory.modelIdentities(second, secondTools)).thenReturn(Map.of("tool:101:input:second", "tool:101"));

        var firstResult = handler.execute(Map.of("query", "documents"), context(first, false));
        var secondResult = handler.execute(Map.of("query", "documents"), context(second, false));

        assertEquals(firstResult, secondResult);
        var json = new ObjectMapper();
        assertArrayEquals(json.writeValueAsBytes(firstResult), json.writeValueAsBytes(secondResult));
        assertEquals(SessionToolNames.wireName("tool:101", "search"), ((Map<?, ?>) ((List<?>) firstResult.get("tools")).getFirst()).get("handle"));
        assertEquals("tool:101:input:first", firstTools.getFirst().target().handle().value());
        assertEquals("tool:101:input:second", secondTools.getFirst().target().handle().value());
    }

    @Test
    void rejectsIncompleteIdentityMappingAndCancellationBeforeReadingDirectory() {
        var directory = mock(SessionToolDirectory.class);
        var run = run("first");
        var tools = List.of(tool("tool:101:input:first", "search", ToolBinding.ProviderType.MCP));
        when(directory.list(run)).thenReturn(tools);
        when(directory.modelIdentities(run, tools)).thenReturn(Map.of());
        var handler = new ToolSearchToolHandler(directory, new ObjectMapper());

        assertEquals(Map.of("success", false, "error", "invalid_arguments", "tools", List.of()),
                handler.execute(Map.of("query", "documents"), context(run, false)));
        clearInvocations(directory);
        assertEquals(Map.of("success", false, "error", "cancelled", "tools", List.of()),
                handler.execute(Map.of("query", "documents"), context(run, true)));
        verifyNoInteractions(directory);
    }

    @Test
    void moreMatchesAreReportedOnlyWhenAnAdditionalFrozenCandidateExists() {
        var directory = mock(SessionToolDirectory.class);
        var run = run("first");
        var first = tool("tool:101", "first", ToolBinding.ProviderType.MCP);
        var second = tool("tool:102", "second", ToolBinding.ProviderType.MCP);
        var handler = new ToolSearchToolHandler(directory, new ObjectMapper());
        when(directory.list(run)).thenReturn(List.of(first, second));
        when(directory.modelIdentities(eq(run), anyList())).thenAnswer(call -> {
            List<SessionToolDirectory.Entry> entries = call.getArgument(1);
            return entries.stream().collect(java.util.stream.Collectors.toMap(
                    entry -> entry.target().handle().value(), entry -> entry.target().handle().value()));
        });

        var partial = handler.execute(Map.of("query", "documents", "limit", 1), context(run, false));
        assertEquals(true, partial.get("has_more"));
        assertEquals(1, ((List<?>) partial.get("tools")).size());
        assertTrue(partial.get("hint").toString().contains("未列出"));
        var complete = handler.execute(Map.of("query", "documents", "limit", 2), context(run, false));
        assertEquals(false, complete.get("has_more"));
        assertFalse(complete.containsKey("hint"));
    }

    @Test
    void noMatchIsSuccessfulEmptyDiscoveryWithScopedGuidanceAndNoInventedTools() {
        var directory = mock(SessionToolDirectory.class);
        var run = run("first");
        var tools = List.of(tool("tool:101", "search", ToolBinding.ProviderType.MCP));
        when(directory.list(run)).thenReturn(tools);
        when(directory.modelIdentities(run, tools)).thenReturn(Map.of("tool:101", "tool:101"));

        var result = new ToolSearchToolHandler(directory, new ObjectMapper()).execute(
                Map.of("query", "nonexistent-private-capability"), context(run, false));

        assertEquals(true, result.get("success"));
        assertEquals(List.of(), result.get("tools"));
        assertEquals(false, result.get("has_more"));
        assertTrue(result.get("hint").toString().contains("本次冻结的已授权工具目录"));
        assertFalse(result.toString().contains("nonexistent-private-capability"));
        assertFalse(result.containsKey("error"));
    }

    @Test
    void longDescriptionKeepsLateMatchAndOpeningRestrictionsWithinUnicodeExcerptBudget() {
        var directory = mock(SessionToolDirectory.class);
        var run = run("first");
        var base = tool("tool:101", "process", ToolBinding.ProviderType.MCP);
        String opening = "只读工具，不执行写入。";
        String description = opening + "🙂资料说明".repeat(120) + "可解析附件表格并返回数据。" + "后续说明".repeat(150);
        var tool = new SessionToolDirectory.Entry(base.target(), base.binding(), new SessionToolDirectory.Discovery(
                base.discovery().serverIdentity(), base.discovery().qualifiedName(), base.discovery().name(),
                Optional.of("读取上传资料"), description));
        var tools = List.of(tool);
        when(directory.list(run)).thenReturn(tools);
        when(directory.modelIdentities(run, tools)).thenReturn(Map.of("tool:101", "tool:101"));

        var result = new ToolSearchToolHandler(directory, new ObjectMapper()).execute(
                Map.of("query", "附件表格"), context(run, false));
        String excerpt = ((Map<?, ?>) ((List<?>) result.get("tools")).getFirst()).get("description").toString();

        assertTrue(excerpt.startsWith("摘录：读取上传资料；" + opening));
        assertTrue(excerpt.contains("附件表格"));
        assertTrue(excerpt.codePointCount(0, excerpt.length()) <= 400);
        assertFalse(excerpt.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF));
        assertFalse(result.toString().contains("input:first"));
    }

    @Test
    void titleOnlyMatchRemainsUnderstandableInTheDiscoveryResult() {
        var directory = mock(SessionToolDirectory.class);
        var run = run("first");
        var base = tool("tool:101", "lookup", ToolBinding.ProviderType.MCP);
        var tool = new SessionToolDirectory.Entry(base.target(), base.binding(), new SessionToolDirectory.Discovery(
                base.discovery().serverIdentity(), base.discovery().qualifiedName(), base.discovery().name(),
                Optional.of("查找已有成果"), "List current files"));
        when(directory.list(run)).thenReturn(List.of(tool));
        when(directory.modelIdentities(run, List.of(tool))).thenReturn(Map.of("tool:101", "tool:101"));

        var result = new ToolSearchToolHandler(directory, new ObjectMapper()).execute(
                Map.of("query", "成果"), context(run, false));

        assertEquals("查找已有成果；List current files", ((Map<?, ?>) ((List<?>) result.get("tools")).getFirst()).get("description"));
    }

    /** 远期固定截止时间使结果不依赖机器速度；取消条件由测试显式传入。 */
    private static PlatformToolExecutionContext context(RunIdentity run, boolean cancelled) {
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(run.owner().sessionRef().tenantId(),
                run.owner().requireUser()), run.owner(), Set.of(), 1, new ResultRef("verification"));
        return new PlatformToolExecutionContext(new PortCallContext(run, identity,
                new InvocationControl(Instant.parse("2099-01-01T00:00:00Z"), () -> cancelled)), "invocation", "attempt");
    }

    /** 固定同一 Agent 和会话，只有运行与输入身份不同。 */
    private static RunIdentity run(String id) {
        var tenant = new TenantId("1");
        var owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("2"), new EndUserId(3));
        var input = new InputId(id);
        return new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, id), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
    }

    /** 同名工具共享发现信息，但保留独立完整执行目标。 */
    private static SessionToolDirectory.Entry tool(String handle, String name, ToolBinding.ProviderType provider) {
        var target = new FrozenToolTarget(new ToolHandle(handle), new CapabilityRef(CapabilityType.TOOL, "search", "v1"),
                "deployment", 1, new TypedPayload("json-schema", "{}"), new TypedPayload("json-schema", "{}"),
                ToolEffect.READ_ONLY, true, Duration.ofSeconds(3), true, ToolConfirmationPolicy.NEVER,
                Set.of(), Set.of(), true);
        var binding = new ToolBinding(provider, provider == ToolBinding.ProviderType.MCP ? "server" : name, name);
        return new SessionToolDirectory.Entry(target, binding, new SessionToolDirectory.Discovery(binding.serverIdentity(),
                binding.serverIdentity() + "/" + name, name, Optional.empty(), "Search documents"));
    }
}
