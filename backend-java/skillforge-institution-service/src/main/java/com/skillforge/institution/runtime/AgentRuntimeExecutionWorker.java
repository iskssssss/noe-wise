package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.starter.ExecutionHostWorker;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** 机构宿主无线程门面；执行槽、租户游标和持久due恢复由Starter唯一Worker拥有。 */
public final class AgentRuntimeExecutionWorker implements AutoCloseable {
    private final ExecutionHostWorker worker;
    private final ExecutionCoordinator<?> coordinator;
    private final AgentRuntimeExecutionActivity activity;
    private final java.util.function.Supplier<AgentRuntimeProgramTools> programs;
    private final AtomicReference<AgentRuntimeExecutionQueueObservation> observation = new AtomicReference<>();
    public AgentRuntimeExecutionWorker(ExecutionHostWorker worker, ExecutionCoordinator<?> coordinator, AgentRuntimeExecutionActivity activity) {
        this(worker, coordinator, activity, () -> { throw new IllegalStateException("program_tools_not_configured"); });
    }
    public AgentRuntimeExecutionWorker(ExecutionHostWorker worker, ExecutionCoordinator<?> coordinator, AgentRuntimeExecutionActivity activity,
            java.util.function.Supplier<AgentRuntimeProgramTools> programs) {
        this.worker = Objects.requireNonNull(worker); this.coordinator = Objects.requireNonNull(coordinator);
        this.activity = Objects.requireNonNull(activity); activity.observeClaims(this::claimed);
        this.programs = Objects.requireNonNull(programs);
    }
    public boolean isRunning() { return worker.isRunning(); }
    public boolean wake(RunRef run) {
        observeReady(run); return worker.wake(run);
    }
    /** 真实准入开始时记录本scope时点，禁止调度；wake只在Store准入提交后调用。 */
    public void observeReady(RunRef run) { var scope = observation.get(); if (scope != null) scope.received(run, System.nanoTime()); }
    public ExecutionCoordinator.Outcome driveProgramChild(com.skillforge.core.capability.connector.PlatformToolExecutionContext parent,
            com.skillforge.runtime.session.spi.ExecutionStore.ProgramChildSource source, RunRef child) {
        if (parent.programChild() || parent.workflowNode()) throw new IllegalArgumentException("program_tool_recursive_execution_rejected");
        if (parent.cancellationSignal().isCancellationRequested() || !java.time.Instant.now().isBefore(parent.deadlineAt()))
            throw new IllegalArgumentException("program_scope_closed");
        return programs.get().withParentControl(child, parent, () -> worker.driveProgramChild(parent.run(), source, child));
    }
    public void cancelled(RunRef run) { worker.cancelled(run); }
    /** 当前已提交context对应的活动材料，只读且缺失时不重建执行或授予派发权。 */
    public Optional<AgentExecutionProgram.Materials> activeAgentMaterials(RunIdentity identity, ResultRef context) {
        return coordinator.activeMaterials(identity, context).filter(AgentRuntimeExecutionPrograms.AgentMaterials.class::isInstance)
                .map(AgentRuntimeExecutionPrograms.AgentMaterials.class::cast).map(AgentRuntimeExecutionPrograms.AgentMaterials::value);
    }
    public AgentRuntimeExecutionQueueObservation observeQueue(TenantId tenant, int maxSamples) {
        if (worker.isClosed()) throw new IllegalStateException("execution_worker_closed");
        var scope = new AgentRuntimeExecutionQueueObservation(tenant, maxSamples, ended -> observation.compareAndSet(ended, null));
        if (!observation.compareAndSet(null, scope)) throw new IllegalStateException("execution_queue_observation_already_active");
        return scope;
    }
    private void claimed(RunRef run, long nanos) { var scope = observation.get(); if (scope != null) scope.claimed(run, nanos); }
    @Override public void close() {
        var scope = observation.getAndSet(null); if (scope != null) scope.close(); activity.observeClaims((run, nanos) -> { }); worker.close();
    }
}
