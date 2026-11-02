package com.skillforge.core.chat.constant;

/**
 * 转人工工单常量。
 *
 * <p>定义工单状态、默认值、业务 ID 前缀与广播事件类型，与
 * {@code human_handoff_tickets} 列取值及坐席 WebSocket 契约保持一致。
 */
public final class HandoffConstants {

    /** 工单状态：待分配。 */
    public static final String STATUS_PENDING = "pending";

    /** 工单状态：已接单。 */
    public static final String STATUS_ASSIGNED = "assigned";

    /** 工单状态：已解决。 */
    public static final String STATUS_RESOLVED = "resolved";

    /** 默认转人工原因。 */
    public static final String DEFAULT_REASON = "user_request";

    /** 坐席回切 AI 事件类型。 */
    public static final String EVENT_AI_RESUME = "ai.resume";

    /** 会话 ID 字段名。 */
    public static final String SESSION_ID = "session_id";

    /** 工单 ID 字段名。 */
    public static final String TICKET_ID = "ticket_id";
    /** 广播事件类型字段名。 */
    public static final String EVENT_TYPE = "type";

    private HandoffConstants() {
    }
}
