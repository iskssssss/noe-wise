package com.skillforge.core.workflow.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.EvidenceRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.CapabilitySnapshotRef;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.api.port.WorkflowPortal.WorkflowHandle;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkflowNodeInputsTest {
    private final SessionRef session = new SessionRef(new TenantId("tenant"), "session");
    private final CapabilitySnapshotRef snapshot = new CapabilitySnapshotRef(session, new InputId("input"), "snapshot", 1);
    private final FrozenWorkflowTarget target = new FrozenWorkflowTarget(new WorkflowHandle("workflow:mount"), "instance", 1,
            new CapabilityRef(CapabilityType.WORKFLOW, "capability", "version"), Duration.ofSeconds(30), 1L);
    private final TypedPayload parameters = new TypedPayload("parameters.v1", "{\"task\":\"业务🚀\"}");

    @Test
    void keepsExactFrozenSdkReferencesAndPromptInImmutableOrder() {
        EvidenceRef first = evidence(session, "first");
        EvidenceRef second = evidence(session, "second");
        var original = new ArrayList<>(List.of(first, second));
        var inputs = new SdkWorkflowPortal.NodeInputs(target, snapshot, parameters, original, Optional.of("冻结提示🚀"),binding(Optional.of("冻结提示🚀")));
        original.clear();
        assertEquals(target, inputs.target());
        assertEquals(snapshot, inputs.snapshot());
        assertEquals(parameters, inputs.parameters());
        assertEquals(List.of(first, second), inputs.evidenceRefs());
        assertEquals(Optional.of("冻结提示🚀"), inputs.customPrompt());
        assertThrows(UnsupportedOperationException.class, () -> inputs.evidenceRefs().clear());
    }

    @Test
    void rejectsCrossSessionAndDuplicateEvidenceWithoutLegacyConversion() {
        EvidenceRef other = evidence(new SessionRef(session.tenantId(), "other-session"), "other");
        assertThrows(IllegalArgumentException.class, () -> new SdkWorkflowPortal.NodeInputs(target, snapshot, parameters,
                List.of(other), Optional.empty(),binding(Optional.empty())));
        EvidenceRef own = evidence(session, "own");
        assertThrows(IllegalArgumentException.class, () -> new SdkWorkflowPortal.NodeInputs(target, snapshot, parameters,
                List.of(own, own), Optional.empty(),binding(Optional.empty())));
    }

    @Test
    void parametersUseOriginalContextPrefixAndCannotOverwritePrivateStateKeys() {
        BigInteger large = new BigInteger("123456789012345678901234567890");
        Map<String,Object> projected = WorkflowExecutionWorker.parameterState(Map.of(
                "task", "业务🚀", "amount", large, "user_input", "target input", "__workflow_action_visits", "untrusted"));
        assertEquals("业务🚀", projected.get("ctx_task"));
        assertEquals(large, projected.get("ctx_amount"));
        assertEquals("target input", projected.get("ctx_user_input"));
        assertEquals("untrusted", projected.get("ctx___workflow_action_visits"));
        assertFalse(projected.containsKey("user_input"));
        assertFalse(projected.containsKey("__workflow_action_visits"));
    }

    private com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings.Binding binding(Optional<String> prompt) {
        return new com.skillforge.core.workflow.integration.runtime.workflow.JdbcWorkflowBindings.Binding(target,"workflow","{}","{}",prompt,"{}","{}","{}");
    }
    private static EvidenceRef evidence(SessionRef session, String id) {
        return new EvidenceRef(session, id, List.of("one", "two"), 7, new EventId("event-" + id));
    }
}
