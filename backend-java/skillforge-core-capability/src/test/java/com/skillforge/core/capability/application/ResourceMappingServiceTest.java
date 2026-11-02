package com.skillforge.core.capability.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.capability.contract.ResourceType;
import com.skillforge.core.capability.persistence.ResourceMappingMapper;
import com.skillforge.core.capability.persistence.ResourceMappingDO;
import org.junit.jupiter.api.Test;

class ResourceMappingServiceTest {

    @Test
    void createsRevisionOneWithStableEtag() {
        ResourceMappingMapper mapper = mock(ResourceMappingMapper.class);
        ResourceMappingService service = new ResourceMappingService(mapper);
        when(mapper.selectOne(any())).thenReturn(null);

        ResourceMappingDO row = service.upsert("7", "integration-1", ResourceType.WORKFLOW,
                "external-1", "workflow-1", "sha256:a", null, null);

        assertEquals(1L, row.getRevision());
        assertEquals(64, row.getEtag().length());
        assertEquals("workflow", row.getResourceType());
        verify(mapper).insert(any(ResourceMappingDO.class));
    }

    @Test
    void updatesOnlyWhenRevisionAndEtagMatchAndRejectsStaleOrMissingIfMatch() {
        ResourceMappingMapper mapper = mock(ResourceMappingMapper.class);
        ResourceMappingService service = new ResourceMappingService(mapper);
        ResourceMappingDO current = row(2L, "etag-2", "workflow-1");
        when(mapper.selectOne(any())).thenReturn(current);
        when(mapper.update(any(), any())).thenReturn(1);

        ResourceMappingDO updated = service.upsert("7", "integration-1", ResourceType.WORKFLOW,
                "external-1", "workflow-2", "sha256:b", 2L, "\"etag-2\"");
        assertEquals(3L, updated.getRevision());
        assertEquals("workflow-2", updated.getResourceId());
        assertEquals(64, updated.getEtag().length());

        when(mapper.selectOne(any())).thenReturn(row(3L, "etag-3", "workflow-2"));
        ResourceRevisionConflictException stale = assertThrows(ResourceRevisionConflictException.class,
                () -> service.upsert("7", "integration-1", ResourceType.WORKFLOW,
                        "external-1", "workflow-3", "sha256:c", 2L, "etag-2"));
        assertEquals(3L, stale.currentRevision());
        assertThrows(ResourceRevisionConflictException.class, () -> service.upsert(
                "7", "integration-1", ResourceType.WORKFLOW, "external-1", "workflow-3", "sha256:c", null, null));
    }

    @Test
    void rejectsUnexpectedCreatePreconditionAndConcurrencyLostUpdate() {
        ResourceMappingMapper mapper = mock(ResourceMappingMapper.class);
        ResourceMappingService service = new ResourceMappingService(mapper);
        when(mapper.selectOne(any())).thenReturn(null);
        assertThrows(ResourceRevisionConflictException.class, () -> service.upsert(
                "7", "integration-1", ResourceType.WORKFLOW, "external-1", "workflow-1", "sha256:a", 1L, "etag"));

        ResourceMappingDO current = row(1L, "etag-1", "workflow-1");
        when(mapper.selectOne(any())).thenReturn(current);
        when(mapper.update(any(), any())).thenReturn(0);
        assertThrows(ResourceRevisionConflictException.class, () -> service.upsert(
                "7", "integration-1", ResourceType.WORKFLOW, "external-1", "workflow-2", "sha256:b", 1L, "etag-1"));
        verify(mapper, never()).insert(any(ResourceMappingDO.class));
    }

    private static ResourceMappingDO row(long revision, String etag, String resourceId) {
        ResourceMappingDO row = new ResourceMappingDO();
        row.setId(101L);
        row.setTenantId(7L);
        row.setIntegrationId("integration-1");
        row.setResourceType("workflow");
        row.setExternalRef("external-1");
        row.setResourceId(resourceId);
        row.setRevision(revision);
        row.setContentHash("sha256:current");
        row.setEtag(etag);
        return row;
    }
}
