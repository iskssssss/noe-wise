package com.skillforge.runtime.session.dag;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.dag.DagWorkPort;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * 已批准分工的执行协调。派发只受理稳定子 Run，不等待成员模型完成；每次结果到达均可推进下游。
 * 实际运行与恢复仍交给原 Runtime，进程重启只需重新扫描持久工作。
 */
public final class DagDriver {
    public interface Runs {
        /** 幂等受理相同 admission/Run；重复调用不能重复外调模型或工具。 */
        void ensureAccepted(DagWork work, DagWork.Execution execution);
        /** 只读真实 Run/Action/委派事实；撤权后仍允许最小事实收尾，不能向成员泄露内容。 */
        Optional<DagCoordinator.Completion> observation(DagWork work, DagWork.Execution execution);
        /** 持久受理取消，返回不代表执行已经停止。 */
        void requestCancel(DagWork work, DagWork.Execution execution);
        /** 经唯一答复出口登记和投递；没有足够结果时返回空，不伪造成功。 */
        Optional<ResultRef> finish(DagWork work);
        /** 单个成员暂不可访问时保留原事实并重试；宿主记录原因，无关分支继续。 */
        default void deferred(DagWork work, DagWork.Execution execution, String phase, RuntimeException failure) {
            throw failure;
        }
    }

    private final DagWorkPort store;
    private final DagCoordinator coordinator;
    private final Runs runs;
    private final Clock clock;

    public DagDriver(DagWorkPort store, DagCoordinator coordinator, Runs runs, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.runs = Objects.requireNonNull(runs);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 可由多个恢复扫描者调用，所有业务准入与观察均由持久 CAS 决定。 */
    public void tick(DagWork.Key key) {
        var work = require(key);
        if (closed(work) || work.status() == DagWork.Status.QUEUED) return;
        for (var execution : work.executions().values()) {
            if (execution.terminal() || execution.binding().task().executorType()
                    == com.skillforge.runtime.api.dag.DagPlan.ExecutorType.PRIMARY) continue;
            if (work.status() == DagWork.Status.CANCELLING) {
                try { runs.requestCancel(work, execution); }
                catch (RuntimeException failure) { runs.deferred(work, execution, "cancel", failure); }
            }
            try {
            var observation = runs.observation(work, execution);
            if (observation.isPresent()) {
                // 并发结果仅重试投影 CAS，永远不重做成员执行。
                for (int attempt = 0; attempt < 8; attempt++) {
                    var result = coordinator.observe(key, observation.orElseThrow());
                    if (result != DagWorkPort.Commit.CONFLICT) break;
                    var current = require(key).executions().get(execution.binding().task().id());
                    if (current.terminal()) break;
                }
            }
            } catch (RuntimeException failure) { runs.deferred(work, execution, "observe", failure); }
        }
        work = require(key);
        if (work.status() == DagWork.Status.ACTIVE) {
            coordinator.advance(key, "dag-advance:" + work.key().rootInputId().value() + ":" + work.revision(), clock.instant());
            work = require(key);
            for (var execution : work.executions().values()) {
                if (execution.binding().task().executorType() == com.skillforge.runtime.api.dag.DagPlan.ExecutorType.MEMBER
                        && execution.status() == DagWork.TaskStatus.RESERVED) {
                    try { runs.ensureAccepted(work, execution); }
                    catch (RuntimeException failure) { runs.deferred(work, execution, "dispatch", failure); }
                }
            }
        }
        work = require(key);
        // 关闭的是整个工作；没有活动 Run、没有已准入任务都不能自动代表工作完成。
        if (DagSettlement.settled(work)) {
            var closure = runs.finish(work);
            if (closure.isPresent()) coordinator.close(key, "dag-close:" + work.key().rootInputId().value(), work.revision(), closure.orElseThrow());
        }
    }

    private DagWork require(DagWork.Key key) { return store.read(key).orElseThrow(); }
    private boolean closed(DagWork work) {
        return work.status() == DagWork.Status.COMPLETED || work.status() == DagWork.Status.CANCELLED;
    }
}
