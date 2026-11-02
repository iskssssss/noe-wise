package com.skillforge.institution;

import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.session.SessionView;

/** 将测试自己写入的持久化夹具映射为跨模块只读会话事实。 */
public final class TestSessions {
    private TestSessions() { }
    public static SessionView view(SessionDO row) {
        return new SessionView(row.getSessionId(), row.getTenantId(), row.getEndUserId(),
                row.getGroupBindingId(), row.getChannelId(), row.getScopeId(), row.getScopeType(),
                row.getOriginKind(), row.getActorRef(), row.getResponsibilityRef(), row.getIntegrationId(),
                row.getBusinessScopeId(), row.getAgentId(), row.getTeamId(), row.getMessageCount(),
                row.getSummaryText(), row.getMetadata(), row.getCreatedAt(), row.getUpdatedAt());
    }
}
