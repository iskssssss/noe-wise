package com.skillforge.institution.group;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.skillforge.institution.group.GroupMessageContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.*;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.institution.runtime.RuntimeWorkerRecoveryMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class GroupMessageServiceTest {
 private final GroupMessageMapper mapper=mock(GroupMessageMapper.class);
 private final GroupBindingService bindings=mock(GroupBindingService.class);
 private final GroupExecutionAdmission runtime=mock(GroupExecutionAdmission.class);
 private final GroupMessageSourceValidator sources=mock(GroupMessageSourceValidator.class);
 private final SessionMediaService media=mock(SessionMediaService.class);
 private GroupMessageService service;
 private final AtomicReference<GroupMessageMapper.Message> message=new AtomicReference<>();
 private final AtomicReference<GroupMessageMapper.TriggerRow> trigger=new AtomicReference<>();
 @BeforeEach void setup(){
  TenantContext.setTenantId("11");ServicePrincipalContext.set(new ServicePrincipalContext.Identity("principal","11","integration",1,Set.of("groups.messages.write","groups.triggers.write","groups.triggers.read")));
  var tm=mock(PlatformTransactionManager.class);when(tm.getTransaction(any())).thenAnswer(ignored->new SimpleTransactionStatus());
  service=new GroupMessageService(mapper,bindings,mock(InstitutionIntegrationService.class),media,sources,runtime,mock(RuntimeWorkerRecoveryMapper.class),new ObjectMapper().findAndRegisterModules(),tm,mock(GroupHistorySourcePublisher.class),mock(GroupControlService.class));
  when(mapper.group(11,"integration",41)).thenReturn(new GroupBindingMapper.Row(41L,11L,"integration","space","external","life","51","session","active",1));
  when(mapper.activeGroup(11,"integration",41)).thenReturn(true);when(mapper.messageAuthorEligible(11,"integration",41,31)).thenReturn(true);
  when(mapper.eligible(11,"integration",41,31,61,"principal")).thenReturn(true);
  when(mapper.lockSlot(11,"integration",41)).thenReturn(new GroupMessageMapper.Slot(11L,"integration",41L,0,0,null,null));
  when(mapper.source(eq(11L),eq("integration"),eq(41L),anyString())).thenAnswer(ignored->message.get());
  when(mapper.message(eq(11L),eq("integration"),eq(41L),anyString())).thenAnswer(ignored->message.get());
  when(mapper.sourceTrigger(eq(11L),eq("integration"),eq(41L),anyString())).thenAnswer(ignored->trigger.get());
  when(mapper.trigger(eq(11L),eq("integration"),eq(41L),anyString())).thenAnswer(ignored->trigger.get());
  when(mapper.insertMessage(any())).thenAnswer(call->{message.set(call.getArgument(0));return 1;});
  when(mapper.insertTrigger(any())).thenAnswer(call->{trigger.set(call.getArgument(0));return 1;});
  when(mapper.advanceSource(anyLong(),anyString(),anyLong(),anyLong())).thenReturn(1);when(mapper.advanceTrigger(anyLong(),anyString(),anyLong(),anyLong())).thenReturn(1);
  when(mapper.pendingExtraction(any())).thenReturn(1);when(mapper.receipt(any())).thenReturn(1);when(mapper.reject(anyLong(),anyString(),anyLong(),anyString(),anyString())).thenReturn(1);
 }
 @AfterEach void clear(){TenantContext.clear();ServicePrincipalContext.clear();}
 private MessageRecord record(String text,long revision){return new MessageRecord("41","event",revision,new EndUserAuthor(31L),ChatContent.text(text));}
 @Test void nonTriggerSourceOnlyRegistersPendingAndCannotCreateRun(){var receipt=service.record(record("hello",1),"key");assertEquals(1,receipt.sourceSequence());verify(mapper).pendingExtraction(message.get());verifyNoInteractions(runtime);verify(bindings).requireMember("11","integration","41",31,false);}
 @Test void sameEventNewKeyReadsOriginalAfterMembershipChangesButCannotEditRevision(){var first=service.record(record("hello",1),"first");clearInvocations(bindings);assertEquals(first,service.record(record("hello",1),"second"));verifyNoInteractions(bindings);var failure=assertThrows(ApiException.class,()->service.record(record("hello",2),"revision"));assertEquals(409,failure.getStatus().value());verify(mapper,times(1)).insertMessage(any());}
 @Test void sameSourceWithNewKeyKeepsOriginalQueuedReceiptAndRejectsDifferentAgent(){var source=service.record(record("hello",1),"message");var command=new Trigger("41",source.sourceMessageId(),31L,61L);var first=service.trigger(command,"first");clearInvocations(bindings);assertEquals(first,service.trigger(command,"second"));verifyNoInteractions(bindings);assertEquals("queued",first.status());assertNull(first.runId());assertEquals(409,assertThrows(ApiException.class,()->service.trigger(new Trigger("41",source.sourceMessageId(),31L,62L),"different")).getStatus().value());verifyNoInteractions(runtime);}
 @Test void queuedRevocationIsDurablyRejectedWithoutAdmission(){service.record(record("hello",1),"message");service.trigger(new Trigger("41",message.get().sourceMessageId(),31L,61L),"trigger");when(mapper.head(11,"integration",41)).thenReturn(trigger.get());when(mapper.eligible(11,"integration",41,31,61,"principal")).thenReturn(false);service.pump("11","integration","41");verify(mapper).reject(11,"integration",41,trigger.get().triggerId(),"group_current_qualification_denied");verifyNoInteractions(runtime);}
 @Test void waitingOrUncertainRunDoesNotReleaseSlotOrAdmitNext(){when(mapper.lockSlot(11,"integration",41)).thenReturn(new GroupMessageMapper.Slot(11L,"integration",41L,1,2,"active","run:active"));when(mapper.terminal(11,"session","run:active")).thenReturn(false);service.pump("11","integration","41");verify(mapper,never()).releaseSlot(anyLong(),anyString(),anyLong(),anyString());verify(mapper,never()).head(anyLong(),anyString(),anyLong());verifyNoInteractions(runtime);}
 @Test void technicalFailureKeepsQueuedAndFreezesContextThroughSourceSequence(){service.record(record("hello",1),"message");service.trigger(new Trigger("41",message.get().sourceMessageId(),31L,61L),"trigger");var row=trigger.get();when(mapper.head(11,"integration",41)).thenReturn(row);when(mapper.eligible(11,"integration",41,31,61,"principal")).thenReturn(true);when(mapper.history(11,"integration",41,1)).thenReturn(List.of(message.get()));when(runtime.admit(any(),any())).thenThrow(new IllegalStateException("connection_commit_unknown"));assertThrows(IllegalStateException.class,()->service.pump("11","integration","41"));verify(mapper,never()).reject(anyLong(),anyString(),anyLong(),anyString(),anyString());verify(runtime).admit(argThat(c->c.sourceSequence()==1&&c.history().size()==1&&c.history().getFirst().sourceMessageId().equals(row.sourceMessageId())),any());}
 private GroupMessageMapper.TriggerRow queuedHead(){
  service.record(record("hello",1),"message");service.trigger(new Trigger("41",message.get().sourceMessageId(),31L,61L),"trigger");
  var row=trigger.get();when(mapper.head(11,"integration",41)).thenReturn(row);when(mapper.history(11,"integration",41,1)).thenReturn(List.of(message.get()));return row;
 }
 @Test void knownHostAuthorizationRejectionPersistsRejectedInsteadOfLeavingQueued(){
  var row=queuedHead();when(runtime.admit(any(),any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN,"group_actor_authorization_revoked","当前执行授权已撤销",false));
  assertDoesNotThrow(()->service.pump("11","integration","41"));
  verify(mapper).reject(11,"integration",41,row.triggerId(),"group_execution_admission_denied");
  verify(mapper,never()).accept(anyLong(),anyString(),anyLong(),anyString(),anyString(),anyString());
 }
 @Test void hostTechnicalUnavailablePreservesQueuedAndOriginalFailure(){
  queuedHead();var failure=new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"group_runtime_unavailable","执行依赖暂不可用",true);
  when(runtime.admit(any(),any())).thenThrow(failure);
  assertSame(failure,assertThrows(ApiException.class,()->service.pump("11","integration","41")));
  verify(mapper,never()).reject(anyLong(),anyString(),anyLong(),anyString(),anyString());
  verify(mapper,never()).accept(anyLong(),anyString(),anyLong(),anyString(),anyString(),anyString());
 }

}
