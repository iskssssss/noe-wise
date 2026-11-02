package example.sdk;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.ExecutionHostRegistry;
import com.skillforge.runtime.starter.ExecutionHostScheduler;
import com.skillforge.runtime.starter.ExecutionHostWorker;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** 发布 JAR 的最小 typed Host：只完成已准入的本地任务，不授予任何模型、工具或子任务能力。 */
final class PublishedExecutionHost implements ExecutionHostRegistry.Host, AutoCloseable {
    private final ExecutionStore store;
    private final RunRef owned;
    private final ExecutionHostScheduler scheduler = new ExecutionHostScheduler();
    private final ExecutionHostWorker worker;
    private final ExecutionCoordinator<?> coordinator;
    private final CompletableFuture<ExecutionStore.Run> completed = new CompletableFuture<>();

    PublishedExecutionHost(ExecutionStore store, RunRef owned) {
        this(store, owned, null);
    }
    PublishedExecutionHost(ExecutionStore store, RunRef owned, ExecutionCoordinator<?> coordinator) {
        this.store = store; this.owned = owned;
        this.coordinator = coordinator;
        worker = new ExecutionHostWorker(new ExecutionHostRegistry(List.of(this)),
                (after, limit) -> List.of(), scheduler,
                (tenant, work) -> {
                    try {
                        if (!owned.tenantId().equals(tenant)) throw new IllegalArgumentException("published_host_tenant_mismatch");
                        work.run();
                    } catch (RuntimeException | Error failure) {
                        completed.completeExceptionally(failure);
                        throw failure;
                    }
                }, "published-host", Duration.ofMinutes(1), false);
    }
    @Override public String id() { return "published-local"; }
    @Override public boolean owns(ExecutionStore.Run persisted) {
        return persisted.identity().runRef().equals(owned);
    }
    @Override public ExecutionStore store() { return store; }
    @Override public void begin(RunRef run) { }
    @Override public void end(RunRef run) { }
    @Override public void recover(TenantId tenant, int limit) { }
    @Override public void cancelled(RunRef run) { }

    /** 此任务仅作本地完成；完整 Agent 宿主在该入口调用 ExecutionCoordinator.drive。 */
    @Override public ExecutionCoordinator.Outcome drive(ExecutionStore.Claim claim) {
        try {
            return driveClaim(claim);
        } catch (RuntimeException | Error failure) {
            // Worker 会记录 RuntimeException，Error 则离开执行线程；测试线程必须收到原始失败。
            completed.completeExceptionally(failure);
            throw failure;
        }
    }
    private ExecutionCoordinator.Outcome driveClaim(ExecutionStore.Claim claim) {
        if (!Thread.currentThread().getName().startsWith("runtime-execution-") || Thread.currentThread().isVirtual())
            throw new IllegalStateException("published_host_requires_platform_slot");
        if (coordinator != null) return coordinator.drive(claim);
        var claimed = store.claim(claim);
        if (claimed.isEmpty()) return new ExecutionCoordinator.Outcome(ExecutionCoordinator.Stop.NOT_CLAIMED,
                Optional.empty(), "published_not_claimed");
        var current = claimed.orElseThrow();
        var finished = store.finish(new ExecutionStore.Finish(owned, current.revision(),
                current.lease().orElseThrow(), RunStatus.COMPLETED, List.of()));
        return new ExecutionCoordinator.Outcome(ExecutionCoordinator.Stop.FINISHED, Optional.of(finished), "published_local_complete");
    }
    @Override public void committed(ExecutionStore.Run run) { completed.complete(run); }

    ExecutionStore.Run execute() throws Exception {
        if (!worker.wake(owned)) throw new IllegalStateException("published_host_wake_rejected");
        try {
            return completed.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Error error) throw error;
            if (failed.getCause() instanceof Exception exception) throw exception;
            throw new IllegalStateException("published_host_failed", failed.getCause());
        }
    }
    ExecutionStore.Run persisted() { return store.load(owned).orElseThrow(); }
    @Override public void close() {
        try { worker.close(); } finally { scheduler.close(); }
    }
}
