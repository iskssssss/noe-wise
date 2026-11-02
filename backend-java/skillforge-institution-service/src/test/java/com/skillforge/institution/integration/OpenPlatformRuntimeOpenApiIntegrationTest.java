package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** 测试环境运行时 OpenAPI 与版本化 Open Platform v1 路由集合的一致性验证。 */
class OpenPlatformRuntimeOpenApiIntegrationTest extends AbstractIntegrationTest {

    private static final Set<String> CONTRACT_OPERATIONS = Set.of(
            "POST /open-platform/groups/ensure",
            "POST /open-platform/groups/close",
            "GET /open-platform/groups/detail",
            "GET /open-platform/groups/members",
            "GET /open-platform/groups/agents",
            "POST /open-platform/groups/members/update",
            "POST /open-platform/groups/agents/update",
            "POST /open-platform/groups/messages",
            "POST /open-platform/groups/triggers",
            "GET /open-platform/groups/triggers/detail",
            "POST /open-platform/groups/runs/respond",
            "POST /open-platform/groups/runs/commands",
            "GET /open-platform/groups/runs/detail",
            "GET /open-platform/groups/history",
            "GET /open-platform/groups/events",
            "GET /open-platform/groups/stream",
            "GET /open-platform/groups/memories",
            "GET /open-platform/groups/memories/extraction-policy",
            "PUT /open-platform/groups/memories/extraction-policy",
            "POST /open-platform/groups/memories/create",
            "POST /open-platform/groups/memories/review",
            "POST /open-platform/groups/memories/correct",
            "POST /open-platform/groups/memories/forget",
            "POST /open-platform/groups/memories/restore",
            "POST /open-platform/groups/controls",
            "GET /open-platform/groups/controls/detail",
            "GET /open-platform/groups/controls/current",
            "POST /open-platform/groups/controls/authorize",
            "POST /open-platform/groups/controls/terminalize",
            "POST /open-platform/groups/controls/release",
            "POST /open-platform/groups/sender-endpoints/register",
            "POST /open-platform/groups/sender-endpoints/retire",
            "POST /open-platform/groups/sender-endpoints/ack",
            "POST /open-platform/groups/deliveries/qualify",
            "POST /open-platform/business-scopes/ensure",
            "POST /open-platform/business-scopes/status",
            "GET /open-platform/agent-deployments",
            "POST /open-platform/agent-deployments",
            "GET /open-platform/agent-deployments/detail",
            "GET /open-platform/agent-deployments/definitions",
            "GET /open-platform/agent-deployments/grants",
            "PUT /open-platform/agent-deployments/bindings",
            "POST /open-platform/agent-deployments/authorization",
            "GET /open-platform/usage/events",
            "GET /open-platform/usage/summary",
            "GET /open-platform/usage/details",
            "GET /open-platform/resources",
            "GET /open-platform/resources/detail",
            "PUT /open-platform/resources/update",
            "POST /open-platform/resources/revisions",
            "GET /open-platform/resources/revisions/latest",
            "GET /open-platform/resources/revisions/detail",
            "PUT /open-platform/resources/revisions/update",
            "POST /open-platform/resources/revisions/ready",
            "POST /open-platform/resources/revisions/publish",
            "PUT /open-platform/deployments/update",
            "PUT /open-platform/channels/binding",
            "POST /open-platform/control-operations",
            "GET /open-platform/control-operations/detail",
            "POST /open-platform/sessions",
            "POST /open-platform/sessions/messages",
            "GET /open-platform/sessions/messages/receipt",
            "POST /open-platform/sessions/context-messages",
            "POST /open-platform/sessions/workflow-invocations",
            "POST /open-platform/sessions/actions",
            "POST /open-platform/sessions/callbacks",
            "POST /open-platform/sessions/workflow-callbacks",
            "POST /open-platform/sessions/commands",
            "GET /open-platform/sessions/events",
            "POST /open-platform/end-users/ensure",
            "GET /open-platform/sessions/history",
            "GET /open-platform/sessions/status",
            "GET /open-platform/sessions/stream",
            "POST /open-platform/sessions/steering",
            "GET /open-platform/sessions/deliverables",
            "GET /open-platform/sessions/deliverables/file",
            "POST /open-platform/sessions/attachments",
            "GET /open-platform/sessions/attachments",
            "POST /open-platform/sessions/media",
            "GET /open-platform/sessions/media/content");

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void runtimeDocumentHasExactlyTheVersionedOpenPlatformOperations() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v3/api-docs"))
                // 首次HTTP请求会同步生成全部OpenAPI；等待响应完成，不能用固定sleep推定就绪。
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        JsonNode document = objectMapper.readTree(response.body());
        assertTrue(document.path("openapi").asText().startsWith("3."), "must export a real OpenAPI document");
        // 导出真实 HTTP 文档供结构 diff 消费；失败时仍保留原始文档用于定位。
        Path output = Path.of(System.getProperty("openPlatform.runtimeDocument"));
        Files.createDirectories(output.getParent());
        Files.writeString(output, response.body(), StandardCharsets.UTF_8);
        var executionConfig = document.path("components").path("schemas").path("AgentExecutionConfig");
        assertFalse(executionConfig.path("additionalProperties").asBoolean(true), "未知预算字段必须在公开 schema 中关闭");
        var businessCalls = executionConfig.path("properties").path("max_business_model_calls");
        assertEquals(1, businessCalls.path("minimum").asInt(), "业务调用覆盖只接受正整数");
        assertTrue(allowsNull(businessCalls), "null 表示继承，运行时文档必须保留该分支");
        assertFalse(java.util.stream.StreamSupport.stream(executionConfig.path("required").spliterator(), false)
                .anyMatch(field -> "max_business_model_calls".equals(field.asText())), "业务调用覆盖允许省略");
        JsonNode paths = document.path("paths");
        Set<String> runtimeOperations = new HashSet<>();
        Iterator<Map.Entry<String, JsonNode>> pathFields = paths.fields();
        while (pathFields.hasNext()) {
            Map.Entry<String, JsonNode> pathField = pathFields.next();
            String path = pathField.getKey();
            if (path.startsWith("/api/v1")) {
                path = path.substring("/api/v1".length());
            }
            if (!path.startsWith("/open-platform")) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> methodFields = pathField.getValue().fields();
            while (methodFields.hasNext()) {
                String method = methodFields.next().getKey().toUpperCase();
                if (Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE")
                        .contains(method)) {
                    runtimeOperations.add(method + " " + path);
                }
            }
        }

        assertTrue(!runtimeOperations.isEmpty(), "runtime OpenAPI must expose Open Platform routes");
        assertEquals(CONTRACT_OPERATIONS, runtimeOperations,
                "runtime OpenAPI routes must match open-platform-v1.yaml");
    }

    private static boolean allowsNull(JsonNode schema) {
        if (schema.path("nullable").asBoolean() || "null".equals(schema.path("type").asText())) return true;
        for (var type : schema.path("type")) if ("null".equals(type.asText())) return true;
        for (var key : new String[]{"anyOf", "oneOf"})
            for (var branch : schema.path(key)) if (allowsNull(branch)) return true;
        return false;
    }
}
