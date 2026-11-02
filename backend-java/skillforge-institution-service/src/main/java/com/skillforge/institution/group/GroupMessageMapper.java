package com.skillforge.institution.group;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
/** 每条读取固定可信Tenant/integration；可登记到Runtime同一SqlSession。 */
@Mapper
public interface GroupMessageMapper {
 /** 将同一正式领域Mapper登记到Runtime自有SqlSession；Spring扫描配置不会自动继承。 */
 static void configure(org.apache.ibatis.session.Configuration configuration) {
  if(configuration.hasMapper(GroupMessageMapper.class))return;
  String resource="mapper/GroupMessageMapper.xml";
  try(var stream=GroupMessageMapper.class.getClassLoader().getResourceAsStream(resource)) {
   if(stream==null)throw new IllegalStateException("group_message_mapper_missing");
   new org.apache.ibatis.builder.xml.XMLMapperBuilder(stream,configuration,resource,configuration.getSqlFragments()).parse();
  } catch(java.io.IOException failure){throw new IllegalStateException("group_message_mapper_invalid",failure);}
 }


 void lockKey(@Param("key")String key);
 GroupBindingMapper.Row group(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
 Message message(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("id")String id);
 Message source(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("event")String event);
 TriggerRow trigger(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("id")String id);
 TriggerRow sourceTrigger(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("id")String id);
 TriggerRow head(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
 Slot slot(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
 Slot lockSlot(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
 int ensureSlot(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
 int insertMessage(Message row);
 int insertTrigger(TriggerRow row);
 int advanceSource(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("sequence")long sequence);
 int advanceTrigger(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("sequence")long sequence);
 int accept(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("id")String id,@Param("input")String input,@Param("run")String run);
 int occupy(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("id")String id,@Param("run")String run);
 int reject(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("id")String id,@Param("reason")String reason);
 int releaseSlot(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("run")String run);
 boolean terminal(@Param("tenant")long tenant,@Param("session")String session,@Param("run")String run);
 List<Message> history(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("through")long through);
 List<String> pollTenantPage(@Param("after")String after,@Param("limit")int limit);
 List<Slot> pendingSlots(@Param("tenant")long tenant);
 List<Slot> pendingSlotPage(@Param("tenant")long tenant,@Param("after")long after,@Param("limit")int limit);
 boolean activeGroup(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group);
 boolean messageAuthorEligible(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("user")long user);
 boolean eligible(@Param("tenant")long tenant,@Param("integration")String integration,@Param("group")long group,@Param("user")long user,@Param("agent")long agent,@Param("principal")String principal);
 Receipt command(@Param("tenant")long tenant,@Param("integration")String integration,@Param("actor")String actor,@Param("operation")String operation,@Param("group")long group,@Param("key")String key);
 int receipt(Receipt row);
 int pendingExtraction(Message row);

 record Message(Long tenantId,String integrationId,Long groupBindingId,String sessionId,String sourceMessageId,long sourceSequence,String sourceEventId,long sourceRevision,String authorKind,Long authorEndUserId,Long authorAgentId,String authorRunId,String sourceRef,String authorJson,String contentJson,String fingerprint) { }
 record TriggerRow(Long tenantId,String integrationId,Long groupBindingId,String sessionId,String triggerId,long triggerSequence,String sourceMessageId,long sourceSequence,Long requesterEndUserId,Long agentId,String principalId,long credentialVersion,String status,String inputId,String runId,String rejectionCode) { }
 record Slot(Long tenantId,String integrationId,Long groupBindingId,long sourceSequence,long triggerSequence,String activeTriggerId,String activeRunId) { }
 record Receipt(Long tenantId,String integrationId,String actorRef,String operation,Long groupBindingId,String requestKey,String fingerprint,String response) { }
}
