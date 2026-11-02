package com.skillforge.core.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Producer bridge 的原子发布、幂等和作用域隔离契约。 */
class CapabilityProducerPublicationBridgeTest {

    private final CapabilityCatalogService catalog = org.mockito.Mockito.mock(
            CapabilityCatalogService.class);
    private final CapabilityProducerPublicationBridge bridge =
            new CapabilityProducerPublicationBridge(catalog);
    private final CapabilityDescriptorCodec descriptorCodec = new CapabilityDescriptorCodec(
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));

    private final CapabilityCatalogScope tenant = CapabilityCatalogScope.tenant(7L);
    private final CapabilityCatalogScope platform = CapabilityCatalogScope.platform();

    @BeforeEach
    void defaultLookups() {
        when(catalog.findVersionByKey(any(), anyLong(), any()))
                .thenReturn(Optional.empty());
        when(catalog.findVersionByContentHash(any(), anyLong(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void firstPublicationCreatesDefinitionValidatesAndPublishesInLifecycleOrder() {
        CapabilityDefinitionView definition = definition(11L, 7L, "DRAFT", 1L);
        CapabilityDefinitionView publishedDefinition = definition(11L, 7L, "PUBLISHED", 2L);
        CapabilityVersionView draft = version(21L, 11L, 7L, "v1", "hash", "DRAFT");
        CapabilityVersionView published = version(21L, 11L, 7L, "v1", "hash", "PUBLISHED");
        when(catalog.findDefinitionForWrite(tenant, CapabilityType.TOOL, "calendar"))
                .thenReturn(Optional.empty());
        when(catalog.createDefinition(tenant, CapabilityType.TOOL,
                "calendar", "Calendar", "calendar tool")).thenReturn(definition);
        when(catalog.createDraftVersion(tenant, 11L, "v1", "Calendar",
                "calendar tool", "hash", descriptor())).thenReturn(draft);
        when(catalog.validateVersion(tenant, 11L, 21L)).thenReturn(
                new CapabilityValidationView(11L, 21L, 1L, true, List.of()));
        when(catalog.publishVersion(tenant, 11L, 21L, 1L)).thenReturn(published);
        when(catalog.getDefinition(tenant, 11L)).thenReturn(publishedDefinition);

        InOrder order = inOrder(catalog);
        CapabilityProducerPublicationResult result = bridge.publish(request(tenant));

        assertFalse(result.idempotent());
        assertEquals(CapabilityProducerPublicationResult.Status.PUBLISHED, result.status());
        assertEquals("PUBLISHED", result.version().status());
        assertEquals("PUBLISHED", result.definition().status());
        assertEquals(2L, result.definition().revision());
        verify(catalog).createDefinition(tenant, CapabilityType.TOOL,
                "calendar", "Calendar", "calendar tool");
        verify(catalog).validateVersion(tenant, 11L, 21L);
        verify(catalog).publishVersion(tenant, 11L, 21L, 1L);
        order.verify(catalog).findDefinitionForWrite(tenant, CapabilityType.TOOL, "calendar");
        order.verify(catalog).createDefinition(tenant, CapabilityType.TOOL,
                "calendar", "Calendar", "calendar tool");
        order.verify(catalog).findVersionByKey(tenant, 11L, "v1");
        order.verify(catalog).findVersionByContentHash(tenant, 11L, "hash");
        order.verify(catalog).createDraftVersion(tenant, 11L, "v1", "Calendar",
                "calendar tool", "hash", descriptor());
        order.verify(catalog).validateVersion(tenant, 11L, 21L);
        order.verify(catalog).publishVersion(tenant, 11L, 21L, 1L);
        order.verify(catalog).getDefinition(tenant, 11L);
    }

    @Test
    void presetInstallationLeavesEveryExistingLifecycleUnderCatalogManagement() {
        for (String status : List.of("DRAFT", "PUBLISHED", "DISABLED", "DEPRECATED", "ARCHIVED")) {
            when(catalog.findDefinitionForWrite(platform, CapabilityType.TOOL, "calendar"))
                    .thenReturn(Optional.of(new CapabilityDefinitionView(11L, null, CapabilityType.TOOL,
                            "calendar", "Calendar", "calendar tool", "PLATFORM", status, 99L, 8L, null, null)));

            assertTrue(bridge.installIfAbsent(request(platform)).isEmpty());
        }
        verify(catalog, never()).createDefinition(any(), any(), any(), any(), any());
        verify(catalog, never()).createDraftVersion(any(), anyLong(), any(), any(), any(), any(), anyMap());
        verify(catalog, never()).publishVersion(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void samePublishedVersionAndHashIsIdempotentWithoutCreatingDraft() {
        CapabilityDefinitionView definition = definition(11L, 7L, "PUBLISHED", 3L);
        CapabilityVersionView published = version(21L, 11L, 7L, "v1", "hash", "PUBLISHED");
        when(catalog.findDefinitionForWrite(tenant, CapabilityType.TOOL, "calendar"))
                .thenReturn(Optional.of(definition));
        when(catalog.findVersionByKey(tenant, 11L, "v1")).thenReturn(Optional.of(published));

        CapabilityProducerPublicationResult result = bridge.publish(request(tenant));

        assertTrue(result.idempotent());
        assertEquals(published, result.version());
        verify(catalog, never()).createDraftVersion(
                any(), anyLong(), any(), any(), any(), any(), anyMap());
        verify(catalog, never()).publishVersion(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void subsequentVersionUsesCurrentDefinitionRevisionForPublishCas() {
        CapabilityDefinitionView definition = definition(11L, 7L, "PUBLISHED", 8L);
        CapabilityVersionView draft = version(22L, 11L, 7L, "v2", "hash-v2", "DRAFT");
        CapabilityVersionView published = version(22L, 11L, 7L, "v2", "hash-v2", "PUBLISHED");
        when(catalog.findDefinitionForWrite(tenant, CapabilityType.TOOL, "calendar"))
                .thenReturn(Optional.of(definition));
        when(catalog.createDraftVersion(tenant, 11L, "v2", "Calendar",
                "calendar tool", "hash-v2", descriptor())).thenReturn(draft);
        when(catalog.validateVersion(tenant, 11L, 22L)).thenReturn(
                new CapabilityValidationView(11L, 22L, 8L, true, List.of()));
        when(catalog.publishVersion(tenant, 11L, 22L, 8L)).thenReturn(published);

        CapabilityProducerPublicationResult result = bridge.publish(request(
                tenant, "v2", "hash-v2"));

        assertFalse(result.idempotent());
        assertEquals("PUBLISHED", result.version().status());
        verify(catalog).publishVersion(tenant, 11L, 22L, 8L);
    }

    @Test
    void sameVersionKeyWithDifferentContentIsStableConflict() {
        CapabilityDefinitionView definition = definition(11L, 7L, "PUBLISHED", 3L);
        CapabilityVersionView existing = version(21L, 11L, 7L, "v1", "old-hash", "PUBLISHED");
        when(catalog.findDefinitionForWrite(tenant, CapabilityType.TOOL, "calendar"))
                .thenReturn(Optional.of(definition));
        when(catalog.findVersionByKey(tenant, 11L, "v1")).thenReturn(Optional.of(existing));

        ApiException exception = assertThrows(ApiException.class,
                () -> bridge.publish(request(tenant, "v1", "new-hash")));

        assertEquals(CapabilityCatalogErrors.CONFLICT,
                exception.getCode());
        verify(catalog, never()).createDraftVersion(
                any(), anyLong(), any(), any(), any(), any(), anyMap());
        verify(catalog, never()).publishVersion(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void tenantWriteScopeDoesNotReuseVisiblePlatformDefinition() {
        CapabilityDefinitionView tenantDefinition = definition(31L, 7L, "DRAFT", 1L);
        CapabilityVersionView draft = version(41L, 31L, 7L, "v1", "hash", "DRAFT");
        CapabilityVersionView published = version(41L, 31L, 7L, "v1", "hash", "PUBLISHED");
        when(catalog.findDefinitionForWrite(tenant, CapabilityType.TOOL, "calendar"))
                .thenReturn(Optional.empty());
        when(catalog.createDefinition(tenant, CapabilityType.TOOL,
                "calendar", "Calendar", "calendar tool")).thenReturn(tenantDefinition);
        when(catalog.createDraftVersion(tenant, 31L, "v1", "Calendar",
                "calendar tool", "hash", descriptor())).thenReturn(draft);
        when(catalog.validateVersion(tenant, 31L, 41L)).thenReturn(
                new CapabilityValidationView(31L, 41L, 1L, true, List.of()));
        when(catalog.publishVersion(tenant, 31L, 41L, 1L)).thenReturn(published);

        bridge.publish(request(tenant));

        verify(catalog).findDefinitionForWrite(tenant, CapabilityType.TOOL, "calendar");
        verify(catalog, never()).findDefinitionForWrite(platform, CapabilityType.TOOL, "calendar");
        verify(catalog).createDefinition(tenant, CapabilityType.TOOL,
                "calendar", "Calendar", "calendar tool");
    }

    @Test
    void invalidDescriptorStopsBeforePublishAndLeavesDraftLifecycleToRollback() {
        CapabilityDefinitionView definition = definition(11L, 7L, "DRAFT", 1L);
        when(catalog.findDefinitionForWrite(tenant, CapabilityType.TOOL, "calendar"))
                .thenReturn(Optional.of(definition));
        when(catalog.createDraftVersion(any(), anyLong(), any(), any(), any(), any(), anyMap()))
                .thenThrow(CapabilityCatalogErrors.exception(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        CapabilityCatalogErrors.DESCRIPTOR_INVALID,
                        "descriptor validation failed"));

        ApiException exception = assertThrows(ApiException.class,
                () -> bridge.publish(request(tenant)));

        assertEquals(CapabilityCatalogErrors.DESCRIPTOR_INVALID,
                exception.getCode());
        verify(catalog, never()).validateVersion(any(), anyLong(), anyLong());
        verify(catalog, never()).publishVersion(any(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void duplicateDuringDraftCreationBecomesPublicationConflict() {
        CapabilityDefinitionView definition = definition(11L, 7L, "DRAFT", 1L);
        when(catalog.findDefinitionForWrite(tenant, CapabilityType.TOOL, "calendar"))
                .thenReturn(Optional.of(definition));
        var duplicate = CapabilityCatalogErrors.exception(org.springframework.http.HttpStatus.CONFLICT,
                CapabilityCatalogErrors.DUPLICATE, "duplicate version");
        when(catalog.createDraftVersion(any(), anyLong(), any(), any(), any(), any(), anyMap()))
                .thenThrow(duplicate);

        var failure = assertThrows(ApiException.class, () -> bridge.publish(request(tenant)));
        assertEquals(CapabilityCatalogErrors.CONFLICT, failure.getCode());
        assertEquals(duplicate, failure.getCause());
        verify(catalog, never()).validateVersion(any(), anyLong(), anyLong());
    }

    private CapabilityProducerPublicationRequest request(CapabilityCatalogScope scope) {
        return request(scope, "v1", "hash");
    }

    private CapabilityProducerPublicationRequest request(
            CapabilityCatalogScope scope, String versionKey, String contentHash) {
        return new CapabilityProducerPublicationRequest(scope, CapabilityType.TOOL,
                "calendar", versionKey, "Calendar", "calendar tool", contentHash,
                descriptor());
    }

    private Map<String, Object> descriptor() {
        Map<String, Object> descriptor = Map.ofEntries(
                Map.entry("model_invocable", true),
                Map.entry("user_invocable", false),
                Map.entry("tool_name", "calendar.lookup"),
                Map.entry("input_schema", Map.of()),
                Map.entry("output_schema", Map.of()),
                Map.entry("effect", "READ_ONLY"),
                Map.entry("idempotent", true),
                Map.entry("confirmation_policy", "NEVER"),
                Map.entry("timeout_ms", 1000),
                Map.entry("sensitive_fields", List.of()),
                Map.entry("provider_requirements", List.of()));
        descriptorCodec.decode(new CapabilityRef(CapabilityType.TOOL, "1", "2"),
                7L, "Calendar", "calendar tool", "TENANT", "PUBLISHED", "hash", descriptor);
        return descriptor;
    }

    private CapabilityDefinitionView definition(
            long id, long tenantId, String status, long revision) {
        return new CapabilityDefinitionView(id, tenantId, CapabilityType.TOOL,
                "calendar", "Calendar", "calendar tool", "TENANT", status, null, revision,
                null, null);
    }

    private CapabilityVersionView version(
            long id, long definitionId, long tenantId, String versionKey,
            String contentHash, String status) {
        return new CapabilityVersionView(id, definitionId, tenantId, CapabilityType.TOOL,
                versionKey, "Calendar v1", "calendar tool", "TENANT", descriptor(),
                contentHash, status, null, "PUBLISHED".equals(status) ? null : null);
    }
}
