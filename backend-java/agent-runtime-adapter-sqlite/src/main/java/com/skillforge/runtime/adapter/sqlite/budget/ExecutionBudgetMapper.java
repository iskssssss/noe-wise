package com.skillforge.runtime.adapter.sqlite.budget;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 预算账本只在调用方事务内读取与写入；SQLite IMMEDIATE 事务串行化资源预留和结算。 */
interface ExecutionBudgetMapper {
    /** 创建租户根预算的场景、版本与活动时间上限；根已存在返回 0，调用方随后核对冻结内容。 */
    int insertRoot(@Param("tenant") String tenant, @Param("root") String root, @Param("scenario") String scenario, @Param("version") String version, @Param("mode") String mode, @Param("seconds") Long seconds);
    /** 创建 Run 对根预算的归属与角色，并冻结本 Run 版本和活动时间上限；重复身份由唯一约束拒绝。 */
    int insertRun(@Param("tenant") String tenant, @Param("run") String run, @Param("root") String root, @Param("role") String role, @Param("version") String version, @Param("mode") String mode, @Param("seconds") Long seconds);
    /** 创建属于指定 Run 的 RESERVED 调用事实，同时冻结需求指纹、类别及收尾标记。 */
    int insertAttempt(@Param("tenant") String tenant, @Param("attempt") String attempt, @Param("run") String run, @Param("fingerprint") String fingerprint, @Param("category") String category, @Param("closing") boolean closing);
    /** 仅将指定租户、Run、attempt 的 RESERVED 状态推进为 SENT；返回 0 表示不可再签发首次发送许可。 */
    int markSent(@Param("tenant") String tenant, @Param("run") String run, @Param("attempt") String attempt);
    /** 真实发送确认后把调用次数维度记为已报告；Token 等尚未知的用量不在此处补零。 */
    int reportSentCounts(@Param("tenant") String tenant, @Param("attempt") String attempt);
    /** 幂等追加 attempt 的精确结果证据引用；已存在相同证据时返回 0。 */
    int insertReceipt(@Param("tenant") String tenant, @Param("attempt") String attempt, @Param("reference") String reference);
    /** 为已发送、已保存或已结算 attempt 补充结果引用；返回 0 时由调用方核对是否为未发送结果。 */
    int saveResult(@Param("reference") String reference, @Param("tenant") String tenant, @Param("run") String run, @Param("attempt") String attempt);
    /** 只接受仍处于 RESERVED 的未发送结果；返回 0 表示 attempt 状态不允许该收敛。 */
    int saveUnsentResult(@Param("reference") String reference, @Param("tenant") String tenant, @Param("run") String run, @Param("attempt") String attempt);
    /** 已证明未发送的 attempt 将缺失用量确认为零；不覆盖先前已报告维度。 */
    int reportUnsentAmounts(@Param("tenant") String tenant, @Param("attempt") String attempt);
    /** 仅当结果已保存且所有金额维度都已结算时推进 SETTLED；仍有未知维度则返回 0。 */
    int markSettled(@Param("tenant") String tenant, @Param("attempt") String attempt, @Param("pendingTenant") String pendingTenant, @Param("pendingAttempt") String pendingAttempt);
    /** 清除同根下前任主 Run 的局部收尾保护；调用方随后原子安装当前主 Run 的保护。 */
    int clearClosingRun(@Param("tenant") String tenant, @Param("root") String root);
    /** 同根锁内安装根与当前Run的全部七维保护；实际影响行数必须恰好为十四。 */
    int updateClosingBalances(Map<String,Object> parameters);
    /** 同时记录根的收尾活动时间和所属主 Run，避免保护额度与控制者分离。 */
    int configureClosing(@Param("seconds") long seconds, @Param("run") String run, @Param("tenant") String tenant, @Param("root") String root);
    /** 将已持有根锁的预算推进 CLOSING；主 Run 资格由调用方先行核验。 */
    int enterClosing(@Param("tenant") String tenant, @Param("root") String root);
    /** 幂等加入指定 Run 的收尾工具目标；非主 Run 不得由业务入口调用该写入。 */
    int allowClosingTarget(@Param("tenant") String tenant, @Param("run") String run, @Param("handle") String handle);
    /** 一次创建作用域全部七维，包含零值；不覆盖既有冻结金额，实际插入行数必须为七。 */
    int insertBalances(Map<String,Object> parameters);
    /** 读取显式租户值以启动 IMMEDIATE 事务；所有实际读写均带租户条件。 */
    String bindTenant(@Param("tenant") String tenant);
    /** 按租户和 attempt 读取原需求与状态；不存在返回 null，Run 归属由业务层比对。 */
    BudgetAttemptRow attempt(@Param("tenant") String tenant, @Param("attempt") String attempt);
    /** 按租户、Run 和 attempt 读取收尾标记；不存在返回 null，不能被视为获准收尾。 */
    Boolean attemptClosing(@Param("tenant") String tenant, @Param("run") String run, @Param("attempt") String attempt);
    /** 读取完整调用身份对应状态；不存在返回 null，由调用方拒绝未知结果写入。 */
    String attemptState(@Param("tenant") String tenant, @Param("run") String run, @Param("attempt") String attempt);
    /** 返回已有 reported 且尚未结算的维度；未知用量不出现在结果中。 */
    List<BudgetAmountRow> pendingAmounts(@Param("tenant") String tenant, @Param("attempt") String attempt);
    /** 列出指定 Run 已保存结果的 attempt，恢复只进行结算，不重发外部调用。 */
    List<String> recoverableAttempts(@Param("tenant") String tenant, @Param("run") String run);
    /** 核对 Run 的明确收尾工具白名单；匹配返回 1，无匹配返回 null。 */
    Integer closingTarget(@Param("tenant") String tenant, @Param("run") String run, @Param("handle") String handle);
    /** 读取根的收尾活动时间保护；无根返回 null，不能默认为零绕过限制。 */
    Long closingSeconds(@Param("tenant") String tenant, @Param("root") String root);
    /** 按开始时间读取同根或单 Run 的确认活动区间，供业务层计算重叠区间并集。 */
    List<BudgetActivityRow> activitySpans(@Param("tenant") String tenant, @Param("scope") String scope, @Param("own") boolean own);
    /** 当前Run真实收尾模型区间，不包含普通业务模型或其他Run。 */
    List<BudgetActivityRow> closingModelSpans(@Param("tenant") String tenant, @Param("run") String run);
    /** 联合读取 Run 与根的冻结配置；无匹配返回 null，不拼接其他租户同名根。 */
    BudgetRegistrationRow registration(@Param("tenant") String tenant, @Param("run") String run);
    /** 读取指定租户根的当前阶段；无根返回 null，禁止推断为正常执行阶段。 */
    String phase(@Param("tenant") String tenant, @Param("root") String root);
    /** 核验显式租户预算根存在；写入所有权由所属 IMMEDIATE 事务持有。 */
    String lockRoot(@Param("tenant") String tenant, @Param("root") String root);
    /** 返回指定租户与作用域的全部资源余额；缺维度由业务层判定为损坏事实。 */
    List<BudgetBalanceRow> balances(@Param("tenant") String tenant, @Param("kind") String kind, @Param("scope") String scope);
    /** 读取 attempt 原始预留维度，供同标识请求的全资源一致性核验。 */
    List<BudgetAmountRow> amounts(@Param("tenant") String tenant, @Param("attempt") String attempt);
    /** 一次读取根、Run、阶段基线三组冻结余额；调用方校验每组维度完整性和数值。 */
    List<BudgetBalanceRow> registrationBalances(Map<String,Object> parameters);
    /** 读取请求中出现的维度，保留 reported 的 NULL 与明确零区别，禁止覆盖既有证据。 */
    List<BudgetAmountRow> readReportedAmounts(Map<String,Object> parameters);
    /** 只批量填充仍为空的 reported；影响行数必须等于本次缺报维度数，否则回滚。 */
    int fillReportedAmounts(Map<String,Object> parameters);
    /** 根锁内同步增加根、Run、阶段预留并扩展阶段上限；必须恰好更新三组完整维度。 */
    int reserveBalances(Map<String,Object> parameters);
    /** 为新 attempt 批量记录全部资源维度，包含零值；缺失维度不得被忽略。 */
    int insertAmounts(Map<String,Object> parameters);
    /** 对根、Run、阶段同步扣减原预留并增加已报告消耗；影响行数必须等于维度数的三倍。 */
    int settleBalances(Map<String,Object> parameters);
    /** 仅标记请求中尚未结算的维度；返回数量用于检测重复或缺失金额事实。 */
    int settleAmounts(Map<String,Object> parameters);
    /** 幂等推进并返回区间实际终点；同标识起点冲突返回 null，幂等较早确认不回退。 */
    BudgetActivityRow recordActivity(@Param("tenant") String tenant,@Param("run") String run,@Param("id") String id,
                                    @Param("start") Instant start,@Param("through") Instant through);
}
final class BudgetAttemptRow { public String runId,fingerprint,category,state; public boolean closing; }
final class BudgetAmountRow { public String dimension; public long reserved; public Long reported; }
final class BudgetBalanceRow { public String scopeKind,dimension,policyMode; public Long maximum; public long consumed,reserved; }
final class BudgetActivityRow { public Instant startedAt,confirmedThrough; }
final class BudgetRegistrationRow { public String rootId,role,sourceVersion,scenario,policyMode,rootPolicyMode; public Long runActiveSeconds,rootActiveSeconds; }
