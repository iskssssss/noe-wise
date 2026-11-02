package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.channel.mapper.ChannelMapper;
import com.skillforge.core.channel.mapper.ChannelAgentBindingMapper;
import com.skillforge.core.channel.model.ChannelDO;
import com.skillforge.core.channel.model.ChannelAgentBindingDO;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.channel.ChannelService;
import com.skillforge.core.channel.dto.request.CreateChannelRequest;
import com.skillforge.core.channel.dto.request.PatchChannelRequest;
import com.skillforge.core.channel.dto.response.ChannelResponse;
import com.skillforge.core.channel.dto.response.ApiKeyIssueResponse;
import com.skillforge.core.channel.enums.ChannelType;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementMapper;
import com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts.SaveTenantGrant;
import com.skillforge.core.foundation.catalog.entitlements.FeatureGate;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.institution.security.HmacSignatureVerifier;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 HTTP + PostgreSQL 验证两种凭证的聊天发现、登记与渠道边界。 */
class ChatClientOpenApiIntegrationTest extends AbstractIntegrationTest {
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired AgentMapper agents;
    @Autowired AgentPublicationService agentPublications;
    @Autowired EntitlementMapper entitlements;
    @Autowired FeatureGate features;
    @Autowired ChannelMapper channels;
    @Autowired ChannelService channelService;
    @Autowired ChannelAgentBindingMapper bindings;
    @Autowired CryptoService crypto;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.skillforge.core.foundation.persistence.TenantMemberMapper webhookMembers;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder webhookPasswords;
    @Autowired com.skillforge.core.chat.session.SessionMailboxService historyMailbox;
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newHttpClient();
    String token;
    String key;
    String secret;
    final Map<String,String> terminalIdentities = new java.util.HashMap<>();

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void credentialsDiscoverOnlyBoundPublishedAgentsAndCannotEscapeBinding() throws Exception {
        String tenant = createTenant(tenants, plans, "chat-client-" + SnowflakeIds.nextId());
        TenantContext.setTenantId(tenant);
        token = "token-" + UUID.randomUUID(); key = "key-" + UUID.randomUUID(); secret = "secret-" + UUID.randomUUID();
        var channel = new ChannelDO(); channel.setId(SnowflakeIds.nextId()); channel.setTenantId(Long.parseLong(tenant));
        channel.setName("chat client"); channel.setChannelType("openapi"); channel.setStatus("active");
        channel.setChannelTokenHash(TokenHash.sha256Hex(token));
        channel.setConfig(Map.of());
        channel.setApiKeyHash(TokenHash.sha256Hex(key)); channel.setApiSecretEncrypted(crypto.encrypt(secret));
        channels.insert(channel);
        long bound = agent(tenant, "published"); long unbound = agent(tenant, "published"); long draft = agent(tenant, "draft");
        long bindingId = bind(tenant, channel.getId(), bound); bind(tenant, channel.getId(), draft);
        // 管理查询保留完整绑定集合，真实非空 SQL 结果必须正确映射为 typed BindingSummary。
        var expectedBindings = List.of(new ChannelResponse.BindingItem(bound, "chat agent", 0, 100, false),
                new ChannelResponse.BindingItem(draft, "chat agent", 0, 100, false));
        assertEquals(expectedBindings, channelService.get(Long.parseLong(tenant), channel.getId()).agentBindings());
        var listedChannels = channelService.list(Long.parseLong(tenant));
        assertEquals(1, listedChannels.total());
        assertEquals(channel.getId(), listedChannels.items().getFirst().id());
        assertEquals(expectedBindings, listedChannels.items().getFirst().agentBindings());
        String lastSession = null;
        for (boolean signed : new boolean[]{false, true}) {
            var catalog = call("GET", "/api/v1/chat/agents", null, signed);
            assertEquals(200, catalog.statusCode(), catalog.body());
            var body = json.readTree(catalog.body()).path("data");
            assertEquals(channel.getId().toString(), body.path("channel_id").asText());
            assertEquals(1, body.path("items").size());
            assertEquals(Long.toString(bound), body.path("items").get(0).path("id").asText());
            assertFalse(catalog.body().contains("system_prompt"));
            var profile = call("POST", "/api/v1/chat/client-profile", Map.of("client_ref", UUID.randomUUID().toString(), "display_name", "访客"), signed);
            assertEquals(200, profile.statusCode(), profile.body());
            String user = json.readTree(profile.body()).path("data").path("end_user_id").asText();
            var created = call("POST", "/api/v1/chat/sessions", Map.of("agent_id", Long.toString(bound), "end_user_id", user), signed);
            assertEquals(200, created.statusCode(), created.body());
            lastSession = json.readTree(created.body()).path("data").path("session_id").asText();
            assertEquals(200, call("GET", "/api/v1/chat/history?session_id=" + lastSession, null, signed).statusCode());
            var workHistory = call("GET", "/api/v1/chat/work/history?session_id=" + lastSession, null, signed);
            assertEquals(200, workHistory.statusCode(), workHistory.body());
            assertTrue(json.readTree(workHistory.body()).path("data").path("items").isArray());
            assertEquals(0, json.readTree(workHistory.body()).path("data").path("items").size());
            assertEquals(403, call("POST", "/api/v1/chat/sessions", Map.of("agent_id", Long.toString(unbound), "end_user_id", user), signed).statusCode());
            assertEquals(403, call("POST", "/api/v1/chat", Map.of("agent_id", Long.toString(unbound), "session_id", lastSession, "message", "no", "context", Map.of("end_user_id", user)), signed).statusCode());
        }
        // 协作历史必须保留渠道与会话归属校验，不因加入外部认证入口而公开。
        String originalToken = token;
        token = "other-" + UUID.randomUUID();
        var otherChannel = new ChannelDO(); otherChannel.setId(SnowflakeIds.nextId()); otherChannel.setTenantId(Long.parseLong(tenant));
        otherChannel.setName("other client"); otherChannel.setChannelType("web_widget"); otherChannel.setStatus("active");
        otherChannel.setChannelTokenHash(TokenHash.sha256Hex(token)); otherChannel.setConfig(Map.of());
        channels.insert(otherChannel);
        assertEquals(200, call("POST", "/api/v1/chat/client-profile", Map.of(
                "client_ref", UUID.randomUUID().toString(), "display_name", "另一渠道访客"), false).statusCode());
        assertEquals(403, call("GET", "/api/v1/chat/work/history?session_id=" + lastSession, null, false).statusCode());
        token = "invalid-" + UUID.randomUUID();
        assertEquals(401, call("GET", "/api/v1/chat/work/history?session_id=" + lastSession, null, false).statusCode());
        token = originalToken;
        bindings.deleteById(bindingId);
        for (boolean signed : new boolean[]{false, true}) {
            assertEquals(403, call("GET", "/api/v1/chat/work/history?session_id=" + lastSession, null, signed).statusCode());
        }
        assertEquals(403, call("GET", "/api/v1/chat/history?session_id=" + lastSession, null, false).statusCode());
        assertEquals(403, call("GET", "/api/v1/chat/replay?session_id=" + lastSession, null, true, "text/event-stream, application/json").statusCode());
        assertEquals(0, json.readTree(call("GET", "/api/v1/chat/agents", null, true).body()).path("data").path("items").size());
    }

