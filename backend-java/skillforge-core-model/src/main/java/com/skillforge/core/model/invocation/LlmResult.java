package com.skillforge.core.model.invocation;

import com.skillforge.core.billing.metering.LlmUsage;

/**
 * LLM 调用结果。
 *
 * <p>包含助手回复全文、实际使用的模型名与 token 用量；
 * 计费流水记录取 {@link #usage()}，排查问题取 {@link #model()}。
 *
 * @param content 助手回复全文；无 choices 时为 null
 * @param model   实际调用的模型名（配置真实值）
 * @param usage   token 用量；服务商未返回时为空用量
 */
public record LlmResult(String content, String model, LlmUsage usage) {
}
