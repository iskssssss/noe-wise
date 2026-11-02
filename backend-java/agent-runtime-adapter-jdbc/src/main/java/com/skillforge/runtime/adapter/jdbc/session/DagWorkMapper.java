package com.skillforge.runtime.adapter.jdbc.session;

import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.RunIdentity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** DAG 状态、准入、占用与命令事实共用调用方 SqlSession。 */
interface DagWorkMapper {
    /** 冻结 Action 归属，冲突保留原绑定，由调用方核验。 */
    int insertStepAction(@Param("run") RunRef run, @Param("action") String action,
            @Param("admission") String admission, @Param("task") String task);
    /** 按真实 Run 与 Action 读取不可变步骤归属。 */
    DagStepActionRow stepAction(@Param("run") RunRef run, @Param("action") String action);
    /** 按租户、Session、根输入读取工作快照，可在同事务加行锁；缺失返回 null。 */
    String read(@Param("key") DagWork.Key key, @Param("lock") boolean lock);
    /** 读取 Session 唯一 ACTIVE 或 CANCELLING 快照，无活动工作返回 null。 */
    String active(@Param("owner") SessionOwner owner);
    /** 活动工作优先，否则按受理序号倒序取最近工作；归属由解码后完整 owner 核验。 */
    String latest(@Param("owner") SessionOwner owner);
    /** 按受理序号限量读取 QUEUED、ACTIVE、CANCELLING 快照，终态工作不进入恢复批次。 */
    List<String> recoverable(@Param("tenant") String tenant, @Param("limit") int limit);
    boolean activeUncancelledControl(@Param("work") DagWork work);
    boolean hasUnsettledCancel(@Param("work") DagWork work);
    /** 通过持久任务准入关联 Run 与工作；不存在准入时返回 null。 */
    String workForRun(@Param("run") RunRef run);
    /** 通过控制 Run 的冻结关联查找工作，不要求控制 Run 已产生 Runtime 执行行。 */
    String workForControl(@Param("run") RunRef run);
    /** 按完整 owner 过滤并倒序返回有限条工作快照，不跨目标或终端用户暴露历史。 */
    List<String> history(@Param("owner") SessionOwner owner, @Param("limit") int limit);
    /** 按完整 owner 统计 QUEUED 工作，供 Session 排队容量判断。 */
    long queuedCount(@Param("owner") SessionOwner owner);
    /** 检查指定工作是否已有最终答复事实，防止答复提交后再修改计划。 */
    boolean answerExists(@Param("key") DagWork.Key key);
    /** 核对任务 Run 是否已经持久受理，防止将实际已启动任务标记为未派发。 */
    boolean runExists(@Param("tenant") String tenant, @Param("run") String run);
    /** 检查同 Session 活动工作及更早排队工作；任一存在则禁止当前工作抢先激活。 */
    boolean activationBlocked(@Param("key") DagWork.Key key);
    /** 写入首次受理快照、owner 和 revision=1；调用方要求单行成功且事件同事务提交。 */
    int insertWork(@Param("work") DagWork work, @Param("payload") String payload);
    /** 仅在期望 revision 匹配时更新状态和快照；返回 0 表示失去写权，调用方回滚。 */
    int updateWork(@Param("work") DagWork work, @Param("payload") String payload, @Param("revision") long revision);
    /** 冻结任务、Run、Agent、计划版本及绑定载荷；唯一冲突不得覆盖原准入。 */
    int insertAdmission(@Param("key") DagWork.Key key, @Param("binding") DagWork.Binding binding, @Param("payload") String payload);
    /** 锁准确原MEMBER准入并核工作owner及原control绑定；缺失返回null，不借当前快照伪造来源。 */
    String planChildAdmission(@Param("parent") RunIdentity parent, @Param("key") DagWork.Key key, @Param("binding") DagWork.Binding binding);
    /** 锁原MEMBER的准确许可，数据库时钟核未过期；丢失、换代或到期返回null。 */
    Long planChildPermit(@Param("binding") DagWork.Binding binding, @Param("permit") DagWork.Permit permit);
    /** 在当前事务安装或替换任务 Run 的 fence 与到期时间，不能独立于工作快照提交。 */
    int upsertPermit(@Param("tenant") String tenant, @Param("run") String run, @Param("fence") long fence, @Param("expires") Instant expires);
    /** 释放指定任务 Run 的工作占用；返回 0 允许此前挂起事务已释放的幂等情况。 */
    int deletePermit(@Param("tenant") String tenant, @Param("run") String run);
    /** 冻结控制 Run 与工作、协调者和阶段的绑定；冲突不覆盖，调用方再核验原值。 */
    int insertControl(@Param("work") DagWork work);
    /** 按租户和控制 Run 读取完整绑定以核对幂等插入；缺失返回 null。 */
    DagControlRow control(@Param("tenant") String tenant, @Param("run") String run);
    /** 读取 Session 命令的根输入与指纹，供同标识重放和冲突判定；未执行返回 null。 */
    DagCommandRow command(@Param("key") DagWork.Key key, @Param("command") String command);
    /** 记录已应用命令指纹；必须与事件及工作状态一起提交，不先占命令标识。 */
    int insertCommand(@Param("key") DagWork.Key key, @Param("command") String command, @Param("fingerprint") String fingerprint);
    /** 按租户与 Session 派生键获取事务 advisory 锁，统一同 Session 工作修改顺序。 */
    String lockSession(@Param("lockKey") String lockKey);
    /** 锁定完整 Session owner 行，与 Runtime NewRun 受理协调；无授权归属行返回 null。 */
    Integer lockOwner(@Param("owner") SessionOwner owner);
    /** 读取数据库当前时钟用于本批事件，不能复用 MyBatis 查询缓存中的旧时间。 */
    Instant now();
}
final class DagControlRow { public String sessionId, rootInputId, agentId, role; }
final class DagCommandRow { public String rootInputId, fingerprint; }

final class DagStepActionRow { public String admissionId, taskId; }
