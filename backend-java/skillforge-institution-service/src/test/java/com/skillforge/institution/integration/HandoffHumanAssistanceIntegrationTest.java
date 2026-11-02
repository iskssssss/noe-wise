package com.skillforge.institution.integration;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.chat.dto.handoff.CreateHandoffTicketRequest;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.agent.mapper.AgentMapper;
import com.skillforge.core.agent.model.AgentDO;
import com.skillforge.institution.service.*;
import com.skillforge.institution.runtime.*;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.runtime.session.spi.AgentRuntimeCollaborationPort.HumanAssistance;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.institution.controller.HandoffController;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 真实 PG 的持久受理→原 SessionLog→中断恢复→公开历史，不调用模型。 */
@TestPropertySource(properties={"skillforge.handoff-reply-delay-ms=3600000","skillforge.session-outbox-cron=-","agent-runtime.workers.auto-startup=false"})
class HandoffHumanAssistanceIntegrationTest extends AbstractIntegrationTest {
 @Autowired HandoffApplicationService handoffs;@Autowired HandoffActionService actions;@Autowired HandoffReplyService replies;@Autowired HandoffReplyMapper replyMapper;
 @Autowired AgentRuntimeInboundGateway runtime;@Autowired AgentRuntimeExecutionProjection projection;
 @Autowired SessionMailboxService mailbox;
 @Autowired PlanMapper plans;@Autowired TenantMapper tenants;@Autowired TenantMemberMapper members;
 @Autowired EndUserMapper users;@Autowired AgentMapper agents;@Autowired SessionMapper sessions;
 @Autowired SessionStorePort runtimeSessions;@Autowired HandoffController controller;
 @Autowired FoundationAccess access;@Autowired FoundationOrganizationService organization;
 @Autowired javax.sql.DataSource dataSource;
 @Autowired com.skillforge.core.chat.session.SessionService productSessions;
 long tenant,operator;SessionDO session;
 @BeforeEach void setup(){
  tenant=Long.parseLong(createTenant(tenants,plans,"handoff-tenant-"+SnowflakeIds.nextId()));TenantContext.setTenantId(Long.toString(tenant));
  var user=new EndUserDO();user.setId(SnowflakeIds.nextId());user.setTenantId(tenant);user.setDisplayName("人工协助验收用户");user.setStatus("active");users.insert(user);
  var member=new TenantMemberDO();member.setId(SnowflakeIds.nextId());member.setTenantId(tenant);member.setUsername("operator-"+member.getId());member.setRole("developer");member.setStatus("active");members.insert(member);operator=member.getId();
  organization.initializeTenant(Long.toString(tenant),Long.toString(operator));
  var agent=new AgentDO();agent.setId(SnowflakeIds.nextId());agent.setTenantId(tenant);agent.setAgentKey("handoff-agent-"+agent.getId());agent.setName("人工协助验收助手");agent.setTemplateVersion("1.0");agent.setSystemPrompt("测试");agent.setRoleDescription("测试");agent.setStatus("published");agents.insert(agent);
  session=new SessionDO();session.setId(SnowflakeIds.nextId());session.setSessionId("handoff-"+session.getId());session.setTenantId(tenant);session.setEndUserId(user.getId());session.setAgentId(agent.getId());session.setOriginKind("native");session.setActorRef("end_user:"+user.getId());session.setResponsibilityRef("tenant:"+tenant);session.setMessageCount(0);sessions.insert(session);
  access.withSubject(subject(),()->{organization.registerSessionOwnership(session.getSessionId());return null;});
 }
 @AfterEach void clear(){TenantContext.clear();}
 @Test void actionReceiptCommitsWithStateAndReplaysOnlyTheSameCommand(){
  access.withSubject(subject(),()->{
   String id=handoffs.create(tenant,new CreateHandoffTicketRequest("user_request",null,session.getSessionId(),null,0,java.util.UUID.randomUUID().toString())).id();
   String accept=java.util.UUID.randomUUID().toString();
   var first=actions.execute(tenant,operator,id,1,accept,"accept",null);
   assertTrue(first.created());assertEquals("assigned",first.status());
   var replay=actions.execute(tenant,operator,id,1,accept,"accept",null);
   assertFalse(replay.created());assertEquals(first.commandId(),replay.commandId());
   assertEquals("assigned",actions.receipt(tenant,operator,id,accept).status());
   assertActionReceiptTenantIsolation();
   assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->actions.execute(tenant,operator,id,2,accept,"resolve","已处理"));
   String rejected=java.util.UUID.randomUUID().toString();
   var failure=assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->actions.execute(tenant,operator,id,1,rejected,"resolve","过期"));
   assertEquals("HANDOFF_ACTION_NOT_ACCEPTED",failure.getCode());
   assertNull(actions.receipt(tenant,operator,id,rejected),"失败事务不能留下成功回执");
   String resolve=java.util.UUID.randomUUID().toString();
   assertEquals("resolved",actions.execute(tenant,operator,id,2,resolve,"resolve","已处理").status());
   assertEquals("resolved",actions.receipt(tenant,operator,id,resolve).status());
   assertEquals("已处理",handoffs.require(tenant,id).getResolutionNote());
   return null;
  });
 }
 @Test void sourceCommittedBeforeWorkerReceiptSurvivesReconstructionAndDoesNotDuplicate(){
  access.withSubject(subject(),()->{
  String id=handoffs.create(tenant,new CreateHandoffTicketRequest("user_request",null,session.getSessionId(),null,0,java.util.UUID.randomUUID().toString())).id();
  handoffs.accept(tenant,id,operator,1);String key="reply-"+SnowflakeIds.nextId();
  var accepted=replies.accept(tenant,operator,id,2,key,"坐席已人工核实申请");assertEquals("accepted",accepted.status());
  var command=new HumanAssistance(key,id,Long.toString(operator),accepted.operatorName(),accepted.content());
  // 模拟 Session 独立事务已提交、worker 来不及写回受理回执即退出。
  var committed=runtime.humanAssistance(com.skillforge.institution.TestSessions.view(session),command);
  assertEquals("accepted",replyMapper.find(tenant,key).getStatus());
  replies.dispatch(tenant,key);replies.dispatch(tenant,key);
  var saved=replyMapper.find(tenant,key);assertEquals("committed",saved.getStatus());assertEquals(committed.sourceEventId(),saved.getSourceEventId());
  var messages=projection.read(com.skillforge.institution.TestSessions.view(session)).messages().stream().filter(m->m.messageId().equals(committed.sourceEventId())).toList();
  assertEquals(1,messages.size());assertEquals("human_operator",messages.getFirst().metadata().get("author_type"));
  var outputs=mailbox.listOutputsAfterSequence(Long.toString(tenant),session.getSessionId(),0,100).stream()
    .filter(output->committed.sourceEventId().equals(output.getSourceEventId())).toList();
  assertEquals(1,outputs.size(),"已提交的同一人工事实在公开Mailbox只出现一次");
  var publicData=assertInstanceOf(java.util.Map.class,outputs.getFirst().getPayload().get("data"));
  assertEquals("human_operator",publicData.get("author_type"));
  assertEquals(accepted.content(),publicData.get("content"));
  assertEquals(committed,runtime.humanAssistance(com.skillforge.institution.TestSessions.view(session),command));
  assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->replies.accept(tenant,operator,id,2,key,"另一段正文"));
  assertNull(replyMapper.find(tenant+1,key),"显式跨租户标识不得读到原受理");
  return null;});
 }
 @Test void closedProductSessionKeepsReadonlyTicketHistoryButRejectsNewHumanFact(){
  access.withSubject(subject(),()->{
  String id=handoffs.create(tenant,new CreateHandoffTicketRequest("user_request",null,session.getSessionId(),null,0,java.util.UUID.randomUUID().toString())).id();
  handoffs.accept(tenant,id,operator,1);
  String firstKey="visible-before-close-"+SnowflakeIds.nextId();
  var first=replies.accept(tenant,operator,id,2,firstKey,"关闭前已核实的公开历史");
  replies.dispatch(tenant,firstKey);assertEquals("committed",replyMapper.find(tenant,firstKey).getStatus());
  String secondKey="rejected-after-close-"+SnowflakeIds.nextId();
  replies.accept(tenant,operator,id,3,secondKey,"关闭后的回复不得提交");
  productSessions.closeForTenant(Long.toString(tenant),session.getSessionId(),"验收关闭");
  var detail=controller.detail(id,null).data();
  assertTrue(detail.messages().stream().anyMatch(m->m.content().equals(first.content())),"关闭不删除已提交公开历史");
  assertTrue(detail.messages().stream().allMatch(m->java.util.Set.of("user","assistant").contains(m.role())));
  var publicBeforeRejectedReply=publicOutputIds();
  replies.dispatch(tenant,secondKey);
  assertEquals("rejected",replyMapper.find(tenant,secondKey).getStatus());
  assertEquals("source_session_unavailable",replyMapper.find(tenant,secondKey).getErrorCode());
  var command=new HumanAssistance(secondKey,id,Long.toString(operator),first.operatorName(),"关闭后的回复不得提交");
  assertTrue(runtime.humanReceipt(com.skillforge.institution.TestSessions.view(session),command).isEmpty(),"关闭后不能追加新的人工会话事实");
  assertEquals(publicBeforeRejectedReply,publicOutputIds(),"关闭后的拒绝不能追加公开Mailbox事件");
  assertTrue(controller.detail(id,null).data().messages().stream().anyMatch(m->m.content().equals(first.content())));
  return null;});
 }
 private void assertActionReceiptTenantIsolation() {
  String role="handoff_rls_"+java.util.UUID.randomUUID().toString().replace("-", "");
  try(var connection=dataSource.getConnection();var statement=connection.createStatement()) {
   String previousTenant;
   try(var rows=statement.executeQuery("SELECT current_setting('app.tenant_id', true)")) {rows.next();previousTenant=rows.getString(1);}
   statement.execute("CREATE ROLE "+role+" NOLOGIN");
   statement.execute("GRANT USAGE ON SCHEMA public TO "+role);
   statement.execute("GRANT SELECT ON public.nw_handoff_action_receipts TO "+role);
   try {
    statement.execute("SET ROLE "+role);
    try(var set=connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
     set.setString(1,Long.toString(tenant));set.execute();
     try(var rows=statement.executeQuery("SELECT count(*) FROM public.nw_handoff_action_receipts WHERE tenant_id="+tenant)) {rows.next();assertEquals(1,rows.getInt(1));}
     set.setString(1,Long.toString(tenant+1));set.execute();
     try(var rows=statement.executeQuery("SELECT count(*) FROM public.nw_handoff_action_receipts WHERE tenant_id="+tenant)) {rows.next();assertEquals(0,rows.getInt(1));}
    }
   } finally {
    statement.execute("RESET ROLE");
    try(var reset=connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {reset.setString(1,previousTenant==null?"":previousTenant);reset.execute();}
    statement.execute("REVOKE SELECT ON public.nw_handoff_action_receipts FROM "+role);
    statement.execute("REVOKE USAGE ON SCHEMA public FROM "+role);
    statement.execute("DROP ROLE "+role);
   }
  } catch(java.sql.SQLException error) {throw new IllegalStateException(error);}
 }
 private FoundationAccess.Subject subject(){return new FoundationAccess.Subject(false,Long.toString(tenant),Long.toString(operator));}
 private java.util.List<String> publicOutputIds(){
  return mailbox.listOutputsAfterSequence(Long.toString(tenant),session.getSessionId(),0,100).stream()
    .map(SessionOutboxEntry::getEventId).toList();
 }
 @Test void persistedReplyRechecksRealMemberStatusBeforeAnyNewRuntimeFact(){
  String key="revoked-before-dispatch-"+SnowflakeIds.nextId();
  var accepted=access.withSubject(subject(),()->{
   String ticket=handoffs.create(tenant,new CreateHandoffTicketRequest("user_request",null,session.getSessionId(),null,0,java.util.UUID.randomUUID().toString())).id();
   handoffs.accept(tenant,ticket,operator,1);
   return replies.accept(tenant,operator,ticket,2,key,"撤权后不得提交的新回复");
  });
  var publicBeforeRevocation=publicOutputIds();
  var revoked=new TenantMemberDO();revoked.setId(operator);revoked.setStatus("disabled");members.updateById(revoked);
  replies.dispatch(tenant,key);
  var saved=replyMapper.find(tenant,key);assertEquals("rejected",saved.getStatus());assertEquals("operator_access_revoked",saved.getErrorCode());
  assertTrue(runtime.humanReceipt(com.skillforge.institution.TestSessions.view(session),new HumanAssistance(key,accepted.ticketId(),Long.toString(operator),accepted.operatorName(),accepted.content())).isEmpty());
  assertEquals(publicBeforeRevocation,publicOutputIds(),"撤权后不能写入公开Mailbox事件");
 }
}
