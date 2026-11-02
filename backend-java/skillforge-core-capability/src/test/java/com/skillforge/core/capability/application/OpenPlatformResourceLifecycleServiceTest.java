package com.skillforge.core.capability.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.contract.ResourceType;
import com.skillforge.core.capability.persistence.OpenPlatformDeploymentMapper;
import com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionMapper;
import com.skillforge.core.capability.persistence.OpenPlatformDeploymentDO;
import com.skillforge.core.capability.persistence.OpenPlatformResourceRevisionDO;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenPlatformResourceLifecycleServiceTest {

    @Test
    void createsAndCasUpdatesDraftThenMovesItToReadyAndPublished() {
        OpenPlatformResourceRevisionMapper revisions = mock(OpenPlatformResourceRevisionMapper.class);
        OpenPlatformDeploymentMapper deployments = mock(OpenPlatformDeploymentMapper.class);
        OpenPlatformPublicationListener bindings = mock(OpenPlatformPublicationListener.class);
        OpenPlatformResourceLifecycleService service = service(revisions, deployments, bindings);
        when(revisions.selectOne(any())).thenReturn(null);

        OpenPlatformResourceRevisionDO draft = service.createDraft(
                "7", ResourceType.AGENT, "agent-1", Map.of("name", "v1"));
        assertEquals(1L, draft.getRevision());
        assertEquals("draft", draft.getStatus());
        assertEquals(64, draft.getEtag().length());

        when(revisions.selectOne(any())).thenReturn(draft);
        when(revisions.update(any(), any())).thenReturn(1);
        OpenPlatformResourceRevisionDO updated = service.updateDraft(
                "7", ResourceType.AGENT, "agent-1", 1L, draft.getEtag(), Map.of("name", "v1-updated"));
        assertEquals("v1-updated", updated.getContent().get("name"));
        assertEquals("ready", service.markReady(
                "7", ResourceType.AGENT, "agent-1", 1L, updated.getEtag()).getStatus());

        updated.setStatus("ready");
        OpenPlatformResourceRevisionDO published = service.publish(
                "7", ResourceType.AGENT, "agent-1", 1L, updated.getEtag());
        assertEquals("published", published.getStatus());
        verify(revisions).insert(any(OpenPlatformResourceRevisionDO.class));
    }

    @Test
    void refusesEditingPublishedRevisionAndStaleCas() {
        OpenPlatformResourceRevisionMapper revisions = mock(OpenPlatformResourceRevisionMapper.class);
        OpenPlatformResourceLifecycleService service = service(revisions,
                mock(OpenPlatformDeploymentMapper.class), mock(OpenPlatformPublicationListener.class));
        OpenPlatformResourceRevisionDO published = revision("published", 3L, "etag-3");
        when(revisions.selectOne(any())).thenReturn(published);

        assertThrows(ResourceRevisionConflictException.class, () -> service.updateDraft(
                "7", ResourceType.WORKFLOW, "workflow-1", 2L, "stale", Map.of()));
        assertThrows(IllegalStateException.class, () -> service.updateDraft(
                "7", ResourceType.WORKFLOW, "workflow-1", 3L, "etag-3", Map.of()));
        verify(revisions, never()).update(any(), any());
    }

    @Test
    void deployRequiresPublishedRevisionAndUsesDeploymentCas() {
        OpenPlatformResourceRevisionMapper revisions = mock(OpenPlatformResourceRevisionMapper.class);
        OpenPlatformDeploymentMapper deployments = mock(OpenPlatformDeploymentMapper.class);
        OpenPlatformResourceLifecycleService service = service(revisions, deployments,
                mock(OpenPlatformPublicationListener.class));
        when(revisions.selectOne(any())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.deploy(
                "7", "deployment-1", ResourceType.AGENT, "agent-1", 1L, "prod", null, null));

        OpenPlatformResourceRevisionDO published = revision("published", 1L, "etag-r1");
        when(revisions.selectOne(any())).thenReturn(published);
        when(deployments.selectOne(any())).thenReturn(null);
        OpenPlatformDeploymentDO created = service.deploy(
                "7", "deployment-1", ResourceType.AGENT, "agent-1", 1L, "prod", null, null);
        assertEquals(1L, created.getDeploymentRevision());
        when(deployments.selectOne(any())).thenReturn(created);
        when(deployments.update(any(), any())).thenReturn(1);
        OpenPlatformDeploymentDO updated = service.deploy(
                "7", "deployment-1", ResourceType.AGENT, "agent-1", 1L, "staging",
                1L, created.getEtag());
        assertEquals(2L, updated.getDeploymentRevision());
        assertEquals("staging", updated.getEnvironment());
    }

    @Test
    void catalogReturnsOneLatestSummaryPerResourceAndSupportsTypeAndStatusFilters() {
        OpenPlatformResourceRevisionMapper revisions = mock(OpenPlatformResourceRevisionMapper.class);
        OpenPlatformResourceLifecycleService service = service(revisions,
                mock(OpenPlatformDeploymentMapper.class), mock(OpenPlatformPublicationListener.class));
        OpenPlatformResourceRevisionDO agentV2 = revision("published", 2L, "agent-v2");
        agentV2.setResourceType("agent");
        OpenPlatformResourceRevisionDO workflowV1 = revision("ready", 1L, "workflow-v1");
        workflowV1.setResourceType("workflow");
        when(revisions.listLatestSummaries(7,null,null,100)).thenReturn(List.of(agentV2,workflowV1));
        when(revisions.listLatestSummaries(7,"agent","published",1)).thenReturn(List.of(agentV2));

        List<OpenPlatformResourceRevisionDO> all = service.listLatest("7", null, null, 100);

        assertEquals(2, all.size());
        assertEquals(List.of("agent-v2", "workflow-v1"), all.stream()
                .map(OpenPlatformResourceRevisionDO::getEtag).toList());
        assertEquals(1, service.listLatest("7", ResourceType.AGENT, "published", 1).size());
        verify(revisions,never()).selectList(any());
        assertThrows(IllegalArgumentException.class,
                () -> service.listLatest("7", null, "unknown", 100));
    }

    @Test
    void readsSpecificRevisionWithTenantScopedKeyAndRejectsNonPositiveRevision() {
        OpenPlatformResourceRevisionMapper revisions = mock(OpenPlatformResourceRevisionMapper.class);
        OpenPlatformResourceLifecycleService service = service(revisions,
                mock(OpenPlatformDeploymentMapper.class), mock(OpenPlatformPublicationListener.class));
        OpenPlatformResourceRevisionDO row = revision("published", 3L, "etag-3");
        when(revisions.selectOne(any())).thenReturn(row);

        assertEquals(row, service.revision("7", ResourceType.AGENT, "agent-1", 3L));
        assertThrows(IllegalArgumentException.class,
                () -> service.revision("7", ResourceType.AGENT, "agent-1", 0L));
    }

    private OpenPlatformResourceLifecycleService service(OpenPlatformResourceRevisionMapper revisions,
            OpenPlatformDeploymentMapper deployments, OpenPlatformPublicationListener bindings) {
        return new OpenPlatformResourceLifecycleService(revisions, deployments, List.of(bindings), new ObjectMapper());
    }

    private OpenPlatformResourceRevisionDO revision(String status, long revision, String etag) {
        OpenPlatformResourceRevisionDO row = new OpenPlatformResourceRevisionDO();
        row.setId(100L);
        row.setTenantId(7L);
        row.setResourceType("agent");
        row.setResourceId("agent-1");
        row.setRevision(revision);
        row.setStatus(status);
        row.setContent(Map.of());
        row.setContentHash("hash");
        row.setEtag(etag);
        return row;
    }

    private OpenPlatformDeploymentDO deployment(String type, String resourceId, long resourceRevision) {
        OpenPlatformDeploymentDO row = new OpenPlatformDeploymentDO();
        row.setId(101L);
        row.setTenantId(7L);
        row.setDeploymentId("deployment-1");
        row.setResourceType(type);
        row.setResourceId(resourceId);
        row.setResourceRevision(resourceRevision);
        row.setEnvironment("prod");
        row.setStatus("active");
        row.setDeploymentRevision(1L);
        row.setEtag("etag-deployment");
        return row;
    }
}
