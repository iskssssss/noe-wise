package com.skillforge.institution.service;

import com.skillforge.core.chat.application.HumanHandoffTicketService;
import com.skillforge.core.chat.dto.handoff.CreateHandoffTicketRequest;
import com.skillforge.core.chat.dto.handoff.ResolveTicketRequest;
import com.skillforge.core.chat.persistence.HumanHandoffTicketDO;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class HandoffApplicationServiceTest {
 HumanHandoffTicketService tickets;SessionService sessions;TenantMemberMapper members;HandoffApplicationService service;
 HumanHandoffTicketDO ticket;com.skillforge.institution.runtime.AgentRuntimeInboundGateway runtime;
 FoundationAccess access; HandoffCreateMapper creates;
 @BeforeAll static void metadata(){TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),""),HumanHandoffTicketDO.class);}
 @BeforeEach void setup(){tickets=mock(HumanHandoffTicketService.class);sessions=mock(SessionService.class);members=mock(TenantMemberMapper.class);
 creates=mock(HandoffCreateMapper.class);when(creates.insert(any())).thenReturn(1);runtime=mock(com.skillforge.institution.runtime.AgentRuntimeInboundGateway.class);access=mock(FoundationAccess.class);when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","42"));service=new HandoffApplicationService(tickets,sessions,members,runtime,access,creates);ticket=new HumanHandoffTicketDO();ticket.setTenantId(7L);ticket.setTicketId("t");ticket.setAssignedTo(42L);ticket.setRevision(2L);
 when(tickets.getOne(any())).thenReturn(ticket);when(tickets.update(any(LambdaUpdateWrapper.class))).thenReturn(true);
 var actor=new TenantMemberDO();actor.setId(42L);actor.setTenantId(7L);actor.setStatus("active");actor.setRole("developer");when(members.selectById(42L)).thenReturn(actor);
 }
 @Test void creationUsesTrustedSessionEndUserAndChecksOptionalExecution(){
 var session=org.mockito.Mockito.mock(SessionView.class);org.mockito.Mockito.when(session.getSessionId()).thenReturn("s");org.mockito.Mockito.when(session.getEndUserId()).thenReturn(99L);when(sessions.requireWritable("7","s")).thenReturn(session);when(tickets.save(any())).thenReturn(true);
 service.create(7,new CreateHandoffTicketRequest(null,"summary","s",null,null,java.util.UUID.randomUUID().toString()));
 var capture=ArgumentCaptor.forClass(HumanHandoffTicketDO.class);verify(creates).insert(capture.capture());
 assertThat(capture.getValue().getEndUserId()).isEqualTo(99);assertThat(capture.getValue().getRevision()).isEqualTo(1);assertThat(capture.getValue().getSessionId()).isEqualTo("s");
 doThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"foreign")).when(runtime).requireWorkflowExecution(session,"foreign");
 assertThatThrownBy(()->service.create(7,new CreateHandoffTicketRequest(null,null,"s","foreign",null,java.util.UUID.randomUUID().toString()))).isInstanceOf(ApiException.class);
 service.create(7,new CreateHandoffTicketRequest(null,null,"s","valid",null,java.util.UUID.randomUUID().toString()));
 verify(runtime).requireWorkflowExecution(session,"valid");
 verify(creates).insert(argThat(value->"valid".equals(value.getExecutionId())));
 }
 @Test void resolveRequiresAssigneeAndRevisionAndPersistsNote(){
 service.resolve(7,"t",42,2,new ResolveTicketRequest("已核实"));
 var capture=ArgumentCaptor.forClass(LambdaUpdateWrapper.class);verify(tickets).update(capture.capture());
 var update=capture.getValue();update.getSqlSegment();assertThat(update.getParamNameValuePairs().values()).contains(7L,"t",42L,2L,3L,"已核实","assigned","resolved");
 }
 @Test void developerCannotResolveAnotherOperatorsTicket(){ticket.setAssignedTo(43L);
 assertThatThrownBy(()->service.resolve(7,"t",42,2,new ResolveTicketRequest("原因"))).isInstanceOf(ApiException.class);verify(tickets,never()).update(any(LambdaUpdateWrapper.class));}
 @Test void currentManagePermissionCanResolveSameTenantTicket(){when(access.allowed("foundation.handoff.manage")).thenReturn(true);ticket.setAssignedTo(43L);assertThat(service.resolve(7,"t",42,2,new ResolveTicketRequest("原因")).status().code()).isEqualTo("resolved");}
 @Test void revokedUpdatePermissionRejectsBeforeWrite(){doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"已撤权")).when(access).require("foundation.handoff.update");assertThatThrownBy(()->service.resolve(7,"t",42,2,null)).isInstanceOf(ApiException.class);verify(tickets,never()).update(any(LambdaUpdateWrapper.class));}
 @Test void revokedSessionWriteRejectsBeforeTicketMutation(){ticket.setSessionId("s");doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"会话写权已撤销")).when(sessions).requireWritable("7","s");assertThatThrownBy(()->service.accept(7,"t",42,2)).isInstanceOf(ApiException.class);verify(tickets,never()).update(any(LambdaUpdateWrapper.class));}
 @Test void currentReadUsesSessionReadAndNeverNeedsWrite(){ticket.setSessionId("s");service.require(7,"t");verify(access).require("foundation.handoff.read");verify(sessions).require("7","s");verify(sessions,never()).requireWritable(anyString(),anyString());}
 @Test void creationReceiptRequiresOriginalActorAndCurrentSessionReadWithoutCreate(){
 String command=java.util.UUID.randomUUID().toString();ticket.setSessionId("s");ticket.setCreatedBy(42L);
 assertThat(service.createReceipt(7,command).id()).isEqualTo("t");
 verify(access).require("foundation.handoff.read");verify(access,never()).require("foundation.handoff.create");
 verify(sessions).require("7","s");verify(sessions,never()).requireWritable(anyString(),anyString());
 ticket.setCreatedBy(43L);assertThatThrownBy(()->service.createReceipt(7,command)).isInstanceOf(ApiException.class);
 ticket.setCreatedBy(42L);doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"无会话读权")).when(sessions).require("7","s");
 assertThatThrownBy(()->service.createReceipt(7,command)).isInstanceOf(ApiException.class);
 }
 @Test void emptyCurrentSessionScopeReturnsNoTicketFacts(){when(access.visibleSessionIds(any(),eq("read"))).thenReturn(java.util.List.of());assertThat(service.list(7,null)).isEmpty();verifyNoInteractions(tickets);}
 @Test void staleRevisionAndOversizedNoteRejectButEmptyNoteIsOptional(){when(tickets.update(any(LambdaUpdateWrapper.class))).thenReturn(false);
 assertThatThrownBy(()->service.accept(7,"t",42,1)).isInstanceOf(ApiException.class);
 assertThatThrownBy(()->service.resolve(7,"t",42,2,new ResolveTicketRequest("x".repeat(2001)))).isInstanceOf(ApiException.class);
 when(tickets.update(any(LambdaUpdateWrapper.class))).thenReturn(true);
 assertThat(service.resolve(7,"t",42,2,new ResolveTicketRequest("")).status().code()).isEqualTo("resolved");
 assertThat(service.resolve(7,"t",42,2,null).status().code()).isEqualTo("resolved");}
}
