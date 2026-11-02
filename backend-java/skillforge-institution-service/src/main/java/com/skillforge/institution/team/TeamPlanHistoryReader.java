package com.skillforge.institution.team;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.dag.DagWork;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 按当前消息页的原始输入身份读取计划，不受最近工作历史的条数上限影响。 */
@Component
public final class TeamPlanHistoryReader {
    private final JdbcDagWorkPort plans;
    private final TeamWorkView views;
    private final ObjectMapper json;
    public TeamPlanHistoryReader(JdbcDagWorkPort plans,TeamWorkView views,ObjectMapper json) {
        this.plans=plans;this.views=views;this.json=json;
    }
    public List<Map<String,Object>> read(SessionView session,Set<String> inputIds) {
        var owner=TeamWorkService.owner(session);
        return inputIds.stream().filter(id->!id.isBlank())
                .map(id->plans.read(new DagWork.Key(owner,new InputId(id))))
                .flatMap(java.util.Optional::stream).filter(work->work.plan()!=null)
                .sorted(java.util.Comparator.comparing(work->work.request().receivedAt()))
                .map(views::view).map(value->json.convertValue(value,new TypeReference<Map<String,Object>>() {})).toList();
    }
}
