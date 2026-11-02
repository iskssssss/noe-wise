package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.ExecutionHostRegistry;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** 机构Agent与其Workflow节点在唯一共享Worker中的宿主；写权仍由各自原Store派发事务核验。 */
final class AgentRuntimeExecutionHost implements ExecutionHostRegistry.Host {
    private final ExecutionCoordinator<AgentRuntimeExecutionPrograms.Materials> coordinator;
    private final ExecutionStore store;
    private final AgentRuntimeExecutionSources sources;
    private final AgentRuntimeExecutionActivity activity;
    private final AgentRuntimeExecutionCatalog catalogs;
    private final WorkflowNodeRequests nodes;
    private final java.util.function.Supplier<AgentRuntimeProgramTools> programs;
    private final Consumer<RunRef> changed;
    private final Consumer<RunIdentity> publications;
    private final BiConsumer<TenantId, Integer> recovery;
    AgentRuntimeExecutionHost(ExecutionCoordinator<AgentRuntimeExecutionPrograms.Materials> coordinator, ExecutionStore store,
            AgentRuntimeExecutionSources sources, AgentRuntimeExecutionActivity activity,
            AgentRuntimeExecutionCatalog catalogs, WorkflowNodeRequests nodes, java.util.function.Supplier<AgentRuntimeProgramTools> programs,
            Consumer<RunIdentity> publications, Consumer<RunRef> changed, BiConsumer<TenantId, Integer> recovery) {
        this.coordinator = coordinator; this.store = store; this.sources = sources;
        this.activity = activity; this.changed = changed; this.recovery = recovery;
        this.catalogs = catalogs; this.nodes = nodes;
        this.programs = programs;
        this.publications = publications;
    }
    @Override public String id() { return "institution"; }
    @Override public boolean owns(ExecutionStore.Run persisted) {
        if (AgentRuntimeProgramTools.owns(persisted)) {
            var original = programs.get().configurationMaterial(persisted);
            var parent = store.load(original.parent().runRef()).orElseThrow();
            programs.get().requireInline(parent, original.source(), persisted); return true;
        }
        String schema;
        if (AgentRuntimeWorkflowNodeExecutions.owns(persisted)) schema = WorkflowNodeRequests.SCHEMA;
        else schema = AgentRuntimeExecutionCatalog.SCHEMA;
        var reference = WorkflowNodeRequests.SCHEMA.equals(schema) ? persisted.configuration() : AgentRuntimeExecutionCatalog.reference(persisted.identity().runRef());
        var material = store.material(persisted.identity().runRef().tenantId(), reference)
                .filter(value -> schema.equals(value.content().schemaId()));
        if (material.isEmpty()) return false;
        if (WorkflowNodeRequests.SCHEMA.equals(schema))
            return com.skillforge.core.workflow.runtime.WorkflowNodeSource.runIdentity(nodes.read(material.orElseThrow()).node()).equals(persisted.identity());
        catalogs.read(material.orElseThrow(), persisted.identity()); return true;
    }
    @Override public ExecutionStore store() { return store; }
    @Override public void begin(RunRef run) { if (!program(run)) sources.begin(run); }
    @Override public void end(RunRef run) { if (!program(run)) sources.end(run); }
    @Override public ExecutionCoordinator.Outcome drive(ExecutionStore.Claim claim) { return coordinator.drive(claim); }
    @Override public void committed(ExecutionStore.Run run) {
        // Store已提交终态后先发布原任务成果，后续依赖/委派才能消费正式完成事实。
        if (run.phase() == ExecutionStore.Phase.FINISHED
                && java.util.Set.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TERMINATED).contains(run.status()))
            publications.accept(run.identity());
        changed.accept(run.identity().runRef());
    }
    @Override public void recover(TenantId tenant, int limit) { recovery.accept(tenant, limit); }
    @Override public void cancelled(RunRef run) { activity.cancelled(run); }
    @Override public void requireProgramChild(ExecutionStore.Run parent, ExecutionStore.ProgramChildSource source, ExecutionStore.Run child) {
        programs.get().requireInline(parent, source, child);
    }
    private boolean program(RunRef run) { return store.load(run).filter(AgentRuntimeProgramTools::owns).isPresent(); }
    ExecutionCoordinator<AgentRuntimeExecutionPrograms.Materials> coordinator() { return coordinator; }
}
