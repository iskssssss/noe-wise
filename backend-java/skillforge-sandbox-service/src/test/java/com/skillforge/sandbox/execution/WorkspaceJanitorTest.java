package com.skillforge.sandbox.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.execution.ExecutionState;
import com.skillforge.execution.Language;
import com.skillforge.sandbox.config.SandboxProperties;
import com.skillforge.sandbox.persistence.TaskRecord;
import com.skillforge.sandbox.persistence.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceJanitorTest {

    @TempDir
    Path temporary;

    @Test
    void deletesOnlyQuiescentWorkspaceAndKeepsPersistentIdentity() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskRepository repository = new TaskRepository(mapper, properties);
        TaskWorkspace workspace = new TaskWorkspace(properties);
        Instant now = Instant.parse("2026-09-21T10:00:00Z");
        TaskRecord completed = record("completed", ExecutionState.COMPLETED, "completed", now.minusSeconds(90000));
        TaskRecord unknown = record("unknown", ExecutionState.UNKNOWN, "termination_unconfirmed",
                now.minusSeconds(90000));
        repository.register(completed);
        repository.register(unknown);
        Files.createDirectories(temporary.resolve("workspaces/completed/output"));
        Files.createDirectories(temporary.resolve("workspaces/unknown/output"));
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        WorkspaceJanitor janitor = new WorkspaceJanitor(repository, workspace,
                Clock.fixed(now, ZoneOffset.UTC), scheduler);

        try {
            janitor.cleanupNow();

            assertFalse(workspace.exists("completed"));
            assertTrue(workspace.exists("unknown"));
            assertNotNull(repository.find("completed").orElseThrow().workspaceDeletedAt());
            assertFalse(repository.register(completed).created());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void retriesFailedDeletionAndKeepsUnknownUnconfirmedCandidates() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskRepository repository = new TaskRepository(mapper, properties);
        TaskWorkspace workspace = mock(TaskWorkspace.class);
        Instant now = Instant.parse("2026-10-02T01:00:00Z");
        repository.register(record("retry", ExecutionState.COMPLETED, "completed", now.minusSeconds(90000)));
        repository.register(record("unknown", ExecutionState.UNKNOWN, "termination_unconfirmed", now.minusSeconds(90000)));
        repository.register(record("quiescent", ExecutionState.UNKNOWN, "unknown_quiescent", now.minusSeconds(90000)));
        doThrow(new IllegalStateException("deletion_failed")).doNothing().when(workspace).delete("retry");
        doNothing().when(workspace).delete("quiescent");
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        WorkspaceJanitor janitor = new WorkspaceJanitor(repository, workspace,
                Clock.fixed(now, ZoneOffset.UTC), scheduler);
        try {
            assertThrows(IllegalStateException.class, janitor::cleanupNow);
            assertEquals(3, repository.findWorkspaceCandidates().size());
            janitor.cleanupNow();
            assertEquals(List.of("unknown"), repository.findWorkspaceCandidates().stream()
                    .map(TaskRecord::taskId).toList());
            assertEquals(3, repository.findAll().size());
            assertFalse(repository.register(record("retry", ExecutionState.COMPLETED,
                    "completed", now.minusSeconds(90000))).created());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private TaskRecord record(String taskId, ExecutionState state, String phase, Instant updatedAt) {
        return new TaskRecord(taskId, "digest-" + taskId, Language.PYTHON, false, List.of(),
                updatedAt.plusSeconds(120), state, phase, null, 0, "", "", false, false,
                null, null, List.of(), null, updatedAt.minusSeconds(60), updatedAt,
                TestSandboxProperties.policy(), com.skillforge.execution.ExecutionProfile.STANDARD, null,
                "skillforge-sandbox-python:test", "sha256:" + "1".repeat(64), null);
    }
}
