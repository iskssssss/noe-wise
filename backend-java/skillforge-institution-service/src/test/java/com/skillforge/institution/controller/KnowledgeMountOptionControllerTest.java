package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogService;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.knowledge.KnowledgeReferenceService;
import com.skillforge.institution.service.KnowledgeManagementService;
import com.skillforge.institution.service.KnowledgeMountOptionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeMountOptionControllerTest {
    private final FoundationAccess access = mock(FoundationAccess.class);
    private final KnowledgeManagementService management = mock(KnowledgeManagementService.class);
    private final KnowledgeReferenceService knowledge = mock(KnowledgeReferenceService.class);
    private final CapabilityCatalogService catalog = mock(CapabilityCatalogService.class);
    private final KnowledgeMountOptionService service = new KnowledgeMountOptionService(access, management, knowledge, catalog);
    @BeforeEach void identity() { TenantContext.setTenantId("7"); }
    @AfterEach void clear() { TenantContext.clear(); }

    @Test void readsExactActivePublishedVersionWhenItMatchesCurrentDefault() throws Exception {
        when(knowledge.base(7, 10)).thenReturn(new KnowledgeReferenceService.Base(10L, 7L, "active", 20L));
        when(knowledge.publishedRevision(7, 20)).thenReturn(new KnowledgeReferenceService.Revision(20L, 7L, 10L, "published", 30L));
        when(catalog.publishedVersions(CapabilityCatalogScope.tenant(7L), List.of(30L))).thenReturn(List.of(
                new CapabilityVersionView(30L, 40L, 7L, CapabilityType.KNOWLEDGE_SOURCE, "revision:1", "知识", "", "", Map.of(), "", "PUBLISHED", null, null)));
        var definition = mock(com.skillforge.core.capability.CapabilityDefinitionView.class);
        when(definition.defaultVersionId()).thenReturn(30L);
        when(catalog.getDefinition(CapabilityCatalogScope.tenant(7L), 40L)).thenReturn(definition);
        var option = service.option(10L);
        assertEquals(20L, option.activeRevisionId());
        assertEquals(30L, option.versionId());
        assertEquals(40L, option.definitionId());
        verify(access).require("foundation.knowledge.read");
        verify(management).requireBase("7", 10L, "read");
        assertEquals("foundation.capabilities.read", KnowledgeMountOptionController.class.getMethod("option", Long.class).getAnnotation(RequirePermission.class).value());
    }
    @Test void unpublishedOrMismatchedRevisionNeverReturnsCapability() {
        when(knowledge.base(7, 10)).thenReturn(new KnowledgeReferenceService.Base(10L, 7L, "active", null),
                new KnowledgeReferenceService.Base(10L, 7L, "active", 20L));
        assertNull(service.option(10L).activeRevisionId());
        when(knowledge.publishedRevision(7, 20)).thenReturn(new KnowledgeReferenceService.Revision(20L, 7L, 11L, "published", 30L));
        var inconsistent = service.option(10L);
        assertEquals(20L, inconsistent.activeRevisionId());
        assertNull(inconsistent.versionId());
        verifyNoInteractions(catalog);
    }
    @Test void unavailableCapabilityDoesNotBecomeMountable() {
        when(knowledge.base(7, 10)).thenReturn(new KnowledgeReferenceService.Base(10L, 7L, "active", 20L));
        when(knowledge.publishedRevision(7, 20)).thenReturn(new KnowledgeReferenceService.Revision(20L, 7L, 10L, "published", 30L));
        when(catalog.publishedVersions(CapabilityCatalogScope.tenant(7L), List.of(30L))).thenReturn(List.of());
        var option = service.option(10L);
        assertEquals(20L, option.activeRevisionId());
        assertNull(option.definitionId());
        assertNull(option.versionId());
    }
    @Test void activeVersionDifferentFromCurrentDefaultCannotBeMounted() {
        when(knowledge.base(7, 10)).thenReturn(new KnowledgeReferenceService.Base(10L, 7L, "active", 20L));
        when(knowledge.publishedRevision(7, 20)).thenReturn(new KnowledgeReferenceService.Revision(20L, 7L, 10L, "published", 30L));
        when(catalog.publishedVersions(CapabilityCatalogScope.tenant(7L), List.of(30L))).thenReturn(List.of(
                new CapabilityVersionView(30L, 40L, 7L, CapabilityType.KNOWLEDGE_SOURCE, "revision:1", "知识", "", "", Map.of(), "", "PUBLISHED", null, null)));
        var definition = mock(com.skillforge.core.capability.CapabilityDefinitionView.class);
        when(definition.defaultVersionId()).thenReturn(31L);
        when(catalog.getDefinition(CapabilityCatalogScope.tenant(7L), 40L)).thenReturn(definition);
        var option = service.option(10L);
        assertEquals(20L, option.activeRevisionId());
        assertNull(option.versionId());
    }
    @Test void deniedResourceNeverReadsItsPublication() {
        doThrow(new IllegalStateException("denied")).when(management).requireBase("7", 10L, "read");
        assertThrows(IllegalStateException.class, () -> service.option(10L));
        verifyNoInteractions(knowledge, catalog);
    }
}
