package com.skillforge.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityProducerPublicationBridge;
import com.skillforge.core.capability.CapabilityProducerPublicationRequest;
import com.skillforge.core.capability.CapabilityProducerPublicationResult;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.workflow.dto.response.WorkflowPublishResponse;
import com.skillforge.core.workflow.mapper.WorkflowContainerMapper;
import com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper;
import com.skillforge.core.workflow.mapper.WorkflowDraftRevisionMapper;
import com.skillforge.core.workflow.model.WorkflowContainerDO;
import com.skillforge.core.workflow.model.WorkflowDefinitionVersionDO;
import com.skillforge.core.workflow.model.WorkflowDraftRevisionDO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Workflow producer bridge mapping, content addressing and source-draft idempotency. */
class WorkflowCapabilityProducerTest {

    private final WorkflowContainerMapper containers = mock(WorkflowContainerMapper.class);
    private final WorkflowDraftRevisionMapper drafts = mock(WorkflowDraftRevisionMapper.class);
    private final WorkflowDefinitionVersionMapper versions = mock(WorkflowDefinitionVersionMapper.class);
    private final CapabilityProducerPublicationBridge bridge =
            mock(CapabilityProducerPublicationBridge.class);
    private final WorkflowDraftLifecycleService service = new WorkflowDraftLifecycleService(
            containers, drafts, versions, new WorkflowDraftValidator(), bridge);

    @Test
    void publishesOnlyThroughBridgeWithExplicitDescriptorAndCanonicalHash() {
        Map<String, Object> definition = definition();
        WorkflowContainerDO container = container();
        WorkflowDraftRevisionDO draft = draft(definition);
        when(containers.lockContainer(7L, "requests")).thenReturn(container);
        when(drafts.selectOne(any())).thenReturn(draft);
        when(versions.selectOne(any())).thenReturn(null);
        when(versions.selectCount(any())).thenReturn(0L);
        when(bridge.publish(any())).thenReturn(publication(901L));
        when(containers.activateDefinitionVersion(eq(42L), eq(7L), eq(1L), anyLong())).thenReturn(1);

        WorkflowPublishResponse response = service.publish(7L, "requests", 1L);

        ArgumentCaptor<CapabilityProducerPublicationRequest> request =
                ArgumentCaptor.forClass(CapabilityProducerPublicationRequest.class);
        verify(bridge).publish(request.capture());
        CapabilityProducerPublicationRequest captured = request.getValue();
        assertEquals(CapabilityCatalogScope.tenant(7L), captured.scope());
        assertEquals(CapabilityType.WORKFLOW, captured.capabilityType());
        assertEquals("workflow:requests", captured.capabilityKey());
        assertEquals("definition:1", captured.versionKey());
        assertEquals(WorkflowDefinitionCanonicalizer.sha256(definition), captured.contentHash());
        assertEquals(Map.of(
                "model_invocable", true,
                "user_invocable", false,
                "input_schema", Map.of("type", "object"),
                "output_schema", Map.of("type", "object"),
                "supports_hitl", true,
                "export_fields", List.of("request_id"),
                "timeout_ms", 1_800,
                "concurrency_safe", false), captured.descriptor());

        ArgumentCaptor<WorkflowDefinitionVersionDO> persisted =
                ArgumentCaptor.forClass(WorkflowDefinitionVersionDO.class);
        verify(versions).insert(persisted.capture());
        assertEquals(901L, persisted.getValue().getCapabilityVersionId());
        assertEquals(901L, response.capabilityVersionId());
    }

    @Test
    void publishedDraftReplayReturnsTheOriginalCapabilityAssociationWithoutBridgeCall() {
        WorkflowContainerDO container = container();
        container.setStatus("published");
        container.setActiveDefinitionVersionId(700L);
        WorkflowDraftRevisionDO draft = draft(definition());
        WorkflowDefinitionVersionDO existing = new WorkflowDefinitionVersionDO();
        existing.setId(700L);
        existing.setDefinitionVersion(1L);
        existing.setCapabilityVersionId(901L);
        when(containers.lockContainer(7L, "requests")).thenReturn(container);
        when(drafts.selectOne(any())).thenReturn(draft);
        when(versions.selectOne(any())).thenReturn(existing);

        WorkflowPublishResponse response = service.publish(7L, "requests", 1L);

        assertEquals(700L, response.definitionVersionId());
        assertEquals(901L, response.capabilityVersionId());
        verify(bridge, never()).publish(any());
        verify(versions, never()).insert(any(WorkflowDefinitionVersionDO.class));
    }

