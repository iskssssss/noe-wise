package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.dto.response.BuilderWorkflowInstanceResponse;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper.BuilderRead;
import com.skillforge.core.workflow.mapper.WorkflowInstallationMapper.InstallationIdentity;
import com.skillforge.core.workflow.mapper.WorkflowContainerMapper;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.model.WorkflowInstanceDO;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 管理读链以当前不可变安装修订为配置和提示词的唯一权威。 */
class WorkflowInstanceServiceTest {

    private final WorkflowInstallationMapper installations = mock(WorkflowInstallationMapper.class);
    private final WorkflowContainerMapper containers = mock(WorkflowContainerMapper.class);
    private final WorkflowDefinitionVersionMapper versions = mock(WorkflowDefinitionVersionMapper.class);
    private final WorkflowInstallationDefinitionService definitions = new WorkflowInstallationDefinitionService(
            containers, versions);
    private final com.skillforge.core.workflow.mapper.WorkflowInstanceMapper instanceMapper = mock(com.skillforge.core.workflow.mapper.WorkflowInstanceMapper.class);
    private final WorkflowInstanceService service = new WorkflowInstanceService(instanceMapper, installations, definitions);

    @Test
    void readsConfigAndPromptFromTheImmutableInstallationRevision() {
        WorkflowInstanceDO instance = instance(11L, 3L, 91L);
        BuilderRead revision = revision(11L, 3L, 91L,
                Map.of("locale", "zh-CN"), "immutable prompt");
        when(installations.findBuilderReads(7L, List.of(new InstallationIdentity(11L, 3L))))
                .thenReturn(List.of(revision));

        BuilderWorkflowInstanceResponse response = service.builderInstance(7L, instance);

        assertEquals(3L, response.installationRevision());
        assertEquals(91L, response.definitionVersionId());
        assertEquals(501L, response.capabilityVersionId());
        assertEquals(Map.of("locale", "zh-CN"), response.config());
        assertEquals("immutable prompt", response.customPrompt());
        verify(installations, never()).find(7L, 11L, 3L);
        verifyNoInteractions(containers, versions);
    }

    @Test
    void rejectsMissingOrMismatchedImmutableRevision() {
        WorkflowInstanceDO instance = instance(11L, 3L, 91L);
        when(installations.findBuilderReads(eq(7L), anyList())).thenReturn(List.of());
        assertInstallationError(() -> service.builderInstance(7L, instance), 11L);
        when(installations.findBuilderReads(eq(7L), anyList()))
                .thenReturn(List.of(revision(11L, 3L, 92L, Map.of(), "")));
        assertInstallationError(() -> service.builderInstance(7L, instance), 11L);
    }

    @Test
    void keepsListOrderAndReadsBoundedBatchesFromCapturedInstallationIdentities() {
        List<WorkflowInstanceDO> instances = new ArrayList<>();
        for (int index = 0; index < 257; index++) {
            instances.add(instance(index + 11L, index + 1L, 91L));
        }
        doReturn(instances).when(instanceMapper).selectList(any(LambdaQueryWrapper.class));
        when(installations.findBuilderReads(eq(7L), anyList())).thenAnswer(invocation -> {
            List<InstallationIdentity> identities = invocation.getArgument(1);
            List<BuilderRead> reads = new ArrayList<>();
            for (var identity : identities.reversed()) {
                reads.add(revision(identity.instanceId(), identity.revision(), 91L, Map.of(), ""));
            }
            return reads;
        });

        var result = service.listBuilderInstances(7L);

        assertEquals(instances.stream().map(WorkflowInstanceDO::getId).toList(),
                result.stream().map(BuilderWorkflowInstanceResponse::id).toList());
        assertEquals(instances.stream().map(WorkflowInstanceDO::getInstallationRevision).toList(),
                result.stream().map(BuilderWorkflowInstanceResponse::installationRevision).toList());
        verify(installations, times(2)).findBuilderReads(eq(7L), anyList());
        verifyNoInteractions(containers, versions);
    }

    @Test
    void emptyListsAndInvalidSinglePointersDoNotQueryRevisions() {
        doReturn(List.of()).when(instanceMapper).selectList(any(LambdaQueryWrapper.class));
        assertEquals(List.of(), service.listBuilderInstances(7L));
        var invalid = instance(11L, 0L, 91L);
        assertInstallationError(() -> service.builderInstance(7L, invalid), 11L);
        invalid.setInstallationRevision(null);
        assertInstallationError(() -> service.builderInstance(7L, invalid), 11L);
        invalid.setInstallationRevision(1L);
        invalid.setId(null);
        assertInstallationError(() -> service.builderInstance(7L, invalid), null);
        verifyNoInteractions(installations);
    }

