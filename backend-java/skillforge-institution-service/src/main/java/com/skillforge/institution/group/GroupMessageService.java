package com.skillforge.institution.group;

import static com.skillforge.institution.group.GroupMessageContracts.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.*;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.institution.runtime.RuntimeWorkerRecoveryMapper;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 群来源与前台队列；只准入，执行和恢复驱动归唯一ExecutionHostWorker。 */
@Service
public class GroupMessageService {
 private final GroupMessageMapper mapper;
 private final GroupHistorySourcePublisher publicSources;
 private final GroupControlService controls;
 private final GroupBindingService bindings;
 private final InstitutionIntegrationService integrations;
 private final SessionMediaService media;
 private final GroupMessageSourceValidator sources;
 private final GroupExecutionAdmission runtime;
 private final RuntimeWorkerRecoveryMapper recovery;
 private final ObjectMapper json;
 private final TransactionTemplate transactions;
 public GroupMessageService(GroupMessageMapper mapper,GroupBindingService bindings,InstitutionIntegrationService integrations,
   SessionMediaService media,GroupMessageSourceValidator sources,GroupExecutionAdmission runtime,RuntimeWorkerRecoveryMapper recovery,
   ObjectMapper json,PlatformTransactionManager transactionManager,GroupHistorySourcePublisher publicSources,GroupControlService controls) {
  this.mapper=mapper;this.publicSources=publicSources;this.controls=controls;this.bindings=bindings;this.integrations=integrations;this.media=media;this.sources=sources;
  this.runtime=runtime;this.recovery=recovery;this.json=json;this.transactions=new TransactionTemplate(transactionManager);
 }
 @Transactional(rollbackFor=Exception.class)
 public MessageReceipt record(MessageRecord command,String requestKey) {
  var actor=actor("groups.messages.write");if(command==null)throw bad("消息不能为空");
  long group=id(command.groupBindingId(),"group_binding_id");String event=text(command.sourceEventId(),"source_event_id",256),key=text(requestKey,"Idempotency-Key",256);
  if(command.sourceRevision()==null||command.sourceRevision()<1||command.author()==null)throw bad("来源修订与作者不能为空");
  var content=content(command.content());validateAuthor(command.author());
  var normalized=new MessageRecord(Long.toString(group),event,command.sourceRevision(),command.author(),content);String fp=fingerprint(normalized);
  lockCommand(actor,group,"message.record",key);var prior=prior(actor,group,"message.record",key,fp,MessageReceipt.class);if(prior!=null)return prior;
  var binding=required(mapper.group(actor.tenant(),actor.integration(),group));
  mapper.ensureSlot(actor.tenant(),actor.integration(),group);var slot=mapper.lockSlot(actor.tenant(),actor.integration(),group);
  var existing=mapper.source(actor.tenant(),actor.integration(),group,event);
  if(existing!=null){if(!fp.equals(existing.fingerprint()))throw conflict("同一来源事件不可改写内容或修订");var result=messageView(existing);save(actor,group,"message.record",key,fp,result);return result;}
  bindings.requireActive(actor.tenantText(),actor.integration(),Long.toString(group));
  if(!mapper.activeGroup(actor.tenant(),actor.integration(),group))throw denied("群或业务范围当前不可用");
  if(command.author() instanceof EndUserAuthor author){bindings.requireMember(actor.tenantText(),actor.integration(),Long.toString(group),author.endUserId(),false);if(!mapper.messageAuthorEligible(actor.tenant(),actor.integration(),group,author.endUserId()))throw denied("来源作者当前不是有效群成员");}
  else sources.require(actor.tenantText(),actor.integration(),Long.toString(group),binding.sessionId(),command.author(),content);
  // 选择器必须解析成该群Session真正持有的媒体，客户端声明不构成授权。
  media.resolveContent(new SessionRef(new TenantId(actor.tenantText()),binding.sessionId()),content);
  long sequence=increment(slot.sourceSequence());String sourceId=Long.toString(SnowflakeIds.nextId());
  var author=command.author();Long user=author instanceof EndUserAuthor a?a.endUserId():null;
  Long agent=author instanceof AgentAuthor a?a.agentId():author instanceof ToolAuthor a?a.agentId():null;
  String run=author instanceof AgentAuthor a?a.runId():author instanceof ToolAuthor a?a.runId():null;
  String ref=author instanceof AgentAuthor a?a.sourceRef():author instanceof ToolAuthor a?a.sourceRef():author instanceof SystemAuthor a?a.sourceRef():null;
  var row=new GroupMessageMapper.Message(actor.tenant(),actor.integration(),group,binding.sessionId(),sourceId,sequence,event,command.sourceRevision(),author.kind(),user,agent,run,ref,write(author),write(content),fp);
  changed(mapper.advanceSource(actor.tenant(),actor.integration(),group,sequence));changed(mapper.insertMessage(row));
  // 正式持久pending登记，不在GI03伪造记忆提取完成或调用另一个执行引擎。
  changed(mapper.pendingExtraction(row));publicSources.publish(row);var result=messageView(row);save(actor,group,"message.record",key,fp,result);return result;
 }
 @Transactional(rollbackFor=Exception.class)
 public TriggerReceipt trigger(Trigger command,String requestKey) {
  var actor=actor("groups.triggers.write");if(command==null)throw bad("触发请求不能为空");
  long group=id(command.groupBindingId(),"group_binding_id"),user=positive(command.requesterEndUserId(),"requester_end_user_id"),agent=positive(command.agentId(),"agent_id");
  String source=text(command.sourceMessageId(),"source_message_id",64),key=text(requestKey,"Idempotency-Key",256);
  var normalized=new Trigger(Long.toString(group),source,user,agent);String fp=fingerprint(normalized);
  lockCommand(actor,group,"trigger.create",key);var prior=prior(actor,group,"trigger.create",key,fp,TriggerReceipt.class);if(prior!=null)return prior;
  var binding=required(mapper.group(actor.tenant(),actor.integration(),group));mapper.ensureSlot(actor.tenant(),actor.integration(),group);
  var slot=mapper.lockSlot(actor.tenant(),actor.integration(),group);var existing=mapper.sourceTrigger(actor.tenant(),actor.integration(),group,source);
  if(existing!=null){if(existing.requesterEndUserId()!=user||existing.agentId()!=agent)throw conflict("同一来源已触发不同Agent或requester");var result=originalTriggerView(existing);save(actor,group,"trigger.create",key,fp,result);return result;}
  var message=mapper.message(actor.tenant(),actor.integration(),group,source);if(message==null)throw denied("来源消息不属于当前群");
  if(!"end_user".equals(message.authorKind()))throw bad("首版触发只允许EndUser来源");if(!Objects.equals(message.authorEndUserId(),user))throw denied("requester必须为来源作者本人");
  bindings.requireMember(actor.tenantText(),actor.integration(),Long.toString(group),user,false);
  bindings.requireServiceAgent(actor.tenantText(),actor.integration(),Long.toString(group),agent);
  if(!mapper.eligible(actor.tenant(),actor.integration(),group,user,agent,actor.principalId()))throw denied("触发当前资格不足");
  long sequence=increment(slot.triggerSequence());var row=new GroupMessageMapper.TriggerRow(actor.tenant(),actor.integration(),group,binding.sessionId(),Long.toString(SnowflakeIds.nextId()),sequence,source,message.sourceSequence(),user,agent,actor.principalId(),actor.version(),"queued",null,null,null);
  changed(mapper.advanceTrigger(actor.tenant(),actor.integration(),group,sequence));changed(mapper.insertTrigger(row));
  controls.registerTrigger(actor.tenant(),actor.integration(),group,row.triggerId(),row.triggerSequence());
  var result=triggerView(row);save(actor,group,"trigger.create",key,fp,result);return result;
 }
 @Transactional(readOnly=true)
 public TriggerReceipt detail(String groupBindingId,String triggerId){var actor=actor("groups.triggers.read");var row=mapper.trigger(actor.tenant(),actor.integration(),id(groupBindingId,"group_binding_id"),text(triggerId,"trigger_id",64));if(row==null)throw denied("触发不属于当前执行域");return triggerView(row);}