    @Test
    void bridgeFailureStopsDefinitionVersionWriteAndActivation() {
        when(containers.lockContainer(7L, "requests")).thenReturn(container());
        when(drafts.selectOne(any())).thenReturn(draft(definition()));
        when(versions.selectOne(any())).thenReturn(null);
        when(bridge.publish(any())).thenThrow(new IllegalStateException("catalog unavailable"));

        assertThrows(IllegalStateException.class, () -> service.publish(7L, "requests", 1L));

        verify(versions, never()).insert(any(WorkflowDefinitionVersionDO.class));
        verify(containers, never()).activateDefinitionVersion(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void validatorReportsEveryGovernanceFieldAndUnknownFieldsWithoutInference() {
        assertTrue(new WorkflowDraftValidator().validate(definition(false)).isEmpty(),
                "supports_hitl must remain the explicit governance value even with a human_input node");
        Map<String, Object> definition = definition();
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("model_invocable", "true");
        capability.put("user_invocable", false);
        capability.put("input_schema", List.of());
        capability.put("output_schema", Map.of());
        capability.put("supports_hitl", 1);
        capability.put("export_fields", List.of("ok", 7));
        capability.put("timeout_ms", 1_800.5d);
        capability.put("concurrency_safe", true);
        capability.put("derived_from_nodes", true);
        definition.put("meta", Map.of("capability", capability));

        var problems = new WorkflowDraftValidator().validate(definition);

        assertTrue(problems.stream().anyMatch(problem ->
                problem.code().equals("WF_CAPABILITY_GOVERNANCE_UNKNOWN")
                        && problem.fieldPath().equals("meta.capability.derived_from_nodes")));
        assertEquals(5, problems.stream()
                .filter(problem -> problem.code().equals("WF_CAPABILITY_GOVERNANCE_TYPE"))
                .count());

        capability.put("export_fields", List.of("ok", " "));
        assertTrue(new WorkflowDraftValidator().validate(definition).stream().anyMatch(problem ->
                problem.code().equals("WF_CAPABILITY_GOVERNANCE_TYPE")
                        && problem.fieldPath().equals("meta.capability.export_fields")));

        definition.put("meta", Map.of("capability", Map.of()));
        assertEquals(8, new WorkflowDraftValidator().validate(definition).stream()
                .filter(problem -> problem.code().equals("WF_CAPABILITY_GOVERNANCE_REQUIRED"))
                .count());
    }

    private static WorkflowContainerDO container() {
        WorkflowContainerDO value = new WorkflowContainerDO();
        value.setId(42L);
        value.setTenantId(7L);
        value.setWorkflowId("requests");
        value.setStatus("draft");
        value.setCurrentDraftRevision(1L);
        return value;
    }

    private static WorkflowDraftRevisionDO draft(Map<String, Object> definition) {
        WorkflowDraftRevisionDO value = new WorkflowDraftRevisionDO();
        value.setId(99L);
        value.setContainerId(42L);
        value.setTenantId(7L);
        value.setRevision(1L);
        value.setDefinition(definition);
        return value;
    }

    private static CapabilityProducerPublicationResult publication(long id) {
        CapabilityVersionView version = new CapabilityVersionView(id, 902L, 7L,
                CapabilityType.WORKFLOW, "definition:1", "Requests", "", "TENANT",
                Map.of(), "hash", "PUBLISHED", null, null);
        return new CapabilityProducerPublicationResult(null, version, false);
    }

    private static Map<String, Object> definition() {
        return definition(true);
    }

    private static Map<String, Object> definition(boolean supportsHitl) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("edges", List.of(Map.of("from", "start", "to", "wait")));
        definition.put("entry", "start");
        definition.put("nodes", List.of(
                Map.of("id", "start", "type", "start"),
                Map.of("id", "wait", "type", "human_input", "prompt", "Confirm")));
        definition.put("meta", Map.of(
                "name", "requests",
                "display_name", "申请",
                "description", "申请工作流",
                "capability", Map.of(
                        "model_invocable", true,
                        "user_invocable", false,
                        "input_schema", Map.of("type", "object"),
                        "output_schema", Map.of("type", "object"),
                        "supports_hitl", supportsHitl,
                        "export_fields", List.of("request_id"),
                        "timeout_ms", 1_800,
                        "concurrency_safe", false)));
        return definition;
    }
}
