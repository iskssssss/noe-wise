package com.skillforge.core.chat.constant;

/**
 * 对话接口常量。
 *
 * <p>定义 SSE 事件名、执行状态、消息角色与会话字段名，形成当前
 * chat / chat/sync / chat/replay 契约。
 */
public final class ChatConstants {

    /** SSE 事件：连接成功。 */
    public static final String EVENT_CONNECTED = "connected";

    /** SSE 事件：消息增量。 */
    public static final String EVENT_MESSAGE_DELTA = "message.delta";

    /** 执行结果标记：回复内容已流式推送（message.delta），避免重复推送完整内容。 */
    public static final String STREAMED = "streamed";

    /** SSE 事件：需要人工确认。 */
    public static final String EVENT_HITL_REQUIRED = "hitl.required";

    /** SSE 事件：卡片消息。 */
    public static final String EVENT_MESSAGE_CARD = "message.card";

    /** SSE 事件：卡片同 id 覆盖（updateFlag 原地更新，前端按 message_id 原位刷新不追加）。 */
    public static final String EVENT_MESSAGE_CARD_UPDATE = "message.card.update";

    /** SSE 事件：执行完成。 */
    public static final String EVENT_EXECUTION_COMPLETED = "execution.completed";

    /** 执行事件：已进入执行账本队列。 */
    public static final String EVENT_EXECUTION_QUEUED = "execution.queued";

    /** 执行事件：执行开始。 */
    public static final String EVENT_EXECUTION_STARTED = "execution.started";

    /** 执行事件：节点级进度。 */
    public static final String EVENT_EXECUTION_STEP = "execution.step";

    /** 执行事件：挂起等待交互。 */
    public static final String EVENT_EXECUTION_HITL = "execution.hitl";

    /** 执行事件：从挂起恢复。 */
    public static final String EVENT_EXECUTION_RESUMED = "execution.resumed";

    /** 执行事件：执行失败。 */
    public static final String EVENT_EXECUTION_FAILED = "execution.failed";

    /** 执行事件：循环保护终止。 */
    public static final String EVENT_EXECUTION_LOOP_TERMINATED = "execution.loop_terminated";

    /** 执行事件：取消 CAS 成功。 */
    public static final String EVENT_EXECUTION_CANCELLED = "execution.cancelled";

    /** SSE 事件：回放完成。 */
    public static final String EVENT_REPLAY_COMPLETED = "replay.completed";

    /** SSE 事件：主 Agent 规划完成，开始执行。 */
    public static final String EVENT_PLAN_STARTED = "plan.started";

    /** SSE 事件：单个步骤状态变更。 */
    public static final String EVENT_PLAN_STEP = "plan.step";

    /** SSE 事件：所有步骤完成，主 Agent 汇总。 */
    public static final String EVENT_PLAN_COMPLETED = "plan.completed";

    /** SSE 事件：流结束错误。 */
    public static final String EVENT_ERROR = "error";

    /** 新会话 ID 占位值。 */
    public static final String NEW_SESSION = "new";

    /** 执行状态：等待人工确认。 */
    public static final String STATUS_WAITING_HITL = "waiting_hitl";

    /** 会话有多个可恢复执行，需先选择候选；不代表任何 Execution 已恢复。 */
    public static final String STATUS_CLARIFICATION_REQUIRED = "clarification_required";

    /** 执行状态：恢复抢占中（原子认领转态，防并发恢复重复执行）。 */
    public static final String STATUS_RUNNING = "running";

    /** 执行状态：已完成。 */
    public static final String STATUS_COMPLETED = "completed";

    /** 执行状态：死循环防护终止（节点访问超限，非正常完成）。 */
    public static final String STATUS_LOOP_TERMINATED = "loop_terminated";

    /** 执行状态字段名。 */
    public static final String STATUS = "status";

    /** 回退提示消息字段名。 */
    public static final String MESSAGE = "message";
    /** HITL 待恢复状态字段名。 */
    public static final String PENDING = "pending";

    /** 消息角色：用户。 */
    public static final String ROLE_USER = "user";

    /** 消息角色：助手。 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** 会话 ID 字段名。 */
    public static final String SESSION_ID = "session_id";

    /** 消息列表字段名。 */
    public static final String MESSAGES = "messages";

    /** 消息内容字段名。 */
    public static final String CONTENT = "content";

    /** 消息角色字段名。 */
    public static final String ROLE = "role";

    /** 执行 ID 字段名。 */
    public static final String EXECUTION_ID = "execution_id";

    /** 人工确认卡片字段名。 */
    public static final String CARD = "card";

    /** 渠道 ID 元数据字段名。 */
    public static final String CHANNEL_ID = "channel_id";

    /** 回放消息数响应字段名。 */
    public static final String REPLAYED_COUNT = "replayed_count";

    /** 消息 ID 字段名。 */
    public static final String MESSAGE_ID = "message_id";

    /** 计划 ID 字段名。 */
    public static final String PLAN_ID = "plan_id";

    /** 计划摘要字段名。 */
    public static final String INTENT = "intent";

    /** 汇总字段名。 */
    public static final String SUMMARY = "summary";

    /** Agent 名称字段名。 */
    public static final String AGENT_NAME = "agent_name";

    /** 步骤数字段名。 */
    public static final String STEPS_COUNT = "steps_count";

    /** 执行模式字段名。 */
    public static final String MODE = "mode";

    /** 步骤 ID 字段名。 */
    public static final String STEP_ID = "step_id";

    /** 步骤序号字段名。 */
    public static final String STEP_INDEX = "step_index";

    /** 输出摘要字段名。 */
    public static final String OUTPUT_SUMMARY = "output_summary";

    /** Agent ID 字段名。 */
    public static final String AGENT_ID = "agent_id";

    private ChatConstants() {
    }
}
