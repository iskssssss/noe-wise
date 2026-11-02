package com.skillforge.institution.group;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.agent.AgentPublicationService;
import com.skillforge.core.agent.AgentService;
import com.skillforge.core.agent.mapper.AgentDeploymentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 群服务名单的本地 Agent 资格；只读当前发布和原部署授权，不准备工具或创建执行。 */
@Service
public class GroupAgentEligibility {
    private final AgentService agents;
    private final AgentPublicationService publications;
    private final AgentDeploymentMapper deployments;

    /** 复用所有入口共用的发布解析器，避免群维护第二套受控源执行判定。 */
    public GroupAgentEligibility(AgentService agents, AgentPublicationService publications,
            AgentDeploymentMapper deployments) {
        this.agents = agents;
        this.publications = publications;
        this.deployments = deployments;
    }

    /** 新增或恢复服务资格必须是本机构当前可发布执行的真实实例，受控源沿原完整链复验。 */
    @Transactional(readOnly = true)
    public void requireAvailable(String tenantId, String integrationId, long agentId) {
        requireIdentity(tenantId, integrationId, agentId);
        publications.requirePublished(Long.parseLong(tenantId), agentId);
    }

    /**
     * 移除只核验稳定身份与执行域，不让停用或撤销阻断撤下旧名单。
     * 原生实例及 integration 为空的受控部署可由明确群授权加入；固定非空域不能跨域。
     */
    @Transactional(readOnly = true)
    public void requireIdentity(String tenantId, String integrationId, long agentId) {
        if (tenantId == null || !tenantId.matches("[1-9][0-9]*")
                || !tenantId.equals(TenantContext.getTenantId()) || integrationId == null || integrationId.isBlank()
                || agentId <= 0)
            throw new ApiException(HttpStatus.FORBIDDEN, "群 Agent 与当前执行域不一致");
        long tenant;
        try { tenant = Long.parseLong(tenantId); }
        catch (NumberFormatException invalid) {
            throw new ApiException(HttpStatus.FORBIDDEN, "群 Agent 与当前执行域不一致");
        }
        var agent = agents.getOne(new LambdaQueryWrapper<AgentDO>()
                .eq(AgentDO::getTenantId, tenant).eq(AgentDO::getId, agentId));
        if (agent == null || !Long.valueOf(tenant).equals(agent.getTenantId()))
            throw new ApiException(HttpStatus.NOT_FOUND, "群服务 Agent 不属于当前机构");
        var deployment = deployments.findAgent(tenant, agentId);
        if (deployment != null && deployment.integrationId() != null
                && !integrationId.equals(deployment.integrationId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "群服务 Agent 已固定于其他 integration");
    }
}
