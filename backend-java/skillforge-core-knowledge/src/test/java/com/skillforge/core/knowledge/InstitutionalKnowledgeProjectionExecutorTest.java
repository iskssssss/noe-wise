package com.skillforge.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.core.knowledge.dto.response.KnowledgeBaseRevisionResponse;
import com.skillforge.core.knowledge.dto.response.KnowledgeCapabilityPolicyResponse;
import com.skillforge.core.knowledge.mapper.KnowledgeGovernanceProjectionMapper;
import com.skillforge.core.knowledge.model.KnowledgeGovernanceProjectionDO;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.knowledge.knowledge.KnowledgeWriteProposal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InstitutionalKnowledgeProjectionExecutorTest {
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KnowledgeGovernanceProjectionDO.class);
    }

    private final KnowledgeGovernanceProjectionMapper mapper =
            Mockito.mock(KnowledgeGovernanceProjectionMapper.class);
    private final KnowledgeService knowledgeService = Mockito.mock(KnowledgeService.class);
    private final KnowledgeBaseRevisionService revisionService =
            Mockito.mock(KnowledgeBaseRevisionService.class);
    private final InstitutionalKnowledgeProjectionExecutor executor =
            new InstitutionalKnowledgeProjectionExecutor(mapper, knowledgeService, revisionService, new TestTransactionManager());

    @Test
    void publishesInstitutionalKnowledgeThroughRevisionPath() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insertClaim(any())).thenReturn(1);
        when(knowledgeService.persistPreparedDocument(eq("7"), any()))
                .thenReturn(new com.skillforge.core.knowledge.dto.response.UploadDocumentResponse(201L, 1, "kbv-201"));
        when(revisionService.create("7", 101L)).thenReturn(revision(501L, "draft"));
        when(revisionService.markReady(eq("7"), eq(101L), eq(1L),
                eq(List.of("kbv-201")), any())).thenReturn(revision(502L, "ready"));
        when(revisionService.publish("7", 101L, 1L)).thenReturn(revision(502L, "published"));

        var result = executor.project("7", proposal("p-1", validPayload()));

        assertEquals("applied", result.status());
        assertNotNull(result.reference());
        assertEquals("institutional_knowledge", result.details().get("projection_type"));
        assertEquals("published", result.details().get("status"));
        verify(revisionService).publish("7", 101L, 1L);
    }

    @Test
    void rejectsGraphAndUnknownProjectionBeforeClaim() {
        ApiException rejected = assertThrows(ApiException.class,
                () -> executor.project("7", proposal("p-graph",
                        Map.of("projection_type", "graph", "graph", Map.of()))));

        assertEquals("projection_type 仅支持 institutional_knowledge", rejected.getMessage());
        verify(mapper, never()).insertClaim(any(KnowledgeGovernanceProjectionDO.class));
        verify(knowledgeService, never()).persistPreparedDocument(any(), any());
        verify(knowledgeService, never()).prepareProjectionDocument(any(), any(), any(), any());
    }

    @Test
    void appliedReplayDoesNotPublishAgain() {
        KnowledgeGovernanceProjectionDO row = row("p-replay", "applied");
        row.setDetails(Map.of("projection_type", "institutional_knowledge", "revision", 1));
        when(mapper.selectOne(any())).thenReturn(row);

        var result = executor.project("7", proposal("p-replay", validPayload()));

        assertEquals(900L, result.reference());
        verify(revisionService, never()).publish(any(), any(), any());
    }

    @Test
    void processingRecordPreventsConcurrentReplay() {
        when(mapper.selectOne(any())).thenReturn(row("p-processing", "processing"));

        var result = executor.project("7", proposal("p-processing", validPayload()));

        assertEquals("failed", result.status());
        assertEquals("机构知识投影正在由其他请求处理", result.error());
        verify(knowledgeService, never()).persistPreparedDocument(any(), any());
        verify(knowledgeService, never()).prepareProjectionDocument(any(), any(), any(), any());
    }

    @Test
    void competingClaimUsesCommittedWinnerWithoutPublishingAgain() {
        when(mapper.selectOne(any())).thenReturn(null);
        KnowledgeGovernanceProjectionDO winner = row("race", "applied");
        winner.setDetails(Map.of("revision", 1));
        when(mapper.insertClaim(any())).thenReturn(0);
        when(mapper.lockByProposal(7L, "race")).thenReturn(winner);
        var result = executor.project("7", proposal("race", validPayload()));
        assertEquals(900L, result.reference());
        verify(knowledgeService, never()).persistPreparedDocument(any(), any());
        verify(revisionService, never()).publish(any(), any(), any());
    }

    private Map<String, Object> validPayload() {
        return Map.of("projection_type", "institutional_knowledge",
                "institutional_knowledge", Map.of("kb_id", 101, "title", "批准内容",
                        "content", "正文", "capability_policy", Map.of(
                                "model_invocable", true, "user_invocable", false,
                                "allowed_operators", List.of("VECTOR"),
                                "freshness_policy", "PINNED", "citation_required", true)));
    }

    private KnowledgeWriteProposal proposal(String id, Map<String, Object> payload) {
        return new KnowledgeWriteProposal(id,
                KnowledgeWriteProposal.Target.INSTITUTIONAL_KNOWLEDGE,
                "event-" + id, "bundle-" + id, payload,
                KnowledgeWriteProposal.Status.APPROVED);
    }

    private KnowledgeGovernanceProjectionDO row(String id, String status) {
        KnowledgeGovernanceProjectionDO row = new KnowledgeGovernanceProjectionDO();
        row.setId(900L);
        row.setTenantId(7L);
        row.setProposalId(id);
        row.setProjectionType("institutional_knowledge");
        row.setStatus(status);
        row.setDetails(Map.of());
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        return row;
    }

    private KnowledgeBaseRevisionResponse revision(Long id, String status) {
        return new KnowledgeBaseRevisionResponse(id, 101L, 1L, status, List.of("kbv-201"),
                Instant.now(), Instant.now(), Instant.now(),
                new KnowledgeCapabilityPolicyResponse(true, false, List.of("VECTOR"),
                        "PINNED", true), 701L, "hash-" + id);
    }
}
