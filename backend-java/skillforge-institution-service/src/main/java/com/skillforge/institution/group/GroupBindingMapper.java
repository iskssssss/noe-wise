package com.skillforge.institution.group;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群领域唯一仓储；每条业务查询显式约束可信Tenant/integration。 */
@Mapper
public interface GroupBindingMapper {
    void lockKey(@Param("key")String key);
    Row extractionGroup(@Param("tenant")long tenant,@Param("group")long group);
    Row find(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
    Row share(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
    Row lock(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
    Row lifecycle(@Param("tenant")long tenant,@Param("integration")String integration,@Param("namespace")String namespace,@Param("external")String external,@Param("lifecycle")String lifecycle);
    Row activeExternal(@Param("tenant")long tenant,@Param("integration")String integration,@Param("namespace")String namespace,@Param("external")String external);
    int insert(Row row);
    int close(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("expected")long expected);
    Member member(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("user")long user);
    Agent agent(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("agent")long agent);
    java.util.List<Member> members(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("after")long after,@Param("limit")int limit);
    java.util.List<Agent> agents(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("after")long after,@Param("limit")int limit);
    int insertMember(Member row);
    int updateMember(@Param("row")Member row,@Param("expected")long expected);
    int insertAgent(Agent row);
    int updateAgent(@Param("row")Agent row,@Param("expected")long expected);
    Receipt command(@Param("tenant")long tenant,@Param("integration")String integration,@Param("actor")String actor,@Param("operation")String operation,@Param("object")String object,@Param("key")String key);
    Receipt source(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("operation")String operation,@Param("object")String object,@Param("event")String event,@Param("sourceRevision")long sourceRevision);
    int receipt(Receipt row);
    record Row(Long groupBindingId,Long tenantId,String integrationId,String namespace,String externalGroupRef,String lifecycleRef,String businessScopeId,String sessionId,String status,long revision) { }
    record Member(Long tenantId,String integrationId,Long groupBindingId,Long endUserId,String role,String status,long revision,String sourceEventId,long sourceRevision,String actorRef) { }
    record Agent(Long tenantId,String integrationId,Long groupBindingId,Long agentId,String status,long revision,String sourceEventId,long sourceRevision,String actorRef) { }
    record Receipt(Long tenantId,String integrationId,String actorRef,String operation,String objectScope,String requestKey,String fingerprint,Long groupBindingId,
            String sourceEventId,Long sourceRevision,String sourceFingerprint,String response) { }
}
