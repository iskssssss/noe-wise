package com.skillforge.core.foundation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.common.context.ManagementContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.security.TokenHash;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.CryptoService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 当前精确动作/目标、独立认证域、稳定安全回执与撤权的服务边界验证。 */
class PlatformManagementServiceTest {
    private FoundationAccess access;
    private ManagementMapper mapper;
    private CryptoService crypto;
    private PlatformManagementService service;
    private ObjectMapper json;
    private ManagementRows.Principal principal;
    private static final String ACTION = "platform.usage.events.read";

    /** 正式构造器装配，每例只替代持久化和加密技术边界。 */
    @BeforeEach void setUp() {
        access = mock(FoundationAccess.class); mapper = mock(ManagementMapper.class); crypto = mock(CryptoService.class);
        json = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new PlatformManagementService(access, mapper, crypto, json, mock(AuditService.class));
        principal = row(); when(mapper.principal(29601L)).thenAnswer(call -> principal);
        when(mapper.forAuthentication(anyString())).thenAnswer(call -> principal);
        when(mapper.targetExists("tenant", 301L)).thenReturn(true);
        when(crypto.decrypt("ciphertext")).thenReturn("synthetic_signing_secret");
    }

    /** 请求线程身份必须在每例清理，防止复用污染。 */
    @AfterEach void clear() { ManagementContext.clear(); ServicePrincipalContext.clear(); TenantContext.clear(); }

