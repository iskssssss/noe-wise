package com.skillforge.core.shared.constant;

/**
 * 面向用户的固定文案常量（非 LLM 提示词）。
 *
 * <p>降级引导、防护终止、拒绝提示、截断附注等固定消息统一收口；
 * %s 占位符与调用点注入参数一一对应。
 */
public final class Messages {

    /** 工作流死循环防护终止引导。 */
    public static final String WORKFLOW_LOOP_GUIDE = "抱歉，未能继续处理您的请求。请补充必要信息后重试。";

    /** 对话缺目标拒绝文案。 */
    public static final String CHAT_NO_TARGET = "请先选择 Agent 开始对话";

    /** Agentic 工具重复调用熔断文案：%s 工具名。 */
    public static final String AGENTIC_REPEAT = "工具 %s 重复调用，已熔断";

    private Messages() {
    }
}
