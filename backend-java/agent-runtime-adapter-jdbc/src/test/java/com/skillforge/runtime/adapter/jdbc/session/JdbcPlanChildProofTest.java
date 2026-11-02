package com.skillforge.runtime.adapter.jdbc.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.dag.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;

/** 新Plan成员必须持真实已批准准入和当前许可；原受理回执不受后续计划/许可变化影响。 */
class JdbcPlanChildProofTest {
    @Test void newChildUsesExactApprovedReservedBindingAndDatabasePermit() {
        var fixture=new Fixture();
        assertEquals(fixture.binding,fixture.require(false));
        var order=inOrder(fixture.mapper);
        order.verify(fixture.mapper).read(fixture.key,true);
        order.verify(fixture.mapper).planChildAdmission(fixture.parent,fixture.key,fixture.binding);
        order.verify(fixture.mapper).planChildPermit(fixture.binding,fixture.permit);
        verify(fixture.session,never()).getConnection();
        verify(fixture.session,never()).commit();verify(fixture.session,never()).close();
    }

    @Test void newChildCannotUseUnapprovedOrMissingExpiredDatabasePermit() {
        var fixture=new Fixture();
        when(fixture.mapper.planChildPermit(fixture.binding,fixture.permit)).thenReturn(null);
        assertThrows(ExecutionStore.Rejection.class,()->fixture.require(false));
        when(fixture.codec.decode("current-work")).thenReturn(new DagWork(fixture.key,fixture.parent.agentId(),3,DagWork.Status.ACTIVE,
                new DagPlan(1,true,List.of(fixture.task)),0,0,Map.of(fixture.task.id(),fixture.execution),Map.of(),fixture.request));
        clearInvocations(fixture.mapper);
        assertThrows(ExecutionStore.Rejection.class,()->fixture.require(false));
        verify(fixture.mapper,never()).planChildAdmission(any(),any(),any());
    }

    @Test void acceptedChildOnlyRechecksImmutableAdmissionAfterLaterCancellationAndPermitRelease() {
        var fixture=new Fixture();
        when(fixture.codec.decode("current-work")).thenReturn(new DagWork(fixture.key,fixture.parent.agentId(),4,DagWork.Status.CANCELLED,
                new DagPlan(1,true,List.of(fixture.task)),1,0,Map.of(),Map.of(),fixture.request));
        when(fixture.mapper.planChildPermit(fixture.binding,fixture.permit)).thenReturn(null);
        assertEquals(fixture.binding,fixture.require(true));
        verify(fixture.mapper,never()).read(any(),anyBoolean());
        verify(fixture.mapper,never()).planChildPermit(any(),any());
        when(fixture.mapper.planChildAdmission(fixture.parent,fixture.key,fixture.binding)).thenReturn("changed-binding");
        assertEquals("execution_plan_child_source_mismatch",assertThrows(ExecutionStore.Rejection.class,()->fixture.require(true)).reason());
    }

    @Test void ownerOrTaskExecutorMismatchCannotReachDomainRows() {
        var fixture=new Fixture();
        var other=new DagWork.Key(SessionOwner.user(fixture.key.owner().sessionRef(),new AgentId("another"),new EndUserId(99)),fixture.key.rootInputId());
        assertThrows(ExecutionStore.Rejection.class,()->fixture.port.requirePlanChild(fixture.session,fixture.parent,other,fixture.binding,fixture.permit,false));
        verifyNoInteractions(fixture.mapper);
    }

    private static final class Fixture {
        final TenantId tenant=new TenantId("1");
        final SessionOwner owner=SessionOwner.user(new SessionRef(tenant,"studio"),new AgentId("7"),new EndUserId(9));
        final InputId input=new InputId("root-input");
        final RunIdentity parent=new RunIdentity(owner, owner.target().requireAgent(), input, new RunRef(tenant,"parent-run"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("identity"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        final DagWork.Key key=new DagWork.Key(owner,input);
        final DagPlan.Task task=new DagPlan.Task("member-task","核对",new AgentId("8"),DagPlan.ExecutorType.MEMBER,"核对订单",List.of(),Set.of("answer"),Set.of());
        final DagWork.Binding binding=new DagWork.Binding("member-admission",1,task,new RunRef(tenant,"member-run"),Map.of());
        final DagWork.Permit permit=new DagWork.Permit(3,Instant.parse("2026-10-22T12:00:00Z"));
        final DagWork.Execution execution=new DagWork.Execution(binding,DagWork.TaskStatus.RESERVED,Optional.of(permit),false,0,Map.of(),"reserved",Optional.empty());
        final DagWork.Request request=new DagWork.Request("核对",Optional.empty(),parent.runRef(),"");
        final SqlSession session=mock(SqlSession.class);
        final DagWorkMapper mapper=mock(DagWorkMapper.class);
        final JdbcDagWorkPort.Codec codec=mock(JdbcDagWorkPort.Codec.class);
        final JdbcDagWorkPort port=new JdbcDagWorkPort(mock(DataSource.class),codec);
        Fixture() {
            var work=new DagWork(key,parent.agentId(),3,DagWork.Status.ACTIVE,new DagPlan(1,true,List.of(task)),1,0,Map.of(task.id(),execution),Map.of(),request);
            when(session.getMapper(DagWorkMapper.class)).thenReturn(mapper);
            when(mapper.read(key,true)).thenReturn("current-work");when(codec.decode("current-work")).thenReturn(work);
            when(codec.encodeBinding(binding)).thenReturn("exact-original-binding");
            when(mapper.planChildAdmission(parent,key,binding)).thenReturn("exact-original-binding");
            when(mapper.planChildPermit(binding,permit)).thenReturn(permit.fence());
        }
        DagWork.Binding require(boolean existing){return port.requirePlanChild(session,parent,key,binding,permit,existing);}
    }
}
