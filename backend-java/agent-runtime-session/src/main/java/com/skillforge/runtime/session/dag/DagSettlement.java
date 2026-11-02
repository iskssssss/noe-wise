package com.skillforge.runtime.session.dag;

import com.skillforge.runtime.api.dag.DagPlan;
import com.skillforge.runtime.api.dag.DagWork;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;

/** 步骤结算与未准入依赖阻断的只读规则；不创建执行、重试或改变未知结果。 */
public final class DagSettlement {
    private DagSettlement() { }

    public static boolean settled(DagWork work) {
        if (!work.executions().values().stream().allMatch(DagWork.Execution::terminal)) return false;
        if (work.plan() == null) return true;
        if (work.status() == DagWork.Status.CANCELLING) return true;
        if (work.plan().approvalRequired() && work.approvedRevision() != work.plan().revision()) return false;
        return work.plan().effectiveTasks().stream().allMatch(task -> task.cancelled()
                || work.executions().containsKey(task.id()) || blockedBy(work, task).isPresent());
    }

    /** 未准入步骤沿已校验的 DAG 查找阻断；UNKNOWN 保持未知，不能当作失败或重做依据。 */
    public static Optional<DagWork.TaskStatus> blockedBy(DagWork work, DagPlan.Task task) {
        DagWork.TaskStatus reason = null;
        var pending = new ArrayDeque<>(task.inputs());
        var visited = new HashSet<String>();
        while (!pending.isEmpty()) {
            var input = pending.removeFirst();
            if (!visited.add(input.sourceTask())) continue;
            var source = work.executions().get(input.sourceTask());
            DagWork.TaskStatus blocked = null;
            if (source != null) {
                if (source.terminal() && source.status() != DagWork.TaskStatus.COMPLETED) blocked = source.status();
            } else {
                var sourceTask = work.plan().task(input.sourceTask());
                if (sourceTask.cancelled()) blocked = DagWork.TaskStatus.CANCELLED;
                else pending.addAll(sourceTask.inputs());
            }
            if (blocked == DagWork.TaskStatus.OUTCOME_UNKNOWN) return Optional.of(blocked);
            if (blocked != null && (reason == null || blocked == DagWork.TaskStatus.FAILED)) reason = blocked;
        }
        return Optional.ofNullable(reason);
    }
}
