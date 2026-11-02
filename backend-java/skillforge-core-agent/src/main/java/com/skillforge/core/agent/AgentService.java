package com.skillforge.core.agent;
import com.baomidou.mybatisplus.spring.service.IService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import org.springframework.stereotype.Service;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
/**
 * Agent MyBatis-Plus 服务。
 *
 * <p>继承 {@link ServiceImpl} 获得 Agent CRUD、批量与条件查询能力，
 * 供 Agent 管理与执行使用。
 */
@Service
public class AgentService
        extends ServiceImpl<AgentMapper, AgentDO>
        implements IService<AgentDO> {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AgentService.class);
    private final com.skillforge.core.agent.mapper.AgentDeploymentMapper deployments;

    /** 机构部署关系拥有公共定义写权，普通草稿编辑不得绕过部署绑定入口。 */
    public AgentService(com.skillforge.core.agent.mapper.AgentDeploymentMapper deployments) {
        LOG.debug("Agent服务装配进入");
        this.deployments=deployments;
        LOG.debug("Agent服务装配退出");
    }

    /** 调用方事务内锁住稳定身份，串行化发布、草稿编辑和能力挂载修改。 */
    public AgentDO lockVersion(long tenantId, long agentId) {
        AgentDO agent = getOne(new LambdaQueryWrapper<AgentDO>()
                .eq(AgentDO::getTenantId, tenantId).eq(AgentDO::getId, agentId).last("FOR UPDATE"));
        if (agent == null) throw new ApiException(HttpStatus.NOT_FOUND, "Agent 不存在");
        return agent;
    }

    /** 工作草稿始终可编辑；修改不会替换当前已发布快照。 */
    public void requireEditable(AgentDO agent) {
        LOG.debug("Agent草稿可编辑检查进入 tenantId={} agentId={}",agent.getTenantId(),agent.getId());
        try {
            if ("archived".equals(agent.getStatus()))
                throw new ApiException(HttpStatus.CONFLICT, "AGENT_ARCHIVED", "已归档智能体不能编辑", false);
            requireIndependentDefinition(agent);
        } finally {LOG.debug("Agent草稿可编辑检查退出 tenantId={} agentId={}",agent.getTenantId(),agent.getId());}
    }

    /** 公共部署实例定义由平台发布和本地绑定共同解析，禁止用普通发布覆盖。 */
    public void requireIndependentDefinition(AgentDO agent) {
        LOG.debug("Agent定义写权检查进入 tenantId={} agentId={}",agent.getTenantId(),agent.getId());
        try {
            if(deployments.findAgent(agent.getTenantId(),agent.getId())!=null)
                throw new ApiException(HttpStatus.CONFLICT,"AGENT_DEFINITION_MANAGED",
                        "公共Agent实例请通过部署绑定配置，公共定义由平台发布维护",false);
        }finally {LOG.debug("Agent定义写权检查退出 tenantId={} agentId={}",agent.getTenantId(),agent.getId());}
    }

    /** 与草稿挂载修改同事务记录待发布状态，不改变生效修订。 */
    public void markDraftChanged(long tenantId, long agentId) {
        if (!update(new LambdaUpdateWrapper<AgentDO>().eq(AgentDO::getTenantId, tenantId)
                .eq(AgentDO::getId, agentId).set(AgentDO::getHasDraftChanges, true)))
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent 不存在");
    }
}
