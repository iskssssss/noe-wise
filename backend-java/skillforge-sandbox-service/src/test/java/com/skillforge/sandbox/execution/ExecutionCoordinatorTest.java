package com.skillforge.sandbox.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.execution.ExecutionState;
import com.skillforge.execution.Language;
import com.skillforge.sandbox.api.SandboxConflictException;
import com.skillforge.sandbox.config.SandboxProperties;
import com.skillforge.sandbox.persistence.TaskRecord;
import com.skillforge.sandbox.persistence.TaskRepository;
import com.skillforge.sandbox.workspace.SessionWorkspaces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

class ExecutionCoordinatorTest {

    @TempDir
    Path temporary;

    @Test
    void workspaceExecutionAcquiresLeaseBeforeWritingAndReleasesAfterStoppedResult() {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var properties = TestSandboxProperties.create(temporary);
        var repository = new TaskRepository(mapper, properties);
        var workspace = new TaskWorkspace(properties);
        var runner = mock(DockerExecutionRunner.class);
        var projects = mock(SessionWorkspaces.class);
        var lease = mock(SessionWorkspaces.Lease.class);
        when(projects.acquire(any(), any(), any())).thenReturn(lease);
        when(runner.resolveEnvironment(any(), any(), any())).thenReturn(
                new DockerExecutionRunner.ExecutionEnvironment(properties.nodeImage(), "sha256:" + "a".repeat(64)));
        when(runner.execute(any(), any(), any(), any(), any())).thenReturn(
                new DockerExecutionRunner.RunnerResult(ExecutionState.COMPLETED, "completed", 0,
                        "", "", false, false, null, null));
        var pool = mock(ExecutorService.class);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(pool).execute(any());
        var coordinator = new ExecutionCoordinator(new RequestPolicy(mapper), repository, workspace, runner,
                new ArtifactCollector(workspace), new ProgramToolExchange(mapper, workspace), pool,
                Clock.systemUTC(), projects);
        var binding = new com.skillforge.execution.WorkspaceBinding("a".repeat(64), "7", "session", 1, "chat",
                new com.skillforge.execution.WorkspacePolicy(1, 300, 24, 10));
        var request = new com.skillforge.execution.ExecuteRequest("workspace-code", Language.NODE, "console.log(1)",
                List.of(), List.of(), null, false, Instant.now().plusSeconds(60), TestSandboxProperties.policy(), List.of(), com.skillforge.execution.ExecutionProfile.STANDARD, null, null, binding);
        coordinator.submit(request);
        assertEquals(ExecutionState.COMPLETED, coordinator.get(request.taskId()).state());
        assertEquals(binding, repository.find(request.taskId()).orElseThrow().workspace());
        var order = org.mockito.Mockito.inOrder(projects, lease, runner);
        order.verify(projects).acquire(any(), any(), any());
        order.verify(lease).beginWrite("task:" + request.taskId());
        order.verify(runner).execute(any(), any(), any(), any(), any());
        order.verify(lease).terminationConfirmed(true);
        order.verify(lease).close();
    }