    /** 合成已提交公开事实验证真实HTTP分页；不派发模型、不伪称120次真实Run执行。 */
    @Test void longHistoryKeepsFullPagedMessagesWithoutRepeatingBodiesInActivities() throws Exception {
        String tenant = createTenant(tenants, plans, "history-payload-" + SnowflakeIds.nextId());
        TenantContext.setTenantId(tenant);
        token = "history-channel-" + UUID.randomUUID();
        var channel = new ChannelDO(); channel.setId(SnowflakeIds.nextId()); channel.setTenantId(Long.parseLong(tenant));
        channel.setName("history payload"); channel.setChannelType("web_widget"); channel.setStatus("active");
        channel.setChannelTokenHash(TokenHash.sha256Hex(token)); channel.setConfig(Map.of()); channels.insert(channel);
        long published = agent(tenant, "published"); bind(tenant, channel.getId(), published);
        var profile = call("POST", "/api/v1/chat/client-profile", Map.of("client_ref", UUID.randomUUID().toString(), "display_name", "合成历史访客"), false);
        assertEquals(200, profile.statusCode(), profile.body());
        String user = json.readTree(profile.body()).path("data").path("end_user_id").asText();
        var created = call("POST", "/api/v1/chat/sessions", Map.of("agent_id", Long.toString(published), "end_user_id", user), false);
        assertEquals(200, created.statusCode(), created.body());
        String session = json.readTree(created.body()).path("data").path("session_id").asText();
        assertNull(historyMailbox.committedCursor(tenant, session));
        String prefix = "synthetic-history-" + session + "-";
        var bodies = new java.util.LinkedHashMap<String, String>();
        var expectedReferences = new java.util.LinkedHashMap<String, JsonNode>();
        var card = Map.<String, Object>of("type", "confirmation", "title", "合成公开确认卡", "actions", List.of());
        java.time.Instant base = java.time.Instant.parse("2026-10-30T00:00:00Z");
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            // 同一私有数据库事务通过正式Mailbox分配Session序号并提交公开事实；没有私有执行账本。
            for (int i = 1; i <= 120; i++) {
                String run = prefix + "run-" + i;
                String message = prefix + "message-" + i;
                String body = "history-body-" + i + "|" + "bounded-public-history-paragraph-".repeat(256);
                bodies.put(run, body);
                long acceptedSequence = (i - 1L) * 3 + 1;
                var accepted = new java.util.LinkedHashMap<String, Object>();
                accepted.put("run_id", run); accepted.put("root_run_id", run);
                accepted.put("updated_sequence", acceptedSequence);
                appendHistoryFact(tenant, session, prefix, i, "execution.accepted", accepted, acceptedSequence);
                var references = i == 1 ? List.of() : List.of(Map.of("label", "上一条公开消息", "source", Map.of(
                        "kind", "message", "source", Map.of("kind", "session"), "message_id", prefix + "message-" + (i - 1), "message_version", 1)));
                expectedReferences.put(message, json.valueToTree(references));
                var delta = new java.util.LinkedHashMap<String, Object>();
                delta.put("run_id", run); delta.put("root_run_id", run); delta.put("message_id", message);
                delta.put("message_version", 1); delta.put("role", "assistant"); delta.put("content", body);
                delta.put("references", references); delta.put("created_at", base.plusSeconds(i).toString());
                delta.put("updated_sequence", acceptedSequence + 1); delta.put("sequence", acceptedSequence + 1);
                appendHistoryFact(tenant, session, prefix, i, "message.delta", delta, acceptedSequence + 1);
                var completed = new java.util.LinkedHashMap<String, Object>();
                completed.put("run_id", run); completed.put("root_run_id", run);
                completed.put("status", i == 118 ? "failed" : i == 119 ? "outcome_unknown" : "completed");
                completed.put("updated_sequence", acceptedSequence + 2);
                if (i == 120) completed.put("card", card);
                appendHistoryFact(tenant, session, prefix, i, "execution.completed", completed, acceptedSequence + 2);
            }
            // 无正文的挂起卡是反例：删正文副本不能删卡、挂起态或待交互事实。
            var accepted = Map.<String, Object>of("run_id", prefix + "run-waiting", "updated_sequence", 361L);
            appendHistoryFact(tenant, session, prefix, 121, "execution.accepted", accepted, 361);
            var waiting = Map.<String, Object>of("execution_id", prefix + "run-waiting", "card", card,
                    "question", "请确认合成测试卡", "tool_wait", Map.of("action_id", prefix + "wait-action", "generation", 1), "updated_sequence", 362L);
            appendHistoryFact(tenant, session, prefix, 121, "hitl.required", waiting, 362);
            var rejectedAccepted = Map.<String, Object>of("run_id", prefix + "run-suspended", "updated_sequence", 363L);
            appendHistoryFact(tenant, session, prefix, 122, "execution.accepted", rejectedAccepted, 363);
            var rejected = Map.<String, Object>of("run_id", prefix + "run-suspended", "reason", "synthetic_interaction_rejected", "updated_sequence", 364L);
            appendHistoryFact(tenant, session, prefix, 122, "interaction.rejected", rejected, 364);
        });
        // 重新读取提交后的正式Outbox，断言身份/因果/序号来自数据库，而不是构造中的内存对象。
        var facts = new java.util.ArrayList<com.skillforge.core.chat.session.SessionOutboxEntry>();
        long after = 0;
        while (true) {
            var page = historyMailbox.listOutputsAfterSequence(tenant, session, after, 100);
            facts.addAll(page);
            if (page.size() < 100) break;
            after = page.getLast().getSessionSequence();
        }
        assertEquals(364, facts.size());
        for (int index = 0; index < facts.size(); index++) {
            var fact = facts.get(index);
            assertEquals(index + 1L, fact.getSessionSequence());
            assertEquals(session, fact.getSessionId()); assertEquals(Long.parseLong(tenant), fact.getTenantId());
            assertTrue(fact.getRuntimeRunId().startsWith(prefix)); assertTrue(fact.getEventId().startsWith(prefix));
            assertTrue(fact.getTraceId().startsWith(prefix)); assertTrue(fact.getSourceEventId().startsWith(prefix));
        }
        String eventCursor = facts.getLast().getEventId();
        var acceptedTimes = new java.util.HashMap<String, String>();
        for (var fact : facts) if ("execution.accepted".equals(fact.getEventType()))
            acceptedTimes.put(fact.getRuntimeRunId(), json.valueToTree(fact.getCreatedAt()).asText());
        var latest = call("GET", "/api/v1/chat/history?session_id=" + session + "&limit=30", null, false);
        assertEquals(200, latest.statusCode(), latest.body());
        var latestTree = json.readTree(latest.body());
        var latestData = latestTree.path("data");
        assertEquals(prefix + "message-91", latestData.path("next_before_message_id").asText());
        String previousCursor = latestData.path("next_before_message_id").asText();
        var previous = call("GET", "/api/v1/chat/history?session_id=" + session + "&limit=30&before_message_id=" + previousCursor, null, false);
        assertEquals(200, previous.statusCode(), previous.body());
        var previousTree = json.readTree(previous.body());
        assertEquals(prefix + "message-61", previousTree.path("data").path("next_before_message_id").asText());
        for (int pageIndex = 0; pageIndex < 2; pageIndex++) {
            var envelope = pageIndex == 0 ? latestTree : previousTree;
            var data = envelope.path("data");
            int first = pageIndex == 0 ? 91 : 61;
            assertEquals("OK", envelope.path("code").asText()); assertFalse(envelope.path("trace_id").asText().isBlank());
            assertEquals(session, data.path("session_id").asText()); assertTrue(data.path("has_more").asBoolean());
            assertEquals(eventCursor, data.path("event_cursor").asText()); assertEquals(30, data.path("messages").size());
            for (int offset = 0; offset < 30; offset++) {
                int i = first + offset;
                var message = data.path("messages").get(offset);
                assertEquals(prefix + "message-" + i, message.path("message_id").asText());
                assertEquals(1, message.path("message_version").asLong()); assertEquals("assistant", message.path("role").asText());
                assertEquals(bodies.get(prefix + "run-" + i), message.path("content").asText());
                assertEquals(expectedReferences.get(prefix + "message-" + i), message.path("references"));
                var metadata = message.path("metadata");
                assertEquals((i - 1L) * 3 + 2, metadata.path("source_sequence").asLong());
                assertEquals(prefix + "run-" + i, metadata.path("run_id").asText());
                assertEquals(prefix + "trace-" + i, metadata.path("trace_id").asText());
                assertEquals(prefix + "message.delta-" + i, metadata.path("event_id").asText());
            }
            assertEquals(122, data.path("activities").size());
            for (var activity : data.path("activities")) {
                String run = activity.path("node_id").asText();
                assertFalse(activity.has("output")); assertEquals("agent_runtime", activity.path("node_type").asText());
                assertEquals(java.time.OffsetDateTime.parse(acceptedTimes.get(run)).toInstant(),
                        java.time.OffsetDateTime.parse(activity.path("started_at").asText()).toInstant());
                if (run.equals(prefix + "run-waiting")) {
                    assertEquals("waiting_hitl", activity.path("status").asText()); assertEquals(json.valueToTree(card), activity.path("card"));
                    assertTrue(activity.path("message_id").isNull());
                    assertEquals(362, activity.path("render").path("updated_sequence").asLong());
                } else if (run.equals(prefix + "run-suspended")) {
                    assertEquals("suspended", activity.path("status").asText());
                    assertTrue(activity.path("message_id").isNull()); assertTrue(activity.path("card").isNull());
                    assertEquals(364, activity.path("render").path("updated_sequence").asLong());
                    assertEquals("synthetic_interaction_rejected", activity.path("render").path("reason").asText());
                } else {
                    int i = Integer.parseInt(run.substring((prefix + "run-").length()));
                    assertEquals(i == 118 ? "failed" : i == 119 ? "outcome_unknown" : "completed", activity.path("status").asText());
                    assertEquals(prefix + "message-" + i, activity.path("message_id").asText());
                    assertEquals(prefix + "run-" + i, activity.path("render").path("root_run_id").asText());
                    assertEquals(prefix + "root-" + i, activity.path("render").path("root_input_id").asText());
                    assertEquals(i * 3L, activity.path("render").path("updated_sequence").asLong());
                    if (i == 120) assertEquals(json.valueToTree(card), activity.path("card"));
                    else assertTrue(activity.path("card").isNull());
                }
            }
            assertEquals(1, data.path("pending_interactions").size());
            assertEquals(prefix + "run-waiting", data.path("pending_interactions").get(0).path("execution_id").asText());
            // 旧服务未在本用例启动；只恢复已知旧映射：每活动output=所属run最后delta的正文。
            var oldEnvelope = envelope.deepCopy();
            for (var activity : oldEnvelope.path("data").path("activities")) {
                var object = (com.fasterxml.jackson.databind.node.ObjectNode) activity;
                String body = bodies.get(activity.path("node_id").asText());
                if (body == null) object.putNull("output"); else object.put("output", body);
            }
            var stripped = oldEnvelope.deepCopy();
            for (var activity : stripped.path("data").path("activities")) ((com.fasterxml.jackson.databind.node.ObjectNode) activity).remove("output");
            assertEquals(envelope, stripped, "除output外整个信封字段、消息全文与引用必须保持一致");
            assertEquals(data.path("messages"), oldEnvelope.path("data").path("messages"));
            String currentSerialized = json.writeValueAsString(envelope);
            String oldSerialized = json.writeValueAsString(oldEnvelope);
            int outsidePage = 0;
            for (int i = 1; i <= 120; i++) {
                String marker = "history-body-" + i + "|";
                assertTrue(oldSerialized.contains(marker));
                if (i < first || i >= first + 30) { assertFalse(currentSerialized.contains(marker)); outsidePage++; }
            }
            assertEquals(90, outsidePage);
            long currentHttpBodyBytes = (pageIndex == 0 ? latest.body() : previous.body()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            long currentEnvelopeBytes = json.writeValueAsBytes(envelope).length;
            long oldEnvelopeBytes = json.writeValueAsBytes(oldEnvelope).length;
            long currentDataBytes = json.writeValueAsBytes(data).length;
            long oldDataBytes = json.writeValueAsBytes(oldEnvelope.path("data")).length;
            assertTrue(currentEnvelopeBytes < oldEnvelopeBytes); assertTrue(currentDataBytes < oldDataBytes);
            assertEquals(oldEnvelopeBytes - currentEnvelopeBytes, oldDataBytes - currentDataBytes);
            System.out.printf("chat-history synthetic-public-facts page=%d messages=30 activities=122 actualHttpBodyUtf8Bytes=%d currentEnvelopeUtf8Bytes=%d reconstructedOldEnvelopeUtf8Bytes=%d currentDataUtf8Bytes=%d reconstructedOldDataUtf8Bytes=%d%n",
                    pageIndex, currentHttpBodyBytes, currentEnvelopeBytes, oldEnvelopeBytes, currentDataBytes, oldDataBytes);
        }
        assertEquals(latestData.path("activities"), previousTree.path("data").path("activities"));
        assertEquals(latestData.path("pending_interactions"), previousTree.path("data").path("pending_interactions"));
        // 当前入口仍拒绝同渠道另一终端身份；不能靠知道Session ID读取该历史。
        String originalIdentity = terminalIdentities.get(token);
        var otherProfile = call("POST", "/api/v1/chat/client-profile", Map.of("client_ref", UUID.randomUUID().toString(), "display_name", "合成历史访客"), false);
        assertEquals(200, otherProfile.statusCode(), otherProfile.body());
        assertEquals(403, call("GET", "/api/v1/chat/history?session_id=" + session + "&limit=30", null, false).statusCode());
        terminalIdentities.put(token, originalIdentity);
        assertEquals(200, call("GET", "/api/v1/chat/history?session_id=" + session + "&limit=30", null, false).statusCode());
    }

    /** 仅供本用例的合成公开事实；runtimeRunId明确是fixture身份，不创建Runtime执行。 */
    private void appendHistoryFact(String tenant, String session, String prefix, int i, String event,
            Map<String, Object> data, long sequence) {
        String run = i == 121 ? prefix + "run-waiting" : i == 122 ? prefix + "run-suspended" : prefix + "run-" + i;
        var trace = new com.skillforge.core.shared.tracing.TraceContext(prefix + "input-" + i, prefix + "root-" + i,
                prefix + "trace-" + i, prefix + "span-" + i, prefix + "source-" + sequence, null);
        var saved = historyMailbox.appendOutput(tenant, session, prefix + event + "-" + i, event,
                Map.of("event", event, "data", data), trace, run);
        assertEquals(sequence, saved.getSessionSequence());
    }

    @Test void onboardingCommitsBindingAndReceiptWithoutPersistingToken() throws Exception {
        String slug = "channel-onboard-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String password = "ChannelManagement123";
        initializeChannelAdministrator(tenant, "channel-admin", password);
        long published = agent(tenant, "published"), draft = agent(tenant, "draft");
        String jwt = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "channel-admin", "password", password), null).path("access_token").asText();
        String requestId = UUID.randomUUID().toString();
        var command = Map.of("request_id", requestId, "channel", Map.of("name", "合并接入",
                "channel_type", "web_widget", "agent_bindings", List.of(Map.of(
                        "agent_id", Long.toString(published), "is_default", true, "priority", 0))));
        var result = managementHttp("POST", "/api/v1/channels/onboarding", command, jwt);
        String channelId = result.path("channel_id").asText();
        assertTrue(result.path("channel").path("id").isTextual());
        assertEquals(channelId, result.path("channel").path("id").asText());
        String issued = result.path("channel").path("channel_token").asText();
        assertFalse(issued.isBlank());
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_channel_agent_bindings WHERE tenant_id=? AND channel_id=? AND agent_id=? AND is_default", Long.valueOf(tenant), Long.valueOf(channelId), published));
        var detail = managementHttp("GET", "/api/v1/channels/detail?channelId=" + channelId, null, jwt);
        assertTrue(detail.path("id").isTextual());
        assertEquals(channelId, detail.path("id").asText());
        assertTrue(detail.path("agent_bindings").get(0).path("id").isTextual());
        assertEquals(Long.toString(published), detail.path("agent_bindings").get(0).path("id").asText());
        var receipt = managementHttp("GET", "/api/v1/channels/onboarding/result?request_id=" + requestId, null, jwt);
        assertEquals(channelId, receipt.path("channel_id").asText());
        assertFalse(receipt.toString().contains(issued));
        assertFalse(managementHttp("POST", "/api/v1/channels/onboarding", command, jwt).toString().contains(issued));
        assertFalse(queryRows(jdbc, tenant, "SELECT * FROM nw_channel_create_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), requestId).toString().contains(issued));
        var conflicting = Map.of("request_id", requestId, "channel", Map.of("name", "不同请求", "channel_type", "web_widget"));
        var conflictRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/channels/onboarding"))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(conflicting))).build();
        var conflict = http.send(conflictRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, conflict.statusCode());
        assertNotEquals("CHANNEL_ONBOARDING_REJECTED", json.readTree(conflict.body()).path("code").asText());
        assertEquals(channelId, managementHttp("GET", "/api/v1/channels/onboarding/result?request_id=" + requestId, null, jwt).path("channel_id").asText());
        String invalidId = UUID.randomUUID().toString();
        var invalid = Map.of("request_id", invalidId, "channel", Map.of("name", "回滚渠道", "channel_type", "web_widget",
                "agent_bindings", List.of(Map.of("agent_id", Long.toString(draft), "is_default", true))));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/channels/onboarding"))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(invalid))).build();
        var rejected = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(422, rejected.statusCode());
        assertEquals("CHANNEL_ONBOARDING_REJECTED", json.readTree(rejected.body()).path("code").asText());
        assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_channels WHERE tenant_id=? AND name=?", Long.valueOf(tenant), "回滚渠道"));
        assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_channel_create_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), invalidId));
    }

    @Test void webhookPauseReceiptRecordsActualChangeAndIsOwnedByOriginalMember() throws Exception {
        String slug = "webhook-pause-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String password = "WebhookManagement123";
        createTestTenantAdministrator(tenant, "pause-owner", password);
        String owner = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "pause-owner", "password", password), null).path("access_token").asText();
        var created = managementHttp("POST", "/api/v1/webhook-subscriptions/create-with-receipt", Map.of(
                "request_id", UUID.randomUUID().toString(), "endpoint_url", "https://127.0.0.1:1/pause-receipt",
                "signing_secret", "synthetic-pause-secret"), owner);
        long subscriptionId = created.path("subscription_id").asLong();
        assertTrue(subscriptionId > 0);
        String path = "/api/v1/webhook-subscriptions/pause-with-receipt";
        String requestId = UUID.randomUUID().toString();
        var command = Map.of("request_id", requestId, "subscription_id", subscriptionId);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + owner).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        var a = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var b = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var first = a.get(30, TimeUnit.SECONDS); var second = b.get(30, TimeUnit.SECONDS);
        assertEquals(200, first.statusCode()); assertEquals(200, second.statusCode());
        var receipt = json.readTree(first.body()).path("data");
        assertEquals(receipt, json.readTree(second.body()).path("data"));
        assertEquals(requestId, receipt.path("request_id").asText());
        assertEquals(Long.toString(subscriptionId), receipt.path("subscription_id").asText());
        assertTrue(receipt.path("changed").asBoolean());
        assertEquals(receipt, managementHttp("GET", path + "/result?request_id=" + requestId, null, owner));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_webhook_pause_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), requestId));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_chat_session_event_subscriptions WHERE tenant_id=? AND id=? AND status='paused'", Long.valueOf(tenant), subscriptionId));
        var conflict = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + owner).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("request_id", requestId, "subscription_id", subscriptionId + 1)))).build();
        assertEquals(409, http.send(conflict, HttpResponse.BodyHandlers.ofString()).statusCode());
        String againId = UUID.randomUUID().toString();
        var again = managementHttp("POST", path, Map.of("request_id", againId, "subscription_id", subscriptionId), owner);
        assertFalse(again.path("changed").asBoolean());
        assertEquals(again, managementHttp("GET", path + "/result?request_id=" + againId, null, owner));
        String missingId = UUID.randomUUID().toString();
        var missing = managementHttp("POST", path, Map.of("request_id", missingId, "subscription_id", subscriptionId + 1), owner);
        assertFalse(missing.path("changed").asBoolean());
        assertEquals(missing, managementHttp("GET", path + "/result?request_id=" + missingId, null, owner));
        var notFound = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/result?request_id=" + UUID.randomUUID()))
                .header("Authorization", "Bearer " + owner).GET().build();
        assertEquals(204, http.send(notFound, HttpResponse.BodyHandlers.ofString()).statusCode());

        var member = new com.skillforge.core.foundation.persistence.TenantMemberDO();
        member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.valueOf(tenant)); member.setUsername("pause-other");
        member.setRole("member"); member.setPermissions(List.of()); member.setStatus("active");
        member.setPasswordHash(webhookPasswords.encode(password)); member.setCreatedAt(java.time.Instant.now());
        webhookMembers.insert(member);
        jdbc.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name) VALUES(?,?,?)", tenant, member.getId(), "其他测试成员");
        long role = SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(?,?,?,'Webhook 暂停测试角色','all')", role, tenant, "webhook_pause_" + role);
        for (String permission : List.of("foundation.webhooks.read", "foundation.webhooks.update"))
            jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,?)", tenant, role, permission);
        jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)", tenant, member.getId(), role);
        String other = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "pause-other", "password", password), null).path("access_token").asText();
        var foreignRead = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/result?request_id=" + requestId))
                .header("Authorization", "Bearer " + other).GET().build();
        assertEquals(204, http.send(foreignRead, HttpResponse.BodyHandlers.ofString()).statusCode());
        var foreignReplay = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + other).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        assertEquals(409, http.send(foreignReplay, HttpResponse.BodyHandlers.ofString()).statusCode());

        long deniedSubscription = managementHttp("POST", "/api/v1/webhook-subscriptions/create-with-receipt", Map.of(
                "request_id", UUID.randomUUID().toString(), "endpoint_url", "https://127.0.0.1:1/pause-denied",
                "signing_secret", "synthetic-pause-denied-secret"), owner).path("subscription_id").asLong();
        String deniedRequestId = UUID.randomUUID().toString();
        for (String permission : List.of("foundation.webhooks.read", "foundation.webhooks.update")) {
            String username = permission.endsWith("read") ? "pause-reader" : "pause-updater";
            var limitedMember = new com.skillforge.core.foundation.persistence.TenantMemberDO();
            limitedMember.setId(SnowflakeIds.nextId()); limitedMember.setTenantId(Long.valueOf(tenant));
            limitedMember.setUsername(username); limitedMember.setRole("member"); limitedMember.setPermissions(List.of());
            limitedMember.setStatus("active"); limitedMember.setPasswordHash(webhookPasswords.encode(password));
            limitedMember.setCreatedAt(java.time.Instant.now()); webhookMembers.insert(limitedMember);
            jdbc.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name) VALUES(?,?,?)", tenant, limitedMember.getId(), "受限测试成员");
            long limitedRole = SnowflakeIds.nextId();
            jdbc.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(?,?,?,'Webhook 受限测试角色','all')", limitedRole, tenant, "pause_limited_" + limitedRole);
            jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,?)", tenant, limitedRole, permission);
            jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)", tenant, limitedMember.getId(), limitedRole);
            String limited = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                    "username", username, "password", password), null).path("access_token").asText();
            var denied = permission.endsWith("read")
                    ? HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Authorization", "Bearer " + limited).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("request_id", deniedRequestId, "subscription_id", deniedSubscription)))).build()
                    : HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/result?request_id=" + requestId))
                        .header("Authorization", "Bearer " + limited).GET().build();
            var deniedResponse = http.send(denied, HttpResponse.BodyHandlers.ofString());
            assertEquals(403, deniedResponse.statusCode());
            if (permission.endsWith("read")) {
                assertEquals("WEBHOOK_PAUSE_REJECTED", json.readTree(deniedResponse.body()).path("code").asText());
                assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_webhook_pause_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), deniedRequestId));
                assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_chat_session_event_subscriptions WHERE tenant_id=? AND id=? AND status='active'", Long.valueOf(tenant), deniedSubscription));
            }
        }

        String foreignSlug = "webhook-pause-other-" + SnowflakeIds.nextId();
        TenantContext.clear();
        String foreignTenant = createTenant(tenants, plans, foreignSlug);
        TenantContext.setTenantId(foreignTenant);
        createTestTenantAdministrator(foreignTenant, "pause-foreign", password);
        String foreign = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", foreignSlug,
                "username", "pause-foreign", "password", password), null).path("access_token").asText();
        var crossTenantRead = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/result?request_id=" + requestId))
                .header("Authorization", "Bearer " + foreign).GET().build();
        assertEquals(204, http.send(crossTenantRead, HttpResponse.BodyHandlers.ofString()).statusCode());
        String crossId = UUID.randomUUID().toString();
        var crossPause = managementHttp("POST", path, Map.of("request_id", crossId, "subscription_id", subscriptionId), foreign);
        assertFalse(crossPause.path("changed").asBoolean());
        assertEquals(crossPause, managementHttp("GET", path + "/result?request_id=" + crossId, null, foreign));

        String roleName = "webhook_pause_rls_" + SnowflakeIds.nextId();
        boolean roleCreated = false;
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                var current = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user");
                assertTrue(current.next());
                if (current.getBoolean(1)) {
                    statement.execute("CREATE ROLE " + roleName + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
                    statement.execute("GRANT USAGE ON SCHEMA public TO " + roleName);
                    statement.execute("GRANT SELECT ON nw_webhook_pause_receipts TO " + roleName);
                    connection.commit(); roleCreated = true;
                    statement.execute("SET LOCAL ROLE " + roleName);
                }
                var restricted = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user");
                assertTrue(restricted.next()); assertFalse(restricted.getBoolean(1));
            }
            try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)");
                 var count = connection.prepareStatement("SELECT count(*) FROM nw_webhook_pause_receipts WHERE request_id=CAST(? AS uuid)")) {
                count.setString(1, requestId);
                bind.setString(1, foreignTenant); bind.execute();
                try (var rows = count.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
                bind.setString(1, tenant); bind.execute();
                try (var rows = count.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getLong(1)); }
            }
            connection.rollback();
        } finally {
            if (roleCreated) {
                try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection(); var statement = connection.createStatement()) {
                    statement.execute("DROP OWNED BY " + roleName);
                    statement.execute("DROP ROLE " + roleName);
                }
            }
        }
    }

    @Test void webhookCreateReceiptIsAtomicAndOwnedByOriginalMember() throws Exception {
        String slug = "webhook-receipt-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String password = "WebhookManagement123";
        createTestTenantAdministrator(tenant, "webhook-owner", password);
        String owner = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "webhook-owner", "password", password), null).path("access_token").asText();
        String requestId = UUID.randomUUID().toString();
        String path = "/api/v1/webhook-subscriptions/create-with-receipt";
        String signingSecret = "synthetic-webhook-secret-" + UUID.randomUUID();
        String endpoint = "https://127.0.0.1:1/receipt-test";
        var command = Map.of("request_id", requestId, "endpoint_url", endpoint, "signing_secret", signingSecret);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + owner).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        var a = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var b = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var first = a.get(30, TimeUnit.SECONDS); var second = b.get(30, TimeUnit.SECONDS);
        assertEquals(200, first.statusCode()); assertEquals(200, second.statusCode());
        var receipt = json.readTree(first.body()).path("data");
        assertEquals(receipt, json.readTree(second.body()).path("data"));
        assertEquals(requestId, receipt.path("request_id").asText());
        String subscriptionId = receipt.path("subscription_id").asText();
        assertFalse(subscriptionId.isBlank());
        assertFalse(first.body().contains(signingSecret)); assertFalse(second.body().contains(signingSecret));
        assertEquals(receipt, managementHttp("GET", path + "/result?request_id=" + requestId, null, owner));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_webhook_create_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), requestId));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_chat_session_event_subscriptions WHERE tenant_id=? AND endpoint_url=?", Long.valueOf(tenant), endpoint));
        assertFalse(queryRows(jdbc, tenant, "SELECT * FROM nw_webhook_create_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), requestId).toString().contains(signingSecret));
        for (var altered : List.of(
                Map.of("request_id", requestId, "endpoint_url", endpoint + "-other", "signing_secret", signingSecret),
                Map.of("request_id", requestId, "endpoint_url", endpoint, "signing_secret", signingSecret + "-other"),
                Map.of("request_id", requestId, "endpoint_url", endpoint, "signing_secret", signingSecret, "session_id", "other-session"))) {
            var conflict = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Authorization", "Bearer " + owner).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(altered))).build();
            assertEquals(409, http.send(conflict, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        String rejectedId = UUID.randomUUID().toString();
        var invalid = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + owner).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("request_id", rejectedId,
                        "endpoint_url", "not-a-url", "signing_secret", signingSecret)))).build();
        var rejected = http.send(invalid, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, rejected.statusCode());
        assertEquals("WEBHOOK_CREATE_REJECTED", json.readTree(rejected.body()).path("code").asText());
        assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_webhook_create_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), rejectedId));

        var member = new com.skillforge.core.foundation.persistence.TenantMemberDO();
        member.setId(SnowflakeIds.nextId()); member.setTenantId(Long.valueOf(tenant)); member.setUsername("webhook-other");
        member.setRole("member"); member.setPermissions(List.of()); member.setStatus("active");
        member.setPasswordHash(webhookPasswords.encode(password)); member.setCreatedAt(java.time.Instant.now());
        webhookMembers.insert(member);
        jdbc.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name) VALUES(?,?,?)", tenant, member.getId(), "其他测试成员");
        long role = SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(?,?,?,'Webhook 测试角色','all')", role, tenant, "webhook_" + role);
        for (String permission : List.of("foundation.webhooks.read", "foundation.webhooks.create"))
            jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,?)", tenant, role, permission);
        jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)", tenant, member.getId(), role);
        String other = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "webhook-other", "password", password), null).path("access_token").asText();
        var foreignRead = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/result?request_id=" + requestId))
                .header("Authorization", "Bearer " + other).GET().build();
        assertEquals(204, http.send(foreignRead, HttpResponse.BodyHandlers.ofString()).statusCode());
        var foreignReplay = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + other).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        assertEquals(409, http.send(foreignReplay, HttpResponse.BodyHandlers.ofString()).statusCode());

        String foreignSlug = "webhook-other-" + SnowflakeIds.nextId();
        TenantContext.clear();
        String foreignTenant = createTenant(tenants, plans, foreignSlug);
        TenantContext.setTenantId(foreignTenant);
        createTestTenantAdministrator(foreignTenant, "webhook-foreign", password);
        String foreign = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", foreignSlug,
                "username", "webhook-foreign", "password", password), null).path("access_token").asText();
        var crossTenantRead = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/result?request_id=" + requestId))
                .header("Authorization", "Bearer " + foreign).GET().build();
        assertEquals(204, http.send(crossTenantRead, HttpResponse.BodyHandlers.ofString()).statusCode());
        String roleName = "webhook_receipt_rls_" + SnowflakeIds.nextId();
        boolean roleCreated = false;
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                var current = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user");
                assertTrue(current.next());
                if (current.getBoolean(1)) {
                    statement.execute("CREATE ROLE " + roleName + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
                    statement.execute("GRANT USAGE ON SCHEMA public TO " + roleName);
                    statement.execute("GRANT SELECT ON nw_webhook_create_receipts TO " + roleName);
                    connection.commit(); roleCreated = true;
                    statement.execute("SET LOCAL ROLE " + roleName);
                }
                var restricted = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user");
                assertTrue(restricted.next()); assertFalse(restricted.getBoolean(1));
            }
            try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)");
                 var count = connection.prepareStatement("SELECT count(*) FROM nw_webhook_create_receipts WHERE request_id=CAST(? AS uuid)")) {
                count.setString(1, requestId);
                bind.setString(1, foreignTenant); bind.execute();
                try (var rows = count.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
                bind.setString(1, tenant); bind.execute();
                try (var rows = count.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getLong(1)); }
            }
            connection.rollback();
        } finally {
            if (roleCreated) {
                try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection(); var statement = connection.createStatement()) {
                    statement.execute("DROP OWNED BY " + roleName);
                    statement.execute("DROP ROLE " + roleName);
                }
            }
        }
    }

    @Test void principalStatusReceiptRecordsActualChangeAndRevokesSuspendedIdentity() throws Exception {
        String slug = "principal-state-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String password = "ChannelManagement123";
        createTestTenantAdministrator(tenant, "state-admin", password);
        String jwt = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "state-admin", "password", password), null).path("access_token").asText();
        var issued = managementHttp("POST", "/api/v1/service-principals/issue-with-receipt", Map.of(
                "request_id", UUID.randomUUID().toString(), "integration_id", "state-test", "scopes", List.of("sessions.read")), jwt);
        long id = issued.path("principal_id").asLong();
        assertTrue(id > 0);
        String requestId = UUID.randomUUID().toString();
        String path = "/api/v1/service-principals/status-with-receipt";
        var command = Map.of("request_id", requestId, "principal_id", id, "action", "suspend");
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        var firstFuture = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var secondFuture = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var first = firstFuture.get(30, TimeUnit.SECONDS); var second = secondFuture.get(30, TimeUnit.SECONDS);
        assertEquals(200, first.statusCode()); assertEquals(200, second.statusCode());
        for (var response : List.of(first, second)) {
            var result = json.readTree(response.body()).path("data");
            assertEquals(requestId, result.path("request_id").asText());
            assertEquals(Long.toString(id), result.path("principal_id").asText());
            assertEquals("suspend", result.path("action").asText()); assertTrue(result.path("changed").asBoolean());
        }
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_principal_status_receipts WHERE tenant_id=? AND request_id=?::uuid AND changed=true", Long.valueOf(tenant), requestId));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_service_principals WHERE tenant_id=? AND id=? AND status='suspended'", Long.valueOf(tenant), id));
        var receipt = managementHttp("GET", path + "/result?request_id=" + requestId, null, jwt);
        assertTrue(receipt.path("changed").asBoolean());
        var conflict = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("request_id", requestId, "principal_id", id, "action", "revoke")))).build();
        assertEquals(409, http.send(conflict, HttpResponse.BodyHandlers.ofString()).statusCode());
        String revokeId = UUID.randomUUID().toString();
        var revoked = managementHttp("POST", path, Map.of("request_id", revokeId, "principal_id", id, "action", "revoke"), jwt);
        assertTrue(revoked.path("changed").asBoolean());
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_service_principals WHERE tenant_id=? AND id=? AND status='revoked'", Long.valueOf(tenant), id));
        String noChangeId = UUID.randomUUID().toString();
        var noChange = managementHttp("POST", path, Map.of("request_id", noChangeId, "principal_id", id, "action", "suspend"), jwt);
        assertTrue(noChange.has("changed")); assertFalse(noChange.path("changed").asBoolean());
        var noChangeReceipt = managementHttp("GET", path + "/result?request_id=" + noChangeId, null, jwt);
        assertTrue(noChangeReceipt.has("changed")); assertFalse(noChangeReceipt.path("changed").asBoolean());
        var missing = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/result?request_id=" + UUID.randomUUID()))
                .header("Authorization", "Bearer " + jwt).GET().build();
        assertEquals(204, http.send(missing, HttpResponse.BodyHandlers.ofString()).statusCode());
        String foreignSlug = "principal-state-other-" + SnowflakeIds.nextId();
        TenantContext.clear();
        String foreign = createTenant(tenants, plans, foreignSlug);
        TenantContext.setTenantId(foreign);
        createTestTenantAdministrator(foreign, "foreign-state-admin", password);
        String foreignJwt = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", foreignSlug,
                "username", "foreign-state-admin", "password", password), null).path("access_token").asText();
        var foreignRead = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/result?request_id=" + requestId))
                .header("Authorization", "Bearer " + foreignJwt).GET().build();
        assertEquals(204, http.send(foreignRead, HttpResponse.BodyHandlers.ofString()).statusCode());
        String role = "principal_status_rls_" + SnowflakeIds.nextId();
        boolean created = false;
        try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                var current = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user");
                assertTrue(current.next());
                if (current.getBoolean(1)) {
                    statement.execute("CREATE ROLE " + role + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
                    statement.execute("GRANT USAGE ON SCHEMA public TO " + role);
                    statement.execute("GRANT SELECT ON nw_principal_status_receipts TO " + role);
                    connection.commit(); created = true;
                    statement.execute("SET LOCAL ROLE " + role);
                }
                var restricted = statement.executeQuery("SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user");
                assertTrue(restricted.next()); assertFalse(restricted.getBoolean(1));
            }
            try (var bind = connection.prepareStatement("SELECT set_config('app.tenant_id',?,true)");
                 var count = connection.prepareStatement("SELECT count(*) FROM nw_principal_status_receipts WHERE request_id=CAST(? AS uuid)")) {
                count.setString(1, requestId);
                bind.setString(1, foreign); bind.execute();
                try (var rows = count.executeQuery()) { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
                bind.setString(1, tenant); bind.execute();
                try (var rows = count.executeQuery()) { assertTrue(rows.next()); assertEquals(1, rows.getLong(1)); }
            }
            connection.rollback();
        } finally {
            if (created) {
                try (var connection = java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection(); var statement = connection.createStatement()) {
                    statement.execute("DROP OWNED BY " + role);
                    statement.execute("DROP ROLE " + role);
                }
            }
        }
    }

    @Test void principalIssueReceiptCommitsOnceAndRejectsDifferentInputs() throws Exception {
        String slug = "principal-issue-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String password = "ChannelManagement123";
        createTestTenantAdministrator(tenant, "issue-admin", password);
        String jwt = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "issue-admin", "password", password), null).path("access_token").asText();
        String requestId = UUID.randomUUID().toString();
        String path = "/api/v1/service-principals/issue-with-receipt";
        var command = Map.of("request_id", requestId, "integration_id", "receipt-test", "scopes", List.of("sessions.read"));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        var firstFuture = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var secondFuture = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var firstResponse = firstFuture.get(30, TimeUnit.SECONDS);
        var secondResponse = secondFuture.get(30, TimeUnit.SECONDS);
        assertEquals(200, firstResponse.statusCode()); assertEquals(200, secondResponse.statusCode());
        var first = json.readTree(firstResponse.body()).path("data");
        var second = json.readTree(secondResponse.body()).path("data");
        assertEquals(first.path("principal_id"), second.path("principal_id"));
        assertNotEquals(first.has("api_key"), second.has("api_key"));
        var fresh = first.has("api_key") ? first : second;
        String apiKey = fresh.path("api_key").asText(); String secret = fresh.path("signing_secret").asText();
        assertFalse(apiKey.isBlank()); assertFalse(secret.isBlank());
        var receipt = managementHttp("GET", path + "/result?request_id=" + requestId, null, jwt);
        var replay = managementHttp("POST", path, command, jwt);
        for (var result : List.of(receipt, replay)) {
            assertEquals(requestId, result.path("request_id").asText());
            assertEquals(first.path("principal_id"), result.path("principal_id"));
            assertFalse(result.has("api_key")); assertFalse(result.has("signing_secret"));
        }
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_service_principals WHERE tenant_id=? AND integration_id=? AND api_key_hash=?", Long.valueOf(tenant), "receipt-test", TokenHash.sha256Hex(apiKey)));
        String stored = queryRows(jdbc, tenant, "SELECT * FROM nw_principal_issue_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), requestId).toString();
        assertFalse(stored.contains(apiKey)); assertFalse(stored.contains(secret)); assertFalse(stored.contains("receipt-test"));
        var conflict = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("request_id", requestId, "integration_id", "other", "scopes", List.of("sessions.read"))))).build();
        assertEquals(409, http.send(conflict, HttpResponse.BodyHandlers.ofString()).statusCode());
        String rejectedId = UUID.randomUUID().toString();
        var invalid = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("request_id", rejectedId, "integration_id", "receipt-test", "scopes", List.of("INVALID SCOPE"))))).build();
        var rejected = http.send(invalid, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, rejected.statusCode());
        assertEquals("PRINCIPAL_ISSUE_REJECTED", json.readTree(rejected.body()).path("code").asText());
        assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_principal_issue_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), rejectedId));
    }

    @Test void principalRotationReceiptIsAtomicAndNeverReplaysSecret() throws Exception {
        String slug = "principal-receipt-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String password = "ChannelManagement123";
        createTestTenantAdministrator(tenant, "principal-admin", password);
        String jwt = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "principal-admin", "password", password), null).path("access_token").asText();
        var create = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/service-principals"))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("integration_id", "receipt",
                        "scopes", List.of("sessions.read"))))).build();
        var created = http.send(create, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, created.statusCode());
        long id = json.readTree(created.body()).path("data").path("principal").path("id").asLong();
        assertTrue(id > 0);
        String requestId = UUID.randomUUID().toString();
        String path = "/api/v1/service-principals/rotate-with-receipt";
        var command = Map.of("request_id", requestId, "principal_id", id);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        var firstFuture = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var secondFuture = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var firstResponse = firstFuture.get(30, TimeUnit.SECONDS);
        var secondResponse = secondFuture.get(30, TimeUnit.SECONDS);
        assertEquals(200, firstResponse.statusCode());
        assertEquals(200, secondResponse.statusCode());
        var first = json.readTree(firstResponse.body()).path("data");
        var second = json.readTree(secondResponse.body()).path("data");
        assertNotEquals(first.has("api_key"), second.has("api_key"));
        var fresh = first.has("api_key") ? first : second;
        String apiKey = fresh.path("api_key").asText();
        String secret = fresh.path("signing_secret").asText();
        assertFalse(apiKey.isBlank()); assertFalse(secret.isBlank());
        var receipt = managementHttp("GET", path + "/result?request_id=" + requestId, null, jwt);
        var replay = managementHttp("POST", path, command, jwt);
        for (var result : List.of(receipt, replay)) {
            assertEquals(requestId, result.path("request_id").asText());
            assertEquals(Long.toString(id), result.path("principal_id").asText());
            assertFalse(result.has("api_key")); assertFalse(result.has("signing_secret"));
        }
        String stored = queryRows(jdbc, tenant, "SELECT * FROM nw_principal_rotation_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), requestId).toString();
        assertFalse(stored.contains(apiKey)); assertFalse(stored.contains(secret));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_principal_rotation_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), requestId));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_service_principals WHERE tenant_id=? AND id=? AND credential_version=2 AND api_key_hash=?", Long.valueOf(tenant), id, TokenHash.sha256Hex(apiKey)));
        managementHttp("POST", "/api/v1/service-principals/suspend", Map.of("id", id), jwt);
        String rejectedId = UUID.randomUUID().toString();
        var rejectedRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("request_id", rejectedId, "principal_id", id)))).build();
        var rejected = http.send(rejectedRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(409, rejected.statusCode());
        assertEquals("PRINCIPAL_ROTATION_REJECTED", json.readTree(rejected.body()).path("code").asText());
        assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_principal_rotation_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), rejectedId));
    }

    @Test void credentialReceiptsCannotBeReadAcrossTenants() throws Exception {
        String suffix = Long.toString(SnowflakeIds.nextId());
        String ownerSlug = "receipt-owner-" + suffix;
        String otherSlug = "receipt-other-" + suffix;
        String owner = createTenant(tenants, plans, ownerSlug);
        String other = createTenant(tenants, plans, otherSlug);
        String password = "ChannelManagement123";
        TenantContext.setTenantId(owner);
        initializeChannelAdministrator(owner, "owner-admin", password);
        String ownerJwt = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", ownerSlug,
                "username", "owner-admin", "password", password), null).path("access_token").asText();
        long id = managementHttp("POST", "/api/v1/channels", Map.of("name", "isolated receipt",
                "channel_type", "openapi", "config", Map.of(), "ui_config", Map.of()), ownerJwt).path("id").asLong();
        String keyRequest = UUID.randomUUID().toString();
        String tokenRequest = UUID.randomUUID().toString();
        String keyPath = "/api/v1/channels/api-keys/issue-with-receipt";
        String tokenPath = "/api/v1/channels/tokens/rotate-with-receipt";
        managementHttp("POST", keyPath, Map.of("request_id", keyRequest, "channel_id", id), ownerJwt);
        managementHttp("POST", tokenPath, Map.of("request_id", tokenRequest, "channel_id", id), ownerJwt);
        TenantContext.setTenantId(other);
        initializeChannelAdministrator(other, "other-admin", password);
        String otherJwt = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", otherSlug,
                "username", "other-admin", "password", password), null).path("access_token").asText();
        for (var pair : Map.of(keyPath, keyRequest, tokenPath, tokenRequest).entrySet()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + pair.getKey()
                    + "/result?request_id=" + pair.getValue()))
                    .header("Authorization", "Bearer " + otherJwt).GET().build();
            assertEquals(204, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void tokenRotationReceiptCommitsOnceWithoutPersistingOrReplayingPlaintext() throws Exception {
        String slug = "token-receipt-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String password = "ChannelManagement123";
        initializeChannelAdministrator(tenant, "token-receipt-admin", password);
        String jwt = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "token-receipt-admin", "password", password), null).path("access_token").asText();
        var created = managementHttp("POST", "/api/v1/channels", Map.of("name", "receipt widget",
                "channel_type", "web_widget", "config", Map.of(), "ui_config", Map.of()), jwt);
        long id = created.path("id").asLong();
        String oldToken = created.path("channel_token").asText();
        assertFalse(oldToken.isBlank());
        String requestId = UUID.randomUUID().toString();
        var command = Map.of("request_id", requestId, "channel_id", id);
        String path = "/api/v1/channels/tokens/rotate-with-receipt";
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command))).build();
        var firstFuture = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var secondFuture = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var firstResponse = firstFuture.get(30, TimeUnit.SECONDS);
        var secondResponse = secondFuture.get(30, TimeUnit.SECONDS);
        assertEquals(200, firstResponse.statusCode(), firstResponse.body());
        assertEquals(200, secondResponse.statusCode(), secondResponse.body());
        var first = json.readTree(firstResponse.body()).path("data");
        var second = json.readTree(secondResponse.body()).path("data");
        assertNotEquals(first.has("channel_token"), second.has("channel_token"), "同一请求只允许一次返回明文");
        String rotated = (first.has("channel_token") ? first : second).path("channel_token").asText();
        assertFalse(rotated.isBlank()); assertNotEquals(oldToken, rotated);
        for (var result : List.of(first, second)) {
            assertEquals(requestId, result.path("request_id").asText());
            assertEquals(Long.toString(id), result.path("channel_id").asText());
        }
        String hash = channels.selectById(id).getChannelTokenHash();
        assertEquals(TokenHash.sha256Hex(rotated), hash);
        var receipt = managementHttp("GET", path + "/result?request_id=" + requestId, null, jwt);
        var replay = managementHttp("POST", path, command, jwt);
        for (var result : List.of(receipt, replay)) {
            assertEquals(requestId, result.path("request_id").asText());
            assertEquals(Long.toString(id), result.path("channel_id").asText());
            assertFalse(result.has("channel_token"));
        }
        assertEquals(hash, channels.selectById(id).getChannelTokenHash());
        String stored = queryRows(jdbc, tenant, "SELECT * FROM nw_channel_token_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), requestId).toString();
        assertFalse(stored.contains(rotated)); assertFalse(stored.contains(oldToken));
        assertEquals(1, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_channel_token_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), requestId));
        long otherId = managementHttp("POST", "/api/v1/channels", Map.of("name", "other widget",
                "channel_type", "web_widget", "config", Map.of(), "ui_config", Map.of()), jwt).path("id").asLong();
        var conflict = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("request_id", requestId, "channel_id", otherId)))).build();
        assertEquals(409, http.send(conflict, HttpResponse.BodyHandlers.ofString()).statusCode());
        String missingId = UUID.randomUUID().toString();
        var missing = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/result?request_id=" + missingId))
                .header("Authorization", "Bearer " + jwt).GET().build();
        assertEquals(204, http.send(missing, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test void keyReceiptCommitsOnceWithoutPersistingOrReplayingPlaintext() throws Exception {
        String slug = "key-receipt-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        TenantContext.setTenantId(tenant);
        String password = "ChannelManagement123";
        initializeChannelAdministrator(tenant, "receipt-admin", password);
        String jwt = managementHttp("POST", "/api/v1/auth/login", Map.of("tenant_slug", slug,
                "username", "receipt-admin", "password", password), null).path("access_token").asText();
        long id = managementHttp("POST", "/api/v1/channels", Map.of("name", "receipt openapi",
                "channel_type", "openapi", "config", Map.of(), "ui_config", Map.of()), jwt).path("id").asLong();
        String requestId = UUID.randomUUID().toString();
        var command = Map.of("request_id", requestId, "channel_id", id);
        String path = "/api/v1/channels/api-keys/issue-with-receipt";
        var first = managementHttp("POST", path, command, jwt);
        String issuedKey = first.path("api_key").asText(), issuedSecret = first.path("api_secret").asText();
        assertFalse(issuedKey.isBlank()); assertFalse(issuedSecret.isBlank());
        assertEquals(requestId, first.path("request_id").asText());
        assertEquals(Long.toString(id), first.path("channel_id").asText());
        String hash = channels.selectById(id).getApiKeyHash();
        assertEquals(TokenHash.sha256Hex(issuedKey), hash);
        var receipt = managementHttp("GET", path + "/result?request_id=" + requestId, null, jwt);
        var replay = managementHttp("POST", path, command, jwt);
        for (var response : List.of(receipt, replay)) {
            assertEquals(Long.toString(id), response.path("channel_id").asText());
            assertFalse(response.has("api_key")); assertFalse(response.has("api_secret"));
        }
        assertEquals(hash, channels.selectById(id).getApiKeyHash());
        String stored = queryRows(jdbc, tenant, "SELECT * FROM nw_channel_key_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), requestId).toString();
        assertFalse(stored.contains(issuedKey)); assertFalse(stored.contains(issuedSecret));
        long widget = managementHttp("POST", "/api/v1/channels", Map.of("name", "receipt widget",
                "channel_type", "web_widget", "config", Map.of(), "ui_config", Map.of()), jwt).path("id").asLong();
        var conflicting = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("request_id", requestId, "channel_id", widget)))).build();
        assertEquals(409, http.send(conflicting, HttpResponse.BodyHandlers.ofString()).statusCode());
        String invalidId = UUID.randomUUID().toString();
        var missing = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path + "/result?request_id=" + invalidId))
                .header("Authorization", "Bearer " + jwt).GET().build();
        assertEquals(204, http.send(missing, HttpResponse.BodyHandlers.ofString()).statusCode());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + jwt).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("request_id", invalidId, "channel_id", widget)))).build();
        var rejected = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(422, rejected.statusCode());
        assertEquals("CHANNEL_KEY_REJECTED", json.readTree(rejected.body()).path("code").asText());
        assertEquals(0, queryCount(jdbc, tenant, "SELECT count(*) FROM nw_channel_key_receipts WHERE tenant_id=? AND request_id=?::uuid", Long.valueOf(tenant), invalidId));
        assertNull(channels.selectById(widget).getApiKeyHash());
    }

    @Test void managementIssueRotationAndRevocationControlRealHttpAuthentication() throws Exception {
        String tenantSlug = "api-key-lifecycle-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, tenantSlug);
        TenantContext.setTenantId(tenant);
        long tenantId = Long.parseLong(tenant);
        String username = "channel-admin";
        String password = "ChannelManagement123";
        initializeChannelAdministrator(tenant, username, password);
        var login = managementHttp("POST", "/api/v1/auth/login",
                Map.of("tenant_slug", tenantSlug, "username", username, "password", password), null);
        String adminJwt = login.path("access_token").asText();
        assertFalse(adminJwt.isBlank()); assertEquals(tenant, login.path("tenant_id").asText());

        var created = managementHttp("POST", "/api/v1/channels",
                Map.of("name", "managed openapi", "channel_type", "openapi",
                        "config", Map.of("label", "initial"), "ui_config", Map.of()), adminJwt);
        long channelId = created.path("id").asLong();
        assertTrue(channelId > 0);
        token = created.path("channel_token").asText(); assertFalse(token.isBlank());
        String detailPath = "/api/v1/channels/detail?channelId=" + channelId;
        var command = Map.of("channel_id", channelId);
        assertFalse(managementHttp("GET", detailPath, null, adminJwt).path("api_key_issued").asBoolean());
        var first = managementHttp("POST", "/api/v1/channels/api-keys", command, adminJwt);
        key = first.path("api_key").asText(); secret = first.path("api_secret").asText();
        assertFalse(key.isBlank()); assertFalse(secret.isBlank());
        assertEquals(200, call("GET", "/api/v1/chat/agents", null, true).statusCode());
        var stored = channels.selectById(channelId);
        assertEquals(TokenHash.sha256Hex(key), stored.getApiKeyHash());
        assertEquals(secret, crypto.decrypt(stored.getApiSecretEncrypted()));
        assertFalse(stored.getConfig().containsKey("api_key_hash"));
        assertFalse(stored.getConfig().containsKey("api_secret_encrypted"));

        var patch = managementHttp("PATCH", "/api/v1/channels/update",
                Map.of("channel_id", channelId,
                        "payload", Map.of("name", "updated", "config", Map.of("label", "updated"))), adminJwt);
        assertEquals("updated", patch.path("name").asText());
        assertEquals("updated", patch.path("config").path("label").asText());
        var updated = channels.selectById(channelId);
        assertEquals(stored.getApiKeyHash(), updated.getApiKeyHash());
        assertEquals(stored.getApiSecretEncrypted(), updated.getApiSecretEncrypted());
        assertEquals(200, call("GET", "/api/v1/chat/agents", null, true).statusCode());
        var summary = managementHttp("GET", detailPath, null, adminJwt);
        assertTrue(summary.path("api_key_issued").asBoolean());
        for (var projected : List.of(patch, summary)) {
            assertFalse(projected.toString().contains(key));
            assertFalse(projected.toString().contains(secret));
            assertFalse(projected.toString().contains(updated.getApiKeyHash()));
            assertFalse(projected.toString().contains(updated.getApiSecretEncrypted()));
        }

        var second = managementHttp("POST", "/api/v1/channels/api-keys/rotate", command, adminJwt);
        assertNotEquals(first.path("api_key").asText(), second.path("api_key").asText());
        assertNotEquals(first.path("api_secret").asText(), second.path("api_secret").asText());
        assertEquals(401, call("GET", "/api/v1/chat/agents", null, true).statusCode(), "轮换后旧 key 必须失效");
        key = second.path("api_key").asText(); secret = second.path("api_secret").asText();
        assertEquals(200, call("GET", "/api/v1/chat/agents", null, true).statusCode());
        var revokedResponse = managementHttp("DELETE", "/api/v1/channels/api-keys", command, adminJwt);
        assertEquals("revoked", revokedResponse.path("status").asText());
        assertEquals(401, call("GET", "/api/v1/chat/agents", null, true).statusCode(), "撤销后当前 key 必须失效");
        assertFalse(managementHttp("GET", detailPath, null, adminJwt).path("api_key_issued").asBoolean());
        var revoked = channels.selectById(channelId);
        assertNull(revoked.getApiKeyHash()); assertNull(revoked.getApiSecretEncrypted());
        assertEquals(Map.of("label", "updated"), revoked.getConfig());
        assertEquals(200, call("GET", "/api/v1/chat/agents", null, false).statusCode(), "独立的渠道 token 仍有效");
    }

    @Test void configUpdateWaitingOnRowLockPreservesRotatedCredentials() throws Exception {
        assertConfigUpdatePreservesConcurrentCredentials(false);
    }

    @Test void configUpdateWaitingOnRowLockCannotRestoreRevokedCredentials() throws Exception {
        assertConfigUpdatePreservesConcurrentCredentials(true);
    }

    /** 两个真实事务由数据库阻塞关系确认重叠；更新读到的必须是锁释放后的凭据。 */
    private void assertConfigUpdatePreservesConcurrentCredentials(boolean revoke) throws Exception {
        String tenant = createTenant(tenants, plans, "credential-config-race-" + SnowflakeIds.nextId());
        TenantContext.setTenantId(tenant);
        long tenantId = Long.parseLong(tenant);
        long administrator = initializeChannelAdministrator(tenant, "concurrent-channel-admin", "ConcurrentChannel123");
        var channel = withAuthenticatedMember(administrator, () -> channelService.create(tenantId,
                new CreateChannelRequest("concurrent openapi", ChannelType.OPENAPI, Map.of("label", "before"), Map.of(), null)));
        var initial = withAuthenticatedMember(administrator, () -> channelService.issueApiKey(tenantId, channel.id()));
        token = channel.channelToken(); key = initial.apiKey(); secret = initial.apiSecret();
        assertEquals(200, call("GET", "/api/v1/chat/agents", null, true).statusCode(), "竞争前原凭据必须有效");
        var executor = Executors.newFixedThreadPool(2);
        var channelLocked = new CountDownLatch(1);
        var updateEntered = new CountDownLatch(1);
        var mutateCredentials = new CountDownLatch(1);
        var holderPid = new AtomicInteger();
        var updaterPid = new AtomicInteger();
        Future<ApiKeyIssueResponse> credentialChange = null;
        Future<ChannelResponse> configChange = null;
        try {
            credentialChange = executor.submit(() -> inChannelTransaction(tenant, administrator, () -> {
                holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                assertEquals(channel.id(), jdbc.queryForObject(
                        "SELECT id FROM nw_channels WHERE tenant_id=? AND id=? FOR UPDATE", Long.class, tenantId, channel.id()));
                channelLocked.countDown();
                awaitChannelLatch(mutateCredentials);
                if (revoke) {
                    channelService.revokeApiKey(tenantId, channel.id());
                    return null;
                }
                return channelService.issueApiKey(tenantId, channel.id());
            }));
            assertTrue(channelLocked.await(10, TimeUnit.SECONDS), "持锁事务必须先获得目标渠道行锁");
            configChange = executor.submit(() -> inChannelTransaction(tenant, administrator, () -> {
                updaterPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                updateEntered.countDown();
                return channelService.update(tenantId, channel.id(),
                        new PatchChannelRequest("concurrent update", Map.of("label", "after"), null, null));
            }));
            assertTrue(updateEntered.await(10, TimeUnit.SECONDS), "配置更新事务必须到达服务入口");
            awaitChannelDatabaseBlock(holderPid.get(), updaterPid.get(), configChange);
            assertFalse(configChange.isDone(), "配置更新必须实际等待同一渠道行锁");
            mutateCredentials.countDown();
            var issued = credentialChange.get(10, TimeUnit.SECONDS);
            var updated = configChange.get(10, TimeUnit.SECONDS);
            assertEquals(Map.of("label", "after"), updated.config());
            assertEquals(!revoke, updated.apiKeyIssued(), "配置更新必须基于锁释放后的最新凭据状态");
            var stored = channels.selectById(channel.id());
            assertEquals("concurrent update", stored.getName());
            assertEquals(Map.of("label", "after"), stored.getConfig());
            assertEquals(401, call("GET", "/api/v1/chat/agents", null, true).statusCode(), "并发配置更新不能恢复旧 API Key");
            if (revoke) {
                assertNull(stored.getApiKeyHash()); assertNull(stored.getApiSecretEncrypted());
            } else {
                assertNotNull(issued);
                assertNotEquals(initial.apiKey(), issued.apiKey());
                assertEquals(TokenHash.sha256Hex(issued.apiKey()), stored.getApiKeyHash());
                assertEquals(issued.apiSecret(), crypto.decrypt(stored.getApiSecretEncrypted()));
                key = issued.apiKey(); secret = issued.apiSecret();
                assertEquals(200, call("GET", "/api/v1/chat/agents", null, true).statusCode());
            }
        } finally {
            // 断言失败也先释放持锁事务，再中断并等待两条事务线程静止。
            mutateCredentials.countDown();
            if (credentialChange != null && !credentialChange.isDone()) credentialChange.cancel(true);
            if (configChange != null && !configChange.isDone()) configChange.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS), "竞争事务必须结束后才释放测试夹具");
        }
    }

    private <T> T inChannelTransaction(String tenant, long memberId, Supplier<T> operation) {
        String previous = TenantContext.getTenantId();
        TenantContext.setTenantId(tenant);
        try {
            return withAuthenticatedMember(memberId, () -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout = '15s'");
                return operation.get();
            }));
        } finally {
            if (previous == null) TenantContext.clear();
            else TenantContext.setTenantId(previous);
        }
    }

    private void awaitChannelLatch(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new AssertionError("未收到凭据变更事务释放信号");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("凭据变更事务被中断", interrupted);
        }
    }

    private void awaitChannelDatabaseBlock(int holderPid, int updaterPid, Future<?> updating) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                      WHERE pid=? AND wait_event_type='Lock'
                        AND CAST(? AS integer)=ANY(pg_blocking_pids(pid)))
                    """, Boolean.class, updaterPid, holderPid))) return;
            assertFalse(updating.isDone(), "配置更新在形成数据库行锁等待前提前结束");
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("渠道行锁观察被中断");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
        }
        throw new AssertionError("未观察到配置更新等待凭据事务的真实数据库行锁");
    }

    /** 本用例独立机构的真实管理员与渠道功能夹具，不改共享免费套餐。 */
    private long initializeChannelAdministrator(String tenant, String username, String password) {
        long memberId = createTestTenantAdministrator(tenant, username, password);
        withAuthenticatedMember(memberId, () -> {
            assertEquals(1, entitlements.insertTenantGrant(
                    new SaveTenantGrant(tenant, "channels", true, 0L), "chat-client-integration-fixture"));
            assertTrue(features.allowed(tenant, "channels"), "渠道创建必须具备当前真实功能授权");
            return null;
        });
        return memberId;
    }

    private long agent(String tenant, String status) {
        var agent = new AgentDO(); agent.setId(SnowflakeIds.nextId()); agent.setTenantId(Long.parseLong(tenant));
        agent.setAgentKey("chat-agent-" + agent.getId()); agent.setName("chat agent"); agent.setStatus("draft");
        agent.setSystemPrompt("测试助手"); agents.insert(agent);
        if ("published".equals(status)) {
            var publication = agentPublications.publish(agent);
            assertNotNull(publication.revisionId());
            assertEquals(agent.getId(), publication.agent().getId());
        }
        return agent.getId();
    }
    private long bind(String tenant, long channel, long agent) {
        var binding = new ChannelAgentBindingDO(); binding.setId(SnowflakeIds.nextId()); binding.setTenantId(Long.parseLong(tenant));
        binding.setChannelId(channel); binding.setAgentId(agent); bindings.insert(binding); return binding.getId();
    }
    /** 管理面使用真实管理员 JWT，响应只从当前 HTTP 信封读取业务数据。 */
    private JsonNode managementHttp(String method, String path, Object payload, String jwt) throws Exception {
        String body = payload == null ? "" : json.writeValueAsString(payload);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .header("Accept", "application/json").method(method, body.isEmpty()
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (jwt != null) request.header("Authorization", "Bearer " + jwt);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var envelope = json.readTree(response.body());
        assertEquals("OK", envelope.path("code").asText());
        assertFalse(envelope.path("trace_id").asText().isBlank());
        assertTrue(envelope.has("data"));
        return envelope.path("data");
    }
    private HttpResponse<String> call(String method, String path, Object payload, boolean signed) throws Exception {
        return call(method, path, payload, signed, "application/json");
    }
    private HttpResponse<String> call(String method, String path, Object payload, boolean signed, String accept) throws Exception {
        String body = payload == null ? "" : json.writeValueAsString(payload);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Accept", accept).method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (signed) {
            String ts = Long.toString(System.currentTimeMillis() / 1000);
            request.header("X-Api-Key", key).header("X-Timestamp", ts).header("X-Signature", HmacSignatureVerifier.sign(secret, HmacSignatureVerifier.canonical(ts, method, path, body)));
        } else {
            request.header("X-Channel-Token", token);
            if (!path.equals("/api/v1/chat/client-profile") && terminalIdentities.containsKey(token))
                request.header("X-Chat-User-Token",terminalIdentities.get(token));
        }
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (!signed && path.equals("/api/v1/chat/client-profile") && response.statusCode()==200)
            terminalIdentities.put(token,json.readTree(response.body()).path("data").path("identity_token").asText());
        return response;
    }
}