 /** 在独立Spring事务取队首，再由Runtime-owned事务按budget-root→slot顺序做最终复核。 */
 public void pump(String tenant,String integration,String groupBindingId) {
  long group=id(groupBindingId,"group_binding_id"),tenantId=id(tenant,"tenant_id");
  TenantContext.callWithTenant(tenant,()->{
   GroupExecutionAdmission.Candidate candidate=transactions.execute(ignored->{
    recovery.bindTenant(tenant);var binding=mapper.group(tenantId,integration,group);if(binding==null)return null;
    var slot=mapper.lockSlot(tenantId,integration,group);if(slot==null)return null;
    if(slot.activeRunId()!=null){if(!mapper.terminal(tenantId,binding.sessionId(),slot.activeRunId()))return null;changed(mapper.releaseSlot(tenantId,integration,group,slot.activeRunId()));}
    var head=mapper.head(tenantId,integration,group);if(head==null)return null;
    if(!eligible(mapper,head)){changed(mapper.reject(tenantId,integration,group,head.triggerId(),"group_current_qualification_denied"));return null;}
    var history=mapper.history(tenantId,integration,group,head.sourceSequence()).stream().map(m->new GroupExecutionAdmission.Source(m.sourceMessageId(),m.sourceSequence(),m.authorJson(),m.contentJson())).toList();
    return new GroupExecutionAdmission.Candidate(tenant,integration,groupBindingId,binding.sessionId(),binding.businessScopeId(),head.triggerId(),head.triggerSequence(),head.sourceMessageId(),head.sourceSequence(),head.requesterEndUserId(),head.agentId(),head.principalId(),head.credentialVersion(),history);
   });
   if(candidate==null)return null;
   try {
    runtime.admit(candidate,(session,admission)->{
     var same=session.getMapper(GroupMessageMapper.class);var slot=same.lockSlot(tenantId,integration,group);var head=same.head(tenantId,integration,group);
     if(slot==null||slot.activeRunId()!=null||head==null||!head.triggerId().equals(candidate.triggerId()))throw new QueueBusy();
     if(!eligible(same,head))throw new QueueDenied();
     var run=admission.run();String runId=run.runRef().runId(),inputId=run.causationInputId().value();
     if(!run.owner().sessionRef().tenantId().value().equals(tenant)||!run.owner().sessionRef().sessionId().equals(candidate.sessionId())||!run.owner().target().id().equals(groupBindingId)||!run.agentId().value().equals(Long.toString(candidate.agentId())))throw new IllegalStateException("group_admission_identity_mismatch");
     changed(same.accept(tenantId,integration,group,candidate.triggerId(),inputId,runId));changed(same.occupy(tenantId,integration,group,candidate.triggerId(),runId));
    });
   } catch(QueueBusy ignored) {
    // 另一事务赢得同一队首；当前事务回滚，不产生新Run或拒绝旧触发。
   } catch(QueueDenied denied) {rejectCandidate(candidate,"group_current_qualification_denied");
   } catch(ApiException denied) {
    if(denied.getStatus()==HttpStatus.FORBIDDEN||denied.getStatus()==HttpStatus.NOT_FOUND||denied.getStatus()==HttpStatus.BAD_REQUEST||denied.getStatus()==HttpStatus.UNPROCESSABLE_ENTITY)
     rejectCandidate(candidate,"group_execution_admission_denied");
    else throw denied;
   }
   return null;
  });
 }
 private void rejectCandidate(GroupExecutionAdmission.Candidate c,String reason){transactions.executeWithoutResult(ignored->{recovery.bindTenant(c.tenantId());long tenant=id(c.tenantId(),"tenant_id"),group=id(c.groupBindingId(),"group_binding_id");mapper.lockSlot(tenant,c.integrationId(),group);var current=mapper.trigger(tenant,c.integrationId(),group,c.triggerId());if(current!=null&&"queued".equals(current.status()))changed(mapper.reject(tenant,c.integrationId(),group,c.triggerId(),reason));});}
 /** 只扫描可恢复的领域队列；WAITING/UNCERTAIN不释放，绝不drive。 */
 @Scheduled(fixedDelayString="${skillforge.groups.admission-poll-ms:1000}")
 public void poll(){
  String tenantCursor="";
  while(true){var tenants=mapper.pollTenantPage(tenantCursor,100);if(tenants==null||tenants.isEmpty())break;
  for(String tenant:tenants){tenantCursor=tenant;TenantContext.callWithTenant(tenant,()->{
   long after=0;
   while(true){
    long cursor=after;var slots=transactions.execute(ignored->{recovery.bindTenant(tenant);return mapper.pendingSlotPage(id(tenant,"tenant_id"),cursor,100);});
    if(slots==null||slots.isEmpty())break;
    for(var slot:slots){after=slot.groupBindingId();try{pump(tenant,slot.integrationId(),slot.groupBindingId().toString());}
     catch(RuntimeException failure){LoggerFactory.getLogger(GroupMessageService.class).warn("Group admission pending after technical failure tenant={} group={}",tenant,slot.groupBindingId(),failure);}}
    if(slots.size()<100)break;
   }
   return null;
  });}
  if(tenants.size()<100)break;
  }
 }

