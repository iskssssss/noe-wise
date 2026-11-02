package com.skillforge.core.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeDocumentVersionMapper;
import com.skillforge.core.knowledge.model.KnowledgeBaseDO;
import com.skillforge.core.knowledge.model.KnowledgeBaseRevisionDO;
import com.skillforge.core.capability.CapabilityProducerPublicationBridge;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Additional revision lifecycle validation coverage. */
class KnowledgeBaseRevisionAdditionalTest {

    @Test
    void listsLatestRevisionAndRejectsInvalidBaseRevisionAndManifestInputs() {
        KnowledgeBaseMapper bases = mock(KnowledgeBaseMapper.class);
        KnowledgeBaseRevisionMapper revisions = mock(KnowledgeBaseRevisionMapper.class);
        KnowledgeDocumentVersionMapper documents = mock(KnowledgeDocumentVersionMapper.class);
        CapabilityProducerPublicationBridge bridge = mock(CapabilityProducerPublicationBridge.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        KnowledgeBaseRevisionService service = new KnowledgeBaseRevisionService(
                bases, revisions, documents, bridge, objectMapper);
        KnowledgeBaseDO base = new KnowledgeBaseDO();
        base.setId(7L);
        base.setTenantId(1L);
        base.setStatus("active");
        when(bases.selectOne(any(LambdaQueryWrapper.class))).thenReturn(base);
        KnowledgeBaseRevisionDO revision = new KnowledgeBaseRevisionDO();
        revision.setRevision(2L);
        revision.setSourceVersions(List.of("v2"));
        revision.setStatus("ready");
        when(revisions.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(revision));
        assertEquals(1, service.list("1", 7L).total());
        assertThrows(ApiException.class, () -> service.list("1", 0L));
        KnowledgeCapabilityPolicyRequest policy = new KnowledgeCapabilityPolicyRequest(
                true, false, Set.of("VECTOR"), "PINNED", true);
        assertThrows(ApiException.class,
                () -> service.markReady("1", 7L, 2L, List.of(), policy));
        assertThrows(ApiException.class,
                () -> service.markReady("1", 7L, 2L, List.of(" "), policy));
    }
}
