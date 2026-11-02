package com.skillforge.core.knowledge.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposalProjectionExecutor;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposalReviewResult;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposalSourceVerifier;
import com.skillforge.core.knowledge.persistence.KnowledgeWriteProposalMapper;
import com.skillforge.core.knowledge.persistence.KnowledgeWriteProposalDO;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

class KnowledgeWriteProposalServiceTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeWriteProposalDO.class);
    }

    private final KnowledgeWriteProposalMapper mapper = Mockito.mock(KnowledgeWriteProposalMapper.class);
    private final KnowledgeWriteProposalSourceVerifier verifier =
            Mockito.mock(KnowledgeWriteProposalSourceVerifier.class);
    private final KnowledgeWriteProposalService service =
            new KnowledgeWriteProposalService(mapper, List.of(), List.of(verifier));
    private final KnowledgeWriteProposalProjectionExecutor executor =
            Mockito.mock(KnowledgeWriteProposalProjectionExecutor.class);
    private final KnowledgeWriteProposalService serviceWithExecutor =
            new KnowledgeWriteProposalService(mapper, List.of(executor), List.of(verifier));

    @Test
    void createsPendingProposalWithoutApplyingFact() {
        KnowledgeWriteProposal proposal = proposal("p-1");

        assertEquals(proposal, service.create("7", proposal));

        ArgumentCaptor<KnowledgeWriteProposalDO> saved = ArgumentCaptor.forClass(KnowledgeWriteProposalDO.class);
        verify(mapper).insert(saved.capture());
        assertEquals("pending_confirm", saved.getValue().getStatus());
        assertEquals("memory", saved.getValue().getTarget());
        assertEquals("pending", saved.getValue().getProjectionStatus());
    }

    @Test
    void rejectsNonPendingAndDuplicateProposal() {
        assertThrows(ApiException.class, () -> service.create("7", new KnowledgeWriteProposal(
                "p-2", KnowledgeWriteProposal.Target.MEMORY, "event-2", "bundle-2", Map.of(),
                KnowledgeWriteProposal.Status.APPROVED)));
        when(mapper.insert(any(KnowledgeWriteProposalDO.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        assertThrows(ApiException.class, () -> service.create("7", proposal("p-3")));
    }

    @Test
    void memoryApprovalFailsClosedWithoutRuntimeProjectionExecutor() {
        KnowledgeWriteProposalDO row = row("p-memory", "approved");
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.update(any(), any())).thenReturn(1);

        KnowledgeWriteProposalReviewResult result = service.approve("7", "p-memory", "admin-1", "确认");

        assertEquals("failed", result.projectionStatus());
        assertEquals("没有匹配的写回投影执行器；禁止绕过事实源治理", result.projectionError());
        verify(mapper).update(any(), any());
    }

    @Test
    void memoryApprovalUsesMatchingProjectionExecutor() {
        KnowledgeWriteProposalDO row = row("p-runtime", "approved");
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.update(any(), any())).thenReturn(1);
        when(executor.supports(any())).thenReturn(true);
        when(executor.project(any(), any()))
                .thenReturn(KnowledgeWriteProposalProjectionExecutor.ProjectionResult.applied(
                        901L, Map.of("store", "agent_runtime_memory")));

        KnowledgeWriteProposalReviewResult result = serviceWithExecutor.approve(
                "7", "p-runtime", "admin-1", "确认");

        assertEquals("applied", result.projectionStatus());
        assertEquals(901L, result.projectionRef());
        verify(executor).project("7", result.proposal());
    }

    @Test
    void institutionalKnowledgeWithoutExecutorFailsClosed() {
        KnowledgeWriteProposalDO row = row("p-governance", "approved");
        row.setTarget("institutional_knowledge");
        when(mapper.selectOne(any())).thenReturn(row);
        when(mapper.update(any(), any())).thenReturn(1);

        KnowledgeWriteProposalReviewResult result = service.approve(
                "7", "p-governance", "admin-1", "确认");

        assertEquals("failed", result.projectionStatus());
        assertEquals("没有匹配的写回投影执行器；禁止绕过事实源治理", result.projectionError());
    }

    @Test
    void pendingApprovalUsesCasBeforeProjectionAndRejectIsSingleCas() {
        KnowledgeWriteProposalDO pending = row("p-pending", "pending_confirm");
        when(mapper.selectOne(any())).thenReturn(pending);
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            pending.setStatus("approved");
            return 1;
        });

        assertEquals("failed", service.approve("7", "p-pending", "admin-1", "确认")
                .projectionStatus());

        KnowledgeWriteProposalDO rejected = row("p-reject", "pending_confirm");
        when(mapper.selectOne(any())).thenReturn(rejected);
        when(mapper.update(any(), any())).thenAnswer(invocation -> {
            rejected.setStatus("rejected");
            return 1;
        });
        assertEquals(KnowledgeWriteProposal.Status.REJECTED,
                service.reject("7", "p-reject", "admin-1", "证据不足").status());
    }

    @Test
    void replaysAppliedProjectionWithoutCallingExecutor() {
        KnowledgeWriteProposalDO row = row("p-replay", "approved");
        row.setProjectionStatus("applied");
        row.setProjectionRef(91L);
        when(mapper.selectOne(any())).thenReturn(row);

        KnowledgeWriteProposalReviewResult result = serviceWithExecutor.approve(
                "7", "p-replay", "admin-1", "重试");

        assertEquals("applied", result.projectionStatus());
        assertEquals(91L, result.projectionRef());
        verify(executor, never()).supports(any());
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void listsPendingProposalsForTenant() {
        when(mapper.selectList(any())).thenReturn(List.of(row("p-6", "pending_confirm")));

        assertEquals(List.of(proposal("p-6")), service.listPending("7"));
    }

    private KnowledgeWriteProposal proposal(String proposalId) {
        return new KnowledgeWriteProposal(proposalId, KnowledgeWriteProposal.Target.MEMORY,
                "event-" + proposalId, "bundle-" + proposalId,
                Map.of("source", Map.of("kind", "observation",
                                "observation_type", "inbound.message"),
                        "memory", Map.of("type", "preference", "subject", "user:101",
                                "predicate", "prefers", "object", Map.of("value", "dark mode"))),
                KnowledgeWriteProposal.Status.PENDING_CONFIRM);
    }

    private KnowledgeWriteProposalDO row(String proposalId, String status) {
        KnowledgeWriteProposalDO row = new KnowledgeWriteProposalDO();
        row.setId(1L);
        row.setTenantId(7L);
        row.setProposalId(proposalId);
        row.setTarget("memory");
        row.setSourceEventId("event-" + proposalId);
        row.setBundleId("bundle-" + proposalId);
        row.setPayload(Map.of("source", Map.of("kind", "observation",
                        "observation_type", "inbound.message"),
                "memory", Map.of("type", "preference", "subject", "user:101",
                        "predicate", "prefers", "object", Map.of("value", "dark mode"))));
        row.setStatus(status);
        row.setProjectionStatus("pending");
        return row;
    }
}
