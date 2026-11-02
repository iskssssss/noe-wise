package com.skillforge.core.billing.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.skillforge.core.billing.metering.CostEventDO;
import com.skillforge.core.billing.dto.billing.UsageAggregateRow;
import com.skillforge.core.billing.dto.billing.UsageByTypeRow;
import com.skillforge.core.billing.dto.billing.UsageGroupedRow;
import com.skillforge.core.billing.application.BillingTraceFacts.EffectiveCost;
import com.skillforge.core.billing.application.BillingTraceFacts.ReadScope;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 用量事件 MyBatis-Plus 映射接口。
 *
 * <p>SQL 均写在 {@code CostEventMapper.xml}，接口只留方法签名与参数注解；
 * 简单查询走 BaseMapper（selectCount/selectList/selectOne/page）。
 */
public interface CostEventMapper extends BaseMapper<CostEventDO> {

    /** 全局计费扫描仅统计明确机构与业务月份的请求根事实，不开放通用查询窗口。 */
    long countRequestsForBilling(@Param("tenantId") long tenantId, @Param("from") Instant from, @Param("to") Instant to);

    /** 供应商报告只追加；冲突不破坏调用方事务，随后由服务核对同键内容。 */
    int insertUsageReport(@Param("event") CostEventDO event);

    /** 读取原模型Attempt已持久登记的派发时间；缺失时保持未知，不用当前时间补造。 */
    Instant modelAttemptStartedAt(@Param("tenantId") Long tenantId, @Param("attemptId") String attemptId);

    /** 仅序列化同租户 attempt 的报告，不更新不可变源账本；必须在报告事务中调用。 */
    void lockUsageAttempt(@Param("tenantId") Long tenantId, @Param("attemptId") String attemptId);

    /** 按修订逐字段读取最新已报告值；无事实返回 null，未知字段保持 null。 */
    CostEventDO effectiveUsageReport(@Param("tenantId") Long tenantId, @Param("attemptId") String attemptId);

    /** 只合并原 attempt 的供应商报告，排除终态计量。 */
    CostEventDO effectiveProviderReports(@Param("tenantId") Long tenantId, @Param("attemptId") String attemptId);

    /** 原媒体操作的首次冻结来源；历史没有该事实时返回空，不能借查询补造。 */
    CostEventDO mediaStarted(@Param("tenantId") Long tenantId, @Param("invocationId") String invocationId);

    /** 同一媒体 attempt 的最新完整观察，不累计查询或流式报告。 */
    CostEventDO latestMediaObservation(@Param("tenantId") Long tenantId, @Param("attemptId") String attemptId);

    /** 原事实的对账追加在事务级锁内串行化，防止同金额不同外部键并发重复追加。 */
    void lockReconciliation(@Param("tenantId") Long tenantId, @Param("originalEventId") Long originalEventId);

    /** 返回原事实最新权威成本确认；没有确认时返回 null，不包含增减 adjustment。 */
    CostEventDO latestReconciliation(@Param("tenantId") Long tenantId, @Param("originalEventId") Long originalEventId);

    /**
     * 月度聚合（不含 request 根事件的 token/金额；calls=request 事件数）。
     *
     * @param tenantId 租户 ID
     * @param from     起始时间（当月第一天，含）
     * @return 聚合行
     */
    UsageAggregateRow sumMonthly(@Param("tenantId") Long tenantId, @Param("from") Instant from);

    /**
     * 按事件类型分组聚合（月度）。
     *
     * @param tenantId 租户 ID
     * @param from     起始时间（当月第一天，含）
     * @return 分组行列表
     */
    List<UsageByTypeRow> groupByType(@Param("tenantId") Long tenantId, @Param("from") Instant from);

    /**
     * 按维度分组聚合（月度，时间窗自定义）。
     *
     * @param tenantId 租户 ID
     * @param from     起始时间（含）
     * @param dimension 维度键：agent / end_user / channel / model / event_type
     * @return 分组行列表
     */
    List<UsageGroupedRow> groupByDimension(
            @Param("tenantId") Long tenantId,
            @Param("from") Instant from,
            @Param("dimension") String dimension);

    /**
     * 一次请求全链路聚合（request_id 下全部事件的调用次数/token/金额）。
     *
     * @param tenantId  租户 ID
     * @param requestId 请求根 ID
     * @return 聚合行；无事件返回全 0 行
     */
    UsageAggregateRow sumByRequest(@Param("tenantId") Long tenantId, @Param("requestId") String requestId);

    /** 按持久 rootInputId 读取一次问题因果链上的全部用量事件。 */
    List<CostEventDO> listByRootInputId(
            @Param("tenantId") Long tenantId, @Param("rootInputId") String rootInputId);

    /** 会话轨迹读取有效模型计量，每个 attempt 仅返回一条合并报告。 */
    List<CostEventDO> listEffectiveBySessionId(
            @Param("tenantId") Long tenantId, @Param("sessionId") String sessionId);

    /** 只读有效成本视图，调用方必须提供非空且至多固定批量的精确来源元组。 */
    List<EffectiveCost> listEffectiveCostsByScopes(
            @Param("tenantId") Long tenantId, @Param("scopes") List<ReadScope> scopes);
    java.util.List<com.skillforge.core.billing.dto.billing.UsageAggregateRow> sumByRequests(
        @org.apache.ibatis.annotations.Param("tenantId") Long tenantId,
        @org.apache.ibatis.annotations.Param("requestIds") java.util.List<String> requestIds);
}
