package com.skillforge.core.chat;

import com.skillforge.core.chat.dto.request.ChatRequest;
import com.skillforge.core.chat.dto.response.ChatResult;
import com.skillforge.core.chat.contract.ChatEventSink;
import com.skillforge.core.chat.session.SessionView;

/**
 * Chat 到 Agent Runtime façade 的唯一提交边界。
 *
 * <p>core-chat 不依赖 Starter；宿主装配提供唯一的 Agent Runtime 实现。</p>
 */
public interface ChatRuntimeGateway {

    /**
     * 提交已经冻结身份与产品 Session 的对话输入。
     *
     * @param submission 入口请求、产品 Session、路由后的 Agent 与可选在线出口
     * @return 已接受的排队结果或宿主投影的完成结果
     */
    ChatResult submit(Submission submission);

    /**
     * 一次 Chat 提交所需的冻结产品事实。
     *
     * @param request 入口请求，已补齐 trace 与 end_user_id
     * @param session 产品 Session 行，sessionId 必须与 Runtime Session 对齐
     * @param agentId 显式或渠道路由后的 Agent 标识
     * @param endUserId 入口冻结的终端用户
     * @param sink 可选在线事件出口；Runtime 完成投影仍由宿主负责
     */
    record Submission(
            ChatRequest request,
            SessionView session,
            String agentId,
            Long endUserId,
            ChatEventSink sink) {
    }
}
