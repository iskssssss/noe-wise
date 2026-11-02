package com.skillforge.institution.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityDefinitionView;
import com.skillforge.core.capability.CapabilityDeploymentView;
import com.skillforge.core.capability.CapabilityProducerPublicationBridge;
import com.skillforge.core.capability.CapabilityProducerPublicationRequest;
import com.skillforge.core.capability.CapabilityProducerPublicationResult;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.capability.application.ConnectorInstanceService;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.capability.persistence.ConnectorInstanceDO;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.service.TenantToolDeploymentProviderValidator;
import com.skillforge.runtime.adapter.mcp.McpDiscoveryClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ToolOnboardingServiceTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeConnectorMetadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                ConnectorInstanceDO.class);
    }
    private final FoundationAccess access = mock(FoundationAccess.class);
    private final ToolOnboardingReceiptMapper receipts = mock(ToolOnboardingReceiptMapper.class);
    private final ConnectorInstanceService connectors = mock(ConnectorInstanceService.class);
    private final McpDiscoveryClient discovery = mock(McpDiscoveryClient.class);
    private final CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
    private final CapabilityProducerPublicationBridge publications = mock(CapabilityProducerPublicationBridge.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final PlatformToolCatalog platformTools = mock(PlatformToolCatalog.class);
    private final ConnectorInstanceDO connector = new ConnectorInstanceDO();
    private ToolOnboardingService service;

    @BeforeEach
    void setUp() {
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "9"));
        when(receipts.claim(any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        when(receipts.complete(any(), any(), any(), any(), any())).thenReturn(1);
        connector.setId(11L);
        connector.setTenantId(7L);
        connector.setInstanceId("inst-owned");
        connector.setExecutorType("mcp");
        connector.setName("Owned service");
        connector.setBaseUrl("http://127.0.0.1:18000/mcp");
        connector.setAuthType("none");
        connector.setHeaders(Map.of());
        connector.setStatus("active");
        connector.setCapabilities(List.of("keep_existing"));
        when(connectors.getOne(any(Wrapper.class))).thenReturn(connector);
        when(catalog.findDefinitionForWrite(any(), any(), any())).thenReturn(Optional.empty());
        when(catalog.listDeployments(any(), any())).thenReturn(List.of());
        when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        service = new ToolOnboardingService(access, mock(CapabilityCatalogDatabaseContext.class), connectors,
                mock(CryptoService.class), discovery, catalog, publications,
                mock(TenantToolDeploymentProviderValidator.class), platformTools,
                new ObjectMapper(), receipts, transactions);
    }

    @Test
    void availableGroupsCurrentSummaryAndKeepsConnectorAndRegistryFilters() {
        when(connectors.list(any(Wrapper.class))).thenReturn(List.of(connector));
        when(platformTools.names()).thenReturn(java.util.Set.of("builtin"));
        var mcp = com.skillforge.core.capability.tool.ToolDeployment.ProviderType.MCP;
        var platform = com.skillforge.core.capability.tool.ToolDeployment.ProviderType.PLATFORM;
        var environment = com.skillforge.core.capability.tool.ToolDeployment.Environment.TEST;
        when(catalog.availableToolDeployments(any())).thenReturn(List.of(
                new com.skillforge.core.capability.AvailableToolDeployment(1L, 9L, "Current", "description", "keep_existing", "a", mcp, "inst-owned", environment),
                new com.skillforge.core.capability.AvailableToolDeployment(1L, 9L, "Current", "description", "keep_existing", "b", platform, "builtin", environment),
                new com.skillforge.core.capability.AvailableToolDeployment(2L, 10L, "Removed", "", "removed", "c", mcp, "inst-owned", environment),
                new com.skillforge.core.capability.AvailableToolDeployment(3L, 11L, "Inactive", "", "keep_existing", "d", mcp, "inactive", environment),
                new com.skillforge.core.capability.AvailableToolDeployment(4L, 12L, "Unknown platform", "", "unknown", "e", platform, "unknown", environment)));
        var items = service.available(7L).items();
        assertEquals(1, items.size());
        assertEquals("9", items.getFirst().defaultVersionId());
        assertEquals(List.of("Owned service", "builtin"), items.getFirst().deployments().stream()
                .map(ToolOnboardingViews.Deployment::providerLabel).toList());
        ArgumentCaptor<Wrapper<ConnectorInstanceDO>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(connectors).list(query.capture());
        assertEquals("instance_id,name,capabilities", query.getValue().getSqlSelect());
        verify(access).require("foundation.capabilities.read");
        org.mockito.Mockito.verify(catalog, org.mockito.Mockito.never()).getVersion(any(), any());
    }

    @Test
    void missingPublishPermissionRejectsBeforeCatalogNetworkOrMutation() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "denied")).when(access)
                .require("foundation.capabilities.publish");
        assertThrows(ApiException.class, () -> service.onboard(7L, java.util.UUID.randomUUID(), "inst-owned", List.of("read_tool"), true));
        verifyNoInteractions(discovery, catalog, publications, transactions);
    }

    @Test
    void missingConnectorUpdatePermissionCannotUnionCapabilitiesIndirectly() {
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "denied")).when(access)
                .require("foundation.connectors.update");
        assertThrows(ApiException.class, () -> service.onboard(7L, java.util.UUID.randomUUID(), "inst-owned", List.of("read_tool"), true));
        verifyNoInteractions(discovery, catalog, publications, transactions);
    }

    @Test
    void missingSelectedToolRejectsWholeSelectionBeforeAnyPublication() {
        when(discovery.listTools(any())).thenReturn(new McpDiscoveryClient.Catalog(
                "inst-owned", Optional.empty(), 0, List.of()));
        var result = service.onboard(7L, java.util.UUID.randomUUID(), "inst-owned", List.of("not_in_catalog"), true);
        assertEquals(ToolOnboardingViews.OperationStatus.REJECTED, result.status());
        verifyNoInteractions(publications);
        assertEquals(List.of("keep_existing"), connector.getCapabilities());
    }

    @Test
    void newToolKeepsUntrustedReadOnlyHintAsMetadataAndUsesConservativePolicy() {
        var tool = new McpDiscoveryClient.Tool("read_tool", Optional.empty(), "",
                Map.of("type", "object", "properties", Map.of()), Optional.empty(), List.of(),
                Optional.of(Map.of("readOnlyHint", true)));
        when(discovery.listTools(any())).thenReturn(new McpDiscoveryClient.Catalog(
                "inst-owned", Optional.empty(), 0, List.of(tool)));
        AtomicReference<CapabilityDefinitionView> saved = new AtomicReference<>();
        AtomicReference<CapabilityVersionView> version = new AtomicReference<>();
        AtomicReference<CapabilityDeploymentView> deployment = new AtomicReference<>();
        when(catalog.findDefinitionForWrite(any(), any(), any())).thenAnswer(ignored -> Optional.ofNullable(saved.get()));
        when(catalog.listDeployments(any(), any())).thenAnswer(ignored -> deployment.get() == null
                ? List.of() : List.of(deployment.get()));
        when(publications.publish(any())).thenAnswer(invocation -> {
            CapabilityProducerPublicationRequest request = invocation.getArgument(0);
            saved.set(new CapabilityDefinitionView(20L, 7L, CapabilityType.TOOL, request.capabilityKey(),
                    request.name(), request.description(), "TENANT", "PUBLISHED", 21L, 2L, null, null));
            version.set(new CapabilityVersionView(21L, 20L, 7L, CapabilityType.TOOL, request.versionKey(),
                    request.name(), request.description(), "TENANT", request.descriptor(), request.contentHash(),
                    "PUBLISHED", null, null));
            return new CapabilityProducerPublicationResult(saved.get(), version.get(), false);
        });
        when(catalog.createDeployment(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            deployment.set(new CapabilityDeploymentView(30L, 7L, invocation.getArgument(1), 21L,
                    ToolDeployment.ProviderType.MCP, "inst-owned", ToolDeployment.Environment.DEVELOPMENT,
                    "ACTIVE", 1L, null, null));
            return deployment.get();
        });
        when(catalog.getVersion(any(), any())).thenAnswer(ignored -> version.get());
        when(catalog.getCurrentPublishedVersion(any(), any())).thenAnswer(ignored -> version.get());
        when(connectors.updateById(any())).thenReturn(true);

        var response = service.onboard(7L, java.util.UUID.randomUUID(), "inst-owned", List.of("read_tool"), true);
        ArgumentCaptor<CapabilityProducerPublicationRequest> publication =
                ArgumentCaptor.forClass(CapabilityProducerPublicationRequest.class);
        verify(publications).publish(publication.capture());
        Map<String, Object> descriptor = publication.getValue().descriptor();
        assertEquals("NON_IDEMPOTENT_WRITE", descriptor.get("effect"));
        assertEquals(false, descriptor.get("idempotent"));
        assertEquals("NEVER", descriptor.get("confirmation_policy"));
        assertEquals(false, descriptor.get("parallel_safe"));
        assertFalse(descriptor.containsKey("output_schema"));
        assertEquals(Map.of("readOnlyHint", true), descriptor.get("annotations"));
        assertEquals("read_tool", publication.getValue().description());
        assertEquals(ToolOnboardingViews.OperationStatus.SUCCEEDED, response.status());
        assertEquals("read_tool", response.items().getFirst().toolName());
        assertTrue(connector.getCapabilities().containsAll(List.of("keep_existing", "read_tool")));
    }
}
