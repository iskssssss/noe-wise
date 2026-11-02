package com.skillforge.runtime.adapter.sqlite;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 同一租户 SqlSession 内的新执行权威数据操作；外部调用不进入此边界。 */
interface ExecutionStoreMapper {
    int authorizeOnce(@Param("tenant") String tenant, @Param("attempt") String attempt);
    List<ExecutionRunRow> runsForSession(@Param("tenant") String tenant, @Param("session") String session);
    /** 在事务开始时绑定租户；所属事务内不再切换。 */
    String bindTenant(@Param("tenant") String tenant);
    long queuedTaskCount(@Param("tenant") String tenant, @Param("sessions") List<String> sessions);
    ExecutionRunRow firstUnfinishedRun(@Param("tenant") String tenant, @Param("session") String session);
    ExecutionRunRow latestRun(@Param("tenant") String tenant, @Param("session") String session);
    DesktopProjectionCountsRow desktopCounts(@Param("tenant") String tenant, @Param("session") String session);
    List<ExecutionRunRow> desktopRecentRuns(@Param("tenant") String tenant, @Param("session") String session, @Param("limit") int limit);
    List<ExecutionRunRow> desktopRuns(@Param("tenant") String tenant, @Param("session") String session, @Param("runs") List<String> runs);
    List<ExecutionEventRow> desktopEvents(@Param("tenant") String tenant, @Param("session") String session, @Param("tool") boolean tool, @Param("limit") int limit);
    List<ExecutionEventRow> desktopFinishedEvents(@Param("tenant") String tenant, @Param("session") String session, @Param("events") List<String> events);
    List<ExecutionActionRow> desktopToolActions(@Param("tenant") String tenant, @Param("session") String session, @Param("limit") int limit);
    List<ExecutionWaitRow> desktopPendingWaits(@Param("tenant") String tenant, @Param("session") String session);
    List<ExecutionWaitRequestRow> desktopWaitRequests(@Param("tenant") String tenant, @Param("session") String session);
    List<ExecutionActionRow> desktopActions(@Param("tenant") String tenant, @Param("actions") List<String> actions);
    List<ExecutionAttemptRow> desktopAttempts(@Param("tenant") String tenant, @Param("attempts") List<String> attempts);
    /** 采样本业务提交的数据库时间。 */
    Instant now();
    /** 原受理身份建立canonical Session owner，不创建日志、流、旧Run图或生命周期事件。 */
    int ensureSessionOwner(ExecutionRunRow row);
    /** 锁定不可变归属以阻止提交前删除；返回原endUserId，冲突不能用插入请求覆盖。 */
    String lockSessionOwner(ExecutionRunRow row);
    /** 首次建立会话归属和消息顺序；重复身份不覆盖已有归属。 */
    int ensureSession(ExecutionRunRow row);
    /** 核对现有会话仍属于相同服务目标和终端用户。 */
    boolean sessionOwned(ExecutionRunRow row);
    /** 尝试插入受理状态，业务身份重复时由调用方核对原事实。 */
    int insertRun(ExecutionRunRow row);
    /** 按完整租户与 Run 读取当前状态。 */
    ExecutionRunRow run(@Param("tenant") String tenant, @Param("run") String run);
    /** 取得单一 Run 推进锁；涉及预算时调用方已先持有根锁。 */
    ExecutionRunRow lockRun(@Param("tenant") String tenant, @Param("run") String run);
    /** 按原输入业务身份查重复受理。 */
    ExecutionRunRow inputRun(ExecutionRunRow row);
    /** CAS 更新唯一状态；即使持有锁仍核对原 revision。 */
    int updateRun(@Param("row") ExecutionRunRow row, @Param("expected") long expected);
    /** 派发最终CAS同时用数据库时钟核验当前lease；未命中必须回滚整个派发事务。 */
    int updateDispatchRun(@Param("row") ExecutionRunRow row, @Param("expected") long expected);
    /** 本地响应最终CAS仍核验当前READY、lease与DB时间，不创建外调事实。 */
    int updateLocalResponseRun(@Param("row") ExecutionRunRow row, @Param("expected") long expected);
    int updatePlanRun(@Param("row") ExecutionRunRow row, @Param("expected") long expected,
                      @Param("lease") com.skillforge.runtime.api.port.LeaseFence lease);
    /** 写入不可变协议材料，原引用存在时不覆盖。 */
    int insertMaterial(ExecutionMaterialRow row);
    int insertMaterials(@Param("rows") List<ExecutionMaterialRow> rows);
    List<ExecutionMaterialRow> materials(@Param("tenant") String tenant, @Param("references") List<String> references);
    /** 精确读取材料原正文。 */
    ExecutionMaterialRow material(@Param("tenant") String tenant, @Param("reference") String reference);
    /** 写入唯一逻辑动作。 */
    int insertAction(ExecutionActionRow row);
    /** 读取逻辑动作。 */
    ExecutionActionRow action(@Param("tenant") String tenant, @Param("action") String action);
    List<ExecutionActionRow> actions(@Param("tenant") String tenant, @Param("run") String run,
                                     @Param("after") long after, @Param("limit") int limit);
    List<ExecutionChildRow> childOrigins(@Param("tenant") String tenant, @Param("child") String child);
    int insertChildOrigin(ExecutionChildRow row);
    ExecutionTokenUsageRow usage(@Param("tenant") String tenant, @Param("run") String run);
    long latestActionOrdinal(@Param("tenant") String tenant, @Param("run") String run);
    ExecutionPlanRow planControl(@Param("tenant") String tenant, @Param("run") String run, @Param("action") String action);
    int insertPlanControl(ExecutionPlanRow row);
    ExecutionPlanChildRow planChild(@Param("tenant") String tenant,@Param("child") String child);
    int insertPlanChild(ExecutionPlanChildRow row);
    ExecutionProgramChildRow programChild(ExecutionProgramChildRow row);
    int insertProgramChild(ExecutionProgramChildRow row);
    /** 派发与结果事务在 Run 锁后取得动作锁。 */
    ExecutionActionRow lockAction(@Param("tenant") String tenant, @Param("action") String action);
    List<ExecutionActionRow> batchActions(@Param("tenant") String tenant, @Param("run") String run, @Param("source") String source);
    List<ExecutionActionRow> lockBatchActions(@Param("tenant") String tenant, @Param("run") String run, @Param("source") String source);
    List<ExecutionAttemptRow> batchAttempts(@Param("tenant") String tenant, @Param("run") String run, @Param("source") String source);
    List<ExecutionAttemptRow> lockBatchAttempts(@Param("tenant") String tenant, @Param("run") String run, @Param("source") String source);
    /** 原模型决定的完整有序成员；旧结果只存精确引用。 */
    List<ExecutionBatchMemberRow> batchMembers(@Param("tenant") String tenant,@Param("run") String run,@Param("source") String source);
    int insertBatchMember(ExecutionBatchMemberRow row);
    long batchPhysicalCalls(@Param("tenant") String tenant,@Param("run") String run,@Param("source") String source);
    /** 设置动作结果及选定 Attempt。 */
    int updateAction(ExecutionActionRow row);
    /** 保存单次派发；相同身份不产生第二次发送权。 */
    int insertAttempt(ExecutionAttemptRow row);
    /** 查找指定 Run 的派发。 */
    ExecutionAttemptRow attempt(@Param("tenant") String tenant, @Param("run") String run, @Param("attempt") String attempt);
    /** 在根与 Run、Action 锁后锁定单次派发。 */
    ExecutionAttemptRow lockAttempt(@Param("tenant") String tenant, @Param("run") String run, @Param("attempt") String attempt);
    /** 保存结果和采纳标记；正文通过不可变引用传递。 */
    int updateAttempt(ExecutionAttemptRow row);
    /** 保存与INPUT_REQUIRED结果同事务建立的一轮等待。 */
    int insertWait(ExecutionWaitRow row);
    int insertWaitRequest(ExecutionWaitRequestRow row);
    int insertWaitChild(ExecutionWaitChildRow row);
    List<ExecutionWaitChildRow> waitChildren(@Param("tenant") String tenant, @Param("action") String action, @Param("generation") long generation);
    ExecutionWaitRow waiting(@Param("tenant") String tenant, @Param("run") String run,
                            @Param("action") String action, @Param("generation") long generation);
    ExecutionWaitRow sourceWaiting(@Param("tenant") String tenant, @Param("run") String run, @Param("attempt") String attempt);
    ExecutionWaitRow response(@Param("tenant") String tenant, @Param("run") String run, @Param("input") String input);
    List<ExecutionWaitRequestRow> waitRequests(@Param("tenant") String tenant, @Param("action") String action, @Param("generation") long generation);
    long nextWaitGeneration(@Param("tenant") String tenant, @Param("action") String action);
    int acceptWaitResponse(ExecutionWaitRow row);
    int consumeWaitResponse(ExecutionWaitRow row);
    /** 人类响应的本地采纳仅登记业务版本，不伪造工具Attempt。 */
    int consumeLocalWaitResponse(ExecutionWaitRow row);
    /** 最早下一次实际可领取的到期时间，仅读取索引字段。 */
    ExecutionDueRow nextDueAt(@Param("tenant") String tenant);
    /** 在持有 Run 锁后分配整个 Session 的下一消息序号，不反向锁其他 Run。 */
    long nextSequence(@Param("tenant") String tenant, @Param("session") String session);
    /** 插入已提交公开事件。 */
    int insertEvent(ExecutionEventRow row);
    /** 精确核对重复事件身份的原内容。 */
    ExecutionEventRow event(@Param("tenant") String tenant, @Param("session") String session, @Param("event") String event);
    /** 按持久游标读取公开事件，不读取或恢复 Run。 */
    List<ExecutionEventRow> events(@Param("tenant") String tenant, @Param("session") String session,
                                   @Param("after") long after, @Param("limit") int limit);
    /** 按上下界读取同一会话的事件窗口。 */
    List<ExecutionEventRow> eventsInRange(@Param("tenant") String tenant, @Param("session") String session,
                                          @Param("after") long after, @Param("through") long through, @Param("limit") int limit);
    /** 核对重复受理的原公开事件，不读取后续执行正文。 */
    List<ExecutionEventRow> admissionEvents(@Param("tenant") String tenant, @Param("run") String run);
    List<ExecutionEventRow> eventsAtRevision(@Param("tenant") String tenant, @Param("run") String run, @Param("revision") long revision);
    /** 按到期索引有界扫描；只返回租户内的 Run 标识。 */
    List<String> due(@Param("tenant") String tenant, @Param("second") long second, @Param("nano") int nano, @Param("limit") int limit);
}
