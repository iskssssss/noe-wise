package com.skillforge.core.foundation.operations.persistence;

import com.skillforge.core.foundation.operations.OperationsContracts.AuditQuery;
import com.skillforge.core.foundation.operations.AuditReadBatch;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** operations SQL边界；所有查询显式绑定作用域，数据库RLS提供第二层隔离。 */
@Mapper
public interface OperationsMapper {
    /** 当前宿主的只读连接探测；SQL超时由对应XML固定为2秒。 */
    Integer probeDatabase();
    /** 查询指定范围审计，platform只读无租户事件。 */
    List<OperationsRows.Audit> audits(@Param("scope") String scope, @Param("query") AuditQuery query,
            @Param("limit") int limit, @Param("offset") long offset);
    /** 目录只读取实际展示列，不加载完整审计 payload。 */
    List<AuditSummaryRow> auditSummaries(@Param("scope") String scope, @Param("query") AuditQuery query,
            @Param("limit") int limit, @Param("offset") long offset);
    /** 导出只读固定 CSV 列；保留溢出探测行，不加载 payload。 */
    List<AuditExportRow> auditExportRows(@Param("scope") String scope, @Param("query") AuditQuery query,
            @Param("limit") int limit);
    /** 与列表相同过滤条件计数。 */
    long auditCount(@Param("scope") String scope, @Param("query") AuditQuery query);
    /** 原子受理一分钟内同主体/授权版本/过滤的首个读取；重复返回0，不更新不可变审计。 */
    int insertReadBatch(AuditReadBatch batch);
    /** 锁定具体审计事实以串行保护登记和清理。 */
    Long lockAudit(@Param("scope") String scope, @Param("id") long id);
    /** 查询当前策略。 */
    OperationsRows.Policy policy(@Param("scope") String scope, @Param("resource") String resource);
    /** 初次创建策略，竞争时不覆盖。 */
    int createPolicy(OperationsRows.Policy policy);
    /** 按版本替换策略。 */
    int updatePolicy(@Param("scope") String scope, @Param("days") int days, @Param("enabled") boolean enabled, @Param("version") long version);
    /** 查找到期候选，包含当前保护状态，最多1000条。 */
    List<Long> candidates(@Param("scope") String scope, @Param("cutoff") Instant cutoff, @Param("limit") int limit);
    /** 统计有活动保护的候选。 */
    long protectedCount(@Param("scope") String scope, @Param("ids") List<Long> ids);
    /** 持久化冻结预览。 */
    int insertRun(OperationsRows.Run run);
    /** 锁定回执并约束归属。 */
    OperationsRows.Run lockRun(@Param("scope") String scope, @Param("id") long id);
    /** 最近清理回执。 */
    List<OperationsRows.Run> runs(@Param("scope") String scope);
    /** 删除仅限冻结候选；同事务重查保护。 */
    int deleteAudits(@Param("scope") String scope, @Param("ids") List<Long> ids, @Param("cutoff") Instant cutoff);
    /** 提交终态回执，ready只能转一次。 */
    int finishRun(@Param("scope") String scope, @Param("id") long id, @Param("status") String status,
            @Param("deleted") long deleted, @Param("protectedCount") long protectedCount, @Param("failure") String failure);
    /** 登记具体事实保护。 */
    int insertHold(OperationsRows.Hold hold);
    /** 当前作用域保护清单。 */
    List<OperationsRows.Hold> holds(@Param("scope") String scope);
    /** 解除同作用域保护，事实本身保持不变。 */
    int releaseHold(@Param("scope") String scope, @Param("id") long id);
}
