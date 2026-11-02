package com.skillforge.sandbox.execution;

import com.skillforge.execution.ArtifactFile;
import com.skillforge.execution.PublicResults;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.execution.ExecuteRequest;
import com.skillforge.execution.ExecutionResponse;
import com.skillforge.execution.ExecutionProfile;
import com.skillforge.execution.ExecutionState;
import com.skillforge.execution.ExecutionAdmissionRejection;
import com.skillforge.sandbox.api.ExecutionAdmissionException;
import com.skillforge.sandbox.api.SandboxConflictException;
import com.skillforge.sandbox.api.SandboxRequestException;
import com.skillforge.sandbox.api.TaskNotFoundException;
import com.skillforge.sandbox.persistence.TaskRecord;
import com.skillforge.sandbox.persistence.TaskRepository;
import com.skillforge.sandbox.workspace.SessionWorkspaces;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** 编排持久登记、非幂等执行、取消和恢复；重复 POST 永不再次调度代码。 */
@Service
public class ExecutionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ExecutionCoordinator.class);

    private final com.fasterxml.jackson.databind.ObjectMapper resultJson=new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final RequestPolicy requestPolicy;
    private final TaskRepository repository;
    private final TaskWorkspace workspace;
    private final DockerExecutionRunner runner;
    private final ArtifactCollector artifacts;
    private final ProgramToolExchange programTools;
    private final ExecutorService executor;
    private final Clock clock;
    private final SessionWorkspaces projects;
    private final Map<String, Object> taskLocks = new ConcurrentHashMap<>();
    private final Object capacity = new Object();
    private int executing;

    @Autowired
    public ExecutionCoordinator(RequestPolicy requestPolicy,
                                TaskRepository repository,
                                TaskWorkspace workspace,
                                DockerExecutionRunner runner,
                                ArtifactCollector artifacts,
                                ProgramToolExchange programTools,
                                SessionWorkspaces projects) {
        this(requestPolicy, repository, workspace, runner, artifacts, programTools,
                Executors.newVirtualThreadPerTaskExecutor(), Clock.systemUTC(), projects);
    }

    ExecutionCoordinator(RequestPolicy requestPolicy,
                         TaskRepository repository,
                         TaskWorkspace workspace,
                         DockerExecutionRunner runner,
                         ArtifactCollector artifacts,
                         ProgramToolExchange programTools,
                         ExecutorService executor,
                         Clock clock,
                         SessionWorkspaces projects) {
        this.requestPolicy = requestPolicy;
        this.repository = repository;
        this.workspace = workspace;
        this.runner = runner;
        this.artifacts = artifacts;
        this.programTools = programTools;
        this.executor = executor;
        this.clock = clock;
        this.projects = projects;
    }

    @PostConstruct
    void recover() {
        runner.cleanupOwnedContainers();
        projects.recoverAfterProcessesStopped();
        for (TaskRecord task : repository.findAll()) {
            taskLocks.putIfAbsent(task.taskId(), new Object());
            if (task.state() == ExecutionState.UNKNOWN) {
                repository.update(task.taskId(), current -> current.withState(ExecutionState.UNKNOWN,
                        "unknown_quiescent", clock.instant()));
            } else if (!task.state().stopped()) {
                repository.update(task.taskId(), current -> current.finished(ExecutionState.UNKNOWN,
                        "unknown_quiescent", current.exitCode(), current.stdout(), current.stderr(),
                        current.stdoutTruncated(), current.stderrTruncated(), "SERVICE_RESTARTED_RESULT_UNKNOWN",
                        List.of(), clock.instant()));
            }
        }
    }

    public Submission submit(ExecuteRequest request) {
        return submit(request,List.of(),null);
    }

    public Submission submit(ExecuteRequest request,List<ArtifactFile> inputs,java.util.zip.ZipInputStream archive) {
        RequestPolicy.ValidatedRequest validated;
        TaskRecord candidate;
        Object lock = taskLocks.computeIfAbsent(request.taskId(), ignored -> new Object());
        synchronized (lock) {
            TaskRecord existing = repository.find(request.taskId()).orElse(null);
            try {
                DockerExecutionRunner.ExecutionEnvironment environment = runner.resolveEnvironment(
                        request.profile(), request.language(), request.environmentVersion());
                validated = requestPolicy.validate(request, inputs, environment.imageReference(), environment.imageId());
            } catch (SandboxRequestException rejected) {
                // 锁内确认尚无登记；已有任务的非法重投不能形成“未提交”事实。
                if (existing != null) throw new SandboxConflictException("TASK_ID_REUSED",
                        "taskId 已登记，不能采用非法请求替换原任务");
                throw new ExecutionAdmissionException(ExecutionAdmissionRejection.Code.INVALID_EXECUTION_POLICY);
            }
            Instant now = clock.instant();
            candidate = new TaskRecord(validated.taskId(), validated.digest(), validated.language(),
                validated.programTools(),
                validated.dependencies(), validated.deadlineAt(), ExecutionState.QUEUED, "queued", null,
                null, "", "", false, false, null, null, List.of(), null, now, now,
                validated.executionPolicy(), validated.profile(), validated.environmentVersion(),
                validated.imageReference(), validated.imageId(), null, validated.workspace());
            if (existing != null) {
                if (!existing.requestDigest().equals(validated.digest())) {
                    throw new SandboxConflictException("TASK_ID_REUSED",
                            "taskId 已用于不同执行请求，拒绝覆盖或重新执行");
                }
                return new Submission(response(existing), false);
            }
            if (!validated.deadlineAt().isAfter(clock.instant())) {
                throw new ExecutionAdmissionException(ExecutionAdmissionRejection.Code.EXECUTION_DEADLINE_EXPIRED);
            }
            repository.register(candidate);
        }

        try {
            if(archive==null)workspace.prepare(validated);else workspace.prepare(validated,archive);
            executor.execute(() -> executeBounded(validated));
        } catch (RejectedExecutionException shuttingDown) {
            synchronized (lock) {
                TaskRecord result = repository.update(validated.taskId(), current -> current.state().stopped()
                        ? current : current.finished(ExecutionState.FAILED, "queue", null, "", "", false, false,
                        "EXECUTOR_NOT_ACCEPTING_TASKS", List.of(), clock.instant()));
                return new Submission(response(result), true);
            }
        } catch (RuntimeException preparationFailure) {
            log.error("准备沙箱任务失败 taskTag={}", taskTag(validated.taskId()), preparationFailure);
            synchronized (lock) {
                TaskRecord result = repository.update(validated.taskId(), current -> current.state().stopped()
                        ? current : current.finished(ExecutionState.FAILED, "prepare", null, "", "", false, false,
                        "WORKSPACE_PREPARATION_FAILED", List.of(), clock.instant()));
                return new Submission(response(result), true);
            }
        }
        return new Submission(response(candidate), true);
    }

    public ExecutionResponse get(String taskId) {
        return response(find(taskId));
    }

    /** 与终态迁移共用任务锁；终态返回空队列，允许宿主从旧的 running 快照继续回读最终结果。 */
    public List<ProgramToolExchange.ToolCall> pendingToolCalls(String taskId) {
        Object lock = taskLocks.computeIfAbsent(taskId, ignored -> new Object());
        synchronized (lock) {
            TaskRecord current = programToolsTask(taskId);
            if (current.state().stopped()) return List.of();
            return programTools.pending(current.taskId());
        }
    }

    /** 在同一任务锁内检查活动状态并冻结响应；终态或取消先取得锁后，后续结果必定拒绝。 */
    public void completeToolCall(String taskId, int ordinal,
                                 com.fasterxml.jackson.databind.JsonNode result) {
        Object lock = taskLocks.computeIfAbsent(taskId, ignored -> new Object());
        synchronized (lock) {
            TaskRecord current = activeProgramToolsTask(taskId);
            programTools.complete(current.taskId(), ordinal, result);
        }
    }

    public ExecutionResponse cancel(String taskId) {
        Object lock = taskLocks.computeIfAbsent(taskId, ignored -> new Object());
        synchronized (lock) {
            TaskRecord current = find(taskId);
            if (current.state() == ExecutionState.UNKNOWN) {
                boolean stopped = runner.cancel(current);
                if (stopped) {
                    current = repository.update(taskId, value -> value.withState(ExecutionState.UNKNOWN,
                            "unknown_quiescent", clock.instant()));
                    if (current.workspace() != null) projects.confirmStopped(current.workspace().workspaceId());
                }
                return response(current);
            }
            if (current.state().stopped()) {
                return response(current);
            }
            boolean stopped = runner.cancel(current);
            List<ArtifactFile> files = List.of();
            TaskRecord cancelled = repository.update(taskId, value -> value.finished(
                    stopped ? ExecutionState.CANCELLED : ExecutionState.UNKNOWN,
                    stopped ? "cancelled" : "termination_unconfirmed", value.exitCode(), value.stdout(),
                    value.stderr(), value.stdoutTruncated(), value.stderrTruncated(),
                    stopped ? "CANCELLED" : "CONTAINER_TERMINATION_UNCONFIRMED", files, clock.instant()));
            return response(cancelled);
        }
    }

    public TaskRecord stoppedTask(String taskId) {
        TaskRecord task = find(taskId);
        if (!task.state().stopped()) {
            throw new SandboxConflictException("TASK_NOT_STOPPED", "任务停止后才能收集文件");
        }
        if (task.state() == ExecutionState.UNKNOWN) {
            throw new SandboxConflictException("FILES_UNAVAILABLE", "结果未知的任务不允许收集文件");
        }
        if (task.workspaceDeletedAt() != null) {
            throw new SandboxConflictException("FILES_UNAVAILABLE", "任务工作目录已按保留期清理");
        }
        if (!workspace.exists(taskId)) {
            throw new SandboxConflictException("FILES_UNAVAILABLE", "任务工作目录不可用");
        }
        return task;
    }

    /** 并发额度按任务冻结策略准入；修改配置不终止已经取得额度的执行。 */
    private void executeBounded(RequestPolicy.ValidatedRequest request) {
        boolean acquired=false;
        boolean executionStarted=false;
        SessionWorkspaces.Lease projectLease = null;
        try {
            // 同一项目的写者先排队，避免空等项目锁的任务占满全局执行名额。
            if (request.workspace() != null) projectLease = projects.acquire(request.workspace(), request.deadlineAt(),
                    () -> find(request.taskId()).state().stopped());
            synchronized(capacity) {
                while(executing>=request.executionPolicy().maxConcurrentExecutions()) {
                    if(find(request.taskId()).state().stopped()) return;
                    if(!clock.instant().isBefore(request.deadlineAt())) {
                        synchronized (taskLocks.get(request.taskId())) {
                            repository.update(request.taskId(), task -> task.state().stopped() ? task :
                                    task.finished(ExecutionState.TIMED_OUT,"queue",null,
                                    "","",false,false,"QUEUE_DEADLINE_EXPIRED",List.of(),clock.instant()));
                        }
                        return;
                    }
                    capacity.wait(100);
                }
                executing++; acquired=true;
            }
            if (find(request.taskId()).state().stopped()) return;
            if (projectLease != null) projectLease.beginWrite("task:" + request.taskId());
            executionStarted=true;
            execute(request);
        } catch (SandboxConflictException projectRejected) {
            synchronized (taskLocks.get(request.taskId())) {
                repository.update(request.taskId(), task -> task.state().stopped() ? task : task.finished(
                        "WORKSPACE_DEADLINE_EXPIRED".equals(projectRejected.code()) ? ExecutionState.TIMED_OUT : ExecutionState.FAILED,
                        "workspace", null, "", "", false, false, projectRejected.code(), List.of(), clock.instant()));
            }
        } catch(InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException workspaceFailure) {
            // 恢复/冻结校验异常必须收敛已登记任务，不能让队列事实永久停在QUEUED。
            boolean resultUnknown = executionStarted;
            log.warn("工作区执行编排失败 taskTag={} resultUnknown={} failureType={}", taskTag(request.taskId()),
                    resultUnknown, workspaceFailure.getClass().getSimpleName());
            synchronized (taskLocks.get(request.taskId())) {
                repository.update(request.taskId(), task -> task.state().stopped() ? task : task.finished(
                        resultUnknown ? ExecutionState.UNKNOWN : ExecutionState.FAILED,
                        "workspace", null, "", "", false, false,
                        resultUnknown ? "WORKSPACE_EXECUTION_RESULT_UNKNOWN" : "WORKSPACE_PREPARATION_FAILED",
                        List.of(), clock.instant()));
            }
        } finally {
            try {
                if (projectLease != null) {
                    TaskRecord current = find(request.taskId());
                    boolean confirmed = current.state().stopped() && (current.state() != ExecutionState.UNKNOWN
                            || "unknown_quiescent".equals(current.phase()));
                    try {
                        if (!confirmed) confirmed = runner.cancel(current);
                    } catch (RuntimeException unconfirmed) {
                        confirmed = false;
                        log.warn("工作区命令停止未确认 taskTag={}", taskTag(current.taskId()));
                    } finally {
                        projectLease.terminationConfirmed(confirmed);
                        projectLease.close();
                    }
                }
            } finally {
                if(acquired) synchronized(capacity){executing--;capacity.notifyAll();}
            }
        }
    }

    private void execute(RequestPolicy.ValidatedRequest request) {
        Object lock = taskLocks.get(request.taskId());
        synchronized (lock) {
            TaskRecord current = find(request.taskId());
            if (current.state().stopped()) {
                return;
            }
            repository.update(request.taskId(), task -> task.withState(ExecutionState.RUNNING,
                    request.profile() == ExecutionProfile.WORKFLOW_SCRIPT_V1
                            ? "execution" : "install_and_execute", clock.instant()));
        }

        DockerExecutionRunner.RunnerResult result;
        try {
            TaskRecord running = find(request.taskId());
            result = runner.execute(running, request,
                    containerId -> recordContainer(request.taskId(), containerId),
                    phase -> recordPhase(request.taskId(), phase),
                    () -> find(request.taskId()).state().stopped());
        } catch (RuntimeException failure) {
            log.error("沙箱执行器异常 taskTag={}", taskTag(request.taskId()), failure);
            result = new DockerExecutionRunner.RunnerResult(ExecutionState.UNKNOWN, "runner_failure", null,
                    "", "", false, false, "RUNNER_RESULT_UNKNOWN", null);
        }

        synchronized (lock) {
            TaskRecord current = find(request.taskId());
            if (current.state().stopped()) {
                return;
            }
            List<ArtifactFile> files = List.of();
            String deliveryError = null;
            if(request.profile()==ExecutionProfile.STANDARD&&result.state()==ExecutionState.COMPLETED
                    &&Integer.valueOf(0).equals(result.exitCode())) {
                try {
                    var parsed=readPublicResults(request);
                    result=new DockerExecutionRunner.RunnerResult(result.state(),result.phase(),result.exitCode(),
                            result.stdout(),result.stderr(),result.stdoutTruncated(),result.stderrTruncated(),
                            result.error(),parsed==null?null:resultJson.valueToTree(parsed));
                } catch(RuntimeException invalid) {
                    result=new DockerExecutionRunner.RunnerResult(ExecutionState.FAILED,"business_result",result.exitCode(),
                            result.stdout(),result.stderr(),result.stdoutTruncated(),result.stderrTruncated(),
                            "PUBLIC_RESULT_FILE_INVALID",null);
                }
            }
            if (request.profile() == ExecutionProfile.STANDARD
                    && result.state() == ExecutionState.COMPLETED && Integer.valueOf(0).equals(result.exitCode())) {
                try {
                    files = artifacts.collect(request.taskId(),request.executionPolicy().maxArtifactFiles(), request.outputFiles());
                } catch (RuntimeException unsafeOrUnreadable) {
                    log.warn("任务产物收集失败 taskTag={} reason={}", taskTag(request.taskId()),
                            safeMessage(unsafeOrUnreadable));
                    deliveryError = "ARTIFACT_COLLECTION_FAILED";
                }
            }
            DockerExecutionRunner.RunnerResult terminal = result;
            List<ArtifactFile> terminalFiles = files;
            String terminalDeliveryError = deliveryError;
            repository.update(request.taskId(), task -> task.finished(terminal.state(), terminal.phase(),
                    terminal.exitCode(), terminal.stdout(), terminal.stderr(), terminal.stdoutTruncated(),
                    terminal.stderrTruncated(), terminal.error(), terminalDeliveryError, terminalFiles,
                    terminal.jsonResult(), clock.instant()));
        }
    }

    private void recordContainer(String taskId, String containerId) {
        Object lock = taskLocks.get(taskId);
        synchronized (lock) {
            TaskRecord current = find(taskId);
            if (!current.state().stopped()) {
                repository.update(taskId, task -> task.withContainer(containerId, clock.instant()));
            }
        }
    }

    private void recordPhase(String taskId, String phase) {
        Object lock = taskLocks.get(taskId);
        synchronized (lock) {
            TaskRecord current = find(taskId);
            if (!current.state().stopped()) {
                repository.update(taskId, task -> task.withState(ExecutionState.RUNNING, phase, clock.instant()));
            }
        }
    }

    private TaskRecord find(String taskId) {
        return repository.find(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private TaskRecord activeProgramToolsTask(String taskId) {
        TaskRecord task = programToolsTask(taskId);
        if (task.state().stopped()) {
            throw new SandboxConflictException("TASK_NOT_ACTIVE", "任务已停止，不再接受工具消息");
        }
        return task;
    }

    private TaskRecord programToolsTask(String taskId) {
        TaskRecord task = find(taskId);
        if (!task.programTools()) {
            throw new SandboxConflictException("PROGRAM_TOOLS_DISABLED", "任务未启用 programTools");
        }
        return task;
    }

    private ExecutionResponse response(TaskRecord task) {
        var business=task.profile()==ExecutionProfile.STANDARD&&task.jsonResult()!=null
                ?resultJson.convertValue(task.jsonResult(),PublicResults.class):null;
        return new ExecutionResponse(task.taskId(),task.state(),task.phase(),task.exitCode(),
                task.stdoutTruncated(),task.stderrTruncated(),task.error(),task.deliveryError(),task.files(),
                List.of(new ExecutionResponse.LogReference("stdout",task.stdout().length(),task.stdoutTruncated()),
                        new ExecutionResponse.LogReference("stderr",task.stderr().length(),task.stderrTruncated())),
                task.profile()==ExecutionProfile.WORKFLOW_SCRIPT_V1?task.jsonResult():null,business);
    }

    private PublicResults readPublicResults(RequestPolicy.ValidatedRequest request) {
        var root=workspace.output(request.taskId());
        var file=root.resolve(ExecutionLimits.PUBLIC_RESULT_FILE);
        try {
            // 只有文件确实不存在才表示无业务结果；链接（包括悬空链接）不能按缺失放行。
            if(java.nio.file.Files.notExists(file,java.nio.file.LinkOption.NOFOLLOW_LINKS))return null;
            for(var current=file;current!=null&&current.startsWith(root);current=current.getParent())
                if(java.nio.file.Files.isSymbolicLink(current))throw new java.io.IOException("public_result_link");
            var attrs=java.nio.file.Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if(!file.startsWith(root)||!attrs.isRegularFile()||attrs.size()>ExecutionLimits.PUBLIC_RESULT_BYTES)
                throw new java.io.IOException("public_result_size");
            try(var input=java.nio.file.Files.newInputStream(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=input.readNBytes(ExecutionLimits.PUBLIC_RESULT_BYTES+1);
                if(bytes.length>ExecutionLimits.PUBLIC_RESULT_BYTES)throw new java.io.IOException("public_result_size");
                var parsed=resultJson.readValue(bytes,PublicResults.class);
                if(parsed==null)throw new java.io.IOException("public_result_null");
                return parsed;
            }
        } catch(java.io.IOException invalid) { throw new IllegalArgumentException("public_result_file_invalid",invalid); }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        for (TaskRecord task : repository.findAll()) {
            if (!task.state().stopped() || task.state() == ExecutionState.UNKNOWN) {
                Object lock = taskLocks.computeIfAbsent(task.taskId(), ignored -> new Object());
                synchronized (lock) {
                    TaskRecord current = find(task.taskId());
                    if (current.state() == ExecutionState.UNKNOWN) {
                        if (runner.cancel(current)) {
                            repository.update(current.taskId(), value -> value.withState(ExecutionState.UNKNOWN,
                                    "unknown_quiescent", clock.instant()));
                        }
                        continue;
                    }
                    if (!current.state().stopped()) {
                        boolean stopped = runner.cancel(current);
                        repository.update(current.taskId(), value -> value.finished(ExecutionState.UNKNOWN,
                                stopped ? "service_shutdown" : "termination_unconfirmed", value.exitCode(),
                                value.stdout(), value.stderr(), value.stdoutTruncated(), value.stderrTruncated(),
                                stopped ? "SERVICE_STOPPED_RESULT_UNKNOWN" : "CONTAINER_TERMINATION_UNCONFIRMED",
                                List.of(), clock.instant()));
                    }
                }
            }
        }
    }

    private String taskTag(String taskId) {
        return Integer.toHexString(taskId.hashCode());
    }

    private String safeMessage(Throwable failure) {
        String value = failure.getMessage();
        if (value == null) {
            return failure.getClass().getSimpleName();
        }
        value = value.replaceAll("[\\r\\n\\p{Cntrl}]", " ");
        return value.length() > 256 ? value.substring(0, 256) : value;
    }

    public record Submission(ExecutionResponse response, boolean created) {
    }
}
