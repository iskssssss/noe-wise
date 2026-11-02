package com.skillforge.core.knowledge;

import com.skillforge.core.knowledge.mapper.KnowledgeIndexLifecycleMapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.knowledge.search.SearchIndexer;
import com.skillforge.core.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 知识来源外部投影的保护与清理意图；网络调用不占用数据库事务。
 * 未确认索引操作保持来源引用，回收提交后持久删除意图可重试，精确版本不影响共享分块的其他投影。
 */
@Service
public class KnowledgeIndexLifecycleService {
    private final KnowledgeIndexLifecycleMapper mapper;
    private final SearchIndexer indexer;
    private final TransactionTemplate writer;

    /** 使用已有索引器及事务设施，不建立独立队列或存储框架。 */
    public KnowledgeIndexLifecycleService(KnowledgeIndexLifecycleMapper mapper,SearchIndexer indexer,
            PlatformTransactionManager transactionManager) {
        this.mapper=mapper;this.indexer=indexer;this.writer=new TransactionTemplate(transactionManager);
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 来源事务登记进行中投影；停用索引时不创建没有实际调用的保护记录。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Long protect(long tenant,long kb,String source) {
        if(!indexer.indexingEnabled())return null;
        long id=SnowflakeIds.nextId();
        if(mapper.insertHold(tenant,kb,source,id)!=1)throw new ApiException(HttpStatus.NOT_FOUND,"knowledge_projection_source_unavailable");
        return id;
    }

    /** 显式修复先在独立事务锁定来源所属知识库并持有引用，再发起外部调用。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Long start(long tenant,long kb,String source) {
        if(!indexer.indexingEnabled())return null;
        if(mapper.lockBase(tenant,kb)==null)throw new ApiException(HttpStatus.NOT_FOUND,"knowledge_projection_source_unavailable");
        long id=SnowflakeIds.nextId();
        if(mapper.insertHold(tenant,kb,source,id)!=1)throw new ApiException(HttpStatus.NOT_FOUND,"knowledge_projection_source_unavailable");
        return id;
    }

    /** 确认全部逐项写入后解除本次持有者；未确认只转UNKNOWN，不能用TTL解除。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finish(long tenant,Long hold,boolean confirmed) {
        if(hold==null)return;
        if(confirmed)mapper.releaseHold(tenant,hold);
        else mapper.markUnknown(tenant,hold);
    }

    /** 版本回收事务先持久登记精确删除意图，数据库删除失败则意图同事务回滚。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(long tenant,long kb,long versionId) { mapper.enqueue(tenant,kb,versionId); }

    /**
     * 既有知识调度器有界消费清理意图；lease超时可重试精确幂等删除，不重放模型调用。
     * ES停用时不领取、不UPDATE；未确认保留意图并延迟后续批次，完成只删除该意图。
     */
    public int drain(long tenant,int limit) {
        if(!indexer.indexingEnabled())return 0;
        if(limit<1||limit>100)throw new IllegalArgumentException("knowledge_index_cleanup_limit_invalid");
        int complete=0;
        for(int slot=0;slot<limit;slot++) {
            // 逐项领取后立即派发，避免后续项等待网络时提前耗尽租约。
            List<Cleanup> claimed=writer.execute(status->mapper.claim(tenant,1));
            if(claimed==null||claimed.isEmpty())break;
            Cleanup cleanup=claimed.getFirst();
            boolean confirmed;
            try { confirmed=indexer.deleteSourceVersion(Long.toString(tenant),Long.toString(cleanup.kbId()),cleanup.sourceVersion()); }
            catch(RuntimeException failure) { confirmed=false; }
            final boolean result=confirmed;
            int affected=writer.execute(status->result?mapper.complete(tenant,cleanup.kbId(),cleanup.sourceVersion(),cleanup.attempt())
                    :mapper.defer(tenant,cleanup.kbId(),cleanup.sourceVersion(),cleanup.attempt()));
            if(confirmed)complete+=affected;
        }
        return complete;
    }

    /**
     * 已领取的精确删除任务；attempt是租约领取代次，迟到回执不能结束新持有者。
     * @param kbId 所属知识库
     * @param sourceVersion 已退役来源版本
     * @param attempt 当前领取代次
     */
    public record Cleanup(long kbId,String sourceVersion,long attempt) {}
}
