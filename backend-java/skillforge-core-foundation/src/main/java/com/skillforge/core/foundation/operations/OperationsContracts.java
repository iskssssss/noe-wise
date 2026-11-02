package com.skillforge.core.foundation.operations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** operations 公开应用契约；租户身份只来自 FoundationAccess，不接受外部覆盖。 */
public final class OperationsContracts {
    private OperationsContracts() { }

    /**
     * 审计查询条件，空条件表示当前授权作用域。
     * @param action 精确动作编码
     * @param actorId 操作者标识
     * @param category 安全分类
     * @param outcome success/failure/denied
     * @param resourceType 资源类型
     * @param traceId 链路标识
     * @param from 包含的开始时间
     * @param to 不包含的结束时间
     * @param page 从1开始的页码
     * @param pageSize 每页1至100条
     */
    public record AuditQuery(String action, String actorId, String category, String outcome,
            String resourceType, String traceId, Instant from, Instant to, Integer page, Integer pageSize) { }

    /**
     * 脱敏审计事实；ID 均按字符串传输。
     * @param id 审计事实ID
     * @param tenantId 机构归属，平台或未归因认证失败为空
     * @param actorId 操作者，未认证时可为空
     * @param action 稳定动作编码
     * @param category 安全分类
     * @param outcome 实际结果
     * @param resourceType 资源种类
     * @param resourceId 资源标识
     * @param message 受控描述
     * @param traceId 链路标识
     * @param payload 过滤秘密后的元数据
     * @param createdAt 发生时间
     */
    public record AuditEvent(String id, String tenantId, String actorId, String action, String category,
            String outcome, String resourceType, String resourceId, String message, String traceId,
            Map<String, Object> payload, Instant createdAt) { }

    /** @param total 精确总量 @param page 页码 @param pageSize 每页条数 @param items 当前页脱敏事实 */
    public record AuditPage(long total, int page, int pageSize, List<AuditEvent> items) {
        /** 防止消费者更改查询结果集合。 */
        public AuditPage { items = List.copyOf(items); }
    }

    /** 安全审计目录投影，仅包含列表和调查保护实际使用的字段。 */
    public record AuditSummary(String id, String actorId, String action, String category, String outcome,
            String resourceType, String message, String traceId, Instant createdAt) { }

    /** 当前授权范围的精确分页目录。 */
    public record AuditSummaryPage(long total, int page, int pageSize, List<AuditSummary> items) {
        public AuditSummaryPage { items = List.copyOf(items); }
    }

    /** @param content UTF-8 CSV字节 @param filename 安全文件名 @param rows 已输出行数 */
    public record AuditExport(byte[] content, String filename, int rows) {
        /** 保护导出内容的所有权。 */
        public AuditExport { content = content.clone(); }
        /** 返回独立字节副本。 */
        @Override public byte[] content() { return content.clone(); }
    }

    /**
     * 当前生效保留策略，完整替换，不支持部分字段隐式继承。
     * @param resource 当前仅audit；其他领域通过其公开清理入口执行
     * @param retentionDays 保留天数，30至3650
     * @param enabled 是否允许新清理预览及执行
     * @param version 当前CAS版本
     */
    public record RetentionPolicy(String resource, int retentionDays, boolean enabled, long version) { }

    /** @param id 清理回执ID @param resource 资源种类 @param cutoff 冻结截止时间 @param candidates 候选量 @param protectedCount 引用保护量 @param deletedCount 实际删除量 @param status ready/succeeded/failed/expired @param expiresAt 预览授权期限 @param createdAt 创建时间 @param completedAt 结束时间 @param failureCode 脱敏失败编码 */
    public record CleanupRun(String id, String resource, Instant cutoff, long candidates, long protectedCount,
            long deletedCount, String status, Instant expiresAt, Instant createdAt, Instant completedAt, String failureCode) { }

    /** @param id 保护记录ID @param auditId 被保护事实ID @param reason 调查/法务保留依据 @param expiresAt 保护到期时间，空表示显式解除前永久保护 @param releasedAt 显式解除时间 */
    public record AuditHold(String id, String auditId, String reason, Instant expiresAt, Instant releasedAt) { }

    /** @param name 真实探测组件 @param status up/down/unavailable @param durationMillis 探测耗时毫秒 @param observedAt 采样时间 @param detail 公开诊断，无凭据/连接串 */
    public record Probe(String name, String status, long durationMillis, Instant observedAt, String detail) { }

    /** @param observedAt 采样时间 @param uptimeMillis JVM已运行毫秒 @param heapUsedBytes JVM堆使用字节 @param heapMaxBytes JVM堆上限字节 @param threads 线程数 @param databasePoolActive 当前连接池活跃连接，无法采集为空 @param databasePoolIdle 当前空闲连接，无法采集为空 @param probes 真实依赖探测 */
    public record RuntimeStatus(Instant observedAt, long uptimeMillis, long heapUsedBytes, long heapMaxBytes,
            int threads, Integer databasePoolActive, Integer databasePoolIdle, List<Probe> probes) { }
}
