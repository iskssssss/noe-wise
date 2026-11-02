package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ModelPort;
import com.skillforge.runtime.api.port.KnowledgePortal;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 显式受保护的多 Provider 机构 ReAct Knowledge Smoke；普通 Gradle test 不会扫描本类。
 *
 * <p>Profile 清单只保存别名、租户和凭据环境变量名，清单文件必须位于仓库外。所有
 * profile 在任何 HTTP 调用前完成结构校验、凭据环境变量解析、数据库 Provider 身份读取，
 * 并确认至少有两个不同的 endpoint/model 组合。凭据只在进程内存中使用，不写入报告或产物。
 * 每个 profile 使用固定 LoopBudget、包含别名的唯一 runId，并且只按本次创建的资源 ID 清理。</p>
 */
class StagingRuntimeE2E {

    static final String PROFILE_FILE_ENV = "STAGING_PROVIDER_PROFILES_FILE";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration TERMINAL_TIMEOUT = Duration.ofMinutes(6);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final String ENVIRONMENT_NAME_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
    private static final Set<String> PROFILE_FIELDS = Set.of(
            "alias", "tenant_slug", "username_env", "password_env");
    private static final Set<String> USAGE_SOURCE_VALUES = Set.of(
            "PROVIDER_REPORTED", "ESTIMATED", "UNKNOWN");
    private static final ObjectMapper PROFILE_MAPPER = new ObjectMapper();
    private static final Map<String, Object> EXECUTION_BUDGET = Map.of(
            "max_business_model_calls", 6,
            "max_tool_calls", 1,
            "max_knowledge_calls", 1,
            // 真实供应商的超时与保守 Token 预留须容纳后续业务动作和收尾。
            "max_total_tokens", 3_000_000,
            "max_wall_time_seconds", 480);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @TestFactory
    Stream<DynamicTest> configuredProviderProfilesRunKnowledgeSmoke() throws Exception {
        StagingConfiguration configuration = loadConfiguration();
        List<ProviderCheck> providerChecks = new ArrayList<>();
        for (ResolvedProfile profile : configuration.profiles()) {
            ProviderFact provider = readActiveProvider(
                    configuration.dbUrl(), configuration.dbUsername(), configuration.dbPassword(),
                    profile.definition().alias(), profile.definition().tenantSlug());
            providerChecks.add(new ProviderCheck(profile, provider));
        }

        Set<ProviderIdentity> identities = providerChecks.stream()
                .map(check -> new ProviderIdentity(check.provider().endpoint(), check.provider().model()))
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(identities.size() >= 2,
                "至少两个 profile 的数据库 endpoint/model 身份组合必须不同");

        return providerChecks.stream().map(check -> DynamicTest.dynamicTest(
                "profile " + check.profile().definition().alias(),
                () -> runSmoke(configuration, check.profile())));
    }

    private StagingConfiguration loadConfiguration() {
        String baseUrl = required("STAGING_RUNTIME_BASE_URL").replaceAll("/$", "");
        String profilePathValue = required(PROFILE_FILE_ENV);
        String dbUrl = required("STAGING_DB_URL");
        String dbUsername = required("STAGING_DB_USERNAME");
        String dbPassword = required("STAGING_DB_PASSWORD");

        Path profilePath;
        try {
            profilePath = Paths.get(profilePathValue).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw configurationError(PROFILE_FILE_ENV);
        }
        Path repositoryRoot = findRepositoryRoot();
        ProfileManifest manifest = ProfileManifest.load(profilePath, repositoryRoot);
        List<ResolvedProfile> profiles = manifest.resolve(System.getenv());
        return new StagingConfiguration(baseUrl, dbUrl, dbUsername, dbPassword, profiles);
    }

