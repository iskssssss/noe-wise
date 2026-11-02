package com.skillforge.runtime.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.ToolPortal;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentExecutionLoop;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.core.ToolCallRouting;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopConfigurationTest {
    @TempDir Path workspace;

    @Test void connectionTestRejectsIncompleteAndFilteredResponses() throws Exception {
        var finish = new java.util.concurrent.atomic.AtomicReference<>("stop");
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] body = ("{\"id\":\"test\",\"model\":\"controlled\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"partial\"},\"finish_reason\":\""
                    + finish.get() + "\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try (var models = new DesktopModels(java.time.Clock.systemUTC(), DesktopRuntime.CODEC_LIMITS)) {
            models.configure("custom", "http://127.0.0.1:" + server.getAddress().getPort(), "controlled", "key");
            for (var reason : List.of("length", "content_filter", "stop")) {
                finish.set(reason);
                var result = models.test();
                assertEquals(reason.equals("stop"), result.path("connected").asBoolean());
                if (!reason.equals("stop")) assertEquals(reason.equals("length") ? "model_output_length" : "model_content_filtered", result.path("code").asText());
            }
        } finally { server.stop(0); }
    }

    /** 桌面目录显式区分不同参数，但相同写入复用原结果，未决命令始终阻止新派发。 */
    @Test void freezesArgumentIdentityWithoutGrantingReplayOfWritesOrUnknownCommands() throws Exception {
        var policy = DesktopPolicy.defaults();
        var model = new DesktopModels.Settings("custom", "http://localhost:1234/v1", "controlled", "service", "1", false);
        try (var tools = new DesktopTools(workspace)) {
            var frozen = DesktopConfiguration.freeze(model, policy, workspace.toString(), Optional.empty(),
                    tools.definitions(), DesktopPrompts.prepare(policy));
            var entries = DesktopConfiguration.tools(DesktopJson.parse(DesktopJson.write(frozen)));
            assertTrue(DesktopConfiguration.supports(DesktopJson.material(DesktopConfiguration.SCHEMA, frozen)));
            var changedPolicy = frozen.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) changedPolicy.required("tools").get(0))
                    .put("callIdentityPolicy", ToolPortal.ToolCallIdentityPolicy.HANDLE.name());
            assertEquals(ToolPortal.ToolCallIdentityPolicy.HANDLE, DesktopConfiguration.tools(changedPolicy).getFirst().target().callIdentityPolicy());
            ((com.fasterxml.jackson.databind.node.ObjectNode) changedPolicy.required("tools").get(0)).remove("callIdentityPolicy");
            assertThrows(IllegalArgumentException.class, () -> DesktopConfiguration.tools(changedPolicy));
            assertFalse(DesktopConfiguration.supports(DesktopJson.material(DesktopConfiguration.SCHEMA, changedPolicy)));
            ((com.fasterxml.jackson.databind.node.ObjectNode) changedPolicy.required("tools").get(0)).put("callIdentityPolicy", "unregistered");
            assertThrows(IllegalArgumentException.class, () -> DesktopConfiguration.tools(changedPolicy));
            assertFalse(DesktopConfiguration.supports(DesktopJson.material(DesktopConfiguration.SCHEMA, changedPolicy)));
            var loop = new AgentExecutionLoop(new CodecLimits(10_000, 1_000, 32));
            for (var entry : entries) {
                assertEquals(ToolPortal.ToolCallIdentityPolicy.ARGUMENTS, entry.target().callIdentityPolicy());
                if (entry.target().idempotent()) continue;
                var loaded = new AgentExecutionLoop.LoadedTool(new ToolCallRouting.Binding(
                        entry.target(), entry.binding(), entry.parallelSafe()), entry.discovery().name());
                var original = new AgentAction.ToolCall(entry.target().handle().value(), new TypedPayload("application/json", "{}"));
                var changed = new AgentAction.ToolCall(entry.target().handle().value(), new TypedPayload("application/json", "{\"value\":\"changed\"}"));
                var source = new AgentExecutionLoop.ToolSource(new ActionRef(new TenantId("tenant"), "saved-action"),
                        "saved-attempt", new ResultRef("saved-request"), new ResultRef("saved-result"));
                var resolved = new AgentExecutionLoop.ToolObservation(original, loaded,
                        AgentExecutionLoop.ToolResolution.SUCCEEDED, Optional.of(new ResultRef("saved-feedback")), List.of(), source);
                assertInstanceOf(AgentExecutionLoop.LoadedSelected.class, loop.resolveTool(changed, List.of(loaded), List.of(resolved)));
                var reused = assertInstanceOf(AgentExecutionLoop.LoadedToolReuse.class,
                        loop.resolveTool(original, List.of(loaded), List.of(resolved)));
                assertSame(resolved, reused.original());
                for (var resolution : List.of(AgentExecutionLoop.ToolResolution.UNKNOWN, AgentExecutionLoop.ToolResolution.INPUT_REQUIRED)) {
                    var unresolved = new AgentExecutionLoop.ToolObservation(original, loaded, resolution, Optional.empty(), List.of(), source);
                    for (var call : List.of(original, changed)) assertEquals(
                            new AgentExecutionLoop.LoadedRejected(AgentExecutionLoop.LoadedRejection.TOOL_RESULT_UNRESOLVED),
                            loop.resolveTool(call, List.of(loaded), List.of(unresolved)));
                }
            }
        }
    }
}
