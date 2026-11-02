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
 * 会话实体，对应数据库表 {@code sessions}。
 *
 * <p>保存会话元数据：个人用户/渠道或群绑定、Agent/Team/Group 目标、
 * 消息计数、SSE 游标与过期时间；会话全量消息见 {@link ChatMessage}。
 */
@Data
@TableName(value = "nw_chat_sessions", autoResultMap = true)
public class SessionDO {

    /** 主键雪花 ID。 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 会话唯一业务标识，形如 sess_xxx。 */
    private String sessionId;

    /** 机构后台创建操作的请求身份；仅用于只读核对。 */
    private String createRequestId;

    /** 所属租户 ID。 */
    private Long tenantId;

    /** 个人会话归属 C 端用户 ID；群会话为空。 */
    private Long endUserId;

    /** 群会话唯一归属与目标；非空时没有 owner EndUser、Agent 或 Team。 */
    private Long groupBindingId;

    /** 渠道 ID，可空。 */
    private Long channelId;

    /** 三方透传实体标识，可空。 */
    private String scopeId;

    /** 三方透传类型标识，可空。 */
    private String scopeType;

    /** 可信入口来源：external/native/system，Session首次创建时固定。 */
    private String originKind;
    /** 已认证并持久保存的发起主体；系统任务使用真实任务责任引用。 */
    private String actorRef;
    /** 费用与业务责任主体；不从模型上下文补造。 */
    private String responsibilityRef;

    /** 认证服务主体冻结的集成标识；不由消息上下文覆盖。 */
    private String integrationId;

    /** 固定Tenant/integration内已声明并授权的业务范围标识。 */
    private String businessScopeId;


    /** 个人会话目标 Agent ID；个人与 team_id 二选一，群会话为空。 */
    private Long agentId;

    /** 个人会话目标 Team ID；个人与 agent_id 二选一，群会话为空。 */
    private String teamId;


    /** 当前活跃执行 ID（HITL 恢复用），可空。 */
    private String activeExecutionId;

    /** 已写入的会话消息条数。 */
    private Integer messageCount;

    /** SSE Last-Event-ID 游标。 */
    private Long lastEventId;

    /** 会话冷摘要，可空；画像摘要任务写入。 */
    private String summaryText;

    /** 会话附加元数据 JSON。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> metadata;

    /** 会话过期时间，可空。 */
    private Instant expiredAt;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;
}
