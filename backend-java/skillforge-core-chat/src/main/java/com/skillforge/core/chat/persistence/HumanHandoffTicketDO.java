package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/**
 * 转人工工单实体，对应数据库表 {@code human_handoff_tickets}。
 *
 * <p>记录用户转人工请求、会话与执行关联、坐席分配与解决状态，
 * 支撑 B 端坐席工作台闭环。
 */
@Data
@TableName(value = "nw_human_handoff_tickets", autoResultMap = true)
public class HumanHandoffTicketDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 工单业务 ID。 */
    private String ticketId;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** C 端用户 ID。 */
    private Long endUserId;

    /** 关联会话 ID。 */
    private String sessionId;

    /** 关联工作流执行 ID。 */
    private String executionId;

    /** 转人工原因。 */
    private String reason;

    /** 会话摘要。 */
    private String summary;

    /** 分配坐席 ID。 */
    private Long assignedTo;

    /** 工单状态：pending / assigned / resolved / closed。 */
    private String status;

    /** 解决备注。 */
    private String resolutionNote;

    /** 创建时间。 */
    private Instant createdAt;

    /** 分配时间。 */
    private Instant assignedAt;

    /** 解决时间。 */
    private Instant resolvedAt;

    /** 关闭时间。 */
    private Instant closedAt;

    /** 优先级。 */
    private Integer priority;

    /** 上下文 JSON，通过 Jackson 类型处理器读写。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> context;

    /** 更新时间。 */
    private Instant updatedAt;

    /** 工单命令 CAS 修订，不使用会话或执行修订代替。 */
    private Long revision;

    /** 创建命令及其受认证主体和请求指纹；历史工单可为空。 */
    private String createCommandId;
    private Long createdBy;
    private String createFingerprint;
}
