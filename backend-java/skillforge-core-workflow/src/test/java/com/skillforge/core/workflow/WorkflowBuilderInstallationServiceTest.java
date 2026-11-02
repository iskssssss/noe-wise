package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.dto.response.WorkflowInstallResponse;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper;
import com.skillforge.core.workflow.model.WorkflowInstallationDO;
import com.skillforge.core.workflow.model.WorkflowInstanceDO;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;

/** Builder 安装只追加不可变修订，再以 CAS 切换实例当前指针。 */
class WorkflowBuilderInstallationServiceTest {

    private final com.skillforge.core.workflow.mapper.WorkflowInstanceMapper instances = org.mockito.Mockito.mock(com.skillforge.core.workflow.mapper.WorkflowInstanceMapper.class);
    private final WorkflowInstallationDefinitionService definitions = org.mockito.Mockito.mock(WorkflowInstallationDefinitionService.class);
    private final WorkflowInstallationMapper installations = org.mockito.Mockito.mock(WorkflowInstallationMapper.class);
    private final WorkflowInstallationValidator validator = org.mockito.Mockito.mock(WorkflowInstallationValidator.class);
    private final WorkflowBuilderInstallationService service = new WorkflowBuilderInstallationService(
            instances, definitions, installations, validator, new ObjectMapper());

    @BeforeEach
    void resolvesConfiguredStableTargetsBeforeFreezingInstallation() {
        when(validator.currentBindings(anyLong(), any())).thenAnswer(call -> call.getArgument(1));
    }

    @Test
    void createsAnImmutableRevisionAndActivatesItsInstancePointer() {
        when(definitions.requirePublishedDefinitionVersion(7L, "builder-flow")).thenReturn(91L);
        when(definitions.definition(7L, 91L)).thenReturn(definition());
        when(instances.selectOne(any())).thenReturn(null);
        when(instances.insert(any(WorkflowInstanceDO.class))).thenReturn(1);
        when(installations.insert(any())).thenReturn(1);
        when(installations.activate(eq(7L), anyLong(), eq(0L), eq(1L), eq(91L))).thenReturn(1);

        WorkflowInstallResponse response = service.installOrUpgrade(7L, "builder-flow",
                Map.of("locale", "zh-CN"), "prompt", WorkflowInstallationBindings.empty());

        assertEquals("builder-flow_7", response.instanceId());
        assertEquals(1L, response.installationRevision());
        var revision = org.mockito.ArgumentCaptor.forClass(WorkflowInstallationDO.class);
        verify(installations).insert(revision.capture());
        assertEquals(1L, revision.getValue().getInstallationRevision());
        assertEquals(91L, revision.getValue().getDefinitionVersionId());
        assertEquals(Map.of("locale", "zh-CN"), revision.getValue().getConfig());
        assertEquals("prompt", revision.getValue().getCustomPrompt());
        var created = org.mockito.ArgumentCaptor.forClass(WorkflowInstanceDO.class);
        verify(instances).insert(created.capture());
        assertEquals(7L, revision.getValue().getTenantId());
        assertEquals(created.getValue().getId(), revision.getValue().getInstanceId());
        assertEquals("builder-flow_7", created.getValue().getInstanceId());
        assertEquals(7L, created.getValue().getTenantId());
        assertEquals(0L, created.getValue().getInstallationRevision());
        assertEquals("draft", created.getValue().getStatus());
        verify(installations).activate(7L, created.getValue().getId(), 0L, 1L, 91L);
        verify(instances, never()).updateById(any(WorkflowInstanceDO.class));
    }

    @Test
    void appendsNextRevisionAndUsesTheCurrentRevisionForCompareAndSet() {
        WorkflowInstanceDO existing = instance(11L, 4L, 91L);
        when(definitions.requirePublishedDefinitionVersion(7L, "builder-flow")).thenReturn(92L);
        when(definitions.definition(7L, 92L)).thenReturn(definition());
        when(instances.selectOne(any())).thenReturn(existing);
        when(installations.insert(any())).thenReturn(1);
        when(installations.activate(7L, 11L, 4L, 5L, 92L)).thenReturn(1);

        WorkflowInstallResponse response = service.installOrUpgrade(7L, "builder-flow",
                Map.of("locale", "en-US"), "new prompt", WorkflowInstallationBindings.empty());

        assertEquals(5L, response.installationRevision());
        var revision = org.mockito.ArgumentCaptor.forClass(WorkflowInstallationDO.class);
        verify(installations).insert(revision.capture());
        assertEquals(5L, revision.getValue().getInstallationRevision());
        assertEquals(92L, revision.getValue().getDefinitionVersionId());
        assertEquals(Map.of("locale", "en-US"), revision.getValue().getConfig());
        assertEquals("new prompt", revision.getValue().getCustomPrompt());
        assertEquals(existing.getId(), revision.getValue().getInstanceId());
        assertEquals(91L, existing.getDefinitionVersionId());
        verify(installations).activate(7L, 11L, 4L, 5L, 92L);
    }

    @Test
    void rejectsAStaleInstallationPointerWhenCompareAndSetFails() {
        WorkflowInstanceDO existing = instance(11L, 4L, 91L);
        when(definitions.requirePublishedDefinitionVersion(7L, "builder-flow")).thenReturn(92L);
        when(definitions.definition(7L, 92L)).thenReturn(definition());
        when(instances.selectOne(any())).thenReturn(existing);
        when(installations.insert(any())).thenReturn(1);
        when(installations.activate(7L, 11L, 4L, 5L, 92L)).thenReturn(0);

        ApiException error = assertThrows(ApiException.class, () -> service.installOrUpgrade(7L,
                "builder-flow", Map.of(), "", WorkflowInstallationBindings.empty()));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
    }

    @Test
    void stopsBeforePersistingWhenRequiredBindingsAreRejected() {
        when(definitions.requirePublishedDefinitionVersion(7L, "builder-flow")).thenReturn(91L);
        when(definitions.definition(7L, 91L)).thenReturn(definition());
        when(instances.selectOne(any())).thenReturn(null);
        org.mockito.Mockito.doThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "缺少必需能力槽"))
                .when(validator).validateForExecution(eq(7L), anyLong(), any(), any());

        ApiException error = assertThrows(ApiException.class, () -> service.installOrUpgrade(7L,
                "builder-flow", Map.of(), "", WorkflowInstallationBindings.empty()));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
        verify(instances, never()).insert(any(WorkflowInstanceDO.class));
        verify(installations, never()).insert(any());
    }

    private static WorkflowInstanceDO instance(long id, long revision, long definitionVersionId) {
        WorkflowInstanceDO instance = new WorkflowInstanceDO();
        instance.setId(id);
        instance.setTenantId(7L);
        instance.setInstanceId("builder-flow_7");
        instance.setInstallationRevision(revision);
        instance.setDefinitionVersionId(definitionVersionId);
        return instance;
    }

    private static Map<String, Object> definition() {
        return Map.of("nodes", List.of(), "meta", Map.of("config_schema", List.of(Map.of(
                "key", "locale", "type", "string", "default", "en-US"))));
    }
}
