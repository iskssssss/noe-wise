package com.skillforge.sandbox.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.execution.ExecutionState;
import com.skillforge.execution.Language;
import com.skillforge.sandbox.config.SandboxProperties;
import com.skillforge.sandbox.execution.TestSandboxProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRepositoryTest {

    @TempDir
    Path temporary;

    @Test
    void persistsIdentityAndNeverReplacesExistingTask() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskRepository repository = new TaskRepository(mapper, properties);
        Instant now = Instant.now();
        TaskRecord first = new TaskRecord("same-task", "digest-a", Language.PYTHON, true, List.of(),
                now.plusSeconds(60), ExecutionState.QUEUED, "queued", null, null, "", "",
                false, false, null, null, List.of(), null, now, now, TestSandboxProperties.policy(),
                com.skillforge.execution.ExecutionProfile.STANDARD, null, properties.pythonImage(),
                "sha256:" + "1".repeat(64), null);
        TaskRecord different = new TaskRecord("same-task", "digest-b", Language.NODE, false, List.of(),
                now.plusSeconds(60), ExecutionState.QUEUED, "queued", null, null, "", "",
                false, false, null, null, List.of(), null, now, now, TestSandboxProperties.policy(),
                com.skillforge.execution.ExecutionProfile.STANDARD, null, properties.nodeImage(),
                "sha256:" + "2".repeat(64), null);

        assertTrue(repository.register(first).created());
        assertFalse(repository.register(different).created());
        TaskRepository reloaded = new TaskRepository(mapper, properties);
        assertEquals("digest-a", reloaded.find("same-task").orElseThrow().requestDigest());
        assertTrue(reloaded.find("same-task").orElseThrow().programTools());
        assertEquals(properties.pythonImage(), reloaded.find("same-task").orElseThrow().imageReference());
    }

    @Test
    void rebuildsWorkspaceCandidatesWithoutDroppingHistoricalIdentities() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskRepository repository = new TaskRepository(mapper, properties);
        Instant now = Instant.parse("2026-10-02T01:00:00Z");
        for (int i = 0; i < 128; i++) {
            TaskRecord task = task("historical-" + i, properties, now);
            repository.register(task);
            repository.update(task.taskId(), current -> current.withWorkspaceDeleted(now));
        }
        TaskRecord pending = task("pending", properties, now);
        repository.register(pending);
        repository.update("pending", current -> current.withState(ExecutionState.UNKNOWN,
                "unknown_quiescent", now.plusSeconds(1)));
        assertEquals(List.of("pending"), repository.findWorkspaceCandidates().stream()
                .map(TaskRecord::taskId).toList());
        assertEquals("unknown_quiescent", repository.findWorkspaceCandidates().getFirst().phase());
        var detached = repository.findWorkspaceCandidates();
        detached.clear();
        assertEquals(1, repository.findWorkspaceCandidates().size());

        TaskRepository reloaded = new TaskRepository(mapper, properties);
        assertEquals(repository.findWorkspaceCandidates(), reloaded.findWorkspaceCandidates());
        assertEquals(129, reloaded.findAll().size());
        assertFalse(reloaded.register(task("historical-0", properties, now)).created());
        assertEquals(1, reloaded.findWorkspaceCandidates().size());
        reloaded.update("pending", current -> current.withWorkspaceDeleted(now.plusSeconds(2)));
        assertTrue(reloaded.findWorkspaceCandidates().isEmpty());
        assertEquals(129, new TaskRepository(mapper, properties).findAll().size());
    }

    @Test
    void workspaceIdentitySurvivesTaskTransitionsAndRepositoryReload() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskRepository repository = new TaskRepository(mapper, properties);
        Instant now = Instant.now();
        var binding = new com.skillforge.execution.WorkspaceBinding("a".repeat(64), "7", "session", 1, "chat",
                new com.skillforge.execution.WorkspacePolicy(1, 300, 24, 10));
        var task = new TaskRecord("workspace-task", "digest", Language.SHELL, false, List.of(),
                now.plusSeconds(60), ExecutionState.QUEUED, "queued", null, null, "", "", false, false,
                null, null, List.of(), null, now, now, TestSandboxProperties.policy(),
                com.skillforge.execution.ExecutionProfile.WORKSPACE_SHELL_V1, null, properties.nodeImage(),
                "sha256:" + "2".repeat(64), null, binding);
        assertEquals(binding, task.withState(ExecutionState.RUNNING, "running", now).workspace());
        assertEquals(binding, task.withContainer("container", now).workspace());
        var terminal = task.finished(ExecutionState.COMPLETED, "completed", 0, "", "", false, false,
                null, List.of(), now);
        assertEquals(binding, terminal.workspace());
        assertEquals(binding, terminal.withWorkspaceDeleted(now).workspace());
        assertTrue(repository.register(terminal).created());
        assertEquals(binding, new TaskRepository(mapper, properties).find(task.taskId()).orElseThrow().workspace());
    }

    private TaskRecord task(String id, SandboxProperties properties, Instant now) {
        return new TaskRecord(id, "digest-" + id, Language.PYTHON, false, List.of(),
                now.plusSeconds(60), ExecutionState.COMPLETED, "completed", null, 0, "", "",
                false, false, null, null, List.of(), null, now, now, TestSandboxProperties.policy(),
                com.skillforge.execution.ExecutionProfile.STANDARD, null, properties.pythonImage(),
                "sha256:" + "1".repeat(64), null);
    }

}
