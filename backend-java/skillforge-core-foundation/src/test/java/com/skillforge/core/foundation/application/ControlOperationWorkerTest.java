package com.skillforge.core.foundation.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.foundation.contract.ControlOperation;
import com.skillforge.core.foundation.contract.ControlOperationItem;
import com.skillforge.core.foundation.contract.ControlOperationStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ControlOperationWorkerTest {
    private final ControlOperationExecutionScope scopes = mock(ControlOperationExecutionScope.class);
    private final ControlOperationExecutionScope.Scope scope = mock(ControlOperationExecutionScope.Scope.class);

    @org.junit.jupiter.api.BeforeEach
    void prepareScope() {
        when(scopes.open(any())).thenReturn(scope);
    }


    @Test
    void claimsExecutesAndPersistsTerminalItemResults() {
        ControlOperationService operations = mock(ControlOperationService.class);
        ControlOperationExecutor executor = mock(ControlOperationExecutor.class);
        ControlOperation accepted = operation(ControlOperationStatus.PROCESSING, false,
                new ControlOperationItem("item-1", "workflow-1", ControlOperationStatus.ACCEPTED, null, null, false));
        ControlOperation completed = operation(ControlOperationStatus.SUCCEEDED, true,
                new ControlOperationItem("item-1", "workflow-1", ControlOperationStatus.SUCCEEDED,
                        "revision-1", null, false));
        when(operations.transition("7", "integration-1", "op-1", ControlOperationStatus.ACCEPTED,
                ControlOperationStatus.PROCESSING, null)).thenReturn(accepted);
        when(executor.supports("integration-1", "publish")).thenReturn(true);
        when(executor.execute(accepted)).thenReturn(new ControlOperationExecutor.Execution(
                ControlOperationStatus.SUCCEEDED, completed.items()));
        when(operations.transition("7", "integration-1", "op-1", ControlOperationStatus.PROCESSING,
                ControlOperationStatus.SUCCEEDED, completed.items())).thenReturn(completed);

        ControlOperation result = new ControlOperationWorker(operations, List.of(executor), scopes)
                .processAccepted("7", "integration-1", "op-1");

        assertEquals(ControlOperationStatus.SUCCEEDED, result.status());
        assertEquals("revision-1", result.items().getFirst().resultRef());
        verify(executor).execute(accepted);
        verify(scopes).open(accepted);
        verify(scope).close();
    }

    @Test
    void missingExecutorFailsClosedWithStablePerItemCode() {
        ControlOperationService operations = mock(ControlOperationService.class);
        ControlOperation processing = operation(ControlOperationStatus.PROCESSING, false,
                new ControlOperationItem("item-1", "workflow-1", ControlOperationStatus.ACCEPTED, null, null, false));
        ControlOperation failed = operation(ControlOperationStatus.FAILED, true,
                new ControlOperationItem("item-1", "workflow-1", ControlOperationStatus.FAILED,
                        null, "NO_EXECUTOR", false));
        when(operations.transition("7", "integration-1", "op-1", ControlOperationStatus.ACCEPTED,
                ControlOperationStatus.PROCESSING, null)).thenReturn(processing);
        when(operations.transition("7", "integration-1", "op-1", ControlOperationStatus.PROCESSING,
                ControlOperationStatus.FAILED, failed.items())).thenReturn(failed);

        ControlOperation result = new ControlOperationWorker(operations, List.of(), scopes)
                .processAccepted("7", "integration-1", "op-1");

        assertEquals(ControlOperationStatus.FAILED, result.status());
        assertEquals("NO_EXECUTOR", result.items().getFirst().errorCode());
    }

    @Test
    void executorFailureBecomesStableFailedResult() {
        ControlOperationService operations = mock(ControlOperationService.class);
        ControlOperationExecutor executor = mock(ControlOperationExecutor.class);
        ControlOperation processing = operation(ControlOperationStatus.PROCESSING, false,
                new ControlOperationItem("item-1", "workflow-1", ControlOperationStatus.ACCEPTED, null, null, false));
        ControlOperation failed = operation(ControlOperationStatus.FAILED, true,
                new ControlOperationItem("item-1", "workflow-1", ControlOperationStatus.FAILED,
                        null, "EXECUTOR_ERROR", false));
        when(operations.transition("7", "integration-1", "op-1", ControlOperationStatus.ACCEPTED,
                ControlOperationStatus.PROCESSING, null)).thenReturn(processing);
        when(executor.supports("integration-1", "publish")).thenReturn(true);
        when(executor.execute(processing)).thenThrow(new RuntimeException("downstream unavailable"));
        when(operations.transition("7", "integration-1", "op-1", ControlOperationStatus.PROCESSING,
                ControlOperationStatus.FAILED, failed.items())).thenReturn(failed);

        ControlOperation result = new ControlOperationWorker(operations, List.of(executor), scopes)
                .processAccepted("7", "integration-1", "op-1");

        assertEquals(ControlOperationStatus.FAILED, result.status());
        assertEquals("EXECUTOR_ERROR", result.items().getFirst().errorCode());
        verify(scopes).open(processing);
        verify(scope).close();
    }

    @Test
    void multipleMatchingExecutorsFailClosedBeforeAnyExecution() {
        ControlOperationService operations = mock(ControlOperationService.class);
        ControlOperationExecutor first = mock(ControlOperationExecutor.class);
        ControlOperationExecutor second = mock(ControlOperationExecutor.class);
        ControlOperation processing = operation(ControlOperationStatus.PROCESSING, false,
                new ControlOperationItem("item-1", "workflow-1", ControlOperationStatus.ACCEPTED, null, null, false));
        ControlOperation failed = operation(ControlOperationStatus.FAILED, true,
                new ControlOperationItem("item-1", "workflow-1", ControlOperationStatus.FAILED,
                        null, "AMBIGUOUS_EXECUTOR", false));
        when(operations.transition("7", "integration-1", "op-1", ControlOperationStatus.ACCEPTED,
                ControlOperationStatus.PROCESSING, null)).thenReturn(processing);
        when(first.supports("integration-1", "publish")).thenReturn(true);
        when(second.supports("integration-1", "publish")).thenReturn(true);
        when(operations.transition("7", "integration-1", "op-1", ControlOperationStatus.PROCESSING,
                ControlOperationStatus.FAILED, failed.items())).thenReturn(failed);

        ControlOperation result = new ControlOperationWorker(operations, List.of(first, second), scopes)
                .processAccepted("7", "integration-1", "op-1");

        assertEquals("AMBIGUOUS_EXECUTOR", result.items().getFirst().errorCode());
        verify(first, never()).execute(any());
        verify(second, never()).execute(any());
    }

    @Test
    void concurrentClaimReturnsCurrentLedgerWithoutExecutingAgain() {
        ControlOperationService operations = mock(ControlOperationService.class);
        ControlOperation current = operation(ControlOperationStatus.PROCESSING, false,
                new ControlOperationItem("item-1", "workflow-1", ControlOperationStatus.ACCEPTED, null, null, false));
        when(operations.transition("7", "integration-1", "op-1", ControlOperationStatus.ACCEPTED,
                ControlOperationStatus.PROCESSING, null)).thenThrow(new IllegalStateException("status conflict"));
        when(operations.find("7", "integration-1", "op-1")).thenReturn(current);
        ControlOperationExecutor executor = mock(ControlOperationExecutor.class);

        ControlOperation result = new ControlOperationWorker(operations, List.of(executor), scopes)
                .processAccepted("7", "integration-1", "op-1");

        assertEquals(ControlOperationStatus.PROCESSING, result.status());
        verify(executor, never()).execute(any());
        verify(scopes, never()).open(any());
    }

    @Test
    void executorExecutionRejectsNonAdvancingStatuses() {
        assertThrows(IllegalArgumentException.class, () -> new ControlOperationExecutor.Execution(
                ControlOperationStatus.PROCESSING, List.of()));
    }

    private ControlOperation operation(ControlOperationStatus status, boolean completed,
                                       ControlOperationItem item) {
        return new ControlOperation("op-1", "op-1", "7", "integration-1", "publish", status,
                "idem-1", "sha256:req", List.of(item), Instant.now(), completed ? Instant.now() : null);
    }
}
