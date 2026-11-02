package com.skillforge.runtime.adapter.jdbc.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.SessionDelegations.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** 新Store的同事务领域证明，不开启旧callback事务或生成RECOVERY输入。 */
final class JdbcSessionDelegationsJoinedTest {
    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private static final RunIdentity PARENT = new RunIdentity(SessionOwner.user(new SessionRef(new TenantId("1"),"session"),
            SessionTarget.agent(new AgentId("1")),new EndUserId(7)), new AgentId("1"), new InputId("input"), new RunRef(new TenantId("1"),"parent"), new TraceEnvelope("trace","span",new InputId("root"),Optional.empty()), new ResultRef("verification"), new com.skillforge.runtime.api.RunRequester.EndUser(SessionOwner.user(new SessionRef(new TenantId("1"),"session"),
            SessionTarget.agent(new AgentId("1")),new EndUserId(7)).requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(SessionOwner.user(new SessionRef(new TenantId("1"),"session"),
            SessionTarget.agent(new AgentId("1")),new EndUserId(7)).sessionRef().tenantId(), new InputId("input").value()));

    @Test void childProofLocksOriginalEntryUsingTheCallersSession() {
        var fixture = new Fixture(); var entry = fixture.entry("one");
        assertEquals(entry,fixture.store.requireEntry(fixture.session,PARENT,"one"));
        verify(fixture.mapper).lockEntry("parent","one","operation:one");
        verify(fixture.session,atLeastOnce()).getMapper(SessionDelegationsMapper.class);
        verifyNoInteractions(fixture.dataSource);
    }
    @Test void dependencyLocksChildrenInStableOrderAndReturnsOriginalWaitOrder() {
        var fixture = new Fixture(); var one = fixture.entry("one"); var two = fixture.entry("two");
        var wait = fixture.waiting(List.of("two","one"));
        assertEquals(List.of(two,one),fixture.store.requireWait(fixture.session,wait));
        var order = inOrder(fixture.mapper);
        order.verify(fixture.mapper).lockWait("parent","wait");
        order.verify(fixture.mapper).lockEntry("parent","one","operation:one");
        order.verify(fixture.mapper).lockEntry("parent","two","operation:two");
        verifyNoInteractions(fixture.dataSource);
    }
    @Test void acceptedInternalResultAcknowledgesBusinessWaitInTheSameTransaction() {
        var fixture = new Fixture(); var wait = fixture.waiting(List.of("one"));
        var next = new Wait(wait.id(),PARENT,wait.delegationIds(),true,2);
        when(fixture.codec.encodeWait(next)).thenReturn("delivered"); when(fixture.codec.decodeWait("delivered")).thenReturn(next);
        when(fixture.mapper.deliverWait(any(),eq(1L))).thenReturn(1);
        fixture.store.confirmDelivery(fixture.session,wait);
        verify(fixture.mapper).deliverWait(argThat(row -> row.delivered && row.revision == 2 && row.snapshot.equals("delivered")),eq(1L));
        verifyNoInteractions(fixture.dataSource);
    }
    private static final class Fixture {
        final DataSource dataSource = mock(DataSource.class);
        final JdbcSessionDelegations.Codec codec = mock(JdbcSessionDelegations.Codec.class);
        final SqlSession session = mock(SqlSession.class);
        final SessionDelegationsMapper mapper = mock(SessionDelegationsMapper.class);
        final JdbcSessionDelegations store = new JdbcSessionDelegations(dataSource,codec);
        Fixture() { when(session.getMapper(SessionDelegationsMapper.class)).thenReturn(mapper); }
        Entry entry(String id) {
            var target = new Target("member:2",new AgentId("2"),"成员","",List.of(),new TypedPayload("target","{}"));
            var entry = new Entry(id,PARENT,"operation:"+id,target,new Task("目标","",List.of(),""),new RunRef(new TenantId("1"),"child:"+id),
                    new InputId("child-input:"+id),Optional.empty(),State.QUEUED,Optional.empty(),Optional.empty(),1,NOW,NOW);
            when(codec.decodeEntry(id)).thenReturn(entry);
            when(mapper.findEntry(any(),eq(id))).thenReturn(SessionDelegationEntryRow.from(entry,id)); return entry;
        }
        Wait waiting(List<String> ids) {
            var wait = new Wait("wait",PARENT,ids,false,1); when(codec.decodeWait("wait")).thenReturn(wait);
            when(mapper.findWait(any(),eq("wait"))).thenReturn(SessionDelegationWaitRow.from(wait,"wait")); return wait;
        }
    }
}
