package com.skillforge.core.agent;

import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.core.agent.capabilitymount.CapabilityMountPersistenceService;
import com.skillforge.core.agent.capabilitymount.CreateCapabilityMountCommand;
import com.skillforge.core.capability.CapabilityRef;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityMount.WorkflowMountConfig;
import com.skillforge.core.foundation.runtimeconfig.AgentExecutionConfig;
import com.skillforge.core.foundation.workflowtest.WorkflowTestRegistry;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Agent领域拥有测试身份的装配与发布；宿主只获得稳定身份，不能跨模块改实体。 */
@Service
public class AgentTestProvisioningService {
    private final AgentService agents;
    private final AgentPublicationService publications;
    private final CapabilityMountPersistenceService mounts;
    private final WorkflowTestRegistry tests;
    public AgentTestProvisioningService(AgentService agents,AgentPublicationService publications,
            CapabilityMountPersistenceService mounts,WorkflowTestRegistry tests) {
        this.agents=agents;this.publications=publications;this.mounts=mounts;this.tests=tests;
    }
    public record Identity(long agentId) {}
    @Transactional(propagation=Propagation.MANDATORY)
    public Identity create(long tenant,long testId,CapabilityRef workflow,WorkflowMountConfig binding,AgentExecutionConfig limits) {
        if(workflow.type()!=CapabilityType.WORKFLOW)throw new IllegalArgumentException("workflow_test_capability_required");limits.validate();
        var agent=new AgentDO();agent.setId(SnowflakeIds.nextId());agent.setAgentKey("workflow-test-"+testId);agent.setTenantId(tenant);
        agent.setName("工作流隔离测试 "+testId);agent.setSystemPrompt("仅执行宿主已授权的唯一工作流测试目标。");agent.setConfig(Map.of());agent.setStatus("draft");
        agent.setExecutionConfig(limits);
        if(!agents.save(agent))throw new IllegalStateException("workflow_test_agent_insert_failed");
        mounts.create(new CreateCapabilityMountCommand(tenant,agent.getId(),workflow,binding,0));publications.publish(agent);
        return new Identity(agent.getId());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void archive(long tenant,long agentId) {
        var test=tests.byAgent(tenant,agentId);
        if(test==null||!"closing".equals(test.status()))throw new SecurityException("workflow_test_close_not_requested");
        var agent=agents.lockVersion(tenant,agentId);agent.setStatus("archived");
        if(!agents.updateById(agent))throw new IllegalStateException("workflow_test_agent_archive_failed");
    }
}
