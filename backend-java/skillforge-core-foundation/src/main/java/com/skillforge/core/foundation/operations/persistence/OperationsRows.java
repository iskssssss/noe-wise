package com.skillforge.core.foundation.operations.persistence;

import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** operations 内部查询行，禁止跨域消费者直接依赖。 */
public final class OperationsRows {
    private OperationsRows() { }

    /** 追加审计事实查询投影。 */
    @Data public static class Audit {
        /** 主键。 */ private Long id;
        /** 机构归属，平台为空。 */ private Long tenantId;
        /** 操作者。 */ private String actorId;
        /** 动作。 */ private String action;
        /** 分类。 */ private String category;
        /** 结果。 */ private String outcome;
        /** 资源类型。 */ private String resourceType;
        /** 资源ID。 */ private String resourceId;
        /** 描述。 */ private String message;
        /** 追踪ID。 */ private String traceId;
        /** 脱敏元数据。 */ private Map<String, Object> payload;
        /** 发生时间。 */ private Instant createdAt;
    }

    /** 保留策略权威行，version控制并发覆盖。 */
    @Data public static class Policy {
        /** 平台或精确机构作用域。 */ private String scope;
        /** 资源类型。 */ private String resource;
        /** 保留天数。 */ private int retentionDays;
        /** 是否启用。 */ private boolean enabled;
        /** 更新版本。 */ private long version;
    }

    /** 冻结的清理预览与执行回执，候选ID持久化后不重新扩大范围。 */
    @Data public static class Run {
        /** 主键。 */ private Long id;
        /** 归属。 */ private String scope;
        /** 请求主体。 */ private String actorId;
        /** 资源。 */ private String resource;
        /** 冻结策略版本。 */ private long policyVersion;
        /** 截止时间。 */ private Instant cutoff;
        /** JSON数字数组，仅由服务构建。 */ private String candidateIds;
        /** 候选总量。 */ private long candidates;
        /** 保护总量。 */ private long protectedCount;
        /** 实际删除数。 */ private long deletedCount;
        /** 执行状态。 */ private String status;
        /** 授权失效时间。 */ private Instant expiresAt;
        /** 创建时间。 */ private Instant createdAt;
        /** 完成时间。 */ private Instant completedAt;
        /** 固定失败编码。 */ private String failureCode;
    }

    /** 调查/法务对具体审计事实的引用保护。 */
    @Data public static class Hold {
        /** 保护ID。 */ private Long id;
        /** 归属。 */ private String scope;
        /** 审计事实。 */ private Long auditId;
        /** 原因。 */ private String reason;
        /** 可选到期。 */ private Instant expiresAt;
        /** 显式解除。 */ private Instant releasedAt;
    }
}
