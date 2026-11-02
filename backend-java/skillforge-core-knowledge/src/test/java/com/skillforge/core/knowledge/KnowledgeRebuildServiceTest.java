package com.skillforge.core.knowledge;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.skillforge.core.knowledge.mapper.*;
import com.skillforge.core.knowledge.model.*;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeRebuildServiceTest {
 @Test void rebuildRejectsPartialOrMissingResultsInsteadOfKeywordOnlySuccess(){
  assertThrows(RuntimeException.class,()->KnowledgeRebuildService.strictVectors(null,1,2));
  assertThrows(RuntimeException.class,()->KnowledgeRebuildService.strictVectors(List.of(),1,2));
  assertThrows(RuntimeException.class,()->KnowledgeRebuildService.strictVectors(List.of(new float[]{1}),1,2));
 }
 @Test void rebuildRejectsNonfiniteVectorsAndPreservesBatchOrder(){
  assertThrows(RuntimeException.class,()->KnowledgeRebuildService.strictVectors(List.of(new float[]{Float.NaN,1}),1,2));
  assertEquals(List.of("[1.0,2.0]","[3.0,4.0]"),KnowledgeRebuildService.strictVectors(List.of(new float[]{1,2},new float[]{3,4}),2,2));
 }
 @Test void latestSnapshotUsesOnlyTheExactJobCountProjectionWithoutLoadingItemsOrReceipts(){
  var bases=mock(KnowledgeBaseMapper.class);var jobs=mock(KnowledgeRebuildJobMapper.class);
  var items=mock(KnowledgeRebuildItemMapper.class);var batches=mock(KnowledgeRebuildBatchMapper.class);
  var base=new KnowledgeBaseDO();base.setId(10L);base.setTenantId(7L);base.setControlRevision(9L);
  var job=new KnowledgeRebuildJobDO();job.setId(20L);job.setTenantId(7L);job.setKbId(10L);job.setStatus("RUNNING");job.setRevision(3L);
  when(bases.selectOne(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(base);
  when(jobs.selectOne(any(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class))).thenReturn(job);
  var service=new KnowledgeRebuildService(bases,mock(KnowledgeBaseRevisionMapper.class),mock(KnowledgeDocumentMapper.class),
    mock(KnowledgeDocumentVersionMapper.class),mock(KnowledgeChunkMapper.class),jobs,items,batches,
    mock(com.skillforge.core.model.PlatformModelCatalogService.class),mock(EmbeddingProvider.class),mock(KnowledgeBaseRevisionService.class),mock(KnowledgeStorageService.class),new TestTransactionManager());
  for(var expected:List.of(new KnowledgeRebuildJobMapper.Progress(0,0,0),new KnowledgeRebuildJobMapper.Progress(200,171,12))){
   when(jobs.progress(7,20)).thenReturn(expected);var result=service.latest("7",10);
   assertEquals(expected.documentCount(),result.documentCount());assertEquals(expected.readyDocuments(),result.readyDocuments());assertEquals(expected.unknownBatches(),result.unknownBatches());
   assertEquals("RUNNING",result.status());assertEquals(9L,result.baseRevision());assertTrue(result.writeLocked());
  }
  verify(jobs,times(2)).progress(7,20);verifyNoInteractions(items,batches);
 }
}
