package com.skillforge.institution.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.adapter.mcp.McpDiscoveryClient;
import com.skillforge.core.capability.constant.ConnectorConstants;
import com.skillforge.core.capability.dto.connector.CreateConnectorInstanceRequest;
import com.skillforge.core.capability.dto.connector.UpdateConnectorInstanceRequest;
import com.skillforge.core.capability.enums.ConnectorAuthType;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.capability.persistence.ConnectorInstanceDO;
import com.skillforge.core.capability.application.ConnectorInstanceService;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.shared.vault.VaultProvider;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** 连接器控制面覆盖：执行策略、MCP 发现、Vault 凭证、绑定和受控删除。 */
class ConnectorControllerCoverageTest {

    @BeforeAll
    static void initTableMetadata() {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
                ConnectorInstanceDO.class);
    }

    private ConnectorInstanceService instances;
    private CryptoService crypto;
    private VaultProvider vault;
    private McpDiscoveryClient mcp;
    private ConnectorInstanceDO instance;
    private ConnectorController controller;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("7");
        instances = mock(ConnectorInstanceService.class);
        crypto = mock(CryptoService.class);
        vault = mock(VaultProvider.class);
        mcp = mock(McpDiscoveryClient.class);
        instance = instance("inst-1", 21L, ConnectorConstants.EXECUTOR_TYPE_HTTP);
        when(instances.getOne(any(Wrapper.class))).thenReturn(instance);
        when(instances.updateById(any(ConnectorInstanceDO.class))).thenReturn(true);
        when(instances.save(any(ConnectorInstanceDO.class))).thenReturn(true);
        when(instances.list(any(Wrapper.class))).thenReturn(List.of(instance));
        when(crypto.encrypt(any())).thenReturn("encrypted");
        when(crypto.decrypt("encrypted")).thenReturn("plain");
        when(mcp.listTools(any(McpDiscoveryClient.Request.class))).thenReturn(new McpDiscoveryClient.Catalog(
                "inst-1", Optional.empty(), 30_000L, List.of()));
        controller = new ConnectorController(instances, crypto, vault, mcp,
                mock(com.skillforge.institution.service.ConnectorUpdateReceiptMapper.class),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void unknownAuthenticationIsRejectedByDtoDecodingRatherThanDefaultingToBearer() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var type : List.of("basic", "unknown", "")) {
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> json.readValue("{\"executorType\":\"mcp\",\"name\":\"test\",\"baseUrl\":\"https://mcp.example\",\"authType\":\"" + type + "\"}",
                            CreateConnectorInstanceRequest.class));
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> json.readValue("{\"name\":\"test\",\"baseUrl\":\"https://mcp.example\",\"authType\":\"" + type + "\"}",
                            UpdateConnectorInstanceRequest.class));
        }
        for (var type : ConnectorAuthType.values()) {
            var decoded = json.readValue("{\"authType\":\"" + type.code() + "\"}", CreateConnectorInstanceRequest.class);
            assertThat(decoded.authType()).isEqualTo(type);
        }
        assertThat(json.readValue("{}", CreateConnectorInstanceRequest.class).authType()).isNull();
        assertThat(json.readValue("{\"authType\":null}", CreateConnectorInstanceRequest.class).authType()).isNull();
        verify(mcp, never()).listTools(any()); verify(instances, never()).save(any(ConnectorInstanceDO.class));
    }

    @Test
    void createListAndMcpDiscoveryProtectCredentials() {
        var created = controller.createInstance(new CreateConnectorInstanceRequest(
                ConnectorConstants.EXECUTOR_TYPE_MCP, "crm", "https://crm.example",
                ConnectorAuthType.BEARER, "secret", null, Map.of("query", "/q"), Map.of("MCP-Protocol-Version", "2025-11-25")));
        assertThat(created.data().status()).isEqualTo(ConnectorConstants.STATUS_CREATED);
        verify(instances).save(any(ConnectorInstanceDO.class));
        assertThat(controller.listInstances().data().total()).isEqualTo(1);

        McpDiscoveryClient.Tool definition = new McpDiscoveryClient.Tool(
                "search", Optional.of("Search"), "find",
                Map.of("type", "object"),
                Optional.of(Map.of("type", "object")),
                List.of(new McpDiscoveryClient.Icon(
                        "https://mcp.example/search.svg", Optional.of("image/svg+xml"),
                        List.of("32x32"), Optional.of("light"))),
                Optional.of(Map.of("readOnlyHint", true)));
        McpDiscoveryClient.Catalog discovered = new McpDiscoveryClient.Catalog(
                "inst-1", Optional.of("tenant-7"), 30_000L, List.of(definition));
        when(mcp.listTools(any(McpDiscoveryClient.Request.class))).thenReturn(discovered);
        var mcpCreated = controller.createInstance(new CreateConnectorInstanceRequest(
                ConnectorConstants.EXECUTOR_TYPE_MCP, "mcp", "https://mcp.example",
                ConnectorAuthType.BEARER, "secret", null, null, null));
        assertThat(mcpCreated.data().instanceId()).isNotBlank();
        instance.setExecutorType(ConnectorConstants.EXECUTOR_TYPE_MCP);
        instance.setBaseUrl("https://mcp.example");
        instance.setAuthCredentialEncrypted("encrypted");
        var catalog = controller.listMcpTools("inst-1");
        assertThat(catalog.data().total()).isEqualTo(1);
        assertThat(catalog.data().cacheScope()).isEqualTo("tenant-7");
        assertThat(catalog.data().items().getFirst().qualifiedName()).isEqualTo("inst-1/search");
        assertThat(catalog.data().items().getFirst().outputSchema()).containsEntry("type", "object");
        assertThat(catalog.data().items().getFirst().icons()).hasSize(1);
        assertThat(catalog.data().items().getFirst().annotations())
                .containsEntry("readOnlyHint", true);
    }

    @Test
    void instanceListReadsOnlyDisplayColumnsAndKeepsFixedCredentialMask() {
        var result = controller.listInstances().data();
        assertThat(result.total()).isEqualTo(1);
        var query = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(instances).list(query.capture());
        assertThat(query.getValue().getSqlSelect().split(","))
                .containsExactly("id", "instance_id", "name", "base_url", "executor_type", "capabilities", "status");
        assertThat(query.getValue().getSqlSegment()).contains("tenant_id =");
        assertThat(query.getValue().getParamNameValuePairs().values()).contains(7L);
        assertThat(result.items().getFirst().authCredential()).isEqualTo(ConnectorConstants.MASKED_CREDENTIAL);
        org.mockito.Mockito.verifyNoInteractions(crypto, vault, mcp);
    }

    @Test
    void vaultCredentialsUpdateAndInvalidPoliciesFailClosed() {
        when(vault.resolve("secret/crm")).thenReturn("vault-secret");
        assertThatThrownBy(() -> controller.createInstance(new CreateConnectorInstanceRequest(
                ConnectorConstants.EXECUTOR_TYPE_MCP, "crm", "https://crm.example",
                null, null, "secret/crm", null, null))).isInstanceOf(ApiException.class).hasMessageContaining("暂不支持 Vault");
        controller.updateInstance("inst-1", new UpdateConnectorInstanceRequest(
                "renamed", "https://new.example", null,
                List.of("search"), ConnectorAuthType.BEARER, null, "secret/crm",
                Map.of("search", "/search"), Map.of("X-Env", "test"), ConnectorConstants.STATUS_DISABLED));
        assertThat(instance.getName()).isEqualTo("renamed");
        assertThat(instance.getStatus()).isEqualTo(ConnectorConstants.STATUS_DISABLED);
        assertThatThrownBy(() -> controller.createInstance(new CreateConnectorInstanceRequest(
                "lambda", "bad", "https://bad", null, null, null, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.updateInstance("inst-1", new UpdateConnectorInstanceRequest(
                "bad", "https://bad", "lambda", null, null, null, null, null, null, "active")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.updateInstance("inst-1", new UpdateConnectorInstanceRequest(
                "bad", "https://bad", null, null, null, null, null, null, null, "unknown")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void credentialSourcesAndAuthenticationHeadersAreRejectedBeforePersistence() {
        assertThatThrownBy(() -> controller.createInstance(new CreateConnectorInstanceRequest(
                ConnectorConstants.EXECUTOR_TYPE_MCP, "crm", "https://crm.example",
                ConnectorAuthType.BEARER, "request-private-secret", "secret/crm", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("只能选择一种");
        for (String header : List.of(
                "Authorization", "proxy-AUTHORIZATION", "Cookie", "X-Api-Key", "X-Auth-Token")) {
            assertThatThrownBy(() -> controller.createInstance(new CreateConnectorInstanceRequest(
                    ConnectorConstants.EXECUTOR_TYPE_MCP, "crm", "https://crm.example",
                    ConnectorAuthType.BEARER, null, null, null,
                    Map.of(header, "header-private-secret"))))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("不得包含认证信息");
        }
        assertThatThrownBy(() -> controller.updateInstance("inst-1", new UpdateConnectorInstanceRequest(
                "crm", "https://crm.example", null, null, null,
                "request-private-secret", "secret/crm", null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("只能选择一种");
        assertThatThrownBy(() -> controller.updateInstance("inst-1", new UpdateConnectorInstanceRequest(
                "crm", "https://crm.example", null, null, null,
                null, null, null, Map.of(" authorization ", "header-private-secret"), null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不得包含认证信息");

        verify(vault, never()).resolve(any());
        verify(instances, never()).save(any(ConnectorInstanceDO.class));
        verify(instances, never()).updateById(any(ConnectorInstanceDO.class));
    }

    @Test
    void rejectsLegacyMockAndHttpExecutionPoliciesBeforePersistence() {
        for (String executorType : List.of("mock", ConnectorConstants.EXECUTOR_TYPE_HTTP)) {
            assertThatThrownBy(() -> controller.createInstance(new CreateConnectorInstanceRequest(
                    executorType, "legacy", "https://legacy.example", null, null, null, null, null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("仅支持 mcp");
        }
        verify(instances, never()).save(any(ConnectorInstanceDO.class));
    }

    @Test
    void detailFiltersStoredAuthenticationHeadersAndCredentialUpdateClearsVaultSource() {
        Map<String, Object> storedHeaders = new LinkedHashMap<>();
        storedHeaders.put("AUTHORIZATION", "stored-private-secret");
        storedHeaders.put("Cookie", "cookie-private-secret");
        storedHeaders.put("X-Tenant", "7");
        instance.setHeaders(storedHeaders);
        instance.setVaultSecretPath("secret/old");

        var detail = controller.getInstance("inst-1");

        assertThat(detail.data().headers()).containsOnly(Map.entry("X-Tenant", "7"));
        assertThat(detail.data().toString()).doesNotContain("stored-private-secret", "cookie-private-secret");

        controller.updateInstance("inst-1", new UpdateConnectorInstanceRequest(
                "crm", "https://crm.example", null, null, null,
                "replacement-secret", null, null, Map.of("X-Tenant", "8"), null));
        assertThat(instance.getVaultSecretPath()).isNull();
        assertThat(instance.getAuthCredentialEncrypted()).isEqualTo("encrypted");
        assertThat(instance.getHeaders()).containsOnly(Map.entry("X-Tenant", "8"));
    }

    /** 写响应与 GET 使用持久安全视图，完整字段及时间精确一致且不重新解密或发现。 */
    @Test
    void updateReturnsPersistedSafeDetailWithExactReadbackTimestamps() {
        Instant createdAt = Instant.parse("2026-10-12T01:02:03.123456Z");
        Instant persistedUpdatedAt = Instant.parse("2026-10-12T04:05:06.414215Z");
        instance.setCreatedAt(createdAt);
        instance.setVaultSecretPath(null);
        ConnectorInstanceDO persisted = instance("inst-1", 21L, ConnectorConstants.EXECUTOR_TYPE_MCP);
        persisted.setName("renamed");
        persisted.setBaseUrl("https://new.example");
        persisted.setCapabilities(List.of("search", "summary"));
        persisted.setVaultSecretPath(null);
        persisted.setAuthCredentialEncrypted("persisted-private-ciphertext");
        persisted.setCapabilityMapping(Map.of("search", Map.of("path", "/search", "schema", Map.of("type", "object"))));
        persisted.setHeaders(Map.of("MCP-Protocol-Version", "2025-11-25"));
        persisted.setStatus(ConnectorConstants.STATUS_DISABLED);
        persisted.setLastHealthCheckAt(createdAt.plusSeconds(1));
        persisted.setHealthCheckResult(Map.of("healthy", true));
        persisted.setCreatedAt(createdAt);
        persisted.setUpdatedAt(persistedUpdatedAt);
        when(instances.getOne(any(Wrapper.class))).thenReturn(instance, persisted, persisted);

        var receipt = controller.updateInstance("inst-1", new UpdateConnectorInstanceRequest(
                "renamed", "https://new.example", ConnectorConstants.EXECUTOR_TYPE_MCP,
                List.of("search", "summary"), ConnectorAuthType.BEARER, null, null,
                persisted.getCapabilityMapping(), null, ConnectorConstants.STATUS_DISABLED));

        assertThat(receipt).isEqualTo(controller.getInstance("inst-1").data());
        assertThat(receipt.createdAt()).isEqualTo(createdAt);
        assertThat(receipt.updatedAt()).isEqualTo(persistedUpdatedAt);
        assertThat(instance.getUpdatedAt()).isNotEqualTo(persistedUpdatedAt);
        assertThat(receipt.capabilities()).containsExactly("search", "summary");
        assertThat(receipt.capabilityMapping()).isEqualTo(persisted.getCapabilityMapping());
        assertThat(receipt.healthCheckResult()).isEqualTo(persisted.getHealthCheckResult());
        assertThat(receipt.lastHealthCheckAt()).isEqualTo(persisted.getLastHealthCheckAt());
        assertThat(receipt.authCredential()).isEqualTo(ConnectorConstants.MASKED_CREDENTIAL);
        assertThat(receipt.headers()).containsOnly(Map.entry("MCP-Protocol-Version", "2025-11-25"));
        assertThat(receipt.toString()).doesNotContain("persisted-private-ciphertext", "stored-private-secret");
        ArgumentCaptor<Wrapper<ConnectorInstanceDO>> query = instanceQueryCaptor();
        verify(instances, times(3)).getOne(query.capture());
        for (Wrapper<ConnectorInstanceDO> wrapper : query.getAllValues()) {
            assertThat(wrapper.getSqlSegment()).contains("instance_id", "tenant_id", "LIMIT 1");
            assertThat(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values())
                    .containsExactlyInAnyOrder("inst-1", 7L);
        }
        verify(crypto, never()).encrypt(any());
        verify(crypto, never()).decrypt(any());
        verify(vault, never()).resolve(any());
        verify(mcp, never()).listTools(any(McpDiscoveryClient.Request.class));
    }

    @Test
    void importingCapabilitiesPreservesCurrentConnectorConfiguration() throws Exception {
        instance.setExecutorType(ConnectorConstants.EXECUTOR_TYPE_MCP);
        instance.setHeaders(Map.of("MCP-Protocol-Version", "2025-11-25"));
        instance.setCapabilityMapping(Map.of("search", "/current-search"));
        instance.setStatus(ConnectorConstants.STATUS_DISABLED);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var command = json.readValue("""
                {"instance_id":"inst-1","payload":{"capabilities":["search","summary"]}}
                """, ConnectorController.UpdateInstanceCommand.class);
        try (var validation = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(validation.getValidator().validate(command)).isEmpty();
        }

        var receipt = controller.updateInstanceEndpoint(command).data();

        assertThat(receipt.capabilities()).containsExactly("search", "summary");
        assertThat(instance.getName()).isEqualTo("crm");
        assertThat(instance.getBaseUrl()).isEqualTo("https://crm.example");
        assertThat(instance.getExecutorType()).isEqualTo(ConnectorConstants.EXECUTOR_TYPE_MCP);
        assertThat(instance.getAuthType()).isEqualTo("bearer");
        assertThat(instance.getAuthCredentialEncrypted()).isEqualTo("encrypted");
        assertThat(instance.getVaultSecretPath()).isNull();
        assertThat(instance.getHeaders()).containsOnly(Map.entry("MCP-Protocol-Version", "2025-11-25"));
        assertThat(instance.getCapabilityMapping()).containsOnly(Map.entry("search", "/current-search"));
        assertThat(instance.getStatus()).isEqualTo(ConnectorConstants.STATUS_DISABLED);
        verify(instances).updateById(instance);
        verify(crypto, never()).encrypt(any());
        verify(crypto, never()).decrypt(any());
        verify(vault, never()).resolve(any());
        verify(mcp, never()).listTools(any());
    }

    @Test
    void providedBlankNameOrAddressIsRejectedBeforeAnyMutation() {
        for (var blank : List.of("", " \t\n")) {
            for (var request : List.of(
                    new UpdateConnectorInstanceRequest(blank, null, null,
                            List.of("summary"), null, "replacement", null, null, null, null),
                    new UpdateConnectorInstanceRequest(null, blank, null,
                            List.of("summary"), null, "replacement", null, null, null, null))) {
                ApiException rejected = assertThrows(ApiException.class,
                        () -> controller.updateInstance("inst-1", request));
                assertThat(rejected.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
        }
        assertThat(instance.getName()).isEqualTo("crm");
        assertThat(instance.getBaseUrl()).isEqualTo("https://crm.example");
        assertThat(instance.getCapabilities()).containsExactly("search");
        verify(instances, never()).updateById(any(ConnectorInstanceDO.class));
        verify(crypto, never()).encrypt(any());
        verify(crypto, never()).decrypt(any());
        verify(vault, never()).resolve(any());
        verify(mcp, never()).listTools(any());
    }

    @Test
    void unsupportedMcpAddressesAreRejectedBeforeSavingEncryptionOrDiscovery() {
        instance.setExecutorType(ConnectorConstants.EXECUTOR_TYPE_MCP);
        for (var endpoint : List.of("https://mcp.example/tools?old=true", "https://user@mcp.example/tools",
                "https://mcp.example/tools#fragment", "/relative", "not a URI")) {
            for (var rejected : List.of(
                    assertThrows(ApiException.class, () -> controller.createInstance(new CreateConnectorInstanceRequest(
                            "mcp", "invalid", endpoint, ConnectorAuthType.BEARER, "secret", null, null, null))),
                    assertThrows(ApiException.class, () -> controller.updateInstance("inst-1", new UpdateConnectorInstanceRequest(
                            null, endpoint, null, null, null, "secret", null, null, null, null))))) {
                assertThat(rejected.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            }
            assertThat(instance.getBaseUrl()).isEqualTo("https://crm.example");
            instance.setBaseUrl(endpoint);
            assertThat(assertThrows(ApiException.class, () -> controller.listMcpTools("inst-1")).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            instance.setBaseUrl("https://crm.example");
        }
        verify(instances, never()).save(any(ConnectorInstanceDO.class));
        verify(instances, never()).updateById(any(ConnectorInstanceDO.class));
        verify(crypto, never()).encrypt(any());
        verify(crypto, never()).decrypt(any());
        verify(vault, never()).resolve(any());
        verify(mcp, never()).listTools(any());
    }

    /** 未实际写入时不能返回内存对象或发起假定成功的回读。 */
    @Test
    void updateWithoutPersistedWriteFailsBeforeReadback() {
        when(instances.updateById(any(ConnectorInstanceDO.class))).thenReturn(false);
        assertThatThrownBy(() -> controller.updateInstance("inst-1", unchangedCredentialUpdate()))
                .isInstanceOf(IllegalStateException.class).hasMessage("连接器更新未写入");
        verify(instances, times(1)).getOne(any(Wrapper.class));
    }

    /** 写入成功但同租户持久回执缺失时显式失败，禁止回退到未持久表示。 */
    @Test
    void missingPersistedUpdateReceiptFailsWithoutMemoryFallback() {
        when(instances.getOne(any(Wrapper.class))).thenReturn(instance).thenReturn(null);
        assertThatThrownBy(() -> controller.updateInstance("inst-1", unchangedCredentialUpdate()))
                .isInstanceOf(IllegalStateException.class).hasMessage("连接器更新后的持久回执缺失");
        verify(instances).updateById(instance);
        verify(instances, times(2)).getOne(any(Wrapper.class));
    }

    /** 跨租户不可见实例在原查询边界拒绝，更新及回执读取均没有执行权。 */
    @Test
    void foreignTenantUpdateRejectsBeforeAnyWriteOrReceiptReadback() {
        TenantContext.setTenantId("8");
        when(instances.getOne(any(Wrapper.class))).thenReturn(null);
        ApiException rejected = assertThrows(ApiException.class,
                () -> controller.updateInstance("inst-1", unchangedCredentialUpdate()));
        assertThat(rejected.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        ArgumentCaptor<Wrapper<ConnectorInstanceDO>> query = instanceQueryCaptor();
        verify(instances).getOne(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("instance_id", "tenant_id", "LIMIT 1");
        assertThat(((AbstractWrapper<?, ?, ?>) query.getValue()).getParamNameValuePairs().values())
                .containsExactlyInAnyOrder("inst-1", 8L);
        verify(instances, never()).updateById(any(ConnectorInstanceDO.class));
        verify(crypto, never()).encrypt(any());
        verify(crypto, never()).decrypt(any());
        verify(vault, never()).resolve(any());
        verify(mcp, never()).listTools(any(McpDiscoveryClient.Request.class));
    }

    /** 合法更新保持原凭证与可选配置，负控只改变写入或回读条件。 */
    private UpdateConnectorInstanceRequest unchangedCredentialUpdate() {
        return new UpdateConnectorInstanceRequest("renamed", "https://new.example", null,
                null, null, null, null, null, null, null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<ConnectorInstanceDO>> instanceQueryCaptor() {
        return ArgumentCaptor.forClass((Class) Wrapper.class);
    }

    @Test
    void unavailableVaultCredentialFailsClosedWithoutSaving() {
        when(vault.resolve("secret/missing"))
                .thenThrow(new IllegalStateException("vault-private-secret"));

        assertThatThrownBy(() -> controller.createInstance(new CreateConnectorInstanceRequest(
                ConnectorConstants.EXECUTOR_TYPE_MCP, "crm", "https://crm.example",
                ConnectorAuthType.BEARER, null, "secret/missing", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("暂不支持 Vault")
                .hasNoCause();
        verify(instances, never()).save(any(ConnectorInstanceDO.class));
    }

    @Test
    void unsupportedMcpConfigurationsFailBeforeDiscoveryEncryptionOrPersistence() {
        var headers = List.<Map<String, Object>>of(Map.of("X-Region", "east"),
                Map.of("MCP-Protocol-Version", "invalid"), Map.of("MCP-Protocol-Version", 123));
        for (var configuration : headers) {
            assertThatThrownBy(() -> controller.createInstance(new CreateConnectorInstanceRequest(
                    "mcp", "invalid", "https://mcp.example", ConnectorAuthType.BEARER, "secret", null, null, configuration)))
                    .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> controller.createInstance(new CreateConnectorInstanceRequest(
                "mcp", "invalid", "https://mcp.example", ConnectorAuthType.API_KEY, "secret", null, null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("无认证或 Bearer");
        assertThatThrownBy(() -> controller.createInstance(new CreateConnectorInstanceRequest(
                "mcp", "invalid", "https://mcp.example", ConnectorAuthType.BEARER, null, "secret/path", null, null)))
                .isInstanceOf(ApiException.class).hasMessageContaining("暂不支持 Vault");
        instance.setExecutorType("mcp");
        for (var configuration : headers) {
            assertThatThrownBy(() -> controller.updateInstance("inst-1", new UpdateConnectorInstanceRequest(
                    "invalid", "https://mcp.example", null, null, null, null, null, null, configuration, null)))
                    .isInstanceOf(ApiException.class);
            instance.setHeaders(configuration);
            assertThatThrownBy(() -> controller.listMcpTools("inst-1")).isInstanceOf(ApiException.class);
            instance.setHeaders(Map.of());
        }
        for (var type : List.of("api_key", "bearer")) {
            instance.setAuthType(type); instance.setVaultSecretPath(type.equals("bearer") ? "secret/path" : null);
            assertThatThrownBy(() -> controller.listMcpTools("inst-1")).isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> controller.updateInstance("inst-1", unchangedCredentialUpdate())).isInstanceOf(ApiException.class);
        }
        verify(mcp, never()).listTools(any()); verify(crypto, never()).encrypt(any()); verify(crypto, never()).decrypt(any());
        verify(vault, never()).resolve(any()); verify(instances, never()).save(any(ConnectorInstanceDO.class));
        verify(instances, never()).updateById(any(ConnectorInstanceDO.class));
    }

    @Test
    void supportedMcpProtocolsAndNoAuthenticationDiscoverWithTheExactRuntimeConfiguration() {
        instance.setExecutorType("mcp"); instance.setAuthType("none"); instance.setAuthCredentialEncrypted(null);
        for (var version : List.of("2026-07-28", "2025-11-25")) {
            instance.setHeaders(Map.of("MCP-Protocol-Version", version));
            controller.listMcpTools("inst-1");
        }
        var calls = ArgumentCaptor.forClass(McpDiscoveryClient.Request.class);
        verify(mcp, times(2)).listTools(calls.capture());
        assertThat(calls.getAllValues()).extracting(McpDiscoveryClient.Request::protocolVersion)
                .containsExactly("2026-07-28", "2025-11-25");
        assertThat(calls.getAllValues()).allMatch(value -> value.bearerToken() == null);
        verify(crypto, never()).decrypt(any());
    }

    @Test
    void changingMcpToNoAuthenticationClearsTheStoredTokenBeforeBindingOrDiscovery() {
        instance.setExecutorType("mcp");
        controller.updateInstance("inst-1", new UpdateConnectorInstanceRequest(
                "no-auth", "https://mcp.example", null, null, ConnectorAuthType.NONE,
                "", "", null, Map.of(), null));
        assertThat(instance.getAuthCredentialEncrypted()).isEmpty();
        controller.listMcpTools("inst-1");
        verify(crypto, never()).decrypt(any());
        var request = ArgumentCaptor.forClass(McpDiscoveryClient.Request.class);
        verify(mcp).listTools(request.capture()); assertThat(request.getValue().bearerToken()).isNull();
    }

    @Test
    void editingMcpCanReplaceUnsupportedStoredConfigurationWithSupportedDirectCredentials() {
        instance.setExecutorType("mcp"); instance.setAuthType("api_key");
        instance.setVaultSecretPath("secret/old"); instance.setHeaders(Map.of("X-Old", "value"));
        controller.updateInstance("inst-1", new UpdateConnectorInstanceRequest(
                "repaired", "https://mcp.example", "mcp", null, ConnectorAuthType.BEARER,
                "direct-secret", "", null, Map.of("MCP-Protocol-Version", "2025-11-25"), null));
        assertThat(instance.getAuthType()).isEqualTo("bearer");
        assertThat(instance.getVaultSecretPath()).isEmpty();
        assertThat(instance.getHeaders()).containsOnly(Map.entry("MCP-Protocol-Version", "2025-11-25"));
        verify(instances).updateById(instance); verify(vault, never()).resolve(any());
    }

    @Test
    void mcpDetailAndDeleteUseTenantScopedApplicationService() {
        assertThat(controller.getInstance("inst-1").data().instanceId()).isEqualTo("inst-1");
        assertThat(controller.deleteInstance("inst-1").status()).isEqualTo(ConnectorConstants.STATUS_DELETED);
        verify(instances).deleteUnused(7L, "inst-1");
    }

    @Test
    void missingAndNonMcpInstancesAreNotLeaked() {
        when(instances.getOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> controller.getInstance("missing")).isInstanceOf(ApiException.class);
        when(instances.getOne(any(Wrapper.class))).thenReturn(instance);
        assertThatThrownBy(() -> controller.listMcpTools("inst-1")).isInstanceOf(ApiException.class);
    }

    private ConnectorInstanceDO instance(String instanceId, Long id, String executorType) {
        ConnectorInstanceDO value = new ConnectorInstanceDO();
        value.setId(id);
        value.setInstanceId(instanceId);
        value.setTenantId(7L);
        value.setName("crm");
        value.setBaseUrl("https://crm.example");
        value.setExecutorType(executorType);
        value.setAuthType("bearer");
        value.setAuthCredentialEncrypted("encrypted");
        value.setCapabilities(List.of("search"));
        value.setCapabilityMapping(Map.of());
        value.setHeaders(Map.of());
        value.setStatus(ConnectorConstants.STATUS_ACTIVE);
        value.setVisibility(ConnectorConstants.VISIBILITY_TENANT);
        return value;
    }
}
