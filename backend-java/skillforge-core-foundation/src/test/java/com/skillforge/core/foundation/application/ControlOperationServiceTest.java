package com.skillforge.core.foundation.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.foundation.contract.ControlOperationItem;
import com.skillforge.core.foundation.contract.ControlOperation;
import com.skillforge.core.foundation.contract.ControlOperationStatus;
import com.skillforge.core.foundation.persistence.ControlOperationMapper;
import com.skillforge.core.foundation.persistence.ControlOperationDO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ControlOperationServiceTest {
    @org.junit.jupiter.api.BeforeEach void authenticate() {
        com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity(
                "fixture-sp","7","integration-1",1,java.util.Set.of("resources.write")));
    }
    @org.junit.jupiter.api.AfterEach void clearIdentity() { com.skillforge.common.context.ServicePrincipalContext.clear(); }


    @Test
    void acceptsAnOperationAndPersistsOnlySanitizedItemReferences() {
        ControlOperationMapper mapper = mock(ControlOperationMapper.class);
        ControlOperationService service = new ControlOperationService(mapper, mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insertAccepted(any(ControlOperationDO.class))).thenReturn(1);
        ControlOperationItem item = new ControlOperationItem(
                "item-1", "workflow-1", ControlOperationStatus.SUCCEEDED, "revision-1", null, false);

        ControlOperationService.Acceptance accepted = service.accept(
                "7", "integration-1", "op-1", null, "publish", "idem-1", "sha256:req", List.of(item));

        ArgumentCaptor<ControlOperationDO> captor = ArgumentCaptor.forClass(ControlOperationDO.class);
        verify(mapper).insertAccepted(captor.capture());
        assertEquals("external",captor.getValue().getOriginKind());
        assertEquals("service_principal:fixture-sp",captor.getValue().getActorRef());
        assertEquals("op-1", accepted.operation().operationId());
        assertEquals("op-1", accepted.operation().rootOperationId());
        assertEquals("accepted", captor.getValue().getStatus());
        assertEquals("revision-1", captor.getValue().getItems().getFirst().resultRef());
        assertEquals(false, accepted.operation().status().terminal());
    }

    @Test
    void sameFingerprintReplaysStoredOperationButDifferentFingerprintConflicts() {
        ControlOperationMapper mapper = mock(ControlOperationMapper.class);
        ControlOperationService service = new ControlOperationService(mapper, mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        ControlOperationDO existing = row();
        when(mapper.selectOne(any())).thenReturn(existing);
        ControlOperationItem item = new ControlOperationItem(
                "item-1", "workflow-1", ControlOperationStatus.SUCCEEDED, "revision-1", null, false);

        ControlOperationService.Acceptance replay = service.accept(
                "7", "integration-1", "ignored-operation", "root", "publish", "idem-1", "sha256:req", List.of(item));

        assertEquals(false, replay.created());
        assertEquals("op-1", replay.operation().operationId());
        assertEquals("revision-1", replay.operation().items().get(0).resultRef());
        verify(mapper, never()).insertAccepted(any(ControlOperationDO.class));
        assertThrows(IllegalArgumentException.class, () -> service.accept(
                "7", "integration-1", "ignored-operation", "root", "publish", "idem-1", "sha256:other", List.of(item)));
    }

    @Test
    void invalidInputFailsBeforePersistenceAndUnknownStoredStatusIsRejected() {
        ControlOperationMapper mapper = mock(ControlOperationMapper.class);
        ControlOperationService service = new ControlOperationService(mapper, mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        assertThrows(IllegalArgumentException.class, () -> service.accept(
                "", "integration-1", "op-1", null, "publish", "idem-1", "sha256:req", List.of()));
        assertThrows(IllegalArgumentException.class, () -> service.accept(
                "7", "integration-1", "op-1", null, "publish", "", "sha256:req", List.of()));
        ControlOperationDO invalid = row();
        invalid.setStatus("unknown");
        when(mapper.selectOne(any())).thenReturn(invalid);
        assertThrows(IllegalArgumentException.class, () -> service.accept(
                "7", "integration-1", "op-1", null, "publish", "idem-1", "sha256:req", List.of()));
    }

    @Test
    void transitionsWithExpectedStatusAndCompletesAtTerminalState() {
        ControlOperationMapper mapper = mock(ControlOperationMapper.class);
        ControlOperationService service = new ControlOperationService(mapper, mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        ControlOperationDO current = row();
        current.setItems(List.of(new ControlOperationItem("item-1", "workflow-1",
                ControlOperationStatus.ACCEPTED, null, null, false)));
        when(mapper.selectOne(any())).thenReturn(current);
        when(mapper.update(any(), any())).thenReturn(1);
        ControlOperationItem succeeded = new ControlOperationItem(
                "item-1", "workflow-1", ControlOperationStatus.SUCCEEDED, "revision-2", null, false);

        ControlOperation processing = service.transition("7", "integration-1", "op-1",
                ControlOperationStatus.ACCEPTED, ControlOperationStatus.PROCESSING, null);
        ControlOperation completed = service.transition("7", "integration-1", "op-1",
                ControlOperationStatus.PROCESSING, ControlOperationStatus.SUCCEEDED, List.of(succeeded));

        assertEquals(ControlOperationStatus.PROCESSING, processing.status());
        assertEquals(ControlOperationStatus.SUCCEEDED, completed.status());
        assertEquals("revision-2", completed.items().getFirst().resultRef());
        org.junit.jupiter.api.Assertions.assertNotNull(completed.completedAt());
        verify(mapper, org.mockito.Mockito.times(2)).update(any(), any());
    }

    @Test
    void rejectsIllegalAndStaleTransitionsBeforeUpdating() {
        ControlOperationMapper mapper = mock(ControlOperationMapper.class);
        ControlOperationService service = new ControlOperationService(mapper, mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        ControlOperationDO current = row();
        when(mapper.selectOne(any())).thenReturn(current);

        assertThrows(IllegalArgumentException.class, () -> service.transition("7", "integration-1", "op-1",
                ControlOperationStatus.ACCEPTED, ControlOperationStatus.SUCCEEDED, List.of()));
        assertThrows(IllegalStateException.class, () -> service.transition("7", "integration-1", "op-1",
                ControlOperationStatus.PROCESSING, ControlOperationStatus.SUCCEEDED, List.of()));
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void statusContractExposesOnlyDeclaredEdges() {
        assertEquals(true, ControlOperationStatus.ACCEPTED
                .canTransitionTo(ControlOperationStatus.PROCESSING));
        assertEquals(true, ControlOperationStatus.PROCESSING
                .canTransitionTo(ControlOperationStatus.PARTIALLY_SUCCEEDED));
        assertEquals(false, ControlOperationStatus.SUCCEEDED
                .canTransitionTo(ControlOperationStatus.PROCESSING));
    }

    @Test
    void listsAcceptedOperationsWithAClampedLimit() {
        ControlOperationMapper mapper = mock(ControlOperationMapper.class);
        ControlOperationService service = new ControlOperationService(mapper, mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class));
        when(mapper.selectList(any())).thenReturn(List.of(row()));

        List<ControlOperation> accepted = service.listAccepted("7", 0);

        assertEquals(1, accepted.size());
        assertEquals("op-1", accepted.getFirst().operationId());
        verify(mapper).selectList(any());
    }

    private static ControlOperationDO row() {
        ControlOperationDO row = new ControlOperationDO();
        row.setTenantId(7L);
        row.setOperationId("op-1");
        row.setRootOperationId("root-1");
        row.setIntegrationId("integration-1");
        row.setOperationType("publish");
        row.setStatus("accepted");
        row.setIdempotencyKey("idem-1");
        row.setRequestFingerprint("sha256:req");
        row.setItems(List.of(new ControlOperationItem("item-1", "workflow-1",
                ControlOperationStatus.SUCCEEDED, "revision-1", null, false)));
        row.setAcceptedAt(java.time.Instant.now());
        return row;
    }
}
