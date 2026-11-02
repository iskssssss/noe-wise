package com.skillforge.institution.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.capability.constant.ConnectorConstants;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.capability.persistence.ConnectorInstanceDO;
import com.skillforge.core.capability.application.ConnectorInstanceService;
import java.util.Locale;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

/** ToolDeployment Provider 引用的租户、状态和执行器校验。 */
class TenantToolDeploymentProviderValidationTest {

    @BeforeAll
    static void initTableMetadata() {
        TableInfoHelper.remove(ConnectorInstanceDO.class);
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, ""), ConnectorInstanceDO.class);
    }

    private final ConnectorInstanceService connectorInstances = mock(ConnectorInstanceService.class);
    private final TenantToolDeploymentProviderValidator validator =
            new TenantToolDeploymentProviderValidator(connectorInstances, new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()));

    @Test
    void knownPlatformHandlerIsAcceptedWithoutConnectorLookup() {
        String handler = "get_current_time";

        for (var environment : ToolDeployment.Environment.values()) {
            validator.validate(7L, ToolDeployment.ProviderType.PLATFORM, handler, environment);
        }

        verifyNoInteractions(connectorInstances);
    }

    @Test
    void unknownPlatformHandlerIsRejectedWithoutConnectorLookup() {
        assertInvalid(() -> validator.validate(7L, ToolDeployment.ProviderType.PLATFORM,
                "not-a-platform-handler", ToolDeployment.Environment.DEVELOPMENT));

        verifyNoInteractions(connectorInstances);
    }

    @Test
    void httpConnectorHasNoInstitutionProviderInAnyEnvironment() {
        for (var environment : ToolDeployment.Environment.values()) {
            assertInvalid(() -> validator.validate(7L, ToolDeployment.ProviderType.CONNECTOR,
                    "inst-http", environment));
        }
        verifyNoInteractions(connectorInstances);
    }

    @Test
    void activeMcpConnectorMustBeOwnedByTenantAndUseMcpExecutor() {
        when(connectorInstances.getOne(any(Wrapper.class))).thenReturn(activeInstance("mcp"));

        for (var environment : ToolDeployment.Environment.values()) {
            validator.validate(7L, ToolDeployment.ProviderType.MCP, "inst-mcp", environment);
        }

        ArgumentCaptor<Wrapper<ConnectorInstanceDO>> captor = wrapperCaptor();
        verify(connectorInstances, times(ToolDeployment.Environment.values().length)).getOne(captor.capture());
        captor.getAllValues().forEach(query -> assertConnectorQuery(query, 7L, "inst-mcp", "mcp"));
    }

    @Test
    void missingCrossTenantDisabledAndWrongExecutorProvidersUseSameStableError() {
        when(connectorInstances.getOne(any(Wrapper.class))).thenReturn(null);

        var missing = assertInvalid(() -> validator.validate(7L,
                ToolDeployment.ProviderType.MCP, "missing", ToolDeployment.Environment.TEST));
        var crossTenant = assertInvalid(() -> validator.validate(7L,
                ToolDeployment.ProviderType.MCP, "owned-by-other-tenant",
                ToolDeployment.Environment.TEST));
        var disabled = assertInvalid(() -> validator.validate(7L,
                ToolDeployment.ProviderType.MCP, "disabled", ToolDeployment.Environment.TEST));
        var wrongExecutor = assertInvalid(() -> validator.validate(7L,
                ToolDeployment.ProviderType.MCP, "inst-http", ToolDeployment.Environment.TEST));

        assertEquals(missing.getDetail(), crossTenant.getDetail());
        assertEquals(missing.getDetail(), disabled.getDetail());
        assertEquals(missing.getDetail(), wrongExecutor.getDetail());
    }

    @Test
    void testMockIsAnSdkExtensionRatherThanAnInstitutionProvider() {
        for (var environment : ToolDeployment.Environment.values()) {
            assertInvalid(() -> validator.validate(7L, ToolDeployment.ProviderType.TEST_MOCK,
                    "test-key", environment));
        }
        verifyNoInteractions(connectorInstances);
    }

    @Test
    void blankOrInvalidValidationArgumentsUseDeploymentError() {
        assertInvalid(() -> validator.validate(7L, ToolDeployment.ProviderType.CONNECTOR,
                " ", ToolDeployment.Environment.TEST));
        assertInvalid(() -> validator.validate(7L, null, "ref", ToolDeployment.Environment.TEST));
        assertInvalid(() -> validator.validate(7L, ToolDeployment.ProviderType.CONNECTOR,
                "ref", null));
        assertInvalid(() -> validator.validate(0L, ToolDeployment.ProviderType.CONNECTOR,
                "ref", ToolDeployment.Environment.TEST));
        verifyNoInteractions(connectorInstances);
    }

    private ConnectorInstanceDO activeInstance(String executorType) {
        ConnectorInstanceDO instance = new ConnectorInstanceDO();
        instance.setInstanceId("inst-" + executorType);
        instance.setTenantId(7L);
        instance.setExecutorType(executorType);
        instance.setStatus(ConnectorConstants.STATUS_ACTIVE);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Wrapper<ConnectorInstanceDO>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    @SuppressWarnings("unchecked")
    private void assertConnectorQuery(
            Wrapper<ConnectorInstanceDO> wrapper,
            Long tenantId,
            String instanceId,
            String executorType) {
        assertTrue(wrapper instanceof LambdaQueryWrapper);
        LambdaQueryWrapper<ConnectorInstanceDO> query =
                (LambdaQueryWrapper<ConnectorInstanceDO>) wrapper;
        String sql = query.getSqlSegment().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("tenant_id"), () -> "sql segment: " + sql);
        assertTrue(sql.contains("instance_id"), () -> "sql segment: " + sql);
        assertTrue(sql.contains("status"), () -> "sql segment: " + sql);
        assertTrue(sql.contains("executor_type"), () -> "sql segment: " + sql);
        assertTrue(sql.contains("limit 1"), () -> "sql segment: " + sql);
        assertTrue(query.getParamNameValuePairs().containsValue(tenantId));
        assertTrue(query.getParamNameValuePairs().containsValue(instanceId));
        assertTrue(query.getParamNameValuePairs().containsValue(ConnectorConstants.STATUS_ACTIVE));
        assertTrue(query.getParamNameValuePairs().containsValue(executorType));
    }

    private ApiException assertInvalid(
            org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(ApiException.class, executable);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, exception.getStatus());
        assertEquals(CapabilityCatalogErrors.DEPLOYMENT_INVALID,
                exception.getCode());
        return exception;
    }
}
