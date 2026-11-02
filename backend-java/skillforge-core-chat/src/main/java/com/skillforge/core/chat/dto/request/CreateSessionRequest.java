package com.skillforge.core.chat.dto.request;

/**
 * 创建会话请求 DTO。
 *
 * <p>供外部集成方（如外部业务系统集成）主动为终端用户创建空会话（先建会话再推送
 * 主动消息的场景）：会话目标为 agent_id（数字 ID 或 agent_key）或 team_id，二者互斥，
 * 缺失时返回 422。
 *
 * @param agentId    Agent 标识（数字 ID 或 agent_key），可选
 * @param teamId     Team 标识，可选
 * @param scopeId    三方透传实体 ID，可选
 * @param scopeType  三方透传类型，可选
 * @param endUserId  入口终端用户 ID，必填；服务端不再创建或选择默认用户
 */
public record CreateSessionRequest(
        String agentId,
        String teamId,
        String scopeId,
        String scopeType,
        Long endUserId) {

    /** 保持既有四参数 Java 调用的构造方式。 */
    public CreateSessionRequest(
            String agentId, String scopeId, String scopeType, Long endUserId) {
        this(agentId, null, scopeId, scopeType, endUserId);
    }
}
