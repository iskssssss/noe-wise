package com.skillforge.core.capability.contract;

import com.skillforge.core.foundation.contract.ControlOperation;
import com.skillforge.core.foundation.contract.ControlOperationItem;
import com.skillforge.core.foundation.contract.ControlOperationStatus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenPlatformControlContractTest {

    @Test
    void freezesExternalMappingRevisionAndDeploymentOnlyAcceptsPublishedContent() {
        IntegrationResourceMapping mapping = new IntegrationResourceMapping(
                "tenant-1", "integration-1", ResourceType.WORKFLOW, "external-1", "workflow-1", 3);
        ResourceRevisionRef published = new ResourceRevisionRef(
                "workflow-1", ResourceType.WORKFLOW, "revision-3", 3,
                ResourceRevisionStatus.PUBLISHED, "sha256:abc");
        DeploymentRef deployment = new DeploymentRef("deployment-1", published, "production", 2);

        assertEquals("tenant-1:integration-1:workflow:external-1", mapping.stableKey());
        assertEquals(3, deployment.publishedRevision().revision());
        assertThrows(IllegalArgumentException.class, () -> new DeploymentRef("deployment-2",
                new ResourceRevisionRef("workflow-1", ResourceType.WORKFLOW, "revision-4", 4,
                        ResourceRevisionStatus.READY, "sha256:def"), "production", 1));
    }

    @Test
    void rejectsInvalidRevisionAndExternalMappingIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new IntegrationResourceMapping(
                "tenant-1", "integration-1", ResourceType.WORKFLOW, "external-1", "workflow-1", 0));
        assertThrows(IllegalArgumentException.class, () -> new ResourceRevisionRef(
                "workflow-1", ResourceType.WORKFLOW, "revision-1", 1,
                ResourceRevisionStatus.DRAFT, ""));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentRef(
                "deployment-1", null, "production", 1));
    }

    @Test
    void validatesControlOperationItemsAndTerminalLifecycle() {
        ControlOperationItem success = new ControlOperationItem(
                "item-1", "workflow-1", ControlOperationStatus.SUCCEEDED, "version-1", null, false);
        ControlOperation pending = new ControlOperation(
                "op-1", "root-op-1", "tenant-1", "integration-1", "publish",
                ControlOperationStatus.PROCESSING, "idem-1", "sha256:req", List.of(success), Instant.now(), null);
        ControlOperation completed = new ControlOperation(
                "op-1", "root-op-1", "tenant-1", "integration-1", "publish",
                ControlOperationStatus.SUCCEEDED, "idem-1", "sha256:req", List.of(success),
                pending.acceptedAt(), Instant.now());

        assertDoesNotThrow(() -> pending);
        assertEquals(ControlOperationStatus.SUCCEEDED, completed.status());
        assertThrows(IllegalArgumentException.class, () -> new ControlOperationItem(
                "item-2", "workflow-2", ControlOperationStatus.SUCCEEDED, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new ControlOperationItem(
                "item-3", "workflow-3", ControlOperationStatus.FAILED, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new ControlOperationItem(
                "item-4", "workflow-4", ControlOperationStatus.PROCESSING, null, null, true));
    }

    @Test
    void rejectsDuplicateItemsAndInconsistentTerminalTimestamp() {
        ControlOperationItem failed = new ControlOperationItem(
                "item-1", "workflow-1", ControlOperationStatus.FAILED, null, "timeout", true);
        assertThrows(IllegalArgumentException.class, () -> new ControlOperation(
                "op-1", "root-op-1", "tenant-1", "integration-1", "publish",
                ControlOperationStatus.FAILED, "idem-1", "sha256:req", List.of(failed, failed), Instant.now(), Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> new ControlOperation(
                "op-1", "root-op-1", "tenant-1", "integration-1", "publish",
                ControlOperationStatus.FAILED, "idem-1", "sha256:req", List.of(failed), Instant.now(), null));
        assertThrows(IllegalArgumentException.class, () -> new ControlOperation(
                "op-1", "root-op-1", "tenant-1", "integration-1", "publish",
                ControlOperationStatus.ACCEPTED, "idem-1", "sha256:req", List.of(), Instant.now(), Instant.now()));
    }
}
