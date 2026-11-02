package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 新旧HTTP候选共用知识资源公开生命周期；借用现有真实客户端和认证，不创建第二套租户或授权夹具。 */
final class RuntimeExecutionKnowledgeFixture {
    private final ObjectMapper json;
    private final HttpClient client;
    private final String baseUrl;
    private final String token;

    RuntimeExecutionKnowledgeFixture(ObjectMapper json, HttpClient client, String baseUrl, String token) {
        this.json = json; this.client = client; this.baseUrl = baseUrl; this.token = token;
    }

    /** 关键词索引不调用Embedding；原文本、sourceVersion和已发布能力均由正式接口产生。 */
    Published publish(String run, String sentinel) throws Exception {
        String text = "Only this document contains the random sentinel " + sentinel + ". The answer must cite this exact source.";
        var created = request("/api/v1/knowledge/bases", "POST", Map.of("name", "execution-knowledge-" + run,
                "description", "sentinel=" + sentinel, "indexing_mode", "economical", "retrieval_mode", "full_text"));
        assertEquals(200, created.statusCode(), created.body());
        String kb = data(created).path("id").asText(); assertFalse(kb.isBlank());
        var settings = request("/api/v1/knowledge/bases/update", "PATCH", Map.of("kb_id", kb,
                "payload", Map.of("indexing_mode", "economical", "retrieval_mode", "full_text")));
        assertEquals(200, settings.statusCode(), settings.body());
        var uploaded = upload(kb, "sentinel document " + run, text);
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertEquals("SUCCEEDED", data(uploaded).path("status").asText());
        String document = data(uploaded).path("document").path("document_id").asText(); assertFalse(document.isBlank());
        assertTrue(data(uploaded).path("document").path("chunks").asInt() > 0, uploaded.body());
        var detail = request("/api/v1/knowledge/documents/detail?docId=" + URLEncoder.encode(document, StandardCharsets.UTF_8), "GET", null);
        assertEquals(200, detail.statusCode(), detail.body());
        assertEquals("ready", data(detail).path("status").asText(), detail.body());
        String source = data(detail).path("source_version").asText(); assertFalse(source.isBlank());
        var revision = request("/api/v1/knowledge/bases/revisions", "POST", Map.of("kb_id", kb));
        assertEquals(200, revision.statusCode(), revision.body());
        String number = data(revision).path("revision").asText(); assertFalse(number.isBlank());
        var ready = request("/api/v1/knowledge/bases/revisions/ready", "POST", Map.of("kb_id", kb, "revision", number,
                "payload", Map.of("source_versions", List.of(source), "policy", Map.of("model_invocable", true, "user_invocable", false,
                        "allowed_operators", List.of("KEYWORD"), "freshness_policy", "PINNED", "citation_required", true))));
        assertEquals(200, ready.statusCode(), ready.body()); assertEquals("ready", data(ready).path("status").asText());
        var published = request("/api/v1/knowledge/bases/revisions/publish", "POST", Map.of("kb_id", kb, "revision", number));
        assertEquals(200, published.statusCode(), published.body()); assertEquals("published", data(published).path("status").asText());
        long revisionId = data(published).path("id").asLong();
        long version = data(published).path("capability_version_id").asLong();
        assertTrue(revisionId > 0); assertTrue(version > 0);
        var capability = request("/api/v1/capabilities/versions/detail?versionId=" + version, "GET", null);
        assertEquals(200, capability.statusCode(), capability.body());
        var value = data(capability); assertEquals("PUBLISHED", value.path("status").asText());
        assertEquals("KNOWLEDGE_SOURCE", value.path("capability_type").asText());
        long definition = value.path("definition_id").asLong(); assertTrue(definition > 0);
        return new Published(kb, document, source, revisionId, definition, version, sentinel, text);
    }

    /** 单份multipart编码供新旧知识候选复用；真实文件由正式文档上传与索引处理器接收。 */
    private HttpResponse<String> upload(String kb, String title, String content) throws Exception {
        String boundary = "SkillForge-" + Long.toUnsignedString(System.nanoTime());
        String body = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"document.txt\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n" + content + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n" + title + "\r\n--" + boundary + "--\r\n";
        return client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/v1/knowledge/bases/documents/upload?kb_id="
                + URLEncoder.encode(kb, StandardCharsets.UTF_8) + "&request_id=" + java.util.UUID.randomUUID()))
                .timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.getBytes(StandardCharsets.UTF_8))).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 所有生命周期调用保留正式IAM检查，不直接写知识或Capability表。 */
    private HttpResponse<String> request(String path, String method, Map<String, Object> body) throws Exception {
        return client.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        var envelope = json.readTree(response.body()); assertEquals("OK", envelope.path("code").asText(), response.body());
        assertTrue(envelope.has("data"), response.body()); return envelope.get("data");
    }

    /** 所有标识来自公开回执；source文档原文用于后续实际检索与引用事实核验。 */
    record Published(String kbId, String documentId, String sourceVersion, long revisionId, long definitionId,
            long capabilityVersionId, String sentinel, String documentText) { }
}
