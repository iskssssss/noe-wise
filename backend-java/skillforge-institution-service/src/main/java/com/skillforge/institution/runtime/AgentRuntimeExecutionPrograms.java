package com.skillforge.institution.runtime;

import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.ExecutionCoordinator;
import com.skillforge.runtime.session.WorkflowNodeProgram;
import com.skillforge.runtime.session.ProgramToolProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 同一协调器内按服务器保存的精确配置引用选择程序，材料始终保留所属强类型。 */
final class AgentRuntimeExecutionPrograms implements ExecutionCoordinator.Program<AgentRuntimeExecutionPrograms.Materials> {
    private final AgentExecutionProgram agents;
    private final WorkflowNodeProgram nodes;
    private final ProgramToolProgram programs;

    AgentRuntimeExecutionPrograms(AgentExecutionProgram agents, WorkflowNodeProgram nodes, ProgramToolProgram programs) {
        this.agents = Objects.requireNonNull(agents); this.nodes = Objects.requireNonNull(nodes);
        this.programs = Objects.requireNonNull(programs);
    }

    sealed interface Materials permits AgentMaterials, NodeMaterials, ProgramMaterials { }
    record ProgramMaterials(ProgramToolProgram.Materials value) implements Materials {
        ProgramMaterials { Objects.requireNonNull(value); }
    }
    record AgentMaterials(AgentExecutionProgram.Materials value) implements Materials {
        AgentMaterials { Objects.requireNonNull(value); }
    }
    record NodeMaterials(WorkflowNodeProgram.Materials value) implements Materials {
        NodeMaterials { Objects.requireNonNull(value); }
    }

    @Override public ExecutionStore.Prepared restorePrepared(ExecutionStore.Run run, ExecutionStore.Prepared prepared) {
        if (AgentRuntimeProgramTools.owns(run)) return programs.restorePrepared(run,prepared);
        return AgentRuntimeWorkflowNodeExecutions.owns(run) ? nodes.restorePrepared(run,prepared) : agents.restorePrepared(run,prepared);
    }

    @Override public Materials open(ExecutionStore.Run run, ExecutionStore.Material context) {
        if (AgentRuntimeProgramTools.owns(run)) return new ProgramMaterials(programs.open(run, context));
        return AgentRuntimeWorkflowNodeExecutions.owns(run) ? new NodeMaterials(nodes.open(run, context))
                : new AgentMaterials(agents.open(run, context));
    }

    @Override public ExecutionCoordinator.Step next(ExecutionStore.Run run, Materials materials) {
        requireOwner(run, materials);
        return switch (materials) {
            case AgentMaterials agent -> agents.next(run, agent.value());
            case NodeMaterials node -> nodes.next(run, node.value());
            case ProgramMaterials program -> programs.next(run, program.value());
        };
    }

    @Override public Optional<ExecutionCoordinator.Step.Finish> dispatchRejected(ExecutionStore.Run run, Materials materials, String reason) {
        requireOwner(run, materials);
        return switch (materials) {
            case AgentMaterials agent -> agents.dispatchRejected(run, agent.value(), reason);
            case NodeMaterials node -> nodes.dispatchRejected(run, node.value(), reason);
            case ProgramMaterials program -> programs.dispatchRejected(run, program.value(), reason);
        };
    }

    @Override public Optional<ExecutionCoordinator.Adoption<Materials>> batchDispatchRejectionAdoption(ExecutionStore.Run run, Materials materials,
            List<ExecutionCoordinator.BatchOutcome> results, String reason) {
        requireOwner(run, materials);
        return switch (materials) {
            case AgentMaterials agent -> agents.batchDispatchRejectionAdoption(run, agent.value(), results, reason).map(AgentRuntimeExecutionPrograms::wrapAgent);
            case NodeMaterials node -> nodes.batchDispatchRejectionAdoption(run, node.value(), results, reason).map(AgentRuntimeExecutionPrograms::wrapNode);
            case ProgramMaterials program -> programs.batchDispatchRejectionAdoption(run, program.value(), results, reason).map(AgentRuntimeExecutionPrograms::wrapProgram);
        };
    }

