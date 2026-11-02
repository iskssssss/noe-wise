package com.skillforge.core.chat.dto.request;

import java.util.Map;
import java.util.List;
import com.skillforge.core.chat.contract.ChatInputReference;
import com.skillforge.core.chat.contract.ChatContent;

/**
 * 对话请求 DTO。
 *
 * @param content          有序图文消息，图片仅携带媒体选择器
 * @param sessionId        会话 ID；新会话可传 new
 * @param agentId          Agent ID（可选）
 * @param teamId           Team ID（可选，与 agent_id 互斥）
 * @param workflowId       工作流业务 ID（可选）
 * @param scopeId          三方透传实体 ID（可选）
 * @param scopeType        三方透传类型（可选）
 * @param locale           语言区域（可选）
 * @param clientMessageId  客户端消息幂等 ID（可选）
 * @param hitlResponse     HITL 确认响应（可选）
 * @param context          业务上下文：必须包含入口传入的正数 {@code end_user_id}；其余键写入
 *                         工作流 state 的 {@code ctx_*} 前缀变量，供流程节点插值引用（如
 *                         {@code ${state.ctx_order_id}}）。服务端不从 Session、租户默认用户或
 *                         外部身份引用补齐该字段。
 */
public record ChatRequest(
        List<ChatContent> content,
        String sessionId,
        String agentId,
        String teamId,
        String workflowId,
        String scopeId,
        String scopeType,
        String locale,
        String clientMessageId,
        Map<String, Object> hitlResponse,
        Map<String, Object> context,
        List<ChatInputReference> references) {

    /** 客户端输入身份同时作为公开消息归档 ID，容量与 message_id 存储契约一致。 */
    public static final int MAX_CLIENT_MESSAGE_ID_LENGTH = 64;

    public ChatRequest {
        if (clientMessageId != null && clientMessageId.length() > MAX_CLIENT_MESSAGE_ID_LENGTH)
            throw new IllegalArgumentException("client_message_id_too_long");
        content = content == null ? List.of() : List.copyOf(content);
        if (content.size() > 128) throw new IllegalArgumentException("chat_content_too_large");
        references = references == null ? List.of() : List.copyOf(references);
        if (references.size() > ChatInputReference.MAX_REFERENCES || references.stream().distinct().count() != references.size())
            throw new IllegalArgumentException(ChatInputReference.INVALID_REFERENCE);
    }

    /** 文字仅是内容投影；请求与历史中的有序内容以 content 为准。 */
    public String message() { return ChatContent.textValue(content); }

    public ChatRequest(String message,String sessionId,String agentId,String teamId,String workflowId,
            String scopeId,String scopeType,String locale,String clientMessageId,
            Map<String,Object> hitlResponse,Map<String,Object> context,List<ChatInputReference> references) {
        this(ChatContent.text(message),sessionId,agentId,teamId,workflowId,scopeId,scopeType,locale,
                clientMessageId,hitlResponse,context,references);
    }

    /** 不选择公开材料时的普通对话入口。 */
    public ChatRequest(String message,String sessionId,String agentId,String teamId,String workflowId,
            String scopeId,String scopeType,String locale,String clientMessageId,
            Map<String,Object> hitlResponse,Map<String,Object> context) {
        this(message,sessionId,agentId,teamId,workflowId,scopeId,scopeType,locale,clientMessageId,hitlResponse,context,List.of());
    }

}