    private Path findRepositoryRoot() {
        Path current;
        try {
            current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            throw configurationError(PROFILE_FILE_ENV);
        }
        while (current != null) {
            if (Files.exists(current.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                try {
                    return current.toRealPath();
                } catch (IOException exception) {
                    throw configurationError(PROFILE_FILE_ENV);
                }
            }
            current = current.getParent();
        }
        throw configurationError(PROFILE_FILE_ENV);
    }

    private void runSmoke(
            StagingConfiguration configuration, ResolvedProfile profile)
            throws Exception {
        String alias = profile.definition().alias();
        String runId = runId(alias);
        String prefix = runId;
        String sentinel = "STAGING_SENTINEL_" + UUID.randomUUID().toString().replace("-", "");
        String token = null;
        String kbId = null;
        String agentId = null;
        String mountId = null;
        String mountRevision = null;
        String sessionId = null;
        boolean runAccepted = false;
        boolean terminalObserved = false;

        try {
            token = login(configuration.baseUrl(), alias, profile.definition().tenantSlug(),
                    profile.username(), profile.password());
            long endUserId = ensureEndUser(configuration.baseUrl(), token, alias);
            kbId = createKnowledge(configuration.baseUrl(), token, alias, prefix, sentinel);
            String sourceVersion = uploadAndReadSourceVersion(
                    configuration.baseUrl(), token, alias, kbId, prefix, sentinel);
            JsonNode published = publishRevision(
                    configuration.baseUrl(), token, alias, kbId, sourceVersion);
            waitUntilSearchable(configuration.baseUrl(), token, alias, kbId, sentinel);
            agentId = createAgent(configuration.baseUrl(), token, alias, prefix);
            long revisionId = published.path("id").asLong();
            long capabilityVersionId = published.path("capability_version_id").asLong();
            assertTrue(revisionId > 0, profileMessage(alias, "revision_id"));
            assertTrue(capabilityVersionId > 0, profileMessage(alias, "capability_version_id"));
            KnowledgeCapabilityFact capability = readKnowledgeCapability(
                    configuration.dbUrl(), configuration.dbUsername(), configuration.dbPassword(),
                    alias, profile.definition().tenantSlug(), capabilityVersionId);
            MountFact mount = mountKnowledge(
                    configuration.baseUrl(), token, alias, agentId, capability, revisionId);
            mountId = mount.mountId();
            mountRevision = Long.toString(mount.revision());
            configureAgentKnowledgePrompt(
                    configuration.baseUrl(), token, alias, agentId, mountId);
            callJson(configuration.baseUrl(), token, alias, "/api/v1/agents/publish", "POST", Map.of("agent_id", agentId));
            sessionId = createSession(
                    configuration.baseUrl(), token, alias, agentId, endUserId);

            JsonNode receipt = chatSync(
                    configuration.baseUrl(), token, alias, sessionId, agentId, endUserId, prefix);
            assertEquals("queued", receipt.path("status").asText(), profileMessage(alias, "status"));
            runAccepted = true;
            assertFalse(receipt.path("execution_id").asText().isBlank(),
                    profileMessage(alias, "execution_id"));
            JsonNode terminal = awaitTerminalEvent(
                    configuration.baseUrl(), token, alias, sessionId);
            terminalObserved = true;
            assertEquals("execution.completed", terminal.path("event").asText(),
                    profileMessage(alias, "terminal_event"));
            assertEquals("completed", terminal.path("data").path("status").asText(),
                    profileMessage(alias, "terminal_status"));
            JsonNode history = callJson(configuration.baseUrl(), token, alias,
                    "/api/v1/chat/history?session_id=" + sessionId, "GET", null);
            String historyText = history.toString();
            assertTrue(historyText.contains(sentinel), profileMessage(alias, "answer"));
            verifyKnowledgeEvidenceAudit(
                    configuration.dbUrl(), configuration.dbUsername(), configuration.dbPassword(),
                    alias, sessionId, receipt.path("execution_id").asText(), sentinel, sourceVersion);
            verifyUsageAttemptAudit(
                    configuration.dbUrl(), configuration.dbUsername(), configuration.dbPassword(),
                    alias, sessionId);
        } finally {
            cleanup(configuration.baseUrl(), token, alias, agentId, mountId, mountRevision, kbId,
                    sessionId, runAccepted, terminalObserved);
        }
    }

    private ProviderFact readActiveProvider(
            String dbUrl, String dbUsername, String dbPassword, String alias, String tenantSlug) {
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword)) {
            long tenantId;
            try (PreparedStatement tenant = connection.prepareStatement(
                    "select id from nw_tenants where slug = ? and status = 'active'")) {
                tenant.setString(1, tenantSlug);
                try (ResultSet result = tenant.executeQuery()) {
                    if (!result.next()) {
                        throw configurationError(alias, "tenant_slug");
                    }
                    tenantId = result.getLong("id");
                }
            }
            try (PreparedStatement bind = connection.prepareStatement(
                    "select set_config('app.tenant_id', ?, false)")) {
                bind.setString(1, Long.toString(tenantId));
                bind.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "select v.config->>'kind' as kind, "
                            + "(v.config->>'enabled')::boolean as enabled, "
                            + "v.config->>'endpoint' as endpoint, "
                            + "v.config->>'modelId' as model, "
                            + "v.config->>'apiKeyEncrypted' as api_key_encrypted, "
                            + "v.config->>'providerId' as provider_id, "
                            + "v.config->>'serviceId' as service_id, "
                            + "v.config->>'profileVersion' as profile_version, "
                            + "v.config->>'protocol' as protocol, "
                            + "v.config->>'authenticationScheme' as authentication_scheme "
                            + "from nw_tenant_model_selections s "
                            + "join nw_platform_models m on m.id = s.llm_model_config_id "
                            + "join nw_platform_model_versions v "
                            + "on v.model_id = m.id and v.revision = m.current_revision "
                            + "where s.tenant_id = ?")) {
                statement.setLong(1, tenantId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw configurationError(alias, "llm_model_selection");
                }
                boolean enabled = result.getBoolean("enabled");
                String endpoint = result.getString("endpoint");
                String model = result.getString("model");
                String providerId = result.getString("provider_id");
                String serviceId = result.getString("service_id");
                String profileVersion = result.getString("profile_version");
                String protocol = result.getString("protocol");
                String authenticationScheme = result.getString("authentication_scheme");
                boolean hasEncryptedKey = !blank(result.getString("api_key_encrypted"));
                if (!"LLM".equals(result.getString("kind"))) {
                    throw configurationError(alias, "llm_model_kind");
                }
                if (!enabled) {
                    throw configurationError(alias, "llm_model_enabled");
                }
                if (blank(endpoint)) {
                    throw configurationError(alias, "llm_model_endpoint");
                }
                if (blank(model)) {
                    throw configurationError(alias, "llm_model_id");
                }
                if (!hasEncryptedKey) {
                    throw configurationError(alias, "llm_model_credential");
                }
                if (blank(providerId) || blank(serviceId) || blank(profileVersion)
                        || blank(protocol) || blank(authenticationScheme)) {
                    throw configurationError(alias, "llm_model_provider_profile");
                }
                return new ProviderFact(endpoint.trim(), model.trim(), providerId.trim(),
                        serviceId.trim(), profileVersion.trim(), protocol.trim(),
                        authenticationScheme.trim());
            }
            }
        } catch (SQLException exception) {
            throw configurationError(alias, "database_provider");
        }
    }

    private String login(
            String baseUrl, String alias, String tenantSlug, String username, String password)
            throws Exception {
        JsonNode result = callJson(baseUrl, null, alias, "/api/v1/auth/login", "POST", Map.of(
                "tenant_slug", tenantSlug, "username", username, "password", password));
        String token = result.path("access_token").asText();
        if (token.isBlank()) {
            throw configurationError(alias, "access_token");
        }
        return token;
    }

    private String createKnowledge(
            String baseUrl, String token, String alias, String prefix, String sentinel)
            throws Exception {
        JsonNode created = callJson(baseUrl, token, alias, "/api/v1/knowledge/bases", "POST", Map.of(
                "name", prefix + "-kb", "description", "staging sentinel " + sentinel));
        String kbId = created.path("id").asText();
        assertFalse(kbId.isBlank(), profileMessage(alias, "knowledge_base_id"));
        callJson(baseUrl, token, alias, "/api/v1/knowledge/bases/update", "PATCH", Map.of("kb_id", kbId, "payload", Map.of(
                "indexing_mode", "economical", "retrieval_mode", "full_text")));
        return kbId;
    }

    private String uploadAndReadSourceVersion(
            String baseUrl, String token, String alias, String kbId, String prefix, String sentinel)
            throws Exception {
        JsonNode uploaded = uploadTextFile(baseUrl, token, alias, kbId, prefix + "-document",
                "检索已挂载的暂存文档。此暂存文档仅包含随机哨兵值。请报告准确的随机哨兵值，"
                        + "并提供证据引用。随机哨兵值为 " + sentinel + "。");
        String documentId = uploaded.path("document_id").asText();
        assertTrue(uploaded.path("chunks").asInt() > 0, profileMessage(alias, "chunks"));
        JsonNode detail = callJson(baseUrl, token, alias, "/api/v1/knowledge/documents/detail?" + "docId=" + java.net.URLEncoder.encode(String.valueOf(documentId), java.nio.charset.StandardCharsets.UTF_8), "GET", null);
        String sourceVersion = detail.path("source_version").asText();
        assertFalse(sourceVersion.isBlank(), profileMessage(alias, "source_version"));
        return sourceVersion;
    }

    private JsonNode publishRevision(
            String baseUrl, String token, String alias, String kbId, String sourceVersion)
            throws Exception {
        JsonNode created = callJson(baseUrl, token, alias, "/api/v1/knowledge/bases/revisions", "POST", Map.of("kb_id", kbId));
        String revision = created.path("revision").asText();
        assertFalse(revision.isBlank(), profileMessage(alias, "revision"));
        callJson(baseUrl, token, alias, "/api/v1/knowledge/bases/revisions/ready", "POST", Map.of("kb_id", kbId, "revision", revision, "payload", Map.of(
                        "source_versions", List.of(sourceVersion),
                        "policy", Map.of(
                                "model_invocable", true,
                                "user_invocable", false,
                                "allowed_operators", List.of("KEYWORD"),
                                "freshness_policy", "PINNED",
                                "citation_required", true))));
        JsonNode published = callJson(baseUrl, token, alias, "/api/v1/knowledge/bases/revisions/publish", "POST", Map.of("kb_id", kbId, "revision", revision));
        assertEquals("published", published.path("status").asText(),
                profileMessage(alias, "published_status"));
        return published;
    }

    private KnowledgeCapabilityFact readKnowledgeCapability(
            String dbUrl, String dbUsername, String dbPassword, String alias, String tenantSlug,
            long capabilityVersionId) {
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
                PreparedStatement statement = connection.prepareStatement(
                        "select cv.definition_id "
                                + "from nw_capability_versions cv "
                                + "join nw_tenants t on t.id = cv.tenant_id "
                                + "where cv.id = ? and cv.capability_type = 'KNOWLEDGE_SOURCE' "
                                + "and cv.status = 'PUBLISHED' and t.slug = ?")) {
            statement.setLong(1, capabilityVersionId);
            statement.setString(2, tenantSlug);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw configurationError(alias, "capability_version_id");
                }
                return new KnowledgeCapabilityFact(result.getLong(1), capabilityVersionId);
            }
        } catch (SQLException exception) {
            throw configurationError(alias, "database_capability");
        }
    }

    /** 等待公开检索 API 观察到刚发布的文档，覆盖 ES refresh 的最终一致窗口。 */
    private void waitUntilSearchable(
            String baseUrl, String token, String alias, String kbId, String sentinel)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                JsonNode search = callJson(baseUrl, token, alias, "/api/v1/knowledge/search", "POST", Map.of(
                        "kb_id", kbId,
                        "query", "检索已挂载的暂存文档。此暂存文档仅包含随机哨兵值。请报告准确的随机哨兵值，"
                                + "并提供证据引用。",
                        "top_k", 5));
                JsonNode items = search.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        if (item.path("content").asText().contains(sentinel)) {
                            return;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 在有限的一致性窗口内继续轮询，不把临时刷新异常写入测试报告。
            }
            Thread.sleep(200);
        }
        throw configurationError(alias, "knowledge_search");
    }

    private String createAgent(String baseUrl, String token, String alias, String prefix)
            throws Exception {
        JsonNode created = callJson(baseUrl, token, alias, "/api/v1/agents", "POST", Map.of(
                "agent_key", prefix + "-agent", "name", prefix + "-agent",
                "system_prompt", knowledgePrompt(
                        "<the exact knowledge handle from the mounted CapabilityMount list>")));
        String agentId = created.path("id").asText();
        assertFalse(agentId.isBlank(), profileMessage(alias, "agent_id"));
        callJson(baseUrl, token, alias, "/api/v1/agents/update", "PATCH",
                Map.of("agent_id", agentId, "payload", Map.of("execution_config", EXECUTION_BUDGET)));
        return agentId;
    }

    /** 为每个 staging profile 复用一个显式入口用户，避免测试运行持续制造孤立用户。 */
    private long ensureEndUser(String baseUrl, String token, String alias) throws Exception {
        String displayName = "staging-runtime-e2e-"
                + Integer.toUnsignedString(alias.hashCode(), 36);
        JsonNode existing = callJson(
                baseUrl, token, alias, "/api/v1/chat/end-users", "GET", null);
        if (existing.isArray()) {
            for (JsonNode item : existing) {
                if (displayName.equals(item.path("display_name").asText())
                        && "active".equalsIgnoreCase(item.path("status").asText())) {
                    long id = item.path("id").asLong();
                    if (id > 0) {
                        return id;
                    }
                }
            }
        }
        JsonNode created = callJson(baseUrl, token, alias,
                "/api/v1/chat/end-users", "POST", Map.of("display_name", displayName));
        long id = created.path("id").asLong();
        assertTrue(id > 0, profileMessage(alias, "end_user_id"));
        return id;
    }

    private void configureAgentKnowledgePrompt(
            String baseUrl, String token, String alias, String agentId, String mountId)
            throws Exception {
        callJson(baseUrl, token, alias, "/api/v1/agents/update", "PATCH", Map.of("agent_id", agentId, "payload", Map.of(
                "system_prompt", knowledgePrompt("knowledge:" + mountId))));
    }

    private String knowledgePrompt(String knowledgeHandle) {
        return "每个事实性回答都必须先检索已挂载的知识。"
                + "这是一个两步验收测试。针对当前问题，第一次响应必须恰好是以下 JSON 对象："
                + "{\"type\":\"knowledge_query\",\"knowledge_handle\":\"" + knowledgeHandle
                + "\",\"query\":\"随机哨兵值\"}; "
                + "不要直接回答，也不要输出准备就绪确认。单个知识结果将包含回答所需的证据项。收到该结果后，"
                + "下一条且唯一允许的输出是 respond JSON；不得再次发出 knowledge_query、tool_call 或 dispatch。"
                + "respond JSON 必须包含准确的随机哨兵值，并引用返回的证据项 ID，只能使用已检索的证据。";
    }

    private MountFact mountKnowledge(
            String baseUrl, String token, String alias, String agentId,
            KnowledgeCapabilityFact capability, long revisionId) throws Exception {
        Map<String, Object> mount = new LinkedHashMap<>();
        mount.put("capability_type", "KNOWLEDGE_SOURCE");
        mount.put("capability_definition_id", Long.toString(capability.definitionId()));
        mount.put("capability_version_id", Long.toString(capability.versionId()));
        mount.put("knowledge_config", Map.of(
                "mode", "PINNED_REVISION",
                "pinned_revision_id", Long.toString(revisionId)));
        mount.put("priority", 0);
        JsonNode mounted = callJson(baseUrl, token, alias, "/api/v1/agents/capability-mounts", "POST", Map.of("agent_id", agentId, "payload", mount));
        JsonNode mountResponse = mounted.path("mounts").path(0);
        String mountId = mountResponse.path("mount_id").asText();
        long mountRevision = mountResponse.path("revision").asLong();
        assertFalse(mountId.isBlank(), profileMessage(alias, "mount_id"));
        assertTrue(mountRevision > 0, profileMessage(alias, "mount_revision"));
        assertEquals("KNOWLEDGE_SOURCE", mountResponse.path("capability_type").asText(),
                profileMessage(alias, "capability_type"));
        return new MountFact(mountId, mountRevision);
    }

    private String createSession(
            String baseUrl, String token, String alias, String agentId, long endUserId)
            throws Exception {
        JsonNode result = callJson(baseUrl, token, alias, "/api/v1/chat/sessions", "POST",
                Map.of("agent_id", agentId, "end_user_id", endUserId));
        String sessionId = result.path("session_id").asText();
        assertFalse(sessionId.isBlank(), profileMessage(alias, "session_id"));
        return sessionId;
    }

    private JsonNode chatSync(
            String baseUrl, String token, String alias, String sessionId, String agentId,
            long endUserId, String runId) throws Exception {
        String clientMessageId = runId + "-input";
        String question = "这是一次机构知识问答验收。请先理解这段包含业务背景、回答格式、审计要求和来源约束的长问题，"
                + "再检索已挂载的暂存文档；不要把整段问题原样当作检索词。此暂存文档仅包含随机哨兵值，"
                + "请报告准确的随机哨兵值并提供证据引用，同时不要引用未挂载知识库、其他版本或其他租户的内容。";
        Map<String, Object> payload = Map.of("content", List.of(Map.of("type", "text", "text", question)), "session_id", sessionId,
                "agent_id", agentId, "client_message_id", clientMessageId,
                "context", Map.of("end_user_id", endUserId));
        return callJson(baseUrl, token, alias, "/api/v1/chat/sync", "POST", payload);
    }

    /** 同步入口只确认持久受理；终态必须从 Session Outbox 的冻结事件读取。 */
    private JsonNode awaitTerminalEvent(
            String baseUrl, String token, String alias, String sessionId) throws Exception {
        long deadline = System.nanoTime() + TERMINAL_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode response = callJson(baseUrl, token, alias,
                    "/api/v1/chat/events?session_id=" + sessionId + "&limit=500", "GET", null);
            JsonNode events = response.path("events");
            if (events.isArray()) {
                for (JsonNode event : events) {
                    String type = event.path("event").asText();
                    if ("execution.completed".equals(type)) {
                        return event;
                    }
                    if ("error".equals(type)) {
                        throw configurationError(alias, "terminal_event_error");
                    }
                }
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw configurationError(alias, "execution_timeout");
    }

    private void verifyUsageAttemptAudit(
            String dbUrl, String dbUsername, String dbPassword, String alias, String sessionId) {
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword);
                PreparedStatement statement = connection.prepareStatement(
                        "select ue.attempt_id, ue.usage_source, "
                                + "ei.attempt_id as linked_attempt_id "
                                + "from nw_usage_events ue "
                                + "join nw_chat_sessions s on s.session_id = ue.session_id "
                                + "left join nw_external_invocations ei "
                                + "on ei.tenant_id = ue.tenant_id "
                                + "and ei.attempt_id = ue.attempt_id "
                                + "where s.session_id = ? and ue.session_id = ?")) {
            // sessions.session_id 全局唯一；usage_events 与 external_invocations 的 tenant_id 均为 bigint，
            // 通过 attempt_id 和 tenant_id 验证同租户调用关联，避免比较不同类型的会话租户列。
            statement.setString(1, sessionId);
            statement.setString(2, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                boolean hasAttempt = false;
                boolean hasLinkedAttempt = false;
                while (result.next()) {
                    String usageSource = result.getString("usage_source");
                    if (!USAGE_SOURCE_VALUES.contains(usageSource)) {
                        throw configurationError(alias, "usage_source");
                    }
                    String attemptId = result.getString("attempt_id");
                    if (!blank(attemptId)) {
                        hasAttempt = true;
                        if (!blank(result.getString("linked_attempt_id"))) {
                            hasLinkedAttempt = true;
                        }
                    }
                }
                if (!hasAttempt) {
                    throw configurationError(alias, "usage_events.attempt_id");
                }
                if (!hasLinkedAttempt) {
                    throw configurationError(alias, "external_invocations.attempt_id");
                }
            }
        } catch (SQLException exception) {
            throw configurationError(alias, "usage_event_audit");
        }
    }

    private JsonNode callJson(
            String baseUrl, String token, String alias, String path, String method,
            Map<String, Object> body) throws Exception {
        return callJsonWithHeaders(baseUrl, token, alias, path, method, body, Map.of());
    }

    private JsonNode uploadTextFile(
            String baseUrl, String token, String alias, String kbId, String title, String content)
            throws Exception {
        String boundary = "SkillForge-" + UUID.randomUUID();
        String payload = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"document.txt\"\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                + content + "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                + title + "\r\n--" + boundary + "--\r\n";
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/knowledge/bases/documents/upload?kb_id="
                            + java.net.URLEncoder.encode(kbId, StandardCharsets.UTF_8) + "&request_id=" + UUID.randomUUID()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary);
        } catch (RuntimeException exception) {
            throw configurationError(alias, "runtime_base_url");
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofByteArray(
                payload.getBytes(StandardCharsets.UTF_8))).build();
        HttpResponse<String> response = sendWithRateLimitBackoff(request, alias);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw configurationError(alias, "http_status_" + response.statusCode()
                    + classifiedError(response.body()));
        }
        try {
            JsonNode receipt = mapper.readTree(response.body()).path("data");
            if (!"SUCCEEDED".equals(receipt.path("status").asText()) || !receipt.path("document").isObject())
                throw configurationError(alias, "upload_receipt");
            return receipt.path("document");
        } catch (IOException exception) {
            throw configurationError(alias, "http_response");
        }
    }

    private void cleanup(
            String baseUrl, String token, String alias, String agentId, String mountId,
            String mountRevision, String kbId, String sessionId,
            boolean runAccepted, boolean terminalObserved) {
        if (token == null) {
            return;
        }
        if (sessionId != null) {
            try {
                callJson(baseUrl, token, alias, "/api/v1/chat/sessions/close", "POST", Map.of("session_id", sessionId, "payload", Map.of("reason", "staging runtime e2e cleanup")));
            } catch (Exception ignored) {
                // 继续按本次运行取得的其他资源 ID 清理。
            }
        }
        boolean capabilityCleanupSafe = !runAccepted || terminalObserved;
        if (capabilityCleanupSafe && agentId != null && mountId != null && mountRevision != null) {
            try {
                callJsonWithHeaders(baseUrl, token, alias,
                        "/api/v1/agents/capability-mounts/delete",
                        "DELETE", Map.of("agent_id", agentId, "mount_id", mountId), Map.of("If-Match", mountRevision));
            } catch (Exception ignored) {
                // 继续按本次运行取得的其他资源 ID 清理。
            }
        }
        if (capabilityCleanupSafe && kbId != null) {
            try {
                callJson(baseUrl, token, alias, "/api/v1/knowledge/bases/delete", "DELETE", Map.of("kb_id", kbId));
            } catch (Exception ignored) {
                // 继续按本次运行取得的其他资源 ID 清理。
            }
        }
        if (capabilityCleanupSafe && agentId != null) {
            try {
                callJson(baseUrl, token, alias, "/api/v1/agents/update", "PATCH", Map.of("agent_id", agentId, "payload", Map.of("status", "archived")));
            } catch (Exception ignored) {
                // 清理范围仅限本次运行创建的 Agent ID。
            }
        }
    }

    private JsonNode callJsonWithHeaders(
            String baseUrl, String token, String alias, String path, String method,
            Map<String, Object> body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
                    .timeout(REQUEST_TIMEOUT).header("Accept", "application/json")
                    .header("Content-Type", "application/json");
        } catch (RuntimeException exception) {
            throw configurationError(alias, "runtime_base_url");
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        headers.forEach(builder::header);
        HttpRequest request;
        try {
            request = body == null
                    ? builder.method(method, HttpRequest.BodyPublishers.noBody()).build()
                    : builder.method(method,
                            HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        } catch (RuntimeException | IOException exception) {
            throw configurationError(alias, "http_request");
        }
        HttpResponse<String> response = sendWithRateLimitBackoff(request, alias);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw configurationError(alias, "http_status_" + response.statusCode()
                    + classifiedError(response.body()));
        }
        try {
            return mapper.readTree(response.body());
        } catch (IOException exception) {
            throw configurationError(alias, "http_response");
        }
    }

    /** 只沿本次Run选定Attempt、原模型请求与完成事件核验知识，不读取已封存的旧会话日志。 */
    private void verifyKnowledgeEvidenceAudit(
            String dbUrl, String dbUsername, String dbPassword, String alias,
            String sessionId, String runId, String sentinel, String sourceVersion) {
        var properties = new AgentRuntimeProperties();
        var codec = new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(),
                properties.getMaxPayloadNodes(), properties.getMaxPayloadDepth()));
        String selected = "SELECT r.tenant_id,a.kind,p.result_ref,q.schema_id AS request_schema,q.canonical_json AS request_json,"
                + "m.schema_id AS result_schema,m.canonical_json AS result_json "
                + "FROM agent_runtime_execution_runs r "
                + "JOIN agent_runtime_execution_actions a ON a.tenant_id=r.tenant_id AND a.run_id=r.run_id "
                + "JOIN agent_runtime_execution_attempts p ON p.tenant_id=a.tenant_id AND p.run_id=a.run_id "
                + "AND p.action_id=a.action_id AND p.attempt_id=a.selected_attempt AND p.adopted "
                + "JOIN agent_runtime_execution_materials q ON q.tenant_id=p.tenant_id AND q.reference=p.request_ref "
                + "JOIN agent_runtime_execution_materials m ON m.tenant_id=p.tenant_id AND m.reference=p.result_ref "
                + "WHERE r.session_id=? AND r.run_id=? AND r.status='COMPLETED' AND a.status='SUCCEEDED' "
                + "AND a.kind IN ('MODEL','KNOWLEDGE') ORDER BY a.action_ordinal";
        try (Connection connection = DriverManager.getConnection(dbUrl, dbUsername, dbPassword)) {
            var actions = new ArrayList<KnowledgeAuditAction>();
            try (PreparedStatement statement = connection.prepareStatement(selected)) {
                statement.setString(1, sessionId); statement.setString(2, runId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) actions.add(new KnowledgeAuditAction(result.getString("tenant_id"), result.getString("kind"),
                            result.getString("result_ref"),
                            new TypedPayload(result.getString("request_schema"), result.getString("request_json")),
                            new TypedPayload(result.getString("result_schema"), result.getString("result_json"))));
                }
            }
            var knowledge = actions.stream().filter(action -> "KNOWLEDGE".equals(action.kind())).toList();
            assertEquals(1, knowledge.size(), profileMessage(alias, "knowledge_selected_attempt"));
            var original = codec.decodeKnowledgeRequest(knowledge.getFirst().request());
            assertEquals(sessionId, original.request().sessionRef().sessionId(), profileMessage(alias, "knowledge_session"));
            assertEquals("随机哨兵值", ((com.skillforge.runtime.api.KnowledgeOperation.Search) original.request().operation()).query(), profileMessage(alias, "knowledge_query"));
            var retrieved = codec.decodeKnowledgeOutcome(knowledge.getFirst().outcome());
            assertTrue(retrieved instanceof KnowledgePortal.KnowledgeOutcome.Retrieved, profileMessage(alias, "knowledge_retrieved"));
            var bundle = ((KnowledgePortal.KnowledgeOutcome.Retrieved) retrieved).bundle();
            assertEquals(original.request().knowledgeRequestId(), bundle.knowledgeRequestId(), profileMessage(alias, "knowledge_request_identity"));
            assertFalse(bundle.items().isEmpty(), profileMessage(alias, "knowledge_observation"));
            for (var item : bundle.items()) {
                assertEquals(sourceVersion, item.sourceVersion(), profileMessage(alias, "knowledge_source_version"));
                assertFalse(item.provenanceRefs().isEmpty(), profileMessage(alias, "knowledge_provenance"));
                assertTrue(mapper.readTree(item.content().canonicalJson()).path("text").asText().contains(sentinel),
                        profileMessage(alias, "knowledge_observation"));
            }
            var models = actions.stream().filter(action -> "MODEL".equals(action.kind())).toList();
            assertFalse(models.isEmpty(), profileMessage(alias, "model_selected_attempt"));
            boolean originalCallRecorded = false;
            for (var model : models) {
                var outcome = codec.decodeModelOutcome(model.outcome());
                assertTrue(outcome instanceof ModelPort.ModelResult.Completed, profileMessage(alias, "model_completed"));
                var completed = (ModelPort.ModelResult.Completed) outcome;
                for (var call : completed.assistantTurn().orElseThrow().toolCalls())
                    if (original.callId().equals(call.callId()) && original.wireName().equals(call.wireName())
                            && mapper.readTree(original.arguments().canonicalJson()).equals(mapper.readTree(call.argumentsJson())))
                        originalCallRecorded = true;
            }
            assertTrue(originalCallRecorded, profileMessage(alias, "knowledge_original_native_call"));
            var finalModel = models.getLast();
            var finalOutcome = (ModelPort.ModelResult.Completed) codec.decodeModelOutcome(finalModel.outcome());
            assertEquals(ModelPort.TEXT_OUTPUT_SCHEMA, finalOutcome.output().schemaId(), profileMessage(alias, "native_answer_schema"));
            assertEquals("stop", finalOutcome.finishReasonCode(), profileMessage(alias, "native_answer_finish"));
            assertTrue(finalOutcome.assistantTurn().orElseThrow().toolCalls().isEmpty(), profileMessage(alias, "native_answer_without_tools"));
            String rawAnswer = mapper.readTree(finalOutcome.output().canonicalJson()).textValue();
            assertEquals(finalOutcome.assistantTurn().orElseThrow().content(), rawAnswer, profileMessage(alias, "native_answer_fact"));
            var frozen = codec.decodeModelRequest(finalModel.request(), refs -> ModelRequestTestMaterials.load(connection, finalModel.tenant(), refs)).request();
            var injected = new LinkedHashMap<String, JsonNode>();
            int paired = 0;
            for (var message : frozen.messages()) if (message.role() == ModelPort.ModelRole.TOOL
                    && message.toolCallId().filter(original.callId()::equals).isPresent()) {
                paired++;
                var actual = mapper.readTree(message.textContent());
                assertEquals(bundle.bundleId(), actual.path("bundleId").asText(), profileMessage(alias, "knowledge_bundle_pair"));
                for (var item : actual.path("items")) {
                    assertTrue(item.path("citationId").asText().matches("k[0-9a-f]{16}"), profileMessage(alias, "knowledge_citation_id"));
                    assertTrue(bundle.items().stream().anyMatch(source -> source.itemId().equals(item.path("itemId").asText())
                            && source.sourceId().equals(item.path("sourceId").asText())
                            && source.sourceVersion().equals(item.path("sourceVersion").asText())), profileMessage(alias, "knowledge_injected_source"));
                    assertTrue(injected.put(item.path("itemId").asText(), item) == null, profileMessage(alias, "knowledge_injected_unique"));
                }
            }
            assertEquals(1, paired, profileMessage(alias, "knowledge_reference_observation"));
            assertFalse(injected.isEmpty(), profileMessage(alias, "knowledge_reference_observation"));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT e.canonical_json FROM agent_runtime_execution_events e "
                            + "JOIN agent_runtime_execution_runs r ON r.tenant_id=e.tenant_id AND r.run_id=e.run_id "
                            + "WHERE r.session_id=? AND r.run_id=? AND e.type=? AND e.schema_id=?")) {
                statement.setString(1, sessionId); statement.setString(2, runId);
                statement.setString(3, AgentExecutionProgram.COMPLETED_EVENT); statement.setString(4, AgentExecutionProgram.COMPLETED_SCHEMA);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next(), profileMessage(alias, "completed_event"));
                    var completed = mapper.readTree(result.getString("canonical_json"));
                    assertEquals("COMPLETED", completed.path("status").asText(), profileMessage(alias, "completed_status"));
                    assertEquals(finalModel.resultRef(), completed.path("resultRef").asText(), profileMessage(alias, "completed_model_result"));
                    assertTrue(completed.path("evidence").isArray() && !completed.path("evidence").isEmpty(), profileMessage(alias, "evidence_citation"));
                    var cited = new HashSet<String>();
                    for (var citation : completed.path("evidence")) {
                        String itemId = citation.path("itemId").asText();
                        assertTrue(cited.add(itemId) && injected.containsKey(itemId), profileMessage(alias, "evidence_visible_unique"));
                        var item = injected.get(itemId);
                        assertEquals(item.path("citationId").asText(), citation.path("citationId").asText(), profileMessage(alias, "evidence_citation_id"));
                        assertEquals(item.path("sourceId").asText(), citation.path("sourceId").asText(), profileMessage(alias, "evidence_source"));
                        assertEquals(sourceVersion, citation.path("sourceVersion").asText(), profileMessage(alias, "evidence_source_version"));
                        assertEquals(bundle.bundleId(), citation.path("bundleId").asText(), profileMessage(alias, "evidence_bundle"));
                        assertEquals(knowledge.getFirst().resultRef(), citation.path("resultRef").asText(), profileMessage(alias, "evidence_selected_result"));
                        assertTrue(rawAnswer.contains("[[cite:" + citation.path("citationId").asText() + "]]"), profileMessage(alias, "evidence_used_in_answer"));
                    }
                    assertFalse(result.next(), profileMessage(alias, "completed_event_unique"));
                }
            }
        } catch (SQLException | IOException | IllegalArgumentException exception) {
            throw configurationError(alias, "knowledge_evidence_audit");
        }
    }

    /** 本次审计仅保留运行材料引用，不输出任何模型正文或凭据。 */
    private record KnowledgeAuditAction(String tenant, String kind, String resultRef, TypedPayload request, TypedPayload outcome) { }

    /** staging HTTP 入口共享租户限流；只对明确的 429 按响应提示退避。 */
    private HttpResponse<String> sendWithRateLimitBackoff(HttpRequest request, String alias) {
        long deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();
        while (true) {
            HttpResponse<String> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw configurationError(alias, "http_request");
            } catch (IOException exception) {
                throw configurationError(alias, "http_request");
            }
            if (response.statusCode() != 429 || System.nanoTime() >= deadline) {
                return response;
            }
            long retryMillis = response.headers().firstValue("Retry-After")
                    .flatMap(StagingRuntimeE2E::retryAfterMillis)
                    .orElse(POLL_INTERVAL.toMillis());
            try {
                Thread.sleep(Math.max(POLL_INTERVAL.toMillis(), retryMillis));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw configurationError(alias, "http_request");
            }
        }
    }

    private static java.util.Optional<Long> retryAfterMillis(String value) {
        try {
            return java.util.Optional.of(Math.multiplyExact(Long.parseLong(value.trim()), 1000L));
        } catch (ArithmeticException | NumberFormatException exception) {
            return java.util.Optional.empty();
        }
    }

    /** 仅暴露机构服务自身的固定错误分类，不回显任意响应正文。 */
    private String classifiedError(String responseBody) {
        try {
            String detail = mapper.readTree(responseBody).path("detail").asText();
            return switch (detail) {
                case "Agent Runtime 模型准备配置缺失" -> "_runtime_preparation_profile_missing";
                case "Agent Runtime 能力绑定缺失" -> "_runtime_capability_binding_missing";
                case "Agent Runtime 服务主体未授权" -> "_runtime_service_principal_rejected";
                case "Chat Runtime 服务主体未配置" -> "_chat_service_principal_missing";
                case "agent_runtime_model_binding_source_unavailable" ->
                        "_runtime_model_binding_source_unavailable";
                case "Agent Runtime 不可用" -> "_runtime_unavailable";
                default -> "";
            };
        } catch (IOException | RuntimeException exception) {
            return "";
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (blank(value)) {
            throw configurationError(name);
        }
        return value;
    }

    private static String profileMessage(String alias, String field) {
        return "profile " + alias + " 的字段 " + field + " 不满足契约";
    }

    /** 保留 alias 可关联摘要，同时为 agent_key 等 varchar(64) 派生键留下后缀空间。 */
    static String runId(String alias) {
        String aliasDigest = Integer.toUnsignedString(alias.hashCode(), 36);
        String random = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        return "e2e-" + aliasDigest + "-" + random;
    }

    private static IllegalStateException configurationError(String field) {
        return new IllegalStateException("缺少或无效的配置字段 " + field);
    }

    private static IllegalStateException configurationError(String alias, String field) {
        return new IllegalStateException(profileMessage(alias, field));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static final class ProfileManifest {
        private final List<Profile> profiles;

        private ProfileManifest(List<Profile> profiles) {
            this.profiles = List.copyOf(profiles);
        }

        static ProfileManifest load(Path configuredPath, Path repositoryRoot) {
            Path profilePath = resolveOutsideRepository(configuredPath, repositoryRoot);
            JsonNode document;
            try (BufferedReader reader = Files.newBufferedReader(profilePath, StandardCharsets.UTF_8)) {
                document = PROFILE_MAPPER.readTree(reader);
            } catch (IOException | RuntimeException exception) {
                throw configurationError(PROFILE_FILE_ENV);
            }
            if (document == null || document.isNull()) {
                throw configurationError(PROFILE_FILE_ENV);
            }

            JsonNode profileNodes;
            if (document.isArray()) {
                profileNodes = document;
            } else if (document.isObject()) {
                Iterator<String> fields = document.fieldNames();
                while (fields.hasNext()) {
                    String field = fields.next();
                    if (!"profiles".equals(field)) {
                        throw configurationError("manifest." + field);
                    }
                }
                profileNodes = document.path("profiles");
            } else {
                throw configurationError(PROFILE_FILE_ENV);
            }
            if (!profileNodes.isArray() || profileNodes.size() < 2) {
                throw configurationError("profiles");
            }

            List<Profile> profiles = new ArrayList<>();
            Set<String> aliases = new HashSet<>();
            for (int index = 0; index < profileNodes.size(); index++) {
                JsonNode node = profileNodes.get(index);
                if (!node.isObject()) {
                    throw configurationError("profiles[" + index + "]");
                }
                Iterator<String> fields = node.fieldNames();
                while (fields.hasNext()) {
                    String field = fields.next();
                    if (!PROFILE_FIELDS.contains(field)) {
                        throw configurationError("profiles[" + index + "]." + field);
                    }
                }
                String alias = requiredText(node, index, "alias");
                if (!aliases.add(alias)) {
                    throw configurationError(alias, "alias");
                }
                String tenantSlug = requiredText(node, index, "tenant_slug");
                String usernameEnv = requiredText(node, index, "username_env");
                String passwordEnv = requiredText(node, index, "password_env");
                validateEnvironmentName(alias, "username_env", usernameEnv);
                validateEnvironmentName(alias, "password_env", passwordEnv);
                profiles.add(new Profile(alias, tenantSlug, usernameEnv, passwordEnv));
            }
            return new ProfileManifest(profiles);
        }

        List<ResolvedProfile> resolve(Map<String, String> environment) {
            List<ResolvedProfile> resolved = new ArrayList<>();
            for (Profile profile : profiles) {
                String username = resolveCredential(environment, profile, "username_env",
                        profile.usernameEnv());
                String password = resolveCredential(environment, profile, "password_env",
                        profile.passwordEnv());
                resolved.add(new ResolvedProfile(profile, username, password));
            }
            return List.copyOf(resolved);
        }

        private static Path resolveOutsideRepository(Path configuredPath, Path repositoryRoot) {
            if (configuredPath == null || repositoryRoot == null) {
                throw configurationError(PROFILE_FILE_ENV);
            }
            Path canonicalPath;
            Path canonicalRepository;
            try {
                Path absoluteConfiguredPath = configuredPath.toAbsolutePath().normalize();
                canonicalRepository = repositoryRoot.toRealPath();
                if (!Files.isRegularFile(absoluteConfiguredPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw configurationError(PROFILE_FILE_ENV);
                }
                canonicalPath = absoluteConfiguredPath.toRealPath();
            } catch (IOException | RuntimeException exception) {
                throw configurationError(PROFILE_FILE_ENV);
            }
            if (canonicalPath.startsWith(canonicalRepository)) {
                throw configurationError(PROFILE_FILE_ENV);
            }
            return canonicalPath;
        }

        private static String requiredText(JsonNode node, int index, String field) {
            JsonNode value = node.get(field);
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                throw configurationError("profiles[" + index + "]." + field);
            }
            return value.asText().trim();
        }

        private static void validateEnvironmentName(String alias, String field, String value) {
            if (!value.matches(ENVIRONMENT_NAME_PATTERN)) {
                throw configurationError(alias, field);
            }
        }

        private static String resolveCredential(
                Map<String, String> environment, Profile profile, String field, String environmentName) {
            String value = environment.get(environmentName);
            if (blank(value)) {
                throw configurationError(profile.alias(), field);
            }
            return value;
        }
    }

    record Profile(String alias, String tenantSlug, String usernameEnv, String passwordEnv) {
    }

    static final class ResolvedProfile {
        private final Profile definition;
        private final String username;
        private final String password;

        private ResolvedProfile(Profile definition, String username, String password) {
            this.definition = definition;
            this.username = username;
            this.password = password;
        }

        Profile definition() {
            return definition;
        }

        String username() {
            return username;
        }

        String password() {
            return password;
        }

        @Override
        public String toString() {
            return "ResolvedProfile[alias=" + definition.alias() + "]";
        }
    }

    private record StagingConfiguration(
            String baseUrl, String dbUrl, String dbUsername, String dbPassword,
            List<ResolvedProfile> profiles) {

        @Override
        public String toString() {
            return "StagingConfiguration[profiles=" + profiles + "]";
        }
    }

    private record ProviderCheck(ResolvedProfile profile, ProviderFact provider) {
    }

    private record ProviderFact(String endpoint, String model, String providerId,
            String serviceId, String profileVersion, String protocol,
            String authenticationScheme) {
    }

    private record ProviderIdentity(String endpoint, String model) {
    }

    private record KnowledgeCapabilityFact(long definitionId, long versionId) {
    }

    private record MountFact(String mountId, long revision) {
    }
}
