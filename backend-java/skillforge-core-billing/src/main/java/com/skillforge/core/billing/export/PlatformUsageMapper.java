package com.skillforge.core.billing.export;

import com.skillforge.core.billing.metering.CostEventDO;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 原子用量与部署级提交后发布流的 SQL 入口；查询必须传当前已授权机构集合。 */
@Mapper
public interface PlatformUsageMapper {
    /** 精确读取技术 Run 的原请求者与群目标，不查询当前群成员或服务Agent。 */
    PlatformUsageExecutionSource executionSource(@Param("tenantId") String tenantId, @Param("runId") String runId);
    /** 系统用途 Run 必须已持久受理，记忆任务需在第一次外调之前绑定该引用。 */
    PlatformUsageSystemSource systemSource(@Param("tenantId") String tenantId, @Param("systemRunId") long systemRunId);

    /** 读取精确 Run 实际执行者的冻结定义与授权，不访问当前定义。 */
    PlatformUsageFrozenSource frozenSource(@Param("tenantId") String tenantId,@Param("runId") String runId,@Param("agentId") Long agentId);
    /** 从同一 Run 的原受理授权快照读取有序链。 */
    List<PlatformUsageGrantHopRow> frozenGrantHops(@Param("tenantId") String tenantId,@Param("runId") String runId,@Param("agentId") Long agentId);
    /** 与原用量同事务插入链子事实，不允许后续更新。 */
    int insertGrantHops(@Param("event") CostEventDO event);
    /** 单页批量获取原链；调用者不传空页，SQL仍对空范围失败关闭。 */
    List<PlatformUsageGrantHopRow> grantHops(@Param("tenants") Set<Long> tenants,@Param("usageIds") List<Long> usageIds);
    /** 源事实同事务持久登记，唯一源引用保护重投幂等。 */
    int insertPending(@Param("event") CostEventDO event);
    /** 固定函数只处理有界 pending 并返回新尾，不返回跨租户正文。 */
    long publishCommitted(@Param("limit") int limit);
    /** 当前发布流世代；重建流后旧 cursor 明确冲突。 */
    String streamRevision();
    /** 已提交尾位置，不采用源主键或数据库 sequence。 */
    long streamTail();
    /** 按扫描窗口和当前机构集合过滤正文；空匹配窗口仍由调用者推进扫描位置。 */
    List<PlatformUsageRow> list(@Param("tenants") Set<Long> tenants,@Param("integrationId") String integrationId,
            @Param("query") PlatformUsageQuery query,@Param("cursor") long cursor,@Param("through") long through,
            @Param("limit") int limit);
    /** 当前授权过滤内仍有待发布事实时，空扫描页也不能宣称已追到末尾。 */
    boolean hasPending(@Param("tenants") Set<Long> tenants,@Param("integrationId") String integrationId,@Param("query") PlatformUsageQuery query);
    /** 按实际 attempt 报告/确认/调整语义计算授权范围内有效汇总。 */
    List<PlatformUsageSummaryRow> summarize(@Param("tenants") Set<Long> tenants,@Param("integrationId") String integrationId,
            @Param("query") PlatformUsageQuery query);
}