    @Override public ExecutionCoordinator.Step continuation(ExecutionStore.Run run, Materials materials,
            ExecutionStore.Waiting waiting, ExecutionStore.Material response) {
        requireOwner(run, materials);
        return switch (materials) {
            case AgentMaterials agent -> agents.continuation(run, agent.value(), waiting, response);
            case NodeMaterials node -> nodes.continuation(run, node.value(), waiting, response);
            case ProgramMaterials program -> programs.continuation(run, program.value(), waiting, response);
        };
    }

    @Override public ExecutionCoordinator.Adoption<Materials> acceptResponse(ExecutionStore.Run run, Materials materials,
            ExecutionStore.Waiting waiting, ExecutionStore.Material response) {
        requireOwner(run, materials);
        return switch (materials) {
            case AgentMaterials agent -> wrapAgent(agents.acceptResponse(run, agent.value(), waiting, response));
            case NodeMaterials node -> wrapNode(nodes.acceptResponse(run, node.value(), waiting, response));
            case ProgramMaterials program -> wrapProgram(programs.acceptResponse(run, program.value(), waiting, response));
        };
    }

    @Override public ExecutionCoordinator.Adoption<Materials> acceptInput(ExecutionStore.Run run, Materials materials,
            ExecutionStore.SupplementalInput input, ExecutionStore.Material content) {
        requireOwner(run, materials);
        return switch (materials) {
            case AgentMaterials agent -> wrapAgent(agents.acceptInput(run, agent.value(), input, content));
            case NodeMaterials node -> wrapNode(nodes.acceptInput(run, node.value(), input, content));
            case ProgramMaterials program -> wrapProgram(programs.acceptInput(run, program.value(), input, content));
        };
    }

    /** 路由已提交补充的身份传播，不能绕过Agent程序和宿主活动帧的同步。 */
    @Override public Materials inputCommitted(ExecutionStore.InputAcceptance accepted, Materials materials) {
        requireOwner(accepted.run(), materials);
        return switch (materials) {
            case AgentMaterials agent -> new AgentMaterials(agents.inputCommitted(accepted, agent.value()));
            case NodeMaterials node -> new NodeMaterials(nodes.inputCommitted(accepted, node.value()));
            case ProgramMaterials program -> new ProgramMaterials(programs.inputCommitted(accepted, program.value()));
        };
    }

    @Override public ExecutionCoordinator.Adoption<Materials> planAdoption(ExecutionStore.Run run, Materials materials,
            ExecutionCoordinator.Step.PlanLocal command, ExecutionStore.Material actualResult) {
        requireOwner(run, materials);
        return switch (materials) {
            case AgentMaterials agent -> wrapAgent(agents.planAdoption(run, agent.value(), command, actualResult));
            case NodeMaterials node -> wrapNode(nodes.planAdoption(run, node.value(), command, actualResult));
            case ProgramMaterials program -> wrapProgram(programs.planAdoption(run, program.value(), command, actualResult));
        };
    }

    @Override public boolean batchParallel(Materials materials) {
        return switch (materials) {
            case AgentMaterials agent -> agents.batchParallel(agent.value());
            case NodeMaterials node -> nodes.batchParallel(node.value());
            case ProgramMaterials program -> programs.batchParallel(program.value());
        };
    }

    @Override public ExecutionCoordinator.BatchResult inspectBatch(ExecutionStore.Run run, Materials materials,
            ExecutionCoordinator.BatchCall result, int slot) {
        requireOwner(run, materials);
        return switch (materials) {
            case AgentMaterials agent -> agents.inspectBatch(run, agent.value(), result, slot);
            case NodeMaterials node -> nodes.inspectBatch(run, node.value(), result, slot);
            case ProgramMaterials program -> programs.inspectBatch(run, program.value(), result, slot);
        };
    }

    @Override public ExecutionCoordinator.Adoption<Materials> adoptBatch(ExecutionStore.Run run, Materials materials,
            List<ExecutionCoordinator.BatchOutcome> results) {
        requireOwner(run, materials);
        return switch (materials) {
            case AgentMaterials agent -> wrapAgent(agents.adoptBatch(run, agent.value(), results));
            case NodeMaterials node -> wrapNode(nodes.adoptBatch(run, node.value(), results));
            case ProgramMaterials program -> wrapProgram(programs.adoptBatch(run, program.value(), results));
        };
    }