    @Test
    void keepsBindingErrorStatusesAndMessagesForCrossTenantNullAndMissingFacts() {
        var instance = instance(11L, 3L, 91L);
        when(installations.findBuilderReads(eq(7L), anyList()))
                .thenReturn(List.of(revision(11L, 3L, 91L, Map.of(), "")));
        instance.setTenantId(8L);
        assertError(() -> service.builderInstance(7L, instance), HttpStatus.FORBIDDEN,
                "Workflow 实例不属于当前租户: 11");
        instance.setTenantId(7L);
        instance.setDefinitionVersionId(null);
        when(installations.findBuilderReads(eq(7L), anyList())).thenReturn(List.of(
                new BuilderRead(7L, 11L, 3L, null, Map.of(), "", null, null, false, null, null)));
        assertError(() -> service.builderInstance(7L, instance), HttpStatus.CONFLICT,
                "Workflow 实例未绑定不可变 DefinitionVersion，请重新安装或升级: 11");
        instance.setDefinitionVersionId(91L);
        for (var unavailable : List.of(
                new BuilderRead(7L, 11L, 3L, 91L, Map.of(), "", null, null, false, null, null),
                new BuilderRead(7L, 11L, 3L, 91L, Map.of(), "", 91L, 501L, false, 42L, "flow"))) {
            when(installations.findBuilderReads(eq(7L), anyList())).thenReturn(List.of(unavailable));
            assertError(() -> service.builderInstance(7L, instance), HttpStatus.CONFLICT,
                    "Workflow 实例绑定的 DefinitionVersion 不可用，请重新安装或升级: 11");
        }
        when(installations.findBuilderReads(eq(7L), anyList())).thenReturn(List.of(
                new BuilderRead(7L, 11L, 3L, 91L, Map.of(), "", 91L, 501L, true, null, null)));
        assertError(() -> service.builderInstance(7L, instance), HttpStatus.CONFLICT,
                "Workflow 实例绑定的 DefinitionVersion 容器不可用: 11");
    }

    @Test
    void anEarlierBindingFailurePrecedesALaterInvalidInstallationPointer() {
        var first = instance(11L, 3L, 91L);
        var later = instance(12L, 0L, 92L);
        doReturn(List.of(first, later)).when(instanceMapper).selectList(any(LambdaQueryWrapper.class));
        when(installations.findBuilderReads(7L, List.of(new InstallationIdentity(11L, 3L))))
                .thenReturn(List.of(new BuilderRead(7L, 11L, 3L, 91L, Map.of(), "",
                        null, null, false, null, null)));
        assertError(() -> service.listBuilderInstances(7L), HttpStatus.CONFLICT,
                "Workflow 实例绑定的 DefinitionVersion 不可用，请重新安装或升级: 11");
    }

    @Test
    void anEarlierMissingInstallationPrecedesALaterBindingFailure() {
        doReturn(List.of(instance(11L, 3L, 91L), instance(12L, 4L, 92L)))
                .when(instanceMapper).selectList(any(LambdaQueryWrapper.class));
        when(installations.findBuilderReads(eq(7L), anyList())).thenReturn(List.of(
                new BuilderRead(7L, 12L, 4L, 92L, Map.of(), "", null, null, false, null, null)));
        assertInstallationError(() -> service.listBuilderInstances(7L), 11L);
    }

    @Test
    void keepsNullableMetadataAndEmptyConfigFallbackWithoutAddingPublicationChecks() {
        var instance = instance(11L, 3L, 91L);
        instance.setStatus("disabled");
        when(installations.findBuilderReads(eq(7L), anyList())).thenReturn(List.of(
                new BuilderRead(7L, 11L, 3L, 91L, null, null, 91L, null, true, 42L, "draft-container")));

        var response = service.builderInstance(7L, instance);

        assertEquals(Map.of(), response.config());
        assertEquals(null, response.customPrompt());
        assertEquals(null, response.capabilityVersionId());
        assertEquals("disabled", response.status());
        assertEquals("draft-container", response.workflowId());
    }

    private static void assertInstallationError(org.junit.jupiter.api.function.Executable action, Long id) {
        assertError(action, HttpStatus.CONFLICT,
                "Workflow 实例缺少当前不可变安装修订，请重新安装或升级: " + id);
    }

    private static void assertError(org.junit.jupiter.api.function.Executable action, HttpStatus status, String message) {
        ApiException error = assertThrows(ApiException.class, action);
        assertEquals(status, error.getStatus());
        assertEquals(message, error.getMessage());
    }

    private static WorkflowInstanceDO instance(long id, long installationRevision, long definitionVersionId) {
        WorkflowInstanceDO value = new WorkflowInstanceDO();
        value.setId(id);
        value.setTenantId(7L);
        value.setInstanceId("builder-flow_7");
        value.setInstallationRevision(installationRevision);
        value.setDefinitionVersionId(definitionVersionId);
        value.setStatus("published");
        return value;
    }

    private static BuilderRead revision(long instanceId, long installationRevision,
            long definitionVersionId, Map<String, Object> config, String prompt) {
        return new BuilderRead(7L, instanceId, installationRevision, definitionVersionId,
                config, prompt, definitionVersionId, 501L, true, 42L, "builder-flow");
    }
}
