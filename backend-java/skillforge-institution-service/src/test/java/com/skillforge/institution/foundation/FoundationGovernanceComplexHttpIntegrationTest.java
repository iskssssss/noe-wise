package com.skillforge.institution.foundation;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.security.Totp;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.institution.integration.AbstractIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 七个 Foundation 域的真实机构 HTTP 验收。每例独立三机构、四层部门和多角色，
 * 只在 Testcontainers 新库和临时文件根中写入；业务断言不从生产过滤/汇总方法推导。
 */
class FoundationGovernanceComplexHttpIntegrationTest extends AbstractIntegrationTest {
    private static final String BASE = "/api/v1/tenant/foundation/";
    private static final String PASSWORD = "SyntheticGovernance123";
    @TempDir static Path fileRoot;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @LocalServerPort int port;
    private HttpClient http;
    private TenantFixture first;
    private TenantFixture second;
    private TenantFixture third;
    private String admin;

    /** 文件和运行工作者均与开发实例隔离；管理任务仍使用实际调度器。 */
    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        registry.add("sa-token.jwt-secret-key", () -> "synthetic-foundation-http-jwt-key-32-bytes");
        registry.add("skillforge.foundation.files.root", () -> fileRoot.toString());
        registry.add("skillforge.foundation.files.max-bytes", () -> 8192);
        registry.add("skillforge.foundation.jobs.poll-ms", () -> 100);
        registry.add("agent-runtime.workers.auto-startup", () -> false);
        registry.add("spring.cloud.nacos.discovery.enabled", () -> false);
        registry.add("spring.cloud.nacos.config.enabled", () -> false);
    }

    /** 合法基线使用正式表/约束；登录与全部被测修改通过真实随机端口。 */
    @BeforeEach
    void graph() throws Exception {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        String hash = passwords.encode(PASSWORD);
        first = fixture(hash);
        second = fixture(hash);
        third = fixture(hash);
        admin = login(first, 0).path("access_token").asText();
    }

    /** 关闭本例客户端并清空测试线程身份，数据库和临时目录由框架回收。 */
    @AfterEach
    void close() { if (http != null) http.close(); TenantContext.clear(); }

    /** 部门深链成环和陈旧版本都不能改变完整树。 */
    @Test
    void accessDeepTreeRejectsCyclesAndStaleVersionsWithoutChangingRows() throws Exception {
        List<String> before = snapshot("nw_foundation_departments", "scope_key", first.id());
        var cycle = Map.of("id", first.department(0), "name", "根部门", "parent_id", first.department(3),
                "enabled", true, "lock_version", 1);
        error(call("PUT", BASE + "access/department", admin, cycle), 400, "foundation.access.invalid");
        assertEquals(before, snapshot("nw_foundation_departments", "scope_key", first.id()));
        var edit = Map.of("id", first.department(3), "name", "第四层·改名", "parent_id", first.department(2),
                "enabled", true, "lock_version", 1);
        assertEquals(2, data(call("PUT", BASE + "access/department", admin, edit)).path("lock_version").asLong());
        List<String> winner = snapshot("nw_foundation_departments", "scope_key", first.id());
        error(call("PUT", BASE + "access/department", admin, edit), 409, "foundation.access.version_conflict");
        assertEquals(winner, snapshot("nw_foundation_departments", "scope_key", first.id()));
    }

    /** 后代与指定部门求并由预先固定的成员集合校验，不借生产鉴权求期望。 */
    @Test
    void accessMultipleRolesFilterBeforePaginationAndDisabledRoleCannotGrantWrites() throws Exception {
        String scoped = login(first, 2).path("access_token").asText();
        Set<String> expected = new TreeSet<>();
        for (int i = 0; i < 12; i++) if (i % 6 >= 1 && i % 6 <= 4) expected.add(first.member(i));
        var page = data(call("GET", BASE + "access/members?page_size=100", scoped, null));
        assertEquals(expected, ids(page.path("items")));
        assertEquals(expected.size(), page.path("total").asLong());
        List<String> before = snapshot("nw_foundation_positions", "scope_key", first.id());
        error(call("POST", BASE + "access/position", scoped, Map.of("code", "forbidden", "name", "越权岗位")),
                403, "foundation.permission.denied");
        assertEquals(before, snapshot("nw_foundation_positions", "scope_key", first.id()));
    }

    /** 跨机构同名资料不能被寻址 ID 绕过，最后管理员也不能停用。 */
    @Test
    void accessForeignMemberAndLastAdministratorRemainUnchanged() throws Exception {
        List<String> before = snapshot("nw_foundation_member_profiles", "scope_key", second.id());
        error(call("PUT", BASE + "access/member", admin, memberUpdate(second, 1, "disabled")), 404, "NOT_FOUND");
        assertEquals(before, snapshot("nw_foundation_member_profiles", "scope_key", second.id()));
        var own = snapshot("nw_tenant_members", "tenant_id", first.id());
        error(call("PUT", BASE + "access/member", admin, memberUpdate(first, 0, "disabled")),
                409, "foundation.access.last_administrator");
        assertEquals(own, snapshot("nw_tenant_members", "tenant_id", first.id()));
    }

    /** 本人三设备中只撤销指定设备；另机构同名成员设备完全不受影响。 */
    @Test
    void securityRevokesOneOfThreeDevicesAndRejectsForeignDevice() throws Exception {
        var extra = login(first, 0);
        login(first, 0);
        var foreign = login(second, 0);
        var devices = data(call("GET", BASE + "security/sessions", admin, null));
        assertEquals(3, devices.size());
        String target = extra.path("session_id").asText();
        assertFalse(target.isBlank());
        data(call("POST", BASE + "security/sessions/revoke", admin, Map.of("session_id", target)));
        error(call("GET", "/api/v1/auth/me", extra.path("access_token").asText(), null), 401, "AUTH_SESSION_INVALID");
        error(call("POST", BASE + "security/sessions/revoke", admin,
                Map.of("session_id", foreign.path("session_id").asText())), 404, "NOT_FOUND");
        data(call("GET", "/api/v1/auth/me", foreign.path("access_token").asText(), null));
        assertEquals(2, data(call("GET", BASE + "security/sessions", admin, null)).size());
    }

    /** 密码错误零修改；正确变更使所有旧 access/refresh 失效但不影响其他机构。 */
    @Test
    void securityPasswordChangeInvalidatesAllOldCredentialsAndPreservesOtherTenant() throws Exception {
        var old = login(first, 0);
        var foreign = login(third, 0);
        var before = snapshot("nw_tenant_members", "tenant_id", first.id());
        error(call("POST", "/api/v1/auth/password", admin,
                Map.of("old_password", "WrongPassword123", "new_password", "ReplacementPassword123")),
                422, "VALIDATION_ERROR");
        assertEquals(before, snapshot("nw_tenant_members", "tenant_id", first.id()));
        data(call("POST", "/api/v1/auth/password", admin,
                Map.of("old_password", PASSWORD, "new_password", "ReplacementPassword123")));
        error(call("POST", "/api/v1/auth/refresh", null, Map.of("refresh_token", old.path("refresh_token").asText())),
                401, "AUTH_SESSION_INVALID");
        error(call("GET", "/api/v1/auth/me", old.path("access_token").asText(), null), 401, "AUTH_SESSION_INVALID");
        data(call("GET", "/api/v1/auth/me", foreign.path("access_token").asText(), null));
        assertEquals("member_0", data(call("POST", "/api/v1/auth/login", null,
                Map.of("tenant_slug", first.slug(), "username", "member_0", "password", "ReplacementPassword123"))).path("username").asText());
    }

    /** TOTP 只用于生成合法认证输入，恢复码单次消费和存储密文独立断言。 */
    @Test
    void securityMfaRecoveryCodeCannotReplayAndPublicViewsContainNoSeed() throws Exception {
        String seed = data(call("POST", "/api/v1/auth/mfa/enroll", admin, Map.of())).path("secret").asText();
        var enabled = data(call("POST", "/api/v1/auth/mfa/enable", admin,
                Map.of("code", Totp.generate(seed, Instant.now().getEpochSecond() / 30))));
        assertEquals(10, enabled.path("recovery_codes").size());
        String proof = enabled.path("recovery_codes").get(0).asText();
        var challenge = login(first, 0);
        assertEquals("challenge", challenge.path("mfa_state").asText());
        String challenged = challenge.path("access_token").asText();
        var verified = data(call("POST", "/api/v1/auth/mfa/verify", challenged, Map.of("code", proof)));
        error(call("POST", "/api/v1/auth/mfa/verify", verified.path("access_token").asText(), Map.of("code", proof)),
                401, "MFA_PROOF_INVALID");
        var status = data(call("GET", "/api/v1/auth/mfa/status", verified.path("access_token").asText(), null));
        assertEquals(9, status.path("recovery_codes_remaining").asInt());
        assertFalse(status.toString().contains(seed));
        String encrypted = jdbc.queryForObject("SELECT secret_encrypted FROM nw_foundation_security_mfa WHERE scope_key=? AND member_id=?",
                String.class, "tenant:" + first.id(), Long.valueOf(first.member(0)));
        assertNotEquals(seed, encrypted);
    }

    /** 相同字典编码跨机构合法复用，排序/停用过滤及 CAS 独立校验。 */
    @Test
    void catalogDictionariesKeepTenantIdentitySortAndRevision() throws Exception {
        dictionary(admin, "business_status");
        String other = login(second, 0).path("access_token").asText();
        dictionary(other, "business_status");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String code = "state_" + i;
            if (i != 3) expected.add(code);
            data(call("PUT", BASE + "catalog/dictionary-values", admin, Map.of("dictionary_code", "business_status",
                    "code", code, "label", "阶段," + i + "\n中文", "sort_order", i, "enabled", i != 3, "expected_revision", 0)));
        }
        var options = data(call("GET", BASE + "catalog/dictionary-options?dictionary_code=business_status", admin, null));
        List<String> actual = new ArrayList<>(); options.forEach(value -> actual.add(value.path("code").asText()));
        assertEquals(expected, actual);
        assertEquals(0, data(call("GET", BASE + "catalog/dictionary-values?dictionary_code=business_status", other, null)).size());
        var update = Map.of("code", "business_status", "name", "状态赢家", "enabled", true, "expected_revision", 1);
        data(call("PUT", BASE + "catalog/dictionaries", admin, update));
        var winner = data(call("GET", BASE + "catalog/dictionaries", admin, null));
        error(call("PUT", BASE + "catalog/dictionaries", admin, update), 409, "catalog_revision_conflict");
        assertEquals(winner, data(call("GET", BASE + "catalog/dictionaries", admin, null)));
    }

    /** 本人偏好和机构品牌的 CAS、作用域以及显式非法输入不能互相覆盖。 */
    @Test
    void catalogPersonalPreferencesAndBrandingAreScopedAndInvalidWriteIsAtomic() throws Exception {
        String reader = login(first, 1).path("access_token").asText();
        var preference = Map.of("theme", "dark", "locale", "zh-CN", "page_size", 50, "compact_navigation", true, "expected_revision", 0);
        data(call("PUT", BASE + "catalog/preferences", reader, preference));
        assertEquals("system", data(call("GET", BASE + "catalog/preferences", admin, null)).path("theme").asText());
        error(call("PUT", BASE + "catalog/preferences", reader, preference), 409, "catalog_revision_conflict");
        var before = data(call("GET", BASE + "catalog/preferences", reader, null));
        error(call("PUT", BASE + "catalog/preferences", reader,
                Map.of("theme", "dark", "locale", "en-US", "page_size", 50, "compact_navigation", true, "expected_revision", 1)),
                400, "catalog_invalid");
        assertEquals(before, data(call("GET", BASE + "catalog/preferences", reader, null)));
        data(call("PUT", BASE + "catalog/branding", admin, Map.of("display_name", "机构一·品牌", "primary_color", "#A1B2C3", "expected_revision", 0)));
        var foreign = data(call("GET", BASE + "catalog/branding", login(second, 0).path("access_token").asText(), null));
        assertNotEquals("机构一·品牌", foreign.path("display_name").asText());
    }

    /** 真实资源额度覆盖命中时拒绝新成员，既有成员事实完整保留。 */
    @Test
    void catalogQuotaAndFeatureOverridesBlockOnlyTheTargetTenant() throws Exception {
        jdbc.update("UPDATE nw_tenants SET override_quota_max_members=12 WHERE id=?", Long.valueOf(first.id()));
        var before = snapshot("nw_tenant_members", "tenant_id", first.id());
        error(call("POST", BASE + "access/member", admin, Map.of("username", "over_limit", "display_name", "超额成员",
                "password", PASSWORD, "role_ids", List.of(first.role(1)))), 409, "resource_quota_exceeded");
        assertEquals(before, snapshot("nw_tenant_members", "tenant_id", first.id()));
        jdbc.update("UPDATE nw_foundation_tenant_features SET enabled=false WHERE tenant_id=? AND feature_code='foundation.files'", Long.valueOf(first.id()));
        long count = countFiles(first);
        error(upload(admin, "valid.csv", csv(false)), 403, "tenant_feature_disabled");
        assertEquals(count, countFiles(first));
        byte[] otherMaterial = "a,b\r\n1,2\r\n".repeat(24).getBytes(StandardCharsets.UTF_8);
        assertEquals(otherMaterial.length, data(upload(login(second, 0).path("access_token").asText(), "valid.csv", otherMaterial)).path("size_bytes").asLong());
    }

    /** multipart 上传与下载按原始字节、独立摘要及范围验证。 */
    @Test
    void assetsMultipartPreservesBytesAndCrossTenantDownloadCannotReadFile() throws Exception {
        byte[] bytes = csv(false);
        var file = data(upload(admin, "多行材料.csv", bytes));
        var received = bytes("GET", BASE + "files/download?file_id=" + file.path("id").asText(), admin, null);
        assertEquals(200, received.statusCode()); assertArrayEquals(bytes, received.body());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), file.path("sha256").asText());
        assertTrue(received.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment"));
        assertEquals("nosniff", received.headers().firstValue("X-Content-Type-Options").orElseThrow());
        error(call("GET", BASE + "files/download?file_id=" + file.path("id").asText(), login(second, 0).path("access_token").asText(), null), 404, "NOT_FOUND");
        assertEquals(1, countFiles(first)); assertEquals(0, countFiles(second));
    }

    /** 不合法材料完整拒绝，既不留 metadata 也不留新 blob。 */
    @Test
    void assetsInvalidUtf8SpoofAndCapacityLeaveNoMetadataOrBlob() throws Exception {
        long rows = countFiles(first); long blobs = blobCount();
        error(upload(admin, "malformed.csv", new byte[]{(byte) 0xc3, 0x28}), 400, "file_type_invalid");
        error(upload(admin, "spoof.png", csv(false)), 400, "file_type_invalid");
        error(upload(admin, "too-large.txt", new byte[8193]), 413, "file_too_large");
        assertEquals(rows, countFiles(first)); assertEquals(blobs, blobCount());
    }

    /** 摘要篡改必须拒绝下载；删除一个本例文件不能破坏同名外机构文件。 */
    @Test
    void assetsTamperIsVisibleAndDeleteCannotEscapeTenant() throws Exception {
        var own = data(upload(admin, "same.csv", csv(false)));
        String other = login(second, 0).path("access_token").asText();
        var foreign = data(upload(other, "same.csv", csv(false)));
        error(call("POST", BASE + "files/delete", admin, Map.of("file_id", foreign.path("id").asText())), 404, "NOT_FOUND");
        Files.writeString(fileRoot.resolve(own.path("id").asText() + ".blob"), "changed");
        error(call("GET", BASE + "files/download?file_id=" + own.path("id").asText(), admin, null), 409, "file_integrity_failed");
        data(call("POST", BASE + "files/delete", admin, Map.of("file_id", own.path("id").asText())));
        assertFalse(Files.exists(fileRoot.resolve(own.path("id").asText() + ".blob")));
        assertEquals("deleted", jdbc.queryForObject("SELECT state FROM nw_foundation_files WHERE id=?", String.class, own.path("id").asText()));
        assertArrayEquals(csv(false), bytes("GET", BASE + "files/download?file_id=" + foreign.path("id").asText(), other, null).body());
    }

    /** 24 行中的四个坏行必须全部报告，预检与拒绝提交没有领域写入。 */
    @Test
    void jobsComplexPreflightReportsAllBadRowsAndWritesNothing() throws Exception {
        dictionary(admin, "bulk_status");
        var file = data(upload(admin, "bad.csv", csv(true)));
        var command = Map.of("handler", "dictionary_values", "file_id", file.path("id").asText(), "options", Map.of("dictionary_code", "bulk_status"));
        var checked = data(call("POST", BASE + "jobs/preflight", admin, command));
        assertFalse(checked.path("valid").asBoolean()); assertEquals(24, checked.path("row_count").asInt());
        Set<Integer> rows = new TreeSet<>(); checked.path("errors").forEach(e -> rows.add(e.path("row_number").asInt()));
        assertEquals(Set.of(5, 11, 17, 23), rows);
        error(call("POST", BASE + "jobs/submit", admin, Map.of("handler", "dictionary_values", "operation", "import", "file_id", file.path("id").asText(),
                "options", Map.of("dictionary_code", "bulk_status"), "idempotency_key", "preflight-reject")), 400, "import_preflight_failed");
        assertEquals(0, data(call("GET", BASE + "catalog/dictionary-values?dictionary_code=bulk_status", admin, null)).size());
        assertEquals(0, count("SELECT count(*) FROM nw_foundation_admin_jobs WHERE tenant_id=?", Long.valueOf(first.id())));
    }

    /** 真正调度执行 24 行，重放不再导入；异请求同 key 不改变原任务。 */
    @Test
    void jobsImportHasExactlyOneReceiptPerRowAndReplaysWithoutVersionChanges() throws Exception {
        dictionary(admin, "bulk_status");
        var file = data(upload(admin, "good.csv", csv(false)));
        var command = Map.of("handler", "dictionary_values", "operation", "import", "file_id", file.path("id").asText(),
                "options", Map.of("dictionary_code", "bulk_status"), "idempotency_key", "bulk-import");
        String job = data(call("POST", BASE + "jobs/submit", admin, command)).path("id").asText();
        var terminal = awaitJob(job);
        assertEquals("succeeded", terminal.path("state").asText()); assertEquals(24, terminal.path("completed_rows").asInt());
        assertEquals(0, count("SELECT count(*) FROM nw_foundation_admin_job_rows WHERE job_id=?", job));
        var receipts = jdbc.queryForList("SELECT block_number, encode(completed_bits,'hex') AS bits FROM nw_foundation_admin_job_receipt_blocks WHERE job_id=? ORDER BY block_number", job);
        // V281 将成功行身份压入位图；24 个连续逻辑行必须恰好占用第 0 块的前 24 位。
        assertEquals(List.of(Map.of("block_number", 0, "bits", "ffffff" + "00".repeat(29))), receipts);
        var values = data(call("GET", BASE + "catalog/dictionary-values?dictionary_code=bulk_status", admin, null));
        assertEquals(24, values.size());
        for (int row = 0; row < 24; row++) {
            var value = values.get(row);
            assertEquals("entry_" + row, value.path("code").asText());
            assertEquals("中文," + row + " \"引号\"", value.path("label").asText());
            assertEquals(row, value.path("sort_order").asInt());
            assertEquals(row % 2 == 0, value.path("enabled").asBoolean());
            assertEquals(1, value.path("revision").asLong());
        }
        assertEquals(job, data(call("POST", BASE + "jobs/submit", admin, command)).path("id").asText());
        var conflicting = new LinkedHashMap<String, Object>(command); conflicting.put("operation", "export"); conflicting.remove("file_id");
        error(call("POST", BASE + "jobs/submit", admin, conflicting), 409, "job_idempotency_conflict");
        assertEquals(values, data(call("GET", BASE + "catalog/dictionary-values?dictionary_code=bulk_status", admin, null)));
        assertEquals(receipts, jdbc.queryForList("SELECT block_number, encode(completed_bits,'hex') AS bits FROM nw_foundation_admin_job_receipt_blocks WHERE job_id=? ORDER BY block_number", job));
        assertEquals(1, count("SELECT count(*) FROM nw_foundation_admin_jobs WHERE tenant_id=?", Long.valueOf(first.id())));
    }

    /** 结果材料由业务任务授权，外机构任务寻址和引用删除均拒绝。 */
    @Test
    void jobsExportUsesBusinessAuthorizationAndProtectsReferencedResult() throws Exception {
        dictionary(admin, "export_status");
        for (int i = 0; i < 8; i++) data(call("PUT", BASE + "catalog/dictionary-values", admin,
                Map.of("dictionary_code", "export_status", "code", "entry_" + i, "label", "中文," + i, "sort_order", i, "enabled", i % 2 == 0, "expected_revision", 0)));
        String job = data(call("POST", BASE + "jobs/submit", admin, Map.of("handler", "dictionary_values", "operation", "export",
                "options", Map.of("dictionary_code", "export_status"), "idempotency_key", "export-fixture"))).path("id").asText();
        var terminal = awaitJob(job); assertEquals("succeeded", terminal.path("state").asText());
        String result = terminal.path("result_file_id").asText();
        var download = bytes("GET", BASE + "files/download?file_id=" + result, admin, null);
        assertEquals(200, download.statusCode());
        String csv = new String(download.body(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF\"code\",\"label\",\"sort_order\",\"enabled\"\r\n"));
        for (int i = 0; i < 8; i++) assertTrue(csv.contains("\"entry_" + i + "\",\"中文," + i + "\",\"" + i + "\",\"" + (i % 2 == 0) + "\""));
        error(call("POST", BASE + "files/delete", admin, Map.of("file_id", result)), 409, "file_referenced");
        error(call("GET", BASE + "jobs/detail?job_id=" + job, login(second, 0).path("access_token").asText(), null), 404, "NOT_FOUND");
        assertArrayEquals(download.body(), bytes("GET", BASE + "files/download?file_id=" + result, admin, null).body());
    }

    /** 全员公告冻结 12 位本机构收件人，同名其他机构不会收到消息。 */
    @Test
    void messagingAllAudienceFreezesTwelveRecipientsAndReadIsMemberScoped() throws Exception {
        publish("notice-all", "例会公告", "多行正文\n第二行中文", "all", List.of());
        assertEquals(12, count("SELECT count(*) FROM nw_foundation_notifications WHERE tenant_id=?", Long.valueOf(first.id())));
        for (int member : new int[]{0, 1, 6, 11}) {
            String token = login(first, member).path("access_token").asText();
            assertEquals(1, data(call("GET", BASE + "messaging/unread", token, null)).asLong());
        }
        String other = login(second, 0).path("access_token").asText();
        assertEquals(0, data(call("GET", BASE + "messaging/unread", other, null)).asLong());
        String notification = data(call("GET", BASE + "messaging/inbox?limit=20", admin, null)).path("items").get(0).path("id").asText();
        error(call("POST", BASE + "messaging/read", login(first, 1).path("access_token").asText(), Map.of("id", notification)), 404, "NOTIFICATION_NOT_FOUND");
        assertEquals(1, data(call("GET", BASE + "messaging/unread", admin, null)).asLong());
        data(call("POST", BASE + "messaging/read", admin, Map.of("id", notification)));
        assertEquals(0, data(call("GET", BASE + "messaging/unread", admin, null)).asLong());
    }

    /** 公告同请求幂等，异正文冲突不产生新收件箱；撤回隐藏原正文。 */
    @Test
    void messagingReplayConflictAndWithdrawalPreserveIndependentAnnouncement() throws Exception {
        String announcement = publish("same-key", "目标公告", "正文一", "members", List.of(first.member(1), first.member(2)));
        assertEquals(announcement, publish("same-key", "目标公告", "正文一", "members", List.of(first.member(2), first.member(1))));
        error(call("POST", BASE + "messaging/announcements/publish", admin, Map.of("title", "目标公告", "body", "不一样",
                "audience", "members", "recipient_ids", List.of(first.member(1), first.member(2)), "email", false, "deduplication_key", "same-key")), 409, "MESSAGING_CONFLICT");
        publish("independent", "其他公告", "保留正文", "members", List.of(first.member(1)));
        String recipient = login(first, 1).path("access_token").asText();
        assertEquals(2, data(call("GET", BASE + "messaging/unread", recipient, null)).asLong());
        data(call("POST", BASE + "messaging/announcements/withdraw", admin, Map.of("id", announcement)));
        var inbox = data(call("GET", BASE + "messaging/inbox?limit=20", recipient, null));
        assertEquals(1, inbox.path("items").size()); assertEquals("保留正文", inbox.path("items").get(0).path("body").asText());
    }

    /** 模板占位符与秘密邮箱字段输入严格；错误发布不生成新版本。 */
    @Test
    void messagingTemplateMismatchAndMissingPermissionLeaveVersionsUnchanged() throws Exception {
        for (int i = 0; i < 3; i++) data(call("POST", BASE + "messaging/templates/publish", admin, Map.of("key", "business.notice",
                "subject", "通知{{name}}", "body", "版本" + i + "\n{{name}}:{{status}}", "variable_names", List.of("name", "status"))));
        var before = data(call("GET", BASE + "messaging/templates", admin, null));
        error(call("POST", BASE + "messaging/templates/publish", admin, Map.of("key", "business.notice", "subject", "通知{{name}}",
                "body", "{{missing}}", "variable_names", List.of("name", "status"))), 400, "MESSAGING_INVALID_INPUT");
        error(call("PUT", BASE + "messaging/mailbox", login(first, 1).path("access_token").asText(), Map.of("enabled", false)), 403, "foundation.permission.denied");
        assertEquals(before, data(call("GET", BASE + "messaging/templates", admin, null)));
        assertFalse(data(call("GET", BASE + "messaging/mailbox", admin, null)).path("configured").asBoolean());
    }

    /** 审计分页在机构边界内，错误分页不会被合法默认值掩盖。 */
    @Test
    void operationsAuditFiltersBeforePagingAndRejectsInvalidQuery() throws Exception {
        for (int i = 0; i < 6; i++) data(call("POST", BASE + "access/position", admin, Map.of("code", "audit_" + i, "name", "审计岗位" + i)));
        var page = data(call("GET", BASE + "operations/audit?action=permission.position.create&page_size=3", admin, null));
        assertEquals(6, page.path("total").asLong()); assertEquals(3, page.path("items").size());
        page.path("items").forEach(row -> assertEquals(first.id(), row.path("tenant_id").asText()));
        assertEquals(0, data(call("GET", BASE + "operations/audit?action=permission.position.create", login(second, 0).path("access_token").asText(), null)).path("total").asLong());
        error(call("GET", BASE + "operations/audit?page_size=0", admin, null), 400, "operations.audit.pagination_invalid");
        assertEquals(6, data(call("GET", BASE + "operations/audit?action=permission.position.create", admin, null)).path("total").asLong());
    }

    /** 保留策略 CAS 和非法范围不影响既有生效策略。 */
    @Test
    void operationsRetentionPolicyRejectsStaleVersionAndInvalidRange() throws Exception {
        var initial = data(call("GET", BASE + "operations/retention", admin, null));
        var command = Map.of("retention_days", 30, "enabled", true, "expected_version", initial.path("version").asLong());
        var winner = data(call("PUT", BASE + "operations/retention", admin, command));
        error(call("PUT", BASE + "operations/retention", admin, command), 409, "operations.retention.version_conflict");
        error(call("PUT", BASE + "operations/retention", admin, Map.of("retention_days", 29, "enabled", true, "expected_version", winner.path("version").asLong())), 400, "operations.retention.policy_invalid");
        assertEquals(winner, data(call("GET", BASE + "operations/retention", admin, null)));
        assertFalse(data(call("GET", BASE + "operations/retention", login(third, 0).path("access_token").asText(), null)).path("enabled").asBoolean());
    }

    /** 12条旧事实和3项调查保护：策略改变拒绝旧预览，新预览只删除冻结且未保护的9条。 */
    @Test
    void operationsChangedPolicyRejectsFrozenPreviewWithoutDeletingAudits() throws Exception {
        List<Long> old = oldAudits(first, 12);
        oldAudits(second, 9);
        var foreign = snapshot("nw_audit_logs", "tenant_id", second.id());
        for (int row : new int[]{1, 5, 9}) data(call("POST", BASE + "operations/retention/holds", admin,
                Map.of("audit_id", Long.toString(old.get(row)), "reason", "案件调查" + row)));
        var initial = data(call("GET", BASE + "operations/retention", admin, null));
        var enabled = data(call("PUT", BASE + "operations/retention", admin, Map.of("retention_days", 30, "enabled", true, "expected_version", initial.path("version").asLong())));
        var preview = data(call("POST", BASE + "operations/retention/preview", admin, Map.of()));
        assertEquals(12, preview.path("candidates").asLong()); assertEquals(3, preview.path("protected_count").asLong());
        data(call("PUT", BASE + "operations/retention", admin, Map.of("retention_days", 31, "enabled", true, "expected_version", enabled.path("version").asLong())));
        var before = snapshot("nw_audit_logs", "tenant_id", first.id());
        error(call("POST", BASE + "operations/retention/execute", admin, Map.of("id", preview.path("id").asText())), 409, "operations.retention.policy_changed");
        assertTrue(snapshot("nw_audit_logs", "tenant_id", first.id()).containsAll(before), "拒绝执行不能删除或改写原审计事实");
        var runs = data(call("GET", BASE + "operations/retention/runs", admin, null));
        assertEquals(1, runs.size()); assertEquals("ready", runs.get(0).path("status").asText());
        var fresh = data(call("POST", BASE + "operations/retention/preview", admin, Map.of()));
        List<Long> insertedLater = oldAudits(first, 1);
        var executed = data(call("POST", BASE + "operations/retention/execute", admin, Map.of("id", fresh.path("id").asText())));
        assertEquals("succeeded", executed.path("status").asText());
        assertEquals(9, executed.path("deleted_count").asLong()); assertEquals(3, executed.path("protected_count").asLong());
        for (int row = 0; row < old.size(); row++) assertEquals(Set.of(1, 5, 9).contains(row) ? 1 : 0,
                count("SELECT count(*) FROM nw_audit_logs WHERE id=?", old.get(row)));
        assertEquals(1, count("SELECT count(*) FROM nw_audit_logs WHERE id=?", insertedLater.getFirst()));
        assertEquals(executed, data(call("POST", BASE + "operations/retention/execute", admin, Map.of("id", fresh.path("id").asText()))));
        assertEquals(foreign, snapshot("nw_audit_logs", "tenant_id", second.id()));
    }

    /** @param id 租户ID @param slug 唯一登录slug @param members 12个成员 @param departments 六节点四层树 @param roles 七种正式角色 */
    private record TenantFixture(String id, String slug, long[] members, long[] departments, long[] roles) {
        String member(int i) { return Long.toString(members[i]); }
        String department(int i) { return Long.toString(departments[i]); }
        String role(int i) { return Long.toString(roles[i]); }
    }

    /** 直接建合法事实仅作认证前置，业务行为全部由 HTTP 验证。 */
    private TenantFixture fixture(String passwordHash) {
        String slug = "foundation-http-" + SnowflakeIds.nextId();
        String tenant = createTenant(tenants, plans, slug);
        long[] members = new long[12], departments = new long[6], roles = new long[7];
        for (int i = 0; i < members.length; i++) members[i] = SnowflakeIds.nextId();
        jdbc.update("UPDATE nw_tenants SET override_quota_max_members=50,override_quota_max_admin_tasks=20,override_quota_file_bytes=1000000 WHERE id=?", Long.valueOf(tenant));
        jdbc.update("INSERT INTO nw_foundation_access_scopes(scope_key,tenant_id) VALUES(?,?)", tenant, Long.valueOf(tenant));
        for (int i = 0; i < 6; i++) {
            departments[i] = SnowflakeIds.nextId();
            Long parent = i >= 1 && i <= 3 ? departments[i - 1] : null;
            jdbc.update("INSERT INTO nw_foundation_departments(id,scope_key,code,name,parent_id,enabled,created_by_id,updated_by_id) VALUES(?,?,?,?,?,?,?,?)",
                    departments[i], tenant, "department_" + i, "部门" + i, parent, i != 5, members[0], members[0]);
        }
        String[] scopes = {"all", "all", "descendants", "specified", "department", "self", "all"};
        for (int i = 0; i < 7; i++) {
            roles[i] = SnowflakeIds.nextId();
            jdbc.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope,built_in,administrator,enabled) VALUES(?,?,?,?,?,?,?,?)",
                    roles[i], tenant, "role_" + i, "角色" + i, scopes[i], i == 0, i == 0, i != 6);
            if (i != 0) for (String permission : List.of("foundation.access.read", "foundation.messaging.inbox", "foundation.dictionary.read", "foundation.preferences.manage", "foundation.jobs.read", "foundation.files.read"))
                jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,?)", tenant, roles[i], permission);
        }
        jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,?)", tenant, roles[6], "foundation.access.organization.manage");
        jdbc.update("INSERT INTO nw_foundation_role_departments(scope_key,role_id,department_id) VALUES(?,?,?)", tenant, roles[3], departments[4]);
        for (int i = 0; i < 12; i++) {
            jdbc.update("INSERT INTO nw_tenant_members(id,tenant_id,username,password_hash,email,role,status) VALUES(?,?,?,?,?,'developer','active')",
                    members[i], Long.valueOf(tenant), "member_" + i, passwordHash, "member-" + i + "@example.test");
            jdbc.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name,department_id) VALUES(?,?,?,?)", tenant, members[i], "成员" + i, i == 2 ? departments[1] : departments[i % 6]);
            jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)", tenant, members[i], roles[i == 0 ? 0 : i == 2 ? 2 : 1]);
        }
        for (int role : new int[]{3, 6}) jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)", tenant, members[2], roles[role]);
        for (String feature : List.of("foundation.files", "foundation.jobs")) jdbc.update("INSERT INTO nw_foundation_tenant_features(tenant_id,feature_code,enabled,updated_by) VALUES(?,?,true,'fixture')", Long.valueOf(tenant), feature);
        return new TenantFixture(tenant, slug, members, departments, roles);
    }

    /** 实际密码登录，不伪造JWT或会话权威记录。 */
    private JsonNode login(TenantFixture tenant, int member) throws Exception {
        return data(call("POST", "/api/v1/auth/login", null, Map.of("tenant_slug", tenant.slug(), "username", "member_" + member, "password", PASSWORD)));
    }

    private Map<String, Object> memberUpdate(TenantFixture tenant, int member, String status) {
        return Map.of("id", tenant.member(member), "display_name", "成员" + member, "department_id", tenant.department(member % 6), "status", status, "lock_version", 1);
    }

    private void dictionary(String token, String code) throws Exception {
        data(call("PUT", BASE + "catalog/dictionaries", token, Map.of("code", code, "name", "业务状态", "enabled", true, "expected_revision", 0)));
    }

    private String publish(String key, String title, String body, String audience, List<String> recipients) throws Exception {
        return data(call("POST", BASE + "messaging/announcements/publish", admin, Map.of("title", title, "body", body, "audience", audience,
                "recipient_ids", recipients, "email", false, "deduplication_key", key))).asText();
    }

    /** 固定24行材料，坏行位置预先声明，中文、逗号和引号走真实CSV解析。 */
    private byte[] csv(boolean invalid) {
        StringBuilder out = new StringBuilder("code,label,sort_order,enabled\r\n");
        for (int i = 0; i < 24; i++) {
            out.append("entry_").append(i).append(",\"中文,").append(i).append(" \"\"引号\"\"\",")
                    .append(i).append(',').append(invalid && i % 6 == 3 ? "not_boolean" : i % 2 == 0 ? "true" : "false").append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private HttpResponse<byte[]> upload(String token, String name, byte[] content) throws Exception {
        String boundary = "fixture-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        var request = builder(BASE + "files/upload", token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> call(String method, String path, String token, Object body) throws Exception { return bytes(method, path, token, body); }

    private HttpResponse<byte[]> bytes(String method, String path, String token, Object body) throws Exception {
        var request = builder(path, token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest.Builder builder(String path, String token) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    /** 精确状态和统一信封；不将失败正文输出到断言以避免MFA/凭据泄露。 */
    private JsonNode data(HttpResponse<byte[]> response) throws Exception {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, "HTTP status=" + response.statusCode());
        JsonNode envelope = json.readTree(response.body()); assertEquals("OK", envelope.path("code").asText());
        return envelope.path("data");
    }

    private void error(HttpResponse<byte[]> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        JsonNode envelope = json.readTree(response.body()); assertEquals(code, envelope.path("code").asText());
        assertTrue(envelope.has("data")); assertTrue(envelope.path("data").isNull());
    }

    private Set<String> ids(JsonNode rows) { Set<String> result = new TreeSet<>(); rows.forEach(row -> result.add(row.path("id").asText())); return result; }

    /** 测试代码只传固定表名和列名，snapshot完整事实用于拒绝后的无副作用比较。 */
    private List<String> snapshot(String table, String column, String value) {
        return jdbc.queryForList("SELECT row_to_json(r)::text FROM " + table + " r WHERE " + column + "::text=? ORDER BY row_to_json(r)::text", String.class, value);
    }

    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private long countFiles(TenantFixture tenant) { return count("SELECT count(*) FROM nw_foundation_files WHERE tenant_id=?", Long.valueOf(tenant.id())); }
    /** 仅为隔离库建立历史时间事实，正常清理动作仍走HTTP冻结与当前授权。 */
    private List<Long> oldAudits(TenantFixture tenant, int amount) {
        List<Long> ids = new ArrayList<>();
        for (int index = 0; index < amount; index++) {
            long id = SnowflakeIds.nextId(); ids.add(id);
            jdbc.update("INSERT INTO nw_audit_logs(id,tenant_id,actor_id,action,payload,created_at) VALUES(?,?,?,'operation.synthetic.old','{}'::jsonb,now()-interval '45 days')",
                    id, Long.valueOf(tenant.id()), tenant.member(0));
        }
        return ids;
    }
    private long blobCount() throws Exception { try (var files = Files.list(fileRoot)) { return files.filter(path -> path.toString().endsWith(".blob")).count(); } }

    /** 以公开任务终态而非固定sleep作为完成条件；超时保留失败。 */
    private JsonNode awaitJob(String job) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var value = data(call("GET", BASE + "jobs/detail?job_id=" + job, admin, null));
            if (List.of("succeeded", "failed", "cancelled").contains(value.path("state").asText())) return value;
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        throw new AssertionError("管理任务未到达终态 job=" + job);
    }
}