    @Test
    void onlyUndispatchedPolicyRejectionIsNotSubmittedAndExistingTaskRemainsAuthoritative() {
        ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var repository=new TaskRepository(mapper,TestSandboxProperties.create(temporary));
        var workspace=mock(TaskWorkspace.class);
        var runner=mock(DockerExecutionRunner.class);
        var pool=mock(ExecutorService.class);
        var policy=new RequestPolicy(mapper);
        var coordinator=new ExecutionCoordinator(policy,repository,workspace,runner,mock(ArtifactCollector.class),
                mock(ProgramToolExchange.class),pool,Clock.systemUTC(),mock(SessionWorkspaces.class));
        when(runner.resolveEnvironment(any(),any(),any())).thenReturn(
                new DockerExecutionRunner.ExecutionEnvironment("python", "sha256:"+"a".repeat(64)));
        var valid=new com.skillforge.execution.ExecuteRequest("admission-test",Language.PYTHON,"pass",List.of(),
                List.of(),null,false,Instant.now().plusSeconds(60),TestSandboxProperties.policy(),List.of(),
                com.skillforge.execution.ExecutionProfile.STANDARD,null,null);
        var invalid=new com.skillforge.execution.ExecuteRequest(valid.taskId(),Language.PYTHON,"pass",List.of(),
                List.of(),"scripts/missing.py",false,valid.deadlineAt(),TestSandboxProperties.policy(),List.of(),
                com.skillforge.execution.ExecutionProfile.STANDARD,null,null);
        var rejection=assertThrows(com.skillforge.sandbox.api.ExecutionAdmissionException.class,()->coordinator.submit(invalid));
        assertEquals(com.skillforge.execution.ExecutionAdmissionRejection.Submission.NOT_SUBMITTED,rejection.rejection().submission());
        assertTrue(repository.findAll().isEmpty());
        verifyNoInteractions(workspace,pool);
        // 登记后的准备失败保留真实失败任务；后续坏请求不可宣称此 taskId 从未提交。
        doThrow(new com.skillforge.sandbox.api.SandboxRequestException("BAD_ARCHIVE","test"))
                .when(workspace).prepare(any(RequestPolicy.ValidatedRequest.class));
        assertEquals(ExecutionState.FAILED,coordinator.submit(valid).response().state());
        var registered=repository.find(valid.taskId()).orElseThrow();
        assertThrows(SandboxConflictException.class,()->coordinator.submit(invalid));
        assertEquals(registered,repository.find(valid.taskId()).orElseThrow());
        assertEquals(ExecutionState.FAILED,coordinator.submit(valid).response().state());
        verifyNoInteractions(pool);
    }

