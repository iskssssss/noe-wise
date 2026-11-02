package com.skillforge.core.knowledge;

import com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeStorageMapper;
import com.skillforge.core.knowledge.model.KnowledgeChunkDO;
import com.skillforge.core.knowledge.model.KnowledgeDocumentVersionDO;
import com.skillforge.core.knowledge.model.KnowledgeStorageCapacity;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证知识写入决策与引用身份；事务、RLS及数据库受影响行数由真实依赖集成测试负责。 */
class KnowledgeStorageServiceTest {
    private KnowledgeStorageMapper mapper;
    private KnowledgeStorageService service;

    /** 每个用例使用明确的所属租户和无额度夹具，避免混用外部环境。 */
    @BeforeEach void setup() {
        mapper=mock(KnowledgeStorageMapper.class);
        service=new KnowledgeStorageService(mapper,mock(KnowledgeBaseRevisionMapper.class),mock(KnowledgeIndexLifecycleService.class));
        when(mapper.lockBudget(7)).thenReturn(7L);
        when(mapper.capacity(7)).thenReturn(new KnowledgeStorageCapacity(0,0,0,0,0,null,null,0,0,0,0,0,0));
        // 真实SELECT无行返回null；Mockito的Long默认0不能表示已存在正文或向量。
        when(mapper.findDocumentContent(anyLong(),anyLong(),anyString(),anyString())).thenReturn(null);
        when(mapper.findChunkContent(anyLong(),anyLong(),anyString(),anyString())).thenReturn(null);
        when(mapper.findEmbedding(anyLong(),anyLong(),anyLong(),anyLong(),anyInt(),anyString())).thenReturn(null);
    }

    /** 标题只改变元数据版本；正文身份、集合和向量保持精确复用。 */
    @Test void titleRevisionDoesNotInsertContentSetOrEmbedding() {
        var title=version();title.setContentId(11L);title.setChunkSetId(12L);
        service.prepareVersion(title,List.of(chunk(31,21,22,"preserved")));
        assertEquals(11L,title.getContentId());assertEquals(12L,title.getChunkSetId());
        verify(mapper,never()).insertDocumentContent(anyLong(),anyLong(),anyLong(),anyString(),anyString());
        verify(mapper,never()).insertSet(anyLong(),anyLong(),anyLong(),anyLong());
        verify(mapper,never()).insertChunk(any());
        verify(mapper,never()).insertEmbedding(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt(),anyString());
    }

    /** 单块变化只新增变化正文/向量；完整关系仍保留未变化块的旧正文身份。 */
    @Test void oneChangedChunkPreservesUnchangedBodyAndEmbeddingIds() {
        var next=version();next.setContentId(11L);
        var changed=chunk(32,0,0,"changed");changed.setContentId(null);changed.setEmbeddingId(null);changed.setEmbedding("[0.3,0.4]");
        var unchanged=chunk(33,21,22,"preserved");
        service.prepareVersion(next,List.of(changed,unchanged));
        verify(mapper,times(1)).insertChunkContent(eq(7L),eq(20L),anyLong(),anyString(),eq("changed"));
        verify(mapper,times(1)).insertEmbedding(eq(7L),anyLong(),eq(changed.getContentId()),eq(81L),eq(3L),eq(2),eq("[0.3,0.4]"));
        var relationships=ArgumentCaptor.forClass(KnowledgeChunkDO.class);
        verify(mapper,times(2)).insertChunk(relationships.capture());
        assertEquals(21L,relationships.getAllValues().get(1).getContentId());
        assertEquals(22L,relationships.getAllValues().get(1).getEmbeddingId());
        assertEquals(2,relationships.getAllValues().size());
        verify(mapper,times(1)).capacity(7);
    }

    /** 同内容向量在重建暂存和正式集合中使用同一所有者。 */
    @Test void stagedSuccessAndTargetVersionReferenceOneVectorBody() {
        var target=version();target.setContentId(11L);
        var source=chunk(31,21,22,"preserved");
        when(mapper.findEmbedding(eq(7L),eq(21L),eq(81L),eq(3L),eq(2),eq("[0.3,0.4]"))).thenReturn(90L);
        service.saveBatch(target,50,List.of(source),List.of("[0.3,0.4]"));
        verify(mapper).insertBatchEmbedding(7,50,0,90);
        var relation=chunk(34,21,90,"preserved");relation.setEmbedding(null);
        service.prepareVersion(target,List.of(relation));
        assertEquals(90L,relation.getEmbeddingId());
        verify(mapper,never()).insertEmbedding(anyLong(),anyLong(),anyLong(),anyLong(),anyLong(),anyInt(),anyString());
    }

    /** 中文真实 UTF-8 字节超额时在首个正文插入之前拒绝。 */
    @Test void budgetUsesUtf8BytesAndRejectsBeforeAnyBodyInsert() {
        when(mapper.capacity(7)).thenReturn(new KnowledgeStorageCapacity(0,0,0,0,0,5L,null,0,0,0,0,0,0));
        var next=version();next.setContent("中文");
        assertThrows(ApiException.class,()->service.prepareVersion(next,List.of()));
        verify(mapper,never()).insertDocumentContent(anyLong(),anyLong(),anyLong(),anyString(),anyString());
        verify(mapper,never()).insertSet(anyLong(),anyLong(),anyLong(),anyLong());
    }

    /** 引用集合和未确认期限不能以终态或默认短 TTL 绕过。 */
    @Test void reclamationRequiresExplicitBoundaryAndBoundedBatch() {
        assertThrows(ApiException.class,()->service.recycle(7,10,null,100));
        assertThrows(ApiException.class,()->service.recycle(7,10,Instant.now(),201));
        verify(mapper,never()).recyclableVersions(anyLong(),anyLong(),any(),anyInt());
    }

    /** 版本夹具保留明确的模型空间，不能由全局当前模型推断。 */
    private static KnowledgeDocumentVersionDO version() {
        var result=new KnowledgeDocumentVersionDO();result.setTenantId(7L);result.setKbId(10L);result.setDocumentId(20L);
        result.setContent("source");result.setSourceVersion("next");result.setEmbeddingModelConfigId(81L);
        result.setEmbeddingModelRevision(3L);result.setEmbeddingDimensions(2);return result;
    }

    /** 只构造同租户同文档的已存在正文身份，变化用例显式移除相应引用。 */
    private static KnowledgeChunkDO chunk(long id,long body,long embedding,String content) {
        var result=new KnowledgeChunkDO();result.setId(id);result.setTenantId(7L);result.setKbId(10L);result.setDocumentId(20L);
        result.setContentId(body);result.setEmbeddingId(embedding);result.setContent(content);result.setChunkIndex((int)(id-31));
        result.setSourceVersion("next");result.setEmbedding("[0.1,0.2]");result.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));return result;
    }
}
