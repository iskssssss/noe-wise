package com.skillforge.core.knowledge;

import com.skillforge.core.knowledge.mapper.KnowledgeIndexLifecycleMapper;
import com.skillforge.core.knowledge.search.SearchIndexer;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只验证投影生命周期决策；真实事务/FK/租约竞争由知识集成测试负责。 */
class KnowledgeIndexLifecycleServiceTest {
    /** 停用外部索引不创建保护行，也不重复领取或UPDATE清理意图。 */
    @Test void disabledIndexDoesNotCreateFakeHoldsOrRepeatedCleanupUpdates() {
        var mapper=mock(KnowledgeIndexLifecycleMapper.class);var index=mock(SearchIndexer.class);
        var service=new KnowledgeIndexLifecycleService(mapper,index,new TestTransactionManager());
        assertNull(service.protect(7,10,"v1"));assertEquals(0,service.drain(7,10));
        verifyNoInteractions(mapper);
    }
    /** 未确认投影结果保留同一持有者，不能因另一个修复确认而解除旧UNKNOWN。 */
    @Test void unconfirmedProjectionPreservesItsOwnHold() {
        var mapper=mock(KnowledgeIndexLifecycleMapper.class);var index=mock(SearchIndexer.class);
        when(index.indexingEnabled()).thenReturn(true);when(mapper.insertHold(eq(7L),eq(10L),eq("v1"),anyLong())).thenReturn(1);
        var service=new KnowledgeIndexLifecycleService(mapper,index,new TestTransactionManager());
        long hold=service.protect(7,10,"v1");service.finish(7,hold,false);
        verify(mapper).markUnknown(7,hold);verify(mapper,never()).releaseHold(anyLong(),anyLong());
    }
    /** 删除只调用精确版本接口；成功回执命中本次领取代次，失败保留持久意图。 */
    @Test void cleanupUsesExactVersionAndAttemptFencing() {
        var mapper=mock(KnowledgeIndexLifecycleMapper.class);var index=mock(SearchIndexer.class);
        when(index.indexingEnabled()).thenReturn(true);
        when(mapper.claim(7,1)).thenReturn(List.of(new KnowledgeIndexLifecycleService.Cleanup(10,"v1",2)),List.of());
        when(index.deleteSourceVersion("7","10","v1")).thenReturn(true);when(mapper.complete(7,10,"v1",2)).thenReturn(1);
        var service=new KnowledgeIndexLifecycleService(mapper,index,new TestTransactionManager());
        assertEquals(1,service.drain(7,2));verify(mapper).complete(7,10,"v1",2);
        verify(index,never()).deleteChunks(anyString(),anyList());
    }

    /** 未确认或网络异常只延迟当前领取代次，不能把意图删除后计为完成。 */
    @Test void unconfirmedDeleteDefersTheSameAttemptWithoutCompletion() {
        var mapper=mock(KnowledgeIndexLifecycleMapper.class);var index=mock(SearchIndexer.class);
        when(index.indexingEnabled()).thenReturn(true);
        when(mapper.claim(7,1)).thenReturn(List.of(new KnowledgeIndexLifecycleService.Cleanup(10,"v1",3)));
        when(index.deleteSourceVersion("7","10","v1")).thenThrow(new IllegalStateException("controlled-network-failure"));
        when(mapper.defer(7,10,"v1",3)).thenReturn(1);
        var service=new KnowledgeIndexLifecycleService(mapper,index,new TestTransactionManager());
        assertEquals(0,service.drain(7,1));
        verify(mapper).defer(7,10,"v1",3);verify(mapper,never()).complete(anyLong(),anyLong(),anyString(),anyLong());
    }

    /** 迟到成功回执的CAS未命中不会确认新领取者，也不增加完成统计。 */
    @Test void staleSuccessfulDeleteReceiptDoesNotCountAsCompleted() {
        var mapper=mock(KnowledgeIndexLifecycleMapper.class);var index=mock(SearchIndexer.class);
        when(index.indexingEnabled()).thenReturn(true);
        when(mapper.claim(7,1)).thenReturn(List.of(new KnowledgeIndexLifecycleService.Cleanup(10,"v1",2)));
        when(index.deleteSourceVersion("7","10","v1")).thenReturn(true);
        when(mapper.complete(7,10,"v1",2)).thenReturn(0);
        var service=new KnowledgeIndexLifecycleService(mapper,index,new TestTransactionManager());
        assertEquals(0,service.drain(7,1));
        verify(mapper).complete(7,10,"v1",2);verify(mapper,never()).defer(anyLong(),anyLong(),anyString(),anyLong());
    }
}
