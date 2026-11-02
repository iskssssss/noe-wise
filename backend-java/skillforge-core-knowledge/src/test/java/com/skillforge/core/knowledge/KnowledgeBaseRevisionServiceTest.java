package com.skillforge.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.knowledge.model.KnowledgeBaseDO;
import com.skillforge.core.knowledge.model.KnowledgeBaseRevisionDO;
import com.skillforge.core.capability.CapabilityProducerPublicationBridge;
import com.skillforge.core.capability.CapabilityProducerPublicationResult;
import com.skillforge.core.capability.CapabilityVersionView;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.CapabilityProducerPublicationRequest;
import com.skillforge.core.shared.security.TokenHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseRevisionServiceTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private KnowledgeBaseRevisionMapper revisionMapper;
    @Mock
    private KnowledgeDocumentVersionMapper documentVersionMapper;
    @Mock
    private CapabilityProducerPublicationBridge publicationBridge;

    private KnowledgeBaseRevisionService service;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KnowledgeDocumentVersionDO.class);
        service = new KnowledgeBaseRevisionService(
                knowledgeBaseMapper, revisionMapper, documentVersionMapper, publicationBridge, objectMapper);
        org.mockito.Mockito.lenient().when(
                documentVersionMapper.countCompleteReadyVersions(anyLong(), anyLong(), any()))
                .thenReturn(1);
        var version=new com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO();
        version.setEmbeddingModelConfigId(81L);version.setEmbeddingModelRevision(3L);version.setEmbeddingDimensions(2);
        org.mockito.Mockito.lenient().when(documentVersionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(version));
    }

    @Test
    void createsFirstDraftAtRevisionOne() {
        when(revisionMapper.lockBase(42L, 7L)).thenReturn(base());
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        var result = service.create("42", 7L);

        assertEquals(1L, result.revision());
        assertEquals("draft", result.status());
        assertEquals(List.of(), result.sourceVersions());
        verify(revisionMapper).insert(any(KnowledgeBaseRevisionDO.class));
    }

    @Test
    void prepareReusesDraftAndFreezesOnlyExplicitVersionsWithoutPublishing() {
        var draft = revision("draft", 3L);
        when(revisionMapper.lockBase(42L, 7L)).thenReturn(base());
        when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draft);
        when(revisionMapper.updateById(any(KnowledgeBaseRevisionDO.class))).thenReturn(1);
        when(revisionMapper.markReady(42L, 7L, 3L, List.of("v-1"))).thenReturn(1);

        var ready = service.prepare("42", 7L, List.of("v-1"), policy());

        assertEquals(draft.getId(), ready.id());
        assertEquals("ready", ready.status());
        assertEquals(List.of("v-1"), ready.sourceVersions());
        verify(revisionMapper, never()).insert(any(KnowledgeBaseRevisionDO.class));
        verify(revisionMapper, never()).activateBase(anyLong(), anyLong(), anyLong());
        verifyNoInteractions(publicationBridge);
    }

    @Test
    void openingEditorAgainReusesUnpublishedDraft() {
        when(revisionMapper.lockBase(42L, 7L)).thenReturn(base());
        KnowledgeBaseRevisionDO draft = revision("draft", 3L);
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draft);

        var first = service.create("42", 7L);
        var second = service.create("42", 7L);

        assertEquals(draft.getId(), first.id());
        assertEquals(first.id(), second.id());
        assertEquals(3L, second.revision());
        verify(revisionMapper, never()).insert(any(KnowledgeBaseRevisionDO.class));
        verifyNoInteractions(publicationBridge);
    }

    @Test
    void readyNormalizesManifestAndRejectsUnknownVersion() {
        when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(revision("draft", 1L));
        when(revisionMapper.updateById(any(KnowledgeBaseRevisionDO.class))).thenReturn(1);
        when(revisionMapper.markReady(anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(1);

        var result = service.markReady("42", 7L, 1L, List.of(" v-1 ", "v-1"), policy());

        assertEquals("ready", result.status());
        assertEquals(List.of("v-1"), result.sourceVersions());
        assertEquals(List.of("VECTOR"), result.policy().allowedOperators());
    }

    @Test
    void explicitDocumentOperatorsSurviveReadyAndCapabilityPublicationWithoutImplicitQueryAuthority() {
        KnowledgeBaseRevisionDO draft = revision("draft", 2L);
        when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(revisionMapper.lockBase(42L, 7L)).thenReturn(base());
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draft);
        when(revisionMapper.updateById(any(KnowledgeBaseRevisionDO.class))).thenReturn(1);
        when(revisionMapper.markReady(anyLong(), anyLong(), anyLong(), any())).thenReturn(1);
        when(revisionMapper.publishReady(42L, 7L, 2L)).thenReturn(1);
        when(revisionMapper.activateBase(42L, 7L, 102L)).thenReturn(1);
        when(publicationBridge.publish(any())).thenReturn(publication());
        var expected = List.of("DOCUMENT_LIST", "DOCUMENT_READ");
        var ready = service.markReady("42", 7L, 2L, List.of("v-1"),
                new KnowledgeCapabilityPolicyRequest(true, false, Set.copyOf(expected), "PINNED", true));
        assertEquals(expected, ready.policy().allowedOperators());
        var published = service.publish("42", 7L, 2L);
        assertEquals("published", published.status()); assertEquals(expected, published.policy().allowedOperators());
        var request = ArgumentCaptor.forClass(CapabilityProducerPublicationRequest.class);
        verify(publicationBridge).publish(request.capture());
        assertEquals(expected, request.getValue().descriptor().get("allowed_operators"));
        assertEquals(List.of("v-1"), request.getValue().descriptor().get("source_versions"));
        assertEquals("7", request.getValue().descriptor().get("source_id"));
    }

    @Test
    void readyFailsClosedWhenManifestContainsMissingSourceVersion() {
        when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(revision("draft", 1L));
        when(documentVersionMapper.countCompleteReadyVersions(anyLong(), anyLong(), any()))
                .thenReturn(0);

        assertThrows(ApiException.class, () -> service.markReady(
                "42", 7L, 1L, List.of("missing"), policy()));
        verify(revisionMapper, never()).updateById(any(KnowledgeBaseRevisionDO.class));
        verify(revisionMapper, never()).markReady(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void publishRequiresReadyAndReportsCasConflict() {
        when(revisionMapper.lockBase(42L, 7L)).thenReturn(base());
        KnowledgeBaseRevisionDO ready = revision("ready", 2L);
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(ready);
        when(revisionMapper.updateById(any(KnowledgeBaseRevisionDO.class))).thenReturn(1);
        when(publicationBridge.publish(any())).thenReturn(publication());
        when(revisionMapper.publishReady(42L, 7L, 2L)).thenReturn(0);

        assertThrows(ApiException.class, () -> service.publish("42", 7L, 2L));

        ready.setCapabilityVersionId(null);
        when(revisionMapper.publishReady(42L, 7L, 2L)).thenReturn(1);
        when(revisionMapper.activateBase(42L, 7L, 102L)).thenReturn(1);
        var result = service.publish("42", 7L, 2L);
        assertEquals("published", result.status());
    }

    @Test
    void archivedBaseCannotPublish() {
        KnowledgeBaseDO base = base();
        base.setStatus("archived");
        when(revisionMapper.lockBase(42L, 7L)).thenReturn(base);

        assertThrows(ApiException.class, () -> service.publish("42", 7L, 1L));
        verify(revisionMapper, never()).publishReady(anyLong(), anyLong(), anyLong());
    }

    @Test
    void readyRequiresCompletePolicyAndRejectsEmptyOperators() {
        when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(revision("draft", 1L));

        assertThrows(ApiException.class,
                () -> service.markReady("42", 7L, 1L, List.of("v-1"), null));
        assertThrows(ApiException.class,
                () -> service.markReady("42", 7L, 1L, List.of("v-1"),
                        new KnowledgeCapabilityPolicyRequest(true, false, Set.of(), "PINNED", true)));
        verifyNoInteractions(publicationBridge);
    }

    @Test
    void readyRejectsPolicyWithMissingGovernanceField() {
        when(knowledgeBaseMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base());
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(revision("draft", 1L));

        assertThrows(ApiException.class,
                () -> service.markReady("42", 7L, 1L, List.of("v-1"),
                        new KnowledgeCapabilityPolicyRequest(true, false, Set.of("VECTOR"), null, true)));
        verifyNoInteractions(publicationBridge);
    }

    @Test
    void publishBridgeReceivesStrictIdentityDescriptorAndDeterministicHash() throws Exception {
        KnowledgeBaseRevisionDO ready = revision("ready", 2L);
        when(revisionMapper.lockBase(42L, 7L)).thenReturn(base());
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(ready);
        when(revisionMapper.updateById(any(KnowledgeBaseRevisionDO.class))).thenReturn(1);
        when(revisionMapper.publishReady(42L, 7L, 2L)).thenReturn(1);
        when(revisionMapper.activateBase(42L, 7L, 102L)).thenReturn(1);
        when(publicationBridge.publish(any())).thenReturn(publication());

        var result = service.publish("42", 7L, 2L);
        ArgumentCaptor<CapabilityProducerPublicationRequest> captor =
                ArgumentCaptor.forClass(CapabilityProducerPublicationRequest.class);
        verify(publicationBridge).publish(captor.capture());
        CapabilityProducerPublicationRequest request = captor.getValue();

        assertEquals(CapabilityType.KNOWLEDGE_SOURCE, request.capabilityType());
        assertEquals("knowledge-base:7", request.capabilityKey());
        assertEquals("revision:2", request.versionKey());
        assertEquals("合同知识库", request.name());
        assertEquals("合同知识库描述", request.description());
        assertEquals(Set.of(
                "model_invocable", "user_invocable", "allowed_operators",
                "freshness_policy", "citation_required", "source_id", "source_versions"),
                request.descriptor().keySet());
        assertEquals(900L, result.capabilityVersionId());
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("model_invocable", true);
        descriptor.put("user_invocable", false);
        descriptor.put("allowed_operators", List.of("VECTOR"));
        descriptor.put("freshness_policy", "PINNED");
        descriptor.put("citation_required", true);
        descriptor.put("source_id", "7");
        descriptor.put("source_versions", List.of("v-1"));
        String canonical = new ObjectMapper().writer()
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(descriptor);
        assertEquals(TokenHash.sha256Hex(canonical), request.contentHash());
        assertEquals(request.contentHash(), result.capabilityContentHash());
    }

    @Test
    void publishNormalizesNullableBaseDescriptionToCatalogEmptyDescription() {
        KnowledgeBaseDO base = base();
        base.setDescription(null);
        KnowledgeBaseRevisionDO ready = revision("ready", 2L);
        when(revisionMapper.lockBase(42L, 7L)).thenReturn(base);
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(ready);
        when(publicationBridge.publish(any())).thenReturn(publication());
        when(revisionMapper.updateById(any(KnowledgeBaseRevisionDO.class))).thenReturn(1);
        when(revisionMapper.publishReady(42L, 7L, 2L)).thenReturn(1);
        when(revisionMapper.activateBase(42L, 7L, 102L)).thenReturn(1);

        service.publish("42", 7L, 2L);

        ArgumentCaptor<CapabilityProducerPublicationRequest> captor =
                ArgumentCaptor.forClass(CapabilityProducerPublicationRequest.class);
        verify(publicationBridge).publish(captor.capture());
        assertEquals("合同知识库", captor.getValue().name());
        assertEquals("", captor.getValue().description());
    }

    @Test
    void catalogPublicationFailureDoesNotAdvanceKnowledgeRevision() {
        KnowledgeBaseRevisionDO ready = revision("ready", 2L);
        when(revisionMapper.lockBase(42L, 7L)).thenReturn(base());
        when(revisionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(ready);
        when(publicationBridge.publish(any()))
                .thenThrow(new IllegalStateException("catalog unavailable"));

        assertThrows(IllegalStateException.class, () -> service.publish("42", 7L, 2L));

        assertEquals("ready", ready.getStatus());
        assertNull(ready.getCapabilityVersionId());
        verify(revisionMapper, never()).updateById(any(KnowledgeBaseRevisionDO.class));
        verify(revisionMapper, never()).archivePublished(anyLong(), anyLong(), anyLong());
        verify(revisionMapper, never()).publishReady(anyLong(), anyLong(), anyLong());
        verify(revisionMapper, never()).activateBase(anyLong(), anyLong(), anyLong());
    }

    private static KnowledgeBaseDO base() {
        KnowledgeBaseDO base = new KnowledgeBaseDO();
        base.setEmbeddingModelConfigId(81L);base.setEmbeddingModelRevision(3L);base.setEmbeddingDimensions(2);
        base.setId(7L);
        base.setTenantId(42L);
        base.setName("合同知识库");
        base.setDescription("合同知识库描述");
        base.setStatus("active");
        return base;
    }

    private static KnowledgeBaseRevisionDO revision(String status, long revisionNumber) {
        KnowledgeBaseRevisionDO revision = new KnowledgeBaseRevisionDO();
        revision.setEmbeddingModelConfigId(81L);revision.setEmbeddingModelRevision(3L);revision.setEmbeddingDimensions(2);
        revision.setId(100L + revisionNumber);
        revision.setTenantId(42L);
        revision.setKbId(7L);
        revision.setRevision(revisionNumber);
        revision.setStatus(status);
        revision.setSourceVersions(List.of("v-1"));
        revision.setCapabilityPolicy(Map.of(
                "model_invocable", true,
                "user_invocable", false,
                "allowed_operators", List.of("VECTOR"),
                "freshness_policy", "PINNED",
                "citation_required", true));
        return revision;
    }

    private static KnowledgeCapabilityPolicyRequest policy() {
        return new KnowledgeCapabilityPolicyRequest(true, false, Set.of("VECTOR"), "PINNED", true);
    }

    private static CapabilityProducerPublicationResult publication() {
        CapabilityVersionView version = new CapabilityVersionView(
                900L, 901L, 42L, CapabilityType.KNOWLEDGE_SOURCE, "revision:2",
                "Knowledge Source revision 2", "", "TENANT", Map.of(), "hash", "PUBLISHED", null, null);
        return new CapabilityProducerPublicationResult(null, version, false);
    }
}
