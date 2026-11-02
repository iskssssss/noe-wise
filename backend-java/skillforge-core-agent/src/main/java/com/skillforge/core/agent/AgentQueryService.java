package com.skillforge.core.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Agent 跨领域只读入口，所有查询显式限定租户。 */
@Service
@Transactional(readOnly = true)
public class AgentQueryService {
    private final AgentMapper agents;
    public AgentQueryService(AgentMapper agents) { this.agents = agents; }

    public AgentView find(long tenantId, long agentId) {
        return view(agents.selectOne(new LambdaQueryWrapper<AgentDO>()
                .eq(AgentDO::getTenantId, tenantId).eq(AgentDO::getId, agentId)));
    }

    public AgentView findPublishedByKey(long tenantId, String key) {
        return view(agents.selectOne(new LambdaQueryWrapper<AgentDO>()
                .eq(AgentDO::getTenantId, tenantId).eq(AgentDO::getAgentKey, key)
                .eq(AgentDO::getStatus, "published").orderByDesc(AgentDO::getRevision).last("LIMIT 1")));
    }

    private AgentView view(AgentDO row) {
        return row == null ? null : new AgentView(row.getId(), row.getTenantId(), row.getName(),
                row.getStatus(), row.getIconUrl(), row.getAgentKey());
    }
}