 private boolean eligible(GroupMessageMapper same,GroupMessageMapper.TriggerRow row){return same.eligible(row.tenantId(),row.integrationId(),row.groupBindingId(),row.requesterEndUserId(),row.agentId(),row.principalId());}
 private Actor actor(String scope){var identity=ServicePrincipalContext.get();if(identity==null||!Objects.equals(identity.tenantId(),TenantContext.getTenantId()))throw denied("须使用当前固定机构执行身份");integrations.requirePrincipal(identity,scope);return new Actor(id(identity.tenantId(),"tenant_id"),identity.integrationId(),identity.principalId(),identity.credentialVersion());}
 private record Actor(long tenant,String integration,String principalId,long version){String tenantText(){return Long.toString(tenant);}String ref(){return "service_principal:"+principalId;}}
 private void lockCommand(Actor actor,long group,String operation,String key){mapper.lockKey("group.message.command:"+fingerprint(List.of(actor.tenant(),actor.integration(),actor.ref(),group,operation,key)));}
 private <T>T prior(Actor actor,long group,String operation,String key,String fp,Class<T> type){var prior=mapper.command(actor.tenant(),actor.integration(),actor.ref(),operation,group,key);if(prior==null)return null;if(!prior.fingerprint().equals(fp))throw conflict("幂等键对应不同请求");return read(prior.response(),type);}
 private void save(Actor actor,long group,String operation,String key,String fp,Object result){changed(mapper.receipt(new GroupMessageMapper.Receipt(actor.tenant(),actor.integration(),actor.ref(),operation,group,key,fp,write(result))));}
 private static MessageReceipt messageView(GroupMessageMapper.Message r){return new MessageReceipt(r.groupBindingId().toString(),r.sessionId(),r.sourceMessageId(),r.sourceSequence(),r.sourceEventId(),r.sourceRevision());}
 private static TriggerReceipt triggerView(GroupMessageMapper.TriggerRow r){return new TriggerReceipt(r.groupBindingId().toString(),r.sessionId(),r.requesterEndUserId(),r.agentId(),r.triggerId(),r.triggerSequence(),r.sourceMessageId(),r.status(),r.inputId(),r.runId(),r.rejectionCode());}
 private static TriggerReceipt originalTriggerView(GroupMessageMapper.TriggerRow r){return new TriggerReceipt(r.groupBindingId().toString(),r.sessionId(),r.requesterEndUserId(),r.agentId(),r.triggerId(),r.triggerSequence(),r.sourceMessageId(),"queued",null,null,null);}
 private static GroupBindingMapper.Row required(GroupBindingMapper.Row row){if(row==null)throw denied("群不属于当前执行域");return row;}
 private static void validateAuthor(Author author){if(author instanceof EndUserAuthor a)positive(a.endUserId(),"end_user_id");else if(author instanceof AgentAuthor a){positive(a.agentId(),"agent_id");text(a.runId(),"run_id",128);text(a.sourceRef(),"source_ref",256);}else if(author instanceof ToolAuthor a){positive(a.agentId(),"agent_id");text(a.runId(),"run_id",128);text(a.sourceRef(),"source_ref",256);}else if(author instanceof SystemAuthor a)text(a.sourceRef(),"source_ref",256);}
 private static List<ChatContent> content(List<ChatContent> content){if(content==null||content.isEmpty()||content.size()>100||content.stream().anyMatch(Objects::isNull))throw bad("content须为1至100个有序内容块");return List.copyOf(content);}
 private static long id(String value,String field){if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw bad(field+"格式非法");try{return Long.parseLong(value);}catch(NumberFormatException failure){throw bad(field+"超出范围");}}
 private static long positive(Long value,String field){if(value==null||value<1)throw bad(field+"格式非法");return value;}
 private static long increment(long value){if(value==Long.MAX_VALUE)throw conflict("群序号已达上限");return value+1;}
 private static String text(String value,String field,int max){if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(Character::isISOControl))throw bad(field+"格式非法");return value;}
 private static void changed(int count){if(count!=1)throw new IllegalStateException("group_message_persistence_conflict");}
 private String fingerprint(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}}
 private String write(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException failure){throw new IllegalStateException("group_message_json_invalid",failure);}}
 private <T>T read(String value,Class<T> type){try{return json.readValue(value,type);}catch(JsonProcessingException failure){throw new IllegalStateException("group_message_receipt_invalid",failure);}}
 private static ApiException bad(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",message,false);}
 private static ApiException conflict(String message){return new ApiException(HttpStatus.CONFLICT,"CONFLICT",message,false);}
 private static ApiException denied(String message){return new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN",message,false);}
 private static final class QueueBusy extends RuntimeException { }
 private static final class QueueDenied extends RuntimeException { }
}
