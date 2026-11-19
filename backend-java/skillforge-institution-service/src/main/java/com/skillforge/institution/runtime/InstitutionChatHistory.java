package com.skillforge.institution.runtime;

import com.skillforge.core.chat.ChatRuntimeHistory;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.team.TeamPlanHistoryReader;
import com.skillforge.institution.team.TeamWorkService;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.session.SessionCollaboration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 产品历史组合执行公开记录、规划选择和消息页对应计划，各事实保持原有归属。 */
@Primary
@Component
public final class InstitutionChatHistory implements ChatRuntimeHistory {
    private final AgentRuntimeExecutionProjection executions;
    private final TeamPlanHistoryReader plans;
    private final SessionCollaboration collaboration;

    public InstitutionChatHistory(AgentRuntimeExecutionProjection executions, TeamPlanHistoryReader plans,
            SessionLogPort log) {
        this.executions = executions; this.plans = plans;
        collaboration = new SessionCollaboration(log);
    }

    @Override public ChatHistoryResponse read(SessionView session) {
        var owner = TeamWorkService.owner(session);
        var history = executions.read(session);
        var selected = collaboration.read(owner);
        return new ChatHistoryResponse(history.sessionId(), history.messages(), history.pendingInteractions(),
                history.activities(), history.processes(),
                new ChatHistoryResponse.Collaboration(selected.active(), selected.pending(), selected.selectedActive()),
                history.hasMore(), history.nextBeforeMessageId(), history.eventCursor(), history.historyRetention(),
                history.workPlans(), history.closed());
    }

    @Override public Optional<ChatMessageRecord> message(SessionView session, String messageId) {
        return executions.message(session, messageId);
    }

    @Override public List<Map<String, Object>> workPlans(SessionView session, Set<String> inputIds) {
        return plans.read(session, inputIds);
    }
}
