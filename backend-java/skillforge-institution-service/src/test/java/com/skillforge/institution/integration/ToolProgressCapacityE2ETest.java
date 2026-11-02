package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.ToolOutcome;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** 窄容量环境中通过实际MCP派发和PostgreSQL提交确定失败。 */
@TestPropertySource(properties = "agent-runtime.max-payload-code-units=262144")
class ToolProgressCapacityE2ETest extends ChatExecutionWaitingE2ETest {
    @Test void oversizedMcpFailureKeepsOriginalFailureAndDoesNotRedispatch() throws Exception {
        try (var server = new RuntimeExecutionMcpWaitServer(objectMapper)) {
            server.start(); login(); mountMcpWaitTool(server, false);
            String ticket = "capacity-" + SnowflakeIds.nextId();
            var script = server.register(ticket, RuntimeExecutionMcpWaitServer.Mode.OVERSIZED_READ_FAILURE);
            provider.enableSingleMcp(ticket);
            var accepted = send("/api/v1/chat", "POST", chatPayload("调用已挂载的受控只读工具。"), accessToken);
            assertEquals(202, accepted.statusCode(), accepted.body());
            var run = successData(accepted).path("execution_id").asText();
            var capture = observeSseUntil(sessionId, null, run, Set.of("execution.completed", "error"));
            assertTrue(capture.events().contains("execution.completed"), capture.raw());
            assertEquals(1, script.calls.get()); assertEquals(2, provider.requestCount());
            assertEquals(1, countRuntime("SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='TOOL'", tenantId, run));
            var rows = queryRows(jdbc, tenantId, "SELECT t.attempt_id,t.result_kind,t.adopted,m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a JOIN agent_runtime_execution_attempts t ON t.tenant_id=a.tenant_id AND t.action_id=a.action_id JOIN agent_runtime_execution_materials m ON m.tenant_id=t.tenant_id AND m.reference=t.result_ref WHERE a.tenant_id=? AND a.run_id=? AND a.kind='TOOL' LIMIT 2", tenantId, run);
            assertEquals(1, rows.size()); var row = rows.getFirst(); assertEquals("FAILED", row.get("result_kind")); assertEquals(Boolean.TRUE, row.get("adopted"));
            var codec = new AgentExecutionCodec(new CodecLimits(262144, 100_000, 128));
            var failure = assertInstanceOf(ToolOutcome.Failed.class, codec.decodeToolOutcome(new TypedPayload(row.get("schema_id").toString(), row.get("canonical_json").toString())));
            assertTrue(failure.comparisonFact().isEmpty());
            assertEquals("mcp_tool_arguments_invalid_after_dispatch", failure.failure().reasonCode());
            var state = queryRows(jdbc, tenantId, "SELECT status,phase FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=? LIMIT 1", tenantId, run).getFirst();
            assertEquals("COMPLETED", state.get("status")); assertEquals("FINISHED", state.get("phase"));
            var location = System.getenv("SKILLFORGE_PROGRESS_EVIDENCE_DIR");
            if (location != null && !location.isBlank()) {
                var directory = Path.of(location); Files.createDirectories(directory);
                Files.writeString(directory.resolve("controlled-capacity-state.json"), objectMapper.writeValueAsString(Map.of(
                        "run", run, "session", sessionId, "status", state.get("status"), "toolAttempts", 1,
                        "mcpCalls", 1, "resultKind", row.get("result_kind"), "comparisonFact", "UNAVAILABLE")), StandardCharsets.UTF_8);
            }
        }
    }
}