    @Test
    void expiredUnregisteredRequestHasTypedAdmissionRejection() {
        ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var repository=new TaskRepository(mapper,TestSandboxProperties.create(temporary));
        var workspace=mock(TaskWorkspace.class);
        var runner=mock(DockerExecutionRunner.class);
        var pool=mock(ExecutorService.class);
        when(runner.resolveEnvironment(any(),any(),any())).thenReturn(
                new DockerExecutionRunner.ExecutionEnvironment("python","sha256:"+"a".repeat(64)));
        var coordinator=new ExecutionCoordinator(new RequestPolicy(mapper),repository,workspace,runner,
                mock(ArtifactCollector.class),mock(ProgramToolExchange.class),pool,Clock.systemUTC(),mock(SessionWorkspaces.class));
        var expired=new com.skillforge.execution.ExecuteRequest("expired",Language.PYTHON,"pass",List.of(),
                List.of(),null,false,Instant.now().minusSeconds(1),TestSandboxProperties.policy(),List.of(),
                com.skillforge.execution.ExecutionProfile.STANDARD,null,null);
        var failure=assertThrows(com.skillforge.sandbox.api.ExecutionAdmissionException.class,()->coordinator.submit(expired));
        assertEquals(com.skillforge.execution.ExecutionAdmissionRejection.Code.EXECUTION_DEADLINE_EXPIRED,failure.rejection().code());
        assertTrue(repository.findAll().isEmpty());
        verifyNoInteractions(workspace,pool);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preparationFailurePreservesConcurrentCancellation(boolean cleanupConfirmed) throws Exception {
        assertSubmissionFailurePreservesCancellation(cleanupConfirmed, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void executorRejectionPreservesConcurrentCancellation(boolean cleanupConfirmed) throws Exception {
        assertSubmissionFailurePreservesCancellation(cleanupConfirmed, false);
    }

    private void assertSubmissionFailurePreservesCancellation(boolean cleanupConfirmed,
                                                              boolean preparationFails) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskRepository repository = new TaskRepository(mapper, properties);
        TaskWorkspace workspace = mock(TaskWorkspace.class);
        DockerExecutionRunner runner = mock(DockerExecutionRunner.class);
        ArtifactCollector artifacts = mock(ArtifactCollector.class);
        ExecutorService executionPool = mock(ExecutorService.class);
        var submitPool = Executors.newSingleThreadExecutor();
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch finishPreparation = new CountDownLatch(1);
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        Clock clock = Clock.fixed(now, java.time.ZoneOffset.UTC);
        ExecutionCoordinator coordinator = new ExecutionCoordinator(new RequestPolicy(mapper, clock), repository,
                workspace, runner, artifacts, mock(ProgramToolExchange.class), executionPool, clock, mock(SessionWorkspaces.class));
        when(runner.resolveEnvironment(any(), any(), any())).thenReturn(
                new DockerExecutionRunner.ExecutionEnvironment(properties.nodeImage(), "sha256:" + "a".repeat(64)));
        when(runner.cancel(any())).thenReturn(cleanupConfirmed);
        doAnswer(call -> {
            preparing.countDown();
            assertTrue(finishPreparation.await(5, TimeUnit.SECONDS), "取消后必须释放准备阶段");
            if (preparationFails) throw new IllegalStateException("archive preparation failed");
            return null;
        }).when(workspace).prepare(any(RequestPolicy.ValidatedRequest.class));
        doThrow(new RejectedExecutionException("executor shutting down")).when(executionPool).execute(any());
        var request = new com.skillforge.execution.ExecuteRequest("cancel-before-dispatch", Language.NODE,
                "", List.of(), List.of(), null, false, now.plusSeconds(60), TestSandboxProperties.policy(), List.of(), com.skillforge.execution.ExecutionProfile.STANDARD, null, null);
        try {
            var submission = submitPool.submit(() -> coordinator.submit(request));
            assertTrue(preparing.await(5, TimeUnit.SECONDS), "提交必须先登记任务并进入准备阶段");
            var cancellation = coordinator.cancel(request.taskId());
            ExecutionState terminal = cleanupConfirmed ? ExecutionState.CANCELLED : ExecutionState.UNKNOWN;
            assertEquals(terminal, cancellation.state());
            TaskRecord cancelled = repository.find(request.taskId()).orElseThrow();
            finishPreparation.countDown();
            assertEquals(cancellation, submission.get(5, TimeUnit.SECONDS).response());
            assertEquals(cancelled, repository.find(request.taskId()).orElseThrow());
            assertEquals(cancelled, new TaskRepository(mapper, properties).find(request.taskId()).orElseThrow());
            if (preparationFails) verifyNoInteractions(executionPool);
            else verify(executionPool).execute(any());
            verify(runner, never()).execute(any(), any(), any(), any(), any());
            verifyNoInteractions(artifacts);
        } finally {
            finishPreparation.countDown();
            submitPool.shutdownNow();
            assertTrue(submitPool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void unknownCancellationRetriesCleanupWithoutClaimingCancelledOrCollecting() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskRepository repository = new TaskRepository(mapper, properties);
        TaskWorkspace workspace = new TaskWorkspace(properties);
        DockerExecutionRunner runner = mock(DockerExecutionRunner.class);
        ArtifactCollector artifacts = mock(ArtifactCollector.class);
        var pool = Executors.newSingleThreadExecutor();
        ExecutionCoordinator coordinator = new ExecutionCoordinator(new RequestPolicy(mapper), repository,
                workspace, runner, artifacts, new ProgramToolExchange(mapper, workspace), pool, Clock.systemUTC(), mock(SessionWorkspaces.class));
        Instant now = Instant.now();
        TaskRecord unknown = new TaskRecord("unknown-task", "digest", Language.PYTHON, false, List.of(),
                now.plusSeconds(60), ExecutionState.UNKNOWN, "termination_unconfirmed", "container-id", null,
                "", "", false, false, "RUNNER_RESULT_UNKNOWN", null, List.of(), null, now, now,
                TestSandboxProperties.policy(), com.skillforge.execution.ExecutionProfile.STANDARD, null,
                properties.pythonImage(), "sha256:" + "1".repeat(64), null);
        repository.register(unknown);
        when(runner.cancel(unknown)).thenReturn(true);

        try {
            var response = coordinator.cancel("unknown-task");

            assertEquals(ExecutionState.UNKNOWN, response.state());
            assertEquals("unknown_quiescent", response.phase());
            assertEquals(List.of(), response.files());
            verify(runner).cancel(unknown);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void recoveryMarksCleanedRunningTaskUnknownAndQuiescent() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskRepository repository = new TaskRepository(mapper, properties);
        TaskWorkspace workspace = new TaskWorkspace(properties);
        DockerExecutionRunner runner = mock(DockerExecutionRunner.class);
        ArtifactCollector artifacts = mock(ArtifactCollector.class);
        var pool = Executors.newSingleThreadExecutor();
        ExecutionCoordinator coordinator = new ExecutionCoordinator(new RequestPolicy(mapper), repository,
                workspace, runner, artifacts, new ProgramToolExchange(mapper, workspace), pool, Clock.systemUTC(), mock(SessionWorkspaces.class));
        Instant now = Instant.now();
        TaskRecord running = new TaskRecord("running-task", "digest", Language.NODE, true, List.of(),
                now.plusSeconds(60), ExecutionState.RUNNING, "execution", "container-id", null,
                "", "", false, false, null, null, List.of(), null, now, now,
                TestSandboxProperties.policy(), com.skillforge.execution.ExecutionProfile.STANDARD, null,
                properties.nodeImage(), "sha256:" + "1".repeat(64), null);
        repository.register(running);

        try {
            coordinator.recover();

            TaskRecord recovered = repository.find("running-task").orElseThrow();
            assertEquals(ExecutionState.UNKNOWN, recovered.state());
            assertEquals("unknown_quiescent", recovered.phase());
            assertEquals("SERVICE_RESTARTED_RESULT_UNKNOWN", recovered.error());
            assertNull(recovered.deliveryError());
            assertTrue(recovered.programTools());
            verify(runner).cleanupOwnedContainers();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void terminalProgramPollingReturnsNoCallsAndRejectsLateResults() throws InterruptedException {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        SandboxProperties properties = TestSandboxProperties.create(temporary);
        TaskRepository repository = new TaskRepository(mapper, properties);
        TaskWorkspace workspace = new TaskWorkspace(properties);
        DockerExecutionRunner runner = mock(DockerExecutionRunner.class);
        ArtifactCollector artifacts = mock(ArtifactCollector.class);
        var pool = Executors.newSingleThreadExecutor();
        ProgramToolExchange exchange = mock(ProgramToolExchange.class);
        ExecutionCoordinator coordinator = new ExecutionCoordinator(new RequestPolicy(mapper), repository,
                workspace, runner, artifacts, exchange, pool, Clock.systemUTC(), mock(SessionWorkspaces.class));
        Instant now = Instant.now();
        try {
            for (ExecutionState terminal : List.of(ExecutionState.COMPLETED, ExecutionState.FAILED,
                    ExecutionState.CANCELLED, ExecutionState.TIMED_OUT, ExecutionState.UNKNOWN)) {
                String taskId = "polling-" + terminal.name();
                repository.register(new TaskRecord(taskId, "digest", Language.NODE, true, List.of(),
                        now.plusSeconds(60), ExecutionState.RUNNING, "execution", null, null,
                        "", "", false, false, null, null, List.of(), null, now, now,
                        TestSandboxProperties.policy(), com.skillforge.execution.ExecutionProfile.STANDARD, null,
                        properties.nodeImage(), "sha256:" + "1".repeat(64), null));
                // 宿主刚读到 running，任务随后在工具轮询前进入终态；该顺序无需时钟或线程碰运气。
                assertEquals(ExecutionState.RUNNING, coordinator.get(taskId).state());
                repository.update(taskId, value -> value.withState(terminal, "stopped", now));
                assertEquals(List.of(), coordinator.pendingToolCalls(taskId));
                assertEquals(terminal, coordinator.get(taskId).state());
                SandboxConflictException failure = assertThrows(SandboxConflictException.class,
                        () -> coordinator.completeToolCall(taskId, 1,
                                mapper.createObjectNode().put("success", true)));
                assertEquals("TASK_NOT_ACTIVE", failure.code());
            }
            verifyNoInteractions(exchange);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }
}
