package com.skillforge.institution.service;

import com.skillforge.core.chat.persistence.HumanHandoffTicketDO;
import com.skillforge.core.chat.session.SessionView;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.runtime.*;
import com.skillforge.runtime.session.spi.AgentRuntimeCollaborationPort.HumanReceipt;

import org.junit.jupiter.api.*;
import java.util.*;

class HandoffReplyServiceTest {
 HandoffReplyMapper mapper;HandoffApplicationService handoffs;FoundationAccess access;AgentRuntimeInboundGateway runtime;HandoffReplyService service;
 HandoffReplyService.Reply reply=new HandoffReplyService.Reply("key","ticket","session",99L,42L,"坐席甲","人工回复","accepted",null,null,null);
 @BeforeEach @SuppressWarnings("unchecked") void setup(){
 mapper=mock(HandoffReplyMapper.class);handoffs=mock(HandoffApplicationService.class);access=mock(FoundationAccess.class);runtime=mock(AgentRuntimeInboundGateway.class);
 when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false,"7","42"));
 doAnswer(call->((java.util.function.Supplier<?>)call.getArgument(1)).get()).when(access).withSubject(any(),any());
 var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
 when(manager.getTransaction(any())).thenAnswer(call->new org.springframework.transaction.support.SimpleTransactionStatus());
 service=new HandoffReplyService(mapper,handoffs,runtime,access,manager);
 var row=new HandoffReplyRequest();row.setCommandId("key");row.setTicketId("ticket");row.setSessionId("session");row.setEndUserId(99L);row.setOperatorId(42L);row.setOperatorName("坐席甲");row.setContent("人工回复");row.setStatus("accepted");row.setRequestFingerprint(HandoffReplyService.fingerprint(7L,42L,"ticket",2L,"key","人工回复"));
 when(mapper.find(7L,"key")).thenReturn(row);when(mapper.claim(7L,"key")).thenReturn(row);when(mapper.commit(anyLong(),anyString(),anyString(),anyLong())).thenReturn(1);when(mapper.reject(anyLong(),anyString(),anyString())).thenReturn(1);
 var session=org.mockito.Mockito.mock(SessionView.class);org.mockito.Mockito.when(session.getSessionId()).thenReturn("session");org.mockito.Mockito.when(session.getEndUserId()).thenReturn(99L);when(handoffs.sourceSession(7L,"session")).thenReturn(session);when(handoffs.sourceSessionForRecovery(7L,"session")).thenReturn(session);
 var ticket=new HumanHandoffTicketDO();ticket.setSessionId("session");ticket.setEndUserId(99L);when(handoffs.requireForWrite(7L,"ticket")).thenReturn(ticket);
 when(runtime.humanReceipt(any(),any())).thenReturn(Optional.empty());
 }
 @Test void sameAcceptedCommandReplaysWithoutAdvancingRevisionOrCallingRuntime(){
 assertThat(service.accept(7L,42L,"ticket",2L,"key","人工回复")).isEqualTo(reply);
 verify(handoffs,never()).reserveReply(anyLong(),anyString(),anyLong(),anyLong());verifyNoInteractions(runtime);
 }
 @Test void sameKeyDifferentTextRejectsBeforeAnyNewEffect(){
 assertThatThrownBy(()->service.accept(7L,42L,"ticket",2L,"key","另一条正文")).isInstanceOf(ApiException.class);
 verify(handoffs,never()).reserveReply(anyLong(),anyString(),anyLong(),anyLong());verifyNoInteractions(runtime);
 }
 @Test void committedSourceIsRecoveredEvenIfOperatorWasLaterRevoked(){
 when(runtime.humanReceipt(any(),any())).thenReturn(Optional.of(new HumanReceipt("source",8L)));
 service.dispatch(7L,"key");
 verifyNoInteractions(access);verify(runtime,never()).humanAssistance(any(),any());verify(runtime).reconcileHuman(any());
 verify(mapper).commit(7L,"key","source",8L);
 }
 @Test void revokedOperatorCannotAppendNewSource(){
 doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"已撤权")).when(access).require("foundation.handoff.update");
 service.dispatch(7L,"key");verify(runtime,never()).humanAssistance(any(),any());
 verify(mapper).reject(7L,"key","operator_access_revoked");
 }
 @Test void closedSourceWithoutCommittedFactIsPersistentlyRejected(){
  when(handoffs.sourceSession(7L,"session")).thenThrow(new ApiException(org.springframework.http.HttpStatus.GONE,"已关闭"));
  service.dispatch(7L,"key");verify(mapper).reject(7L,"key","source_session_unavailable");verify(runtime,never()).humanAssistance(any(),any());
 }
 @Test void persistedOperatorIsBoundAndCurrentSessionWriteRevocationStopsNewFact(){
  doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"会话写权已撤销")).when(handoffs).requireForWrite(7L,"ticket");
  service.dispatch(7L,"key");
  verify(access).withSubject(eq(new FoundationAccess.Subject(false,"7","42")),any());
  verify(mapper).reject(7L,"key","operator_access_revoked");verify(runtime,never()).humanAssistance(any(),any());
 }
 @Test void arbitraryHttpOperatorCannotClaimOrReadPersistedReply(){
  assertThatThrownBy(()->service.accept(7L,43L,"ticket",2L,"key","人工回复")).isInstanceOf(ApiException.class);
  verifyNoInteractions(mapper,handoffs,runtime);
 }
}
