package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.mapper.WorkflowContainerMapper;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.model.WorkflowContainerDO;
import com.skillforge.core.workflow.model.WorkflowDefinitionVersionDO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Builder 安装按发布态选版；精确定义读取不重新选择当前版本。 */
class WorkflowInstallationDefinitionServiceTest {

    private final WorkflowContainerMapper containers = mock(WorkflowContainerMapper.class);
    private final WorkflowDefinitionVersionMapper versions = mock(WorkflowDefinitionVersionMapper.class);
    private final WorkflowInstallationDefinitionService service = new WorkflowInstallationDefinitionService(
            containers, versions);

    @Test
    void requiresAPublishedActiveVersionForBuilderInstallOrUpgrade() {
        WorkflowContainerDO draft = container(42L, "builder-flow", "draft", null);
        when(containers.selectOne(any())).thenReturn(draft);

        ApiException error = assertThrows(ApiException.class,
                () -> service.requirePublishedDefinitionVersion(7L, "builder-flow"));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
    }

    @Test
    void returnsThePublishedActiveVersionForBuilderInstallOrUpgrade() {
        WorkflowContainerDO published = container(42L, "builder-flow", "published", 90L);
        when(containers.selectOne(any())).thenReturn(published);
        when(versions.selectOne(any())).thenReturn(version(90L, 42L, 3L));

        assertEquals(90L, service.requirePublishedDefinitionVersion(7L, "builder-flow"));
    }

    @Test
    void draftEditsKeepTheCurrentPublicationAvailableWithoutReinstallation() {
        when(containers.selectOne(any())).thenReturn(container(42L, "builder-flow", "draft", 90L));
        when(versions.selectOne(any())).thenReturn(version(90L, 42L, 3L));
        assertEquals(90L, service.requirePublishedDefinitionVersion(7L, "builder-flow"));
    }

    @Test
    void rejectsMissingBuilderOrMissingPublishedVersionAtInstallBoundary() {
        when(containers.selectOne(any())).thenReturn(null);
        ApiException missingWorkflow = assertThrows(ApiException.class,
                () -> service.requirePublishedDefinitionVersion(7L, "missing"));
        assertEquals(HttpStatus.NOT_FOUND, missingWorkflow.getStatus());

        WorkflowContainerDO published = container(42L, "builder-flow", "published", 90L);
        when(containers.selectOne(any())).thenReturn(published);
        when(versions.selectOne(any())).thenReturn(null);
        ApiException missingVersion = assertThrows(ApiException.class,
                () -> service.requirePublishedDefinitionVersion(7L, "builder-flow"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, missingVersion.getStatus());
    }

    @Test
    void readsTheExactDefinitionAndRejectsMissingBody() {
        var version = version(90L, 42L, 3L);
        when(versions.selectOne(any())).thenReturn(version);
        assertEquals(version.getDefinition(), service.definition(7L, 90L));
        org.mockito.Mockito.verifyNoInteractions(containers);
        version.setDefinition(null);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class,
                () -> service.definition(7L, 90L)).getStatus());
    }

    private static WorkflowDefinitionVersionDO version(long id, long containerId, long version) {
        WorkflowDefinitionVersionDO definition = new WorkflowDefinitionVersionDO();
        definition.setId(id);
        definition.setTenantId(7L);
        definition.setContainerId(containerId);
        definition.setDefinitionVersion(version);
        definition.setDefinition(Map.of("entry", "start", "nodes", List.of(), "edges", List.of()));
        return definition;
    }

    private static WorkflowContainerDO container(long id, String workflowId, String status, Long activeVersion) {
        WorkflowContainerDO container = new WorkflowContainerDO();
        container.setId(id);
        container.setTenantId(7L);
        container.setWorkflowId(workflowId);
        container.setStatus(status);
        container.setActiveDefinitionVersionId(activeVersion);
        return container;
    }
}