    /** 正确签名保留原始查询串和原始 JSON body，认证不注入机构或管理线程身份。 */
    @Test void authenticatesExactRequestWithoutCreatingExecutionIdentity() throws Exception {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String target = "/api/v1/platform/usage/events?tenant_id=301&cursor=a%2Fb";
        String body = "{\"tenant_id\":\"301\"}";
        var identity = service.authenticate("synthetic_key", timestamp, signature(timestamp, "GET", target, body), "GET", target, body);
        assertThat(identity).isEqualTo(new ManagementContext.Identity("29601", 3, 7));
        assertThat(TenantContext.getTenantId()).isNull(); assertThat(ManagementContext.get()).isNull();
        assertThatThrownBy(() -> service.authenticate("synthetic_key", timestamp, signature(timestamp, "GET", target, body),
                "GET", target.replace("301", "302"), body)).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    /** 管理身份不能进入执行、身份自管理或相似前缀路径，拒绝发生在凭据库查询之前。 */
    @Test void deniesExecutionAndSelfAdministrationBeforeCredentialLookup() {
        for (String path : List.of("/api/v1/open-platform/sessions", "/api/v1/platform/management-principals", "/api/v1/platform/usage/events/extra")) {
            assertThatThrownBy(() -> service.authenticate("key", "0", "signature", "GET", path, ""))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
        verifyNoInteractions(mapper);
    }

    /** 签名有效不扩张目标集合，旧线程中的当前修订及凭据版本同样复验。 */
    @Test void exactTargetsAndCurrentCredentialVersionAreBothRequired() {
        ManagementContext.set(new ManagementContext.Identity("29601", 3, 7));
        TenantContext.setTenantId("999");
        service.requireTenant(ACTION, 301);
        assertThat(TenantContext.getTenantId()).isEqualTo("999");
        assertThat(service.allowedTenantIds(ACTION)).containsExactly(301L);
        assertThat(service.actorRef()).isEqualTo("management_principal:29601");
        assertThat(service.authorizationVersion()).isEqualTo(7);
        assertThatThrownBy(() -> service.requireTenant(ACTION, 302)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        principal.setCredentialVersion(4L);
        assertThatThrownBy(() -> service.requireTenant(ACTION, 301)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(TenantContext.getTenantId()).isEqualTo("999");
    }

    /** 机构身份和管理机器均不能使用人员专属签发或撤销入口。 */
    @Test void machinesAndInstitutionPrincipalsCannotIssueManagementCredentials() {
        ManagementContext.set(new ManagementContext.Identity("29601", 3, 7));
        assertThatThrownBy(() -> service.create(request(), "key")).isInstanceOf(ApiException.class);
        ManagementContext.clear();
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp_synthetic", "301", "integration", 1, Set.of("resources.write")));
        assertThatThrownBy(() -> service.requireAction(ACTION)).isInstanceOf(ApiException.class);
        verify(mapper, never()).insertPrincipal(any()); verifyNoInteractions(crypto);
    }

    /** 原安全回执重读不重新签发密钥，且安全响应永远没有哈希或密文。 */
    @Test void repeatedCreateReturnsOriginalSafeReceiptWithoutNewSecrets() throws Exception {
        var person = person(Set.of("platform.management_principals.create", ACTION), true);
        when(access.currentPlatformAuthorization()).thenReturn(person);
        when(mapper.targetAllowed("tenant", 301, "manage", person.dataScope())).thenReturn(true);
        var receipt = new ManagementRows.Receipt();
        receipt.setFingerprint(TokenHash.sha256Hex(json.writeValueAsString(List.of("usage-reader", List.of(ACTION), List.of("301"), List.of(), List.of()))));
        var safe = new ManagementContracts.Principal("29601", "usage-reader", "active", 7, 3,
                List.of(ACTION), List.of("301"), List.of(), List.of(), Instant.EPOCH, Instant.EPOCH);
        receipt.setResultJson(json.writeValueAsString(safe));
        when(mapper.receipt("platform_member:401", "create", "new", "same-key")).thenReturn(receipt);
        var result = service.create(request(), "same-key");
        assertThat(result.replayed()).isTrue(); assertThat(result.managementKey()).isNull(); assertThat(result.signingSecret()).isNull();
        assertThat(result.principal()).isEqualTo(safe);
        assertThat(json.writeValueAsString(result)).doesNotContain("key_hash", "ciphertext");
        verifyNoInteractions(crypto); verify(mapper, never()).insertPrincipal(any());
    }

    /** 签发必须证明当前人员拥有全部精确 scope，不能凭管理员名称或旧JWT扩权。 */
    @Test void cannotDelegateAnActionTheCurrentPersonDoesNotHave() {
        when(access.currentPlatformAuthorization()).thenReturn(person(Set.of("platform.management_principals.create"), true));
        assertThatThrownBy(() -> service.create(request(), "key")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(crypto); verify(mapper, never()).claimReceipt(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /** 空目标不视为全部，非法和未知 scope 不进入任何数据库写入。 */
    @Test void refusesEmptyTargetsAndWildcardScopes() {
        when(access.currentPlatformAuthorization()).thenReturn(person(Set.of("platform.management_principals.create", ACTION), true));
        assertThatThrownBy(() -> service.create(new ManagementContracts.Create("reader", Set.of(ACTION), Set.of(), Set.of(), Set.of()), "key"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> service.create(new ManagementContracts.Create("reader", Set.of("platform.*"), Set.of("301"), Set.of(), Set.of()), "key"))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(crypto);
    }

    /** 固定部署路径同时限制身份域和真实动作，类似后缀不触发平台JWT认证。 */
    @Test void deploymentRoutesAreExactAndMethodSpecific() {
        assertThat(ManagementActions.isDeploymentPath("/api/v1/platform/agent-deployments/grants")).isTrue();
        assertThat(ManagementActions.isDeploymentPath("/api/v1/platform/agent-deployments/receipt/query")).isTrue();
        assertThat(ManagementActions.action("POST", "/api/v1/platform/agent-deployments/receipt/query")).isEqualTo("platform.agent_deployments.read");
        assertThat(ManagementActions.isDeploymentPath("/api/v1/platform/agent-deployments/grants/extra")).isFalse();
        assertThat(ManagementActions.action("GET", "/api/v1/platform/agent-deployments/definitions")).isEqualTo("platform.agent_deployments.read");
        assertThat(ManagementActions.action("DELETE", "/api/v1/platform/agent-deployments")).isNull();
    }

    /** 身份、版本和机构集合从一次当前投影产生，游标消费不能拼接多次IAM读取。 */
    @Test void usageScopeReusesOneCurrentManagementProjection() {
        ManagementContext.set(new ManagementContext.Identity("29601", 3, 7));
        var scope = service.authorizedTenantScope(ACTION);
        assertThat(scope.actorRef()).isEqualTo("management_principal:29601");
        assertThat(scope.authorizationVersion()).isEqualTo(7);
        assertThat(scope.tenantIds()).containsExactly(301L);
        verify(mapper, times(1)).principal(29601L);
    }

    @Test void definitionImpactChecksSourceAndTenantTargetsFromOneCurrentMachineProjection() {
        principal.setScopes(List.of("platform.agent_definitions.read"));
        principal.setTargetDefinitionIds(List.of("601"));
        when(mapper.targetExists("agent_definition",601)).thenReturn(true);
        ManagementContext.set(new ManagementContext.Identity("29601",3,7));
        var scope=service.authorizedDefinitionReadScope(601);
        assertThat(scope.tenantIds()).containsExactly(301L);
        verify(mapper,times(1)).principal(29601L);
        assertThatThrownBy(()->service.authorizedDefinitionReadScope(602)).isInstanceOf(ApiException.class);
        verify(mapper,never()).targetExists("agent_definition",602);
    }

    @Test void definitionImpactPersonScopeDoesNotTurnSourceReadIntoAllInstitutions() {
        var person=person(Set.of("platform.agent_definitions.read"),false);
        when(access.currentPlatformAuthorization()).thenReturn(person);
        when(mapper.targetAllowed("agent_definition",601,"read",person.dataScope())).thenReturn(true);
        when(mapper.allowedTargets("tenant","read",person.dataScope())).thenReturn(List.of(301L));
        assertThat(service.authorizedDefinitionReadScope(601).tenantIds()).containsExactly(301L);
        verify(access,times(1)).currentPlatformAuthorization();
        assertThatThrownBy(()->service.authorizedDefinitionReadScope(602)).isInstanceOf(ApiException.class);
        verify(mapper,times(1)).allowedTargets("tenant","read",person.dataScope());
    }

    @Test void definitionImpactAllowsNoImplicitTenantTargetsAndRejectsExecutionIdentity() {
        principal.setScopes(List.of("platform.agent_definitions.read"));
        principal.setTargetDefinitionIds(List.of("601"));principal.setTargetTenantIds(List.of());
        when(mapper.targetExists("agent_definition",601)).thenReturn(true);
        ManagementContext.set(new ManagementContext.Identity("29601",3,7));
        assertThat(service.authorizedDefinitionReadScope(601).tenantIds()).isEmpty();
        ManagementContext.clear();
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("sp","301","integration",1,Set.of("resources.read")));
        clearInvocations(mapper);
        assertThatThrownBy(()->service.authorizedDefinitionReadScope(601)).isInstanceOf(ApiException.class);
        verifyNoInteractions(mapper);
    }

    /** JSON数字不能经Jackson默认强转绕过十进制字符串ID契约。 */
    @Test void protocolRejectsNumericIdentifiersBeforeServiceAdmission() throws Exception {
        assertThatThrownBy(() -> json.readValue("{\"principal_id\":9007199254740993,\"expected_revision\":1}", ManagementContracts.Command.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        String request = "{\"name\":\"reader\",\"scopes\":[\"" + ACTION
                + "\"],\"target_tenant_ids\":[9007199254740993],\"target_definition_ids\":[],\"target_namespaces\":[]}";
        assertThatThrownBy(() -> json.readValue(request, ManagementContracts.Create.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThat(json.readValue("{\"principal_id\":\"9007199254740993\",\"expected_revision\":1}", ManagementContracts.Command.class).principalId())
                .isEqualTo("9007199254740993");
        for (String revision : List.of("1.5", "\"1\"", "9223372036854775808")) {
            assertThatThrownBy(() -> json.readValue("{\"principal_id\":\"29601\",\"expected_revision\":" + revision + "}", ManagementContracts.Command.class))
                    .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }
    }

    /** 当前平台投影只含已验证身份，scope由测试声明当前事实。 */
    private static FoundationAccess.PlatformAuthorization person(Set<String> permissions, boolean all) {
        return new FoundationAccess.PlatformAuthorization(new FoundationAccess.Subject(true, null, "401"),
                new FoundationAccess.Snapshot(List.of("reader"), permissions.stream().sorted().toList(), 8),
                new FoundationAccess.DataScope(all, "401", Set.of()));
    }
    /** 与公开 DTO 对齐的最小固定目标请求。 */
    private static ManagementContracts.Create request() { return new ManagementContracts.Create("usage-reader", Set.of(ACTION), Set.of("301"), Set.of(), Set.of()); }
    /** 当前身份投影没有任何 Application 关联。 */
    private static ManagementRows.Principal row() {
        var row = new ManagementRows.Principal(); row.setPrincipalId(29601L); row.setStatus("active"); row.setName("reader");
        row.setRevision(7L); row.setCredentialVersion(3L); row.setScopes(List.of(ACTION));
        row.setTargetTenantIds(List.of("301")); row.setTargetDefinitionIds(List.of()); row.setTargetNamespaces(List.of());
        row.setSigningSecretEncrypted("ciphertext"); row.setCreatedAt(Instant.EPOCH); row.setUpdatedAt(Instant.EPOCH); return row;
    }
    /** 独立构造真实HMAC，验证query/body都参与原始canonical。 */
    private static String signature(String timestamp, String method, String target, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("synthetic_signing_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((timestamp + "\n" + method + "\n" + target + "\n" + TokenHash.sha256Hex(body)).getBytes(StandardCharsets.UTF_8)));
    }
}
