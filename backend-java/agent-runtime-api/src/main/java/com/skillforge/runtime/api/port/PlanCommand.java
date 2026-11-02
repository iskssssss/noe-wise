package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.ResultRef;
import java.util.*;

/** 原生任务计划命令；运行身份、审批策略和执行证据由宿主核实。 */
public record PlanCommand(Operation operation, String workId, long expectedRevision, Optional<Plan> plan,
        String taskId, String outcome, String summary, List<ResultRef> resultRefs, List<String> waitTaskIds, Map<String,String> outputs) {
    public PlanCommand(Operation operation,String workId,long expectedRevision,Optional<Plan> plan,String taskId,String outcome,String summary,List<ResultRef> resultRefs,List<String> waitTaskIds) {
        this(operation,workId,expectedRevision,plan,taskId,outcome,summary,resultRefs,waitTaskIds,Map.of());
    }
    public enum Operation { SUBMIT, GET, START_STEP, FINISH_STEP, WAIT }
    public enum ExecutorType { PRIMARY, MEMBER }
    public record Executor(ExecutorType type, String targetHandle) {
        public Executor { Objects.requireNonNull(type); targetHandle = Objects.requireNonNullElse(targetHandle, "");
            if (type == ExecutorType.PRIMARY ? !targetHandle.isEmpty() : targetHandle.isBlank()) throw invalid(); }
    }
    public record Task(String id, String title, Executor executor, DelegationCommand.Task task,
            List<Input> inputs, Set<String> outputs, Set<String> exclusiveResources, boolean cancelled, String replacesTaskId) {
        public Task(String id,String title,Executor executor,DelegationCommand.Task task,List<Input> inputs,Set<String> outputs,Set<String> exclusiveResources) { this(id,title,executor,task,inputs,outputs,exclusiveResources,false,""); }
        public Task { id = PortContracts.requireText(id,"id"); title = PortContracts.requireText(title,"title");
            Objects.requireNonNull(executor); Objects.requireNonNull(task); inputs = List.copyOf(inputs);
            outputs = Set.copyOf(outputs); exclusiveResources = Set.copyOf(exclusiveResources); replacesTaskId=Objects.requireNonNullElse(replacesTaskId, "");
            if(id.equals(replacesTaskId)) throw invalid();
            outputs.forEach(v -> PortContracts.requireText(v,"output")); exclusiveResources.forEach(v -> PortContracts.requireText(v,"resource"));
            if (outputs.isEmpty()) throw invalid();
            var inputNames = new HashSet<String>();
            for (var input : inputs) if (!inputNames.add(input.name())) throw invalid(); }
    }
    /** 输入只引用同一计划内上游声明的逻辑输出名，不携带未产生的结果引用。 */
    public record Input(String name, String sourceTask, String output) {
        public Input {
            name = PortContracts.requireText(name, "name");
            sourceTask = PortContracts.requireText(sourceTask, "sourceTask");
            output = PortContracts.requireText(output, "output");
        }
    }
    public record Plan(String title, String changeReason, List<Task> tasks, boolean approvalRequired) {
        public Plan(String title,String changeReason,List<Task> tasks) { this(title,changeReason,tasks,false); }
        public Plan { title = PortContracts.requireText(title,"title"); changeReason = Objects.requireNonNullElse(changeReason, "");
            tasks = List.copyOf(tasks);
            // 修订携带完整未准入集合，可能引用宿主保留的已准入任务；此处仅验证局部形状。
            if (tasks.size() > 100) throw invalid();
            var ids = new HashSet<String>();
            for (var task : tasks) if (!ids.add(task.id())) throw invalid(); }
        private void validateInitialGraph() {
            if (tasks.isEmpty()) throw invalid();
            var byId = new HashMap<String, Task>();
            for (var task : tasks) byId.put(task.id(), task);
            for (var task : tasks) for (var input : task.inputs()) {
                var source = byId.get(input.sourceTask());
                if (source == null || source == task || !source.outputs().contains(input.output())) throw invalid();
            }
            var colors = new HashMap<String, Integer>();
            for (var id : byId.keySet()) visit(id, byId, colors);
        }
        private static void visit(String id, Map<String, Task> tasks, Map<String, Integer> colors) {
            int color = colors.getOrDefault(id, 0);
            if (color == 1) throw invalid();
            if (color == 2) return;
            colors.put(id, 1);
            for (var input : tasks.get(id).inputs()) visit(input.sourceTask(), tasks, colors);
            colors.put(id, 2);
        }
    }
    public PlanCommand {
        Objects.requireNonNull(operation); workId = Objects.requireNonNullElse(workId, ""); Objects.requireNonNull(plan);
        taskId = Objects.requireNonNullElse(taskId, ""); outcome = Objects.requireNonNullElse(outcome, ""); summary = Objects.requireNonNullElse(summary, "");
        resultRefs = List.copyOf(resultRefs); waitTaskIds = List.copyOf(waitTaskIds); outputs = Map.copyOf(outputs);
        outputs.forEach((key,value) -> { PortContracts.requireText(key,"outputName"); PortContracts.requireText(value,"outputValue"); });
        if((operation != Operation.FINISH_STEP || !outcome.equals("completed")) && !outputs.isEmpty()) throw invalid();
        if (expectedRevision < 0 || new HashSet<>(waitTaskIds).size() != waitTaskIds.size()) throw invalid();
        waitTaskIds.forEach(v -> PortContracts.requireText(v,"taskId"));
        if (operation == Operation.SUBMIT) { if (plan.isEmpty() || !taskId.isEmpty() || !outcome.isEmpty() || !summary.isEmpty() || !resultRefs.isEmpty() || !waitTaskIds.isEmpty()) throw invalid();
            // 初次提交必须是非空完整图；修订的合并全图由宿主原 DAG 验证器核验。
            if (expectedRevision == 0) plan.orElseThrow().validateInitialGraph();
        }
        else { if (workId.isBlank() || plan.isPresent()) throw invalid();
            if (operation == Operation.START_STEP || operation == Operation.FINISH_STEP) { if(taskId.isBlank()) throw invalid(); }
            else if (!taskId.isEmpty()) throw invalid();
            if (operation == Operation.FINISH_STEP) { if (!Set.of("completed","failed","unknown").contains(outcome) || summary.isBlank()) throw invalid(); }
            else if (!outcome.isEmpty() || !summary.isEmpty() || !resultRefs.isEmpty()) throw invalid();
            if (operation != Operation.WAIT && !waitTaskIds.isEmpty()) throw invalid(); }
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("plan_command_invalid"); }
    @Override public String toString() { return "PlanCommand[" + operation + "]"; }
}
