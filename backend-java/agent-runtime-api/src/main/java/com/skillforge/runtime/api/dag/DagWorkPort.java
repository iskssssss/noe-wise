package com.skillforge.runtime.api.dag;

import java.util.List;
import java.util.Optional;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionOwner;

/** 工作分工与准入事实的持久边界；CAS 同时提交 SessionLog 事件和协调投影。 */
public interface DagWorkPort {
    Optional<DagWork> read(DagWork.Key key);

    /** 持久工作占用包括澄清、评审、交接与取消收尾，不从活动 Run 数量推断。 */
    Optional<DagWork> active(SessionOwner owner);

    /** 租户内恢复扫描只提供候选，派发前仍校验权威绑定与容量许可。 */
    List<DagWork.Key> recoverable(TenantId tenant, int limit);

    /** 一个 Session 只能有一个活动工作；创建和占用须处于同一事务。 */
    boolean create(DagWork work, String commandId, String fingerprint);

    /**
     * 同命令同指纹重放，异体冲突；revision CAS 失败不产生任何执行派发。
     * task.not_dispatched 必须与真实 Run 受理互斥并校验 Run 尚不存在，否则返回冲突。
     */
    Commit commit(DagWork.Key key, long expectedRevision, String commandId,
                  String fingerprint, DagWork next, List<Event> events);

    enum Commit { APPLIED, REPLAYED, CONFLICT }

    record Event(String type, String taskId, String description) { }
}