    /** 批次续跑保留原slot/等待和实际发送清单；终止采纳仍带所属强类型材料。 */
    @Override public ExecutionCoordinator.BatchPlan<Materials> batchContinuation(ExecutionStore.Run run, Materials materials,
            List<ExecutionCoordinator.BatchOutcome> results, List<ExecutionCoordinator.BatchResponse> responses) {
        requireOwner(run, materials);
        return switch (materials) {
            case AgentMaterials agent -> {
                var plan = agents.batchContinuation(run, agent.value(), results, responses);
                if (plan instanceof ExecutionCoordinator.BatchPlan.Terminate<AgentExecutionProgram.Materials> terminal)
                    yield new ExecutionCoordinator.BatchPlan.Terminate<>(wrapAgent(terminal.adoption()), terminal.status());
                yield new ExecutionCoordinator.BatchPlan.Resume<>(((ExecutionCoordinator.BatchPlan.Resume<AgentExecutionProgram.Materials>) plan).calls());
            }
            case NodeMaterials node -> {
                var plan = nodes.batchContinuation(run, node.value(), results, responses);
                if (plan instanceof ExecutionCoordinator.BatchPlan.Terminate<WorkflowNodeProgram.Materials> terminal)
                    yield new ExecutionCoordinator.BatchPlan.Terminate<>(wrapNode(terminal.adoption()), terminal.status());
                yield new ExecutionCoordinator.BatchPlan.Resume<>(((ExecutionCoordinator.BatchPlan.Resume<WorkflowNodeProgram.Materials>) plan).calls());
            }
            case ProgramMaterials program -> {
                var plan = programs.batchContinuation(run, program.value(), results, responses);
                if (plan instanceof ExecutionCoordinator.BatchPlan.Terminate<ProgramToolProgram.Materials> terminal)
                    yield new ExecutionCoordinator.BatchPlan.Terminate<>(wrapProgram(terminal.adoption()), terminal.status());
                yield new ExecutionCoordinator.BatchPlan.Resume<>(((ExecutionCoordinator.BatchPlan.Resume<ProgramToolProgram.Materials>) plan).calls());
            }
        };
    }

    @Override public ExecutionCoordinator.Adoption<Materials> adopt(ExecutionStore.Run run, Materials materials,
            ExecutionStore.Prepared prepared, ExecutionCoordinator.CapabilityResult result) {
        requireOwner(run, materials);
        return switch (materials) {
            case AgentMaterials agent -> wrapAgent(agents.adopt(run, agent.value(), prepared, result));
            case NodeMaterials node -> wrapNode(nodes.adopt(run, node.value(), prepared, result));
            case ProgramMaterials program -> wrapProgram(programs.adopt(run, program.value(), prepared, result));
        };
    }

    private static ExecutionCoordinator.Adoption<Materials> wrapAgent(ExecutionCoordinator.Adoption<AgentExecutionProgram.Materials> result) {
        return new ExecutionCoordinator.Adoption<>(new AgentMaterials(result.materials()), result.next(), result.context(), result.events(), result.waiting());
    }

    private static ExecutionCoordinator.Adoption<Materials> wrapNode(ExecutionCoordinator.Adoption<WorkflowNodeProgram.Materials> result) {
        return new ExecutionCoordinator.Adoption<>(new NodeMaterials(result.materials()), result.next(), result.context(), result.events(), result.waiting());
    }
    private static ExecutionCoordinator.Adoption<Materials> wrapProgram(ExecutionCoordinator.Adoption<ProgramToolProgram.Materials> result) {
        return new ExecutionCoordinator.Adoption<>(new ProgramMaterials(result.materials()), result.next(), result.context(), result.events(), result.waiting());
    }

    private static void requireOwner(ExecutionStore.Run run, Materials materials) {
        if (AgentRuntimeWorkflowNodeExecutions.owns(run) != (materials instanceof NodeMaterials)
                || AgentRuntimeProgramTools.owns(run) != (materials instanceof ProgramMaterials))
            throw new IllegalStateException("execution_program_materials_mismatch");
    }
}
