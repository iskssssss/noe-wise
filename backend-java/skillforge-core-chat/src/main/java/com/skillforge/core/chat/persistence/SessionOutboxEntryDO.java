package com.skillforge.core.chat.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.skillforge.core.shared.config.JsonbTypeHandler;
import java.time.Instant;
import java.util.Map;
import lombok.Data;

/** Session Outbox 持久输出事实；消费者按 sessionSequence 有序且按 eventId 幂等。 */
@Data
@TableName(value = "nw_chat_session_outbox", autoResultMap = true)
public class SessionOutboxEntryDO {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long tenantId;
    private String sessionId;
    private String runtimeRunId;
    private String eventId;
    private String inputId;
    private String rootInputId;
    private String traceId;
    private String sourceEventId;
    private String causedByEventId;
    private Long sessionSequence;
    private String eventType;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private Map<String, Object> payload;

    private Instant createdAt;
    private Instant updatedAt;

    /** 最近消息查询派生的首次Outbox序号；不持久化、不改变当前快照的真实sessionSequence。 */
    @TableField(exist = false)
    private Long firstMessageSequence;

    /** 最近消息查询派生的首次公开时刻；后续delta版本不移动消息历史位置。 */
    @TableField(exist = false)
    private Instant firstMessageCreatedAt;
}
