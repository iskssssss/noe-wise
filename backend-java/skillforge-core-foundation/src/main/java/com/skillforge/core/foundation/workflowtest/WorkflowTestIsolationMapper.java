package com.skillforge.core.foundation.workflowtest;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 同租户持久归属读写；Runtime 和管理域都以此目录为准。 */
@Mapper
public interface WorkflowTestIsolationMapper extends BaseMapper<WorkflowTestSessionDO> {
    WorkflowTestSessionDO lockBySession(@Param("tenant") long tenant,@Param("session") String session);
    WorkflowTestSessionDO bySession(@Param("tenant") long tenant,@Param("session") String session);
    WorkflowTestSessionDO byRequest(@Param("tenant") long tenant,@Param("member") long member,@Param("request") String request);
    WorkflowTestSessionDO byEndUser(@Param("tenant") long tenant,@Param("user") long user);
    WorkflowTestSessionDO byAgent(@Param("tenant") long tenant,@Param("agent") long agent);
    WorkflowTestSessionDO byInstance(@Param("tenant") long tenant,@Param("instance") long instance);
    boolean ownedResource(@Param("tenant") long tenant,@Param("kind") String kind,@Param("id") String id);
    List<Long> ownedAgentIds(@Param("tenant") long tenant,@Param("agentIds") List<Long> agentIds);
    void lockRequest(@Param("key") String key);
    List<Binding> bindings(@Param("tenant") long tenant,@Param("test") long test);
    int insertBinding(Binding binding);
    int closing(@Param("tenant") long tenant,@Param("test") long test);
    int closed(@Param("tenant") long tenant,@Param("test") long test);
    boolean quiet(@Param("tenant") long tenant,@Param("session") String session);
    List<WorkflowTestSessionDO> expiring(@Param("tenant") long tenant);
    record Binding(long tenantId,long testSessionId,String slot,String capabilityType,long capabilityDefinitionId,
            long capabilityVersionId,Long toolDeploymentId,String toolDeploymentKey,Long toolDeploymentRevision,Long knowledgeRevision) {}
}
