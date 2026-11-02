package com.skillforge.sandbox.execution;

import com.skillforge.execution.ExecutionState;
import com.skillforge.sandbox.persistence.TaskRecord;
import com.skillforge.sandbox.persistence.TaskRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 到期删除已确认静止的短期工作目录，但永久保留任务身份和终态用于去重。 */
@Component
public class WorkspaceJanitor {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceJanitor.class);

    private final TaskRepository repository;
    private final TaskWorkspace workspace;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final java.util.Map<Long, Instant> lastSweep = new java.util.HashMap<>();

    @Autowired
    public WorkspaceJanitor(TaskRepository repository,
                            TaskWorkspace workspace) {
        this(repository, workspace, Clock.systemUTC(),
                new ScheduledThreadPoolExecutor(1,
                        Thread.ofPlatform().name("sandbox-workspace-janitor").daemon(true).factory()));
    }

    WorkspaceJanitor(TaskRepository repository,
                     TaskWorkspace workspace,
                     Clock clock,
                     ScheduledExecutorService scheduler) {
        this.repository = repository;
        this.workspace = workspace;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        cleanupSafely();
        long intervalMillis = 60_000;
        scheduler.scheduleWithFixedDelay(this::cleanupSafely, intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
    }

    synchronized void cleanupNow() {
        Instant now = clock.instant();
        java.util.Set<Long> swept = new java.util.HashSet<>();
        for (TaskRecord task : repository.findWorkspaceCandidates()) {
            long revision = task.executionPolicy().revision();
            Instant previous = lastSweep.get(revision);
            if (previous != null && now.isBefore(previous.plusSeconds(
                    task.executionPolicy().workspaceCleanupIntervalMinutes() * 60L))) continue;
            swept.add(revision);
            Instant cutoff = clock.instant().minus(java.time.Duration.ofHours(task.executionPolicy().workspaceRetentionHours()));
            if (!eligible(task, cutoff)) {
                continue;
            }
            workspace.delete(task.taskId());
            repository.update(task.taskId(), current -> current.withWorkspaceDeleted(clock.instant()));
            log.info("已清理沙箱短期工作目录 taskTag={}", Integer.toHexString(task.taskId().hashCode()));
        }
        swept.forEach(revision -> lastSweep.put(revision, now));
    }

    private boolean eligible(TaskRecord task, Instant cutoff) {
        if (!task.state().stopped() || task.workspaceDeletedAt() != null || !task.updatedAt().isBefore(cutoff)) {
            return false;
        }
        return task.state() != ExecutionState.UNKNOWN || "unknown_quiescent".equals(task.phase());
    }

    private void cleanupSafely() {
        try {
            cleanupNow();
        } catch (RuntimeException failure) {
            log.error("清理沙箱短期工作目录失败", failure);
        }
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}
