package com.skillforge.institution.group;

import static com.skillforge.institution.group.GroupContracts.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.core.chat.session.SessionService;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 用可读存储夹具验证命令/来源回执与对象CAS；真实事务、RLS和并发另由PG/HTTP验收。 */
class GroupBindingServiceTest {
    private final GroupBindingMapper mapper=mock(GroupBindingMapper.class);
    private final InstitutionIntegrationService integrations=mock(InstitutionIntegrationService.class);
    private final EndUserService users=mock(EndUserService.class);
    private final SessionService sessions=mock(SessionService.class);
    private final GroupAgentEligibility agents=mock(GroupAgentEligibility.class);
    private final AuditService audit=mock(AuditService.class);
    private final GroupBindingService service=new GroupBindingService(mapper,integrations,users,sessions,agents,audit,new ObjectMapper().findAndRegisterModules());
    private final Map<Long,GroupBindingMapper.Row> groups=new HashMap<>();
    private final Map<String,GroupBindingMapper.Member> members=new HashMap<>();
    private final Map<String,GroupBindingMapper.Agent> serving=new HashMap<>();
    private final Map<String,GroupBindingMapper.Receipt> receipts=new HashMap<>();
    @BeforeEach void setup() {
        TenantContext.setTenantId("7");principal(1);
        when(mapper.find(anyLong(),anyString(),anyLong())).thenAnswer(c->lookup(c.getArgument(0),c.getArgument(1),c.getArgument(2)));
        when(mapper.share(anyLong(),anyString(),anyLong())).thenAnswer(c->lookup(c.getArgument(0),c.getArgument(1),c.getArgument(2)));
        when(mapper.lock(anyLong(),anyString(),anyLong())).thenAnswer(c->lookup(c.getArgument(0),c.getArgument(1),c.getArgument(2)));
        when(mapper.lifecycle(anyLong(),anyString(),anyString(),anyString(),anyString())).thenAnswer(c->groups.values().stream().filter(r->r.tenantId().equals(c.getArgument(0))&&r.integrationId().equals(c.getArgument(1))&&r.namespace().equals(c.getArgument(2))&&r.externalGroupRef().equals(c.getArgument(3))&&r.lifecycleRef().equals(c.getArgument(4))).findFirst().orElse(null));
        when(mapper.activeExternal(anyLong(),anyString(),anyString(),anyString())).thenAnswer(c->groups.values().stream().filter(r->r.tenantId().equals(c.getArgument(0))&&r.integrationId().equals(c.getArgument(1))&&r.namespace().equals(c.getArgument(2))&&r.externalGroupRef().equals(c.getArgument(3))&&r.status().equals("active")).findFirst().orElse(null));
        when(mapper.insert(any())).thenAnswer(c->{var r=c.<GroupBindingMapper.Row>getArgument(0);groups.put(r.groupBindingId(),r);return 1;});
        when(mapper.close(anyLong(),anyString(),anyLong(),anyLong())).thenAnswer(c->{var r=groups.get(c.<Long>getArgument(2));if(r==null||r.revision()!=c.<Long>getArgument(3))return 0;groups.put(r.groupBindingId(),new GroupBindingMapper.Row(r.groupBindingId(),r.tenantId(),r.integrationId(),r.namespace(),r.externalGroupRef(),r.lifecycleRef(),r.businessScopeId(),r.sessionId(),"closed",r.revision()+1));return 1;});
        var counter=new AtomicInteger();when(sessions.createGroup(anyString(),anyString(),anyString(),anyString())).thenAnswer(c->{var s=org.mockito.Mockito.mock(SessionView.class);org.mockito.Mockito.when(s.getSessionId()).thenReturn("session-"+counter.incrementAndGet());return s;});
        when(mapper.member(anyLong(),anyString(),anyLong(),anyLong())).thenAnswer(c->members.get(c.getArgument(2)+":"+c.getArgument(3)));
        when(mapper.agent(anyLong(),anyString(),anyLong(),anyLong())).thenAnswer(c->serving.get(c.getArgument(2)+":"+c.getArgument(3)));
        when(mapper.insertMember(any())).thenAnswer(c->{var r=c.<GroupBindingMapper.Member>getArgument(0);members.put(r.groupBindingId()+":"+r.endUserId(),r);return 1;});
        when(mapper.updateMember(any(),anyLong())).thenAnswer(c->{var r=c.<GroupBindingMapper.Member>getArgument(0);members.put(r.groupBindingId()+":"+r.endUserId(),r);return 1;});
        when(mapper.insertAgent(any())).thenAnswer(c->{var r=c.<GroupBindingMapper.Agent>getArgument(0);serving.put(r.groupBindingId()+":"+r.agentId(),r);return 1;});
        when(mapper.updateAgent(any(),anyLong())).thenAnswer(c->{var r=c.<GroupBindingMapper.Agent>getArgument(0);serving.put(r.groupBindingId()+":"+r.agentId(),r);return 1;});
        when(mapper.command(anyLong(),anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(c->receipts.get(c.getArgument(2)+"|"+c.getArgument(3)+"|"+c.getArgument(4)+"|"+c.getArgument(5)));
        when(mapper.receipt(any())).thenAnswer(c->{var r=c.<GroupBindingMapper.Receipt>getArgument(0);receipts.put(r.actorRef()+"|"+r.operation()+"|"+r.objectScope()+"|"+r.requestKey(),r);return 1;});
        when(mapper.source(anyLong(),anyString(),anyLong(),anyString(),anyString(),anyString(),anyLong())).thenAnswer(c->receipts.values().stream().filter(r->r.groupBindingId().equals(c.getArgument(2))&&r.operation().equals(c.getArgument(3))&&r.objectScope().equals(c.getArgument(4))&&Objects.equals(r.sourceEventId(),c.getArgument(5))&&Objects.equals(r.sourceRevision(),c.getArgument(6))).findFirst().orElse(null));
    }
    @AfterEach void cleanup(){ServicePrincipalContext.clear();TenantContext.clear();}
    @Test void directoryReadsUseExactScopeAndStableIdentityPages() {
        var group=service.ensure(ensure("life-1"),"first");long id=Long.parseLong(group.groupBindingId());
        long user=9007199254740993L,agent=9007199254740995L;
        var member=new GroupBindingMapper.Member(7L,"integration",id,user,"member","left",2,"leave",2,"actor");
        var next=new GroupBindingMapper.Member(7L,"integration",id,user+1,"manager","active",1,"join",1,"actor");
        var serving=new GroupBindingMapper.Agent(7L,"integration",id,agent,"removed",2,"remove",2,"actor");
        when(mapper.members(7,"integration",id,0,2)).thenReturn(java.util.List.of(member,next));
        when(mapper.members(7,"integration",id,user,2)).thenReturn(java.util.List.of(next));
        when(mapper.agents(7,"integration",id,0,2)).thenReturn(java.util.List.of(serving));
        var first=service.members(group.groupBindingId(),null,1);
        assertTrue(first.hasMore());assertEquals(Long.toString(user),first.nextAfter());
        assertEquals("left",first.items().getFirst().status());
        var last=service.members(group.groupBindingId(),first.nextAfter(),1);
        assertFalse(last.hasMore());assertNull(last.nextAfter());assertEquals(user+1,last.items().getFirst().endUserId());
        assertEquals("removed",service.agents(group.groupBindingId(),null,1).items().getFirst().status());
        assertThrows(ApiException.class,()->service.members(group.groupBindingId(),"1e3",1));
        assertThrows(ApiException.class,()->service.agents(group.groupBindingId(),null,0));
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("19","7","other",1,Set.of("groups.read")));
        assertThrows(ApiException.class,()->service.members(group.groupBindingId(),null,1));
        assertThrows(ApiException.class,()->service.agents(group.groupBindingId(),null,1));
    }
    @Test void directoryScopeDenialPrecedesEveryMapperRead() {
        doThrow(denied()).when(integrations).requirePrincipal(any(),eq("groups.read"));
        assertThrows(ApiException.class,()->service.members("1",null,100));
        assertThrows(ApiException.class,()->service.agents("1",null,100));
        verifyNoInteractions(mapper);
    }
    @Test void currentExactScopeIsCheckedBeforeAnyGroupLookup() {
        doThrow(denied()).when(integrations).requirePrincipal(any(),eq("groups.write"));
        assertThrows(ApiException.class,()->service.ensure(ensure("life-1"),"key"));verifyNoInteractions(mapper,sessions);
    }
    @Test void ensureOriginalReceiptSurvivesCloseAndNewKeyReadsCurrentClosedFact() {
        var first=service.ensure(ensure("life-1"),"ensure");service.close(new Close(first.groupBindingId(),1L),"close");
        assertEquals(first,service.ensure(ensure("life-1"),"ensure"));
        var closed=service.ensure(ensure("life-1"),"new-ensure");assertEquals("closed",closed.status());assertEquals(first.sessionId(),closed.sessionId());
        verify(sessions,times(1)).createGroup(anyString(),anyString(),anyString(),anyString());
    }
    @Test void newLifecycleRequiresOldCloseAndCreatesIndependentSharedSession() {
        var first=service.ensure(ensure("life-1"),"first");
        assertThrows(ApiException.class,()->service.ensure(ensure("life-2"),"second"));
        service.close(new Close(first.groupBindingId(),1L),"close");var next=service.ensure(ensure("life-2"),"second");
        assertNotEquals(first.groupBindingId(),next.groupBindingId());assertNotEquals(first.sessionId(),next.sessionId());
        assertEquals("group",next.subject().kind());assertEquals(next.groupBindingId(),next.subject().groupBindingId());
    }
    @Test void ensureKeyCannotChooseAnotherExternalGroupOrScope() {
        service.ensure(ensure("life-1"),"first");
        assertThrows(ApiException.class,()->service.ensure(new Ensure("room","other","life-1","21"),"first"));
        assertThrows(ApiException.class,()->service.ensure(new Ensure("room","outside","life-1","22"),"new-key"));
        verify(sessions,times(1)).createGroup(anyString(),anyString(),anyString(),anyString());
    }
    @Test void sameTenantAnotherIntegrationCannotReadKnownGroupId() {
        var group=service.ensure(ensure("life-1"),"first");ServicePrincipalContext.set(new ServicePrincipalContext.Identity("19","7","other",1,Set.of("groups.read")));
        assertEquals(403,assertThrows(ApiException.class,()->service.detail(group.groupBindingId())).getStatus().value());
    }
    @Test void membersHaveIndependentCasWithoutChangingGroupRevision() {
        var group=service.ensure(ensure("life-1"),"first");
        assertEquals(1,service.updateMember(member(group,"active",0,1,"u1"),"member-1").revision());
        assertEquals(1,service.updateMember(new UpdateMember(group.groupBindingId(),12L,"manager","active",0L,"u2",1L),"member-2").revision());
        assertEquals(1,service.detail(group.groupBindingId()).revision());
    }
    @Test void oldSourceAndOriginalCommandCannotResurrectExitedMember() {
        var group=service.ensure(ensure("life-1"),"first");var joined=service.updateMember(member(group,"active",0,1,"join"),"join");
        service.updateMember(member(group,"left",1,2,"leave"),"leave");
        assertEquals(joined,service.updateMember(member(group,"active",0,1,"join"),"join"));
        assertEquals(joined,service.updateMember(member(group,"active",999,1,"join"),"different-key"));
        assertEquals("left",members.get(group.groupBindingId()+":11").status());
        assertThrows(ApiException.class,()->service.updateMember(member(group,"active",2,1,"new-event-old-version"),"stale"));
        verify(mapper,times(1)).updateMember(any(),anyLong());
    }
    @Test void changedSourceBodyAndChangedCommandBodyConflict() {
        var group=service.ensure(ensure("life-1"),"first");service.updateMember(member(group,"active",0,1,"join"),"join");
        assertThrows(ApiException.class,()->service.updateMember(member(group,"left",0,1,"join"),"different-key"));
        assertThrows(ApiException.class,()->service.updateMember(member(group,"left",0,1,"join"),"join"));
        verify(mapper,never()).updateMember(any(),anyLong());
    }
    @Test void firstLeftTombstoneAndCleanupDoNotRequireActiveScopeOrActiveEndUser() {
        var group=service.ensure(ensure("life-1"),"first");
        doThrow(denied()).when(integrations).requireBusinessScopeById("7","integration","21");
        assertEquals("left",service.updateMember(member(group,"left",0,3,"late-leave"),"left").status());
        verify(users).requireExternal("7","integration",11L,false);
        assertThrows(ApiException.class,()->service.updateMember(member(group,"active",1,4,"rejoin"),"rejoin"));
        assertEquals("closed",service.close(new Close(group.groupBindingId(),1L),"close").status());
    }
    @Test void agentRemovalUsesOnlyStableIdentityAndActiveJoinUsesPublicationEligibility() {
        var group=service.ensure(ensure("life-1"),"first");
        assertEquals("removed",service.updateAgent(new UpdateAgent(group.groupBindingId(),31L,"removed",0L,"removed",1L),"remove").status());
        verify(agents).requireIdentity("7","integration",31);verify(agents,never()).requireAvailable(anyString(),anyString(),anyLong());
        service.updateAgent(new UpdateAgent(group.groupBindingId(),31L,"active",1L,"join",2L),"join");verify(agents).requireAvailable("7","integration",31);
        assertEquals(1,service.detail(group.groupBindingId()).revision());
    }
    @Test void closedGroupRejectsNewProjectionButAllowsOriginalSourceReceipt() {
        var group=service.ensure(ensure("life-1"),"first");var joined=service.updateMember(member(group,"active",0,1,"join"),"join");
        service.close(new Close(group.groupBindingId(),1L),"close");
        assertEquals(joined,service.updateMember(member(group,"active",99,1,"join"),"new-replay"));
        assertThrows(ApiException.class,()->service.updateMember(member(group,"left",1,2,"leave"),"new-write"));
    }
    @Test void credentialRotationPreservesOriginalCommandAndCurrentScopeCheck() {
        var original=service.ensure(ensure("life-1"),"first");principal(2);assertEquals(original,service.ensure(ensure("life-1"),"first"));
        verify(sessions,times(1)).createGroup(anyString(),anyString(),anyString(),anyString());
        verify(integrations,times(2)).requirePrincipal(any(),eq("groups.write"));
    }
    @Test void receiptOrAuditFailureNeverReturnsAcceptedMutation() {
        doReturn(0).when(mapper).receipt(any());assertThrows(IllegalStateException.class,()->service.ensure(ensure("life-1"),"first"));verifyNoInteractions(audit);
    }
    private GroupBindingMapper.Row lookup(long tenant,String integration,long id){var r=groups.get(id);return r!=null&&r.tenantId()==tenant&&r.integrationId().equals(integration)?r:null;}
    private Ensure ensure(String lifecycle){return new Ensure("room","outside",lifecycle,"21");}
    private UpdateMember member(GroupView group,String status,long expected,long source,String event){return new UpdateMember(group.groupBindingId(),11L,"member",status,expected,event,source);}
    private void principal(long version){ServicePrincipalContext.set(new ServicePrincipalContext.Identity("19","7","integration",version,Set.of("groups.write","groups.read","groups.members.write","groups.agents.write")));}
    private ApiException denied(){return new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","拒绝",false);}
}
