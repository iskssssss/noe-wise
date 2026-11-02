package com.skillforge.institution.artifact;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.institution.runtime.*;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 单次MODEL准备的成果读取边界；不替代真实finalize事务和持久成果发布验收。 */
class DeliverableOutputPreparationTest {
    /** 无成果任务只读取实际计划和正式版本，不触发自然语言要求解释。 */
    @Test void ordinaryPreparationLoadsCurrentFactsOnce() {
        var f=new Fixture();
        var output=f.service.prepareOutput(f.run);
        assertTrue(output.state().isEmpty());assertTrue(output.preparedResult().isEmpty());assertFalse(output.hasOutputs());
        verify(f.mapper).planRows(7,f.run.runRef().runId(),false);
        verify(f.mapper).currentPlans(7,f.run.owner().sessionRef().sessionId());
        verifyNoInteractions(f.requirements,f.store);
    }

    /** 下次请求重新读取当前实际计划；已提交选择的冻结和放弃保留准确结果。 */
    @Test void nextPreparationReadsNewPlanStateAndNeverReusesPriorFacts() {
        var f=new Fixture();
        var ordinary=f.service.prepareOutput(f.run);
        for(String state:List.of("planned","finalizing","finalized","abandoned")) {
            var plan=f.plan(state);
            when(f.mapper.planRows(7,f.run.runRef().runId(),false)).thenReturn(List.of(plan));
            var output=f.service.prepareOutput(f.run);
            assertEquals(Optional.of(state),output.state());assertTrue(output.hasOutputs());
            assertEquals(state.equals("planned")?Optional.empty():Optional.of("task-result:"+f.run.runRef().runId()),output.preparedResult());
        }
        assertFalse(ordinary.hasOutputs());assertTrue(ordinary.state().isEmpty());
        verify(f.mapper,times(5)).planRows(7,f.run.runRef().runId(),false);
        verifyNoInteractions(f.requirements);
    }

    /** 小long和超过double精确范围的大long均须接受同一原始身份，不能按内存数值节点类型误拒绝。 */
    @Test void persistedIdentityAcceptsSmallAndLargeLongWithoutChangingPrecision() {
        for(long user:List.of(9L,9_007_199_254_740_993L)) {
            var f=new Fixture(user);var plan=f.plan("finalizing");
            when(f.mapper.planRows(7,f.run.runRef().runId(),false)).thenReturn(List.of(plan));
            assertEquals(Optional.of("finalizing"),f.service.prepareOutput(f.run).state());
        }
    }

    /** 不同整数、浮点替换及大整数末位变化仍须拒绝，不能用数值/精度归一化绕过全身份一致性。 */
    @Test void persistedIdentityRejectsChangedIntegerAndFloatingPointRepresentations() {
        for(long user:List.of(9L,9_007_199_254_740_993L)) {
            var f=new Fixture(user);String exact=f.plan("finalizing").get("run_identity").toString();
            for(String replacement:List.of(Long.toString(user-1),Long.toString(user)+".0")) {
                var plan=new HashMap<>(f.plan("finalizing"));
                String changed=exact.replace(Long.toString(user),replacement);
                assertNotEquals(exact,changed,"负控必须实际改变身份字段");plan.put("run_identity",changed);
                when(f.mapper.planRows(7,f.run.runRef().runId(),false)).thenReturn(List.of(plan));
                assertEquals("OUTPUT_RUN_OWNER_MISMATCH",assertThrows(IllegalArgumentException.class,
                        ()->f.service.prepareOutput(f.run)).getMessage());
            }
        }
    }

    /** 没有真实finalize状态时不以请求正文、历史格式或候选生成答复门禁。 */
    @Test void noFinalizationStateKeepsOrdinaryReplyAndToolsAvailable() {
        var f=new Fixture();
        var output=f.service.prepareOutput(f.run);
        assertFalse(output.hasOutputs());assertTrue(output.preparedResult().isEmpty());assertTrue(output.state().isEmpty());
        assertNull(f.service.actionRejection(f.run,"respond"));
        assertNull(f.service.actionRejection(f.run,"execute"));
        assertFalse(f.service.hasOutputs(f.run));
        verifyNoInteractions(f.requirements);
    }

    /** 独立合法Run和成果Mapper；只模拟所属读取，不生成派发或业务成功回执。 */
    private static final class Fixture {
        private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        private final ExecutionStore store=mock(ExecutionStore.class);
        private final DeliverableMapper mapper=mock(DeliverableMapper.class);
        private final DeliverableRequirements requirements=mock(DeliverableRequirements.class);
        private final RunIdentity run;
        private final DeliverableService service;
        private Fixture() { this(8); }
        private Fixture(long user) {
            var tenant=new TenantId("7");var inputId=new InputId("output-preparation-input");
            var owner=SessionOwner.user(new SessionRef(tenant,"output-preparation-session"),new AgentId("101"),new EndUserId(user));
            run=new RunIdentity(owner, new AgentId((owner).target().id()), inputId, new RunRef(tenant,"output-preparation-run"), new TraceEnvelope("output-trace","output-span",inputId,Optional.empty()), new ResultRef("output-identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (inputId).value()));
            when(mapper.planRows(7,run.runRef().runId(),false)).thenReturn(List.of());
            when(mapper.currentPlans(7,owner.sessionRef().sessionId())).thenReturn(List.of());
            service=new DeliverableService(json,mock(ArtifactStorage.class),mock(ArtifactAccess.class),mock(AgentRunBindings.class),
                    mock(StudioArtifactProjection.class),store,mock(AgentRuntimeGoalDriver.class),mapper,new ArtifactClosingBudget(),
                    mock(InstitutionPlanHost.class),requirements,ProgramResourceOriginFixture.ordinary(store));
        }
        private Map<String,Object> plan(String state) {
            return Map.of("state",state,"tenant_id",7L,"run_id",run.runRef().runId(),"session_id",run.owner().sessionRef().sessionId(),
                    "owner_session_id",run.owner().sessionRef().sessionId(),"run_identity",json.valueToTree(run).toString());
        }
    }
}
