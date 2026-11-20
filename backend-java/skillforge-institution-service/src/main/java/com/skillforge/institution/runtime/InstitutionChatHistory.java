package com.skillforge.institution.runtime;

import com.skillforge.core.chat.ChatRuntimeHistory;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.dto.response.SessionItemResponse;
import com.skillforge.core.chat.session.SessionDirectoryItem;
import com.skillforge.core.chat.message.ChatMessageRecord;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.team.TeamPlanHistoryReader;
import com.skillforge.institution.team.TeamWorkService;
import com.skillforge.runtime.api.port.SessionLogPort;
import com.skillforge.runtime.session.SessionCollaboration;
import com.skillforge.runtime.session.SessionGoal;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.SessionOwner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final SessionGoal goals;
    private static final int TITLE_LENGTH = 80;

    public InstitutionChatHistory(AgentRuntimeExecutionProjection executions, TeamPlanHistoryReader plans,
            SessionLogPort log) {
        this.executions = executions; this.plans = plans;
        collaboration = new SessionCollaboration(log);
        goals = new SessionGoal(log);
    }

    @Override public List<SessionItemResponse> directory(String tenantId, List<SessionDirectoryItem> sessions) {
        record Row(SessionItemResponse response, Instant latestActivity) { }
        var rows = new ArrayList<Row>();
        for (var session : sessions) {
            var owner = SessionOwner.user(new SessionRef(new TenantId(tenantId), session.sessionId()),
                    session.teamId() == null ? SessionTarget.agent(new AgentId(session.agentId().toString()))
                            : SessionTarget.team(session.teamId()), new EndUserId(session.endUserId()));
            var activity = goals.activity(owner);
            String title = session.title();
            Integer count = session.messageCount();
            Instant updated = session.updatedAt();
            Instant latest = session.latestMessageAt();
            if (activity.isPresent()) {
                var goal = activity.orElseThrow();
                if (title == null) {
                    title = goal.firstObjective().strip().replaceAll("(?U)\\s+", " ");
                    title = title.substring(0, title.offsetByCodePoints(0, Math.min(TITLE_LENGTH, title.codePointCount(0, title.length()))));
                }
                count = (count == null ? 0 : count) + goal.createdCount();
                if (updated == null || goal.updatedAt().isAfter(updated)) updated = goal.updatedAt();
                if (latest == null || goal.updatedAt().isAfter(latest)) latest = goal.updatedAt();
            }
            rows.add(new Row(new SessionItemResponse(session.sessionId(), session.summaryText(), count, updated,
                    session.agentId(), session.teamId(), title), latest));
        }
        // 相同活跃时间沿用目录原有的创建时间和 ID 顺序；空会话仍排在有实际活动的会话之后。
        rows.sort(Comparator.comparing(Row::latestActivity, Comparator.nullsLast(Comparator.reverseOrder())));
        return rows.stream().map(Row::response).toList();
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
