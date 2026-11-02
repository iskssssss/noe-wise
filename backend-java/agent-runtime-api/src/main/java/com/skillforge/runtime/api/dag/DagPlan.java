package com.skillforge.runtime.api.dag;

import com.skillforge.runtime.api.AgentId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 已提交分工的不可变定义；输入先指向声明产出，准入时才解析实际结果。 */
public record DagPlan(long revision, boolean approvalRequired, List<Task> tasks, String title, String changeReason) {
    public DagPlan(long revision, boolean approvalRequired, List<Task> tasks) {
        this(revision, approvalRequired, tasks, "任务计划", "");
    }
    public DagPlan {
        if (revision < 1) throw new IllegalArgumentException("plan revision must be positive");
        title = text(title); changeReason = Objects.requireNonNullElse(changeReason, "");
        tasks = List.copyOf(tasks);
        if (tasks.isEmpty() || tasks.size() > 100) throw new IllegalArgumentException("plan requires 1..100 tasks");
        Set<String> replaced = new HashSet<>();
        for (Task task : tasks) if (!task.replacesTaskId().isEmpty() && !replaced.add(task.replacesTaskId()))
            throw new IllegalArgumentException("task has multiple replacements");
        Map<String, Task> byId = new HashMap<>();
        for (Task task : tasks) {
            if (!task.replacesTaskId().isEmpty() && !byId.containsKey(task.replacesTaskId()))
                throw new IllegalArgumentException("replacement source task is missing");
            if (byId.put(task.id(), task) != null) throw new IllegalArgumentException("duplicate task: " + task.id());
        }
        for (Task task : tasks) {
            Set<String> names = new HashSet<>();
            for (Input input : task.inputs()) {
                Task source = byId.get(input.sourceTask());
                if (source == null || !source.outputs().contains(input.output())) {
                    throw new IllegalArgumentException("undeclared dependency output: " + input.sourceTask() + "/" + input.output());
                }
                if (!names.add(input.name())) throw new IllegalArgumentException("duplicate input name");
            }
        }
        Map<String, Integer> colors = new HashMap<>();
        for (String id : byId.keySet()) visit(id, byId, colors);
    }

    public Task task(String id) {
        return tasks.stream().filter(task -> task.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown task: " + id));
    }

    /** 历史任务仍保留原定义和执行事实，后续准入及工作答复只采用未被替代的任务。 */
    public List<Task> effectiveTasks() {
        Set<String> replaced = tasks.stream().map(Task::replacesTaskId).filter(id -> !id.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        return tasks.stream().filter(task -> !replaced.contains(task.id())).toList();
    }

    private static void visit(String id, Map<String, Task> tasks, Map<String, Integer> colors) {
        int color = colors.getOrDefault(id, 0);
        if (color == 1) throw new IllegalArgumentException("cyclic task dependency");
        if (color == 2) return;
        colors.put(id, 1);
        for (Input input : tasks.get(id).inputs()) visit(input.sourceTask(), tasks, colors);
        if (!tasks.get(id).replacesTaskId().isEmpty()) visit(tasks.get(id).replacesTaskId(), tasks, colors);
        colors.put(id, 2);
    }

    public enum ExecutorType { PRIMARY, MEMBER }

    public record Task(String id, String title, AgentId agentId, ExecutorType executorType, String objective, String context, List<String> constraints, String expectedOutput,
                       List<Input> inputs, Set<String> outputs,
                       Set<String> exclusiveResources, boolean cancelled, String replacesTaskId) {
        public Task {
            id = text(id); title = text(title); objective = text(objective);
            agentId = Objects.requireNonNull(agentId); executorType = Objects.requireNonNull(executorType);
            context = Objects.requireNonNullElse(context, ""); constraints = List.copyOf(constraints);
            constraints.forEach(DagPlan::text); expectedOutput = text(expectedOutput);
            replacesTaskId = replacesTaskId == null ? "" : replacesTaskId;
            if (!replacesTaskId.isEmpty()) text(replacesTaskId);
            inputs = List.copyOf(inputs);
            outputs = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(outputs));
            exclusiveResources = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(exclusiveResources));
            outputs.forEach(DagPlan::text); exclusiveResources.forEach(DagPlan::text);
            if (outputs.isEmpty()) throw new IllegalArgumentException("outputs required");
        }
        public Task(String id, String title, AgentId agentId, String objective, String context,
                List<String> constraints, String expectedOutput, List<Input> inputs, Set<String> outputs,
                Set<String> exclusiveResources, boolean cancelled, String replacesTaskId) {
            this(id, title, agentId, ExecutorType.MEMBER, objective, context, constraints, expectedOutput,
                    inputs, outputs, exclusiveResources, cancelled, replacesTaskId);
        }
        public Task(String id, String title, AgentId agentId, ExecutorType executorType, String objective,
                List<Input> inputs, Set<String> outputs, Set<String> exclusiveResources) {
            this(id, title, agentId, executorType, objective, "", List.of(), "完成声明的产出", inputs,
                    outputs, exclusiveResources, false, "");
        }
        public Task(String id,String title,AgentId agentId,String objective,List<Input> inputs,Set<String> outputs,
                Set<String> exclusiveResources) {
            this(id,title,agentId,objective,"",List.of(),"完成声明的产出",inputs,outputs,exclusiveResources,false,"");
        }
    }

    /** 输出名称属于上游定义，不能在提交分工时伪造一个尚不存在的 ResultRef。 */
    public record Input(String name, String sourceTask, String output) {
        public Input { name = text(name); sourceTask = text(sourceTask); output = text(output); }
    }

    static String text(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) throw new IllegalArgumentException("normalized text required");
        return value;
    }
}
