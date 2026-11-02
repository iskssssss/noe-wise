package com.skillforge.core.chat;

/** Chat 查询工作室当前已发布主助手的边界。 */
public interface ChatTeamGateway {

    /** 返回工作室当前发布版本的主助手；每个新 Run 由宿主再次冻结精确版本。 */
    String primaryAgentId(String tenantId, String teamId);

    /** 校验团队在当前租户可用于新对话。 */
    default void requireAvailable(String tenantId, String teamId) {
        primaryAgentId(tenantId, teamId);
    }
}
