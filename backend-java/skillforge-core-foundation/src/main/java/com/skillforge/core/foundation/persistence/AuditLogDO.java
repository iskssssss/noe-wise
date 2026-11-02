package com.skillforge.core.foundation.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/**
 * 审计日志实体，对应数据库表 {@code audit_logs}。
 *
 * <p>仅追加写入，记录关键业务操作与操作者信息；写入前必须对 PII 进行脱敏，
 * 用于合规审计与问题追溯。
 */
@Data
@TableName(value = "nw_audit_logs", autoResultMap = true)
public class AuditLogDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 操作者 ID（成员或渠道身份）。 */
    private String actorId;

    /** 操作类型，如 auth.login / tenant.register。 */
    private String action;

    /** 被操作资源类型，如 tenant / tenant_member。 */
    private String resourceType;

    /** 被操作资源 ID。 */
    private String resourceId;

    /** 可读的操作描述。 */
    private String message;

    /** 链路追踪 ID，用于关联日志与请求。 */
    private String traceId;

    /** 操作附加数据 JSON，写入前已脱敏；通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> payload;

    /** 安全事件分类：authentication/authorization/sensitive/export/operation。 */
    private String category;

    /** 已观察到的操作结果：success/failure/denied。 */
    private String outcome;

    /** 创建时间。 */
    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
