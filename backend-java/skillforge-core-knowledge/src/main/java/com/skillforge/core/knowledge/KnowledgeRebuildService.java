package com.skillforge.core.knowledge;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.skillforge.core.knowledge.mapper.*;
import com.skillforge.core.knowledge.model.*;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.shared.security.TokenHash;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 全库重建只准备新不可变版本；显式发布在同一事务切换模型、来源指针和Catalog。 */
@Service
public class KnowledgeRebuildService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(KnowledgeRebuildService.class);
    private static final int BATCH_SIZE=20;
    private static final Set<String> TERMINAL=Set.of("FAILED","UNKNOWN","CANCELLED","PUBLISHED");
    private final KnowledgeBaseMapper bases;
    private final KnowledgeBaseRevisionMapper revisions;
    private final KnowledgeDocumentMapper documents;
    private final KnowledgeDocumentVersionMapper versions;
    private final KnowledgeChunkMapper chunks;
    private final KnowledgeRebuildJobMapper jobs;
    private final KnowledgeRebuildItemMapper items;
    private final KnowledgeRebuildBatchMapper batches;
    private final PlatformModelCatalogService models;
    private final EmbeddingProvider embedding;
    private final KnowledgeBaseRevisionService publications;
    /** 知识领域唯一正文/向量所有者与暂存引用入口。 */
    private final KnowledgeStorageService storage;
    private final TransactionTemplate tx;

    public KnowledgeRebuildService(KnowledgeBaseMapper bases,KnowledgeBaseRevisionMapper revisions,
            KnowledgeDocumentMapper documents,KnowledgeDocumentVersionMapper versions,KnowledgeChunkMapper chunks,
            KnowledgeRebuildJobMapper jobs,KnowledgeRebuildItemMapper items,KnowledgeRebuildBatchMapper batches,
            PlatformModelCatalogService models,EmbeddingProvider embedding,KnowledgeBaseRevisionService publications,
            KnowledgeStorageService storage,
            PlatformTransactionManager transactionManager) {
        this.bases=bases;this.revisions=revisions;this.documents=documents;this.versions=versions;this.chunks=chunks;
        this.jobs=jobs;this.items=items;this.batches=batches;this.models=models;this.embedding=embedding;
        this.publications=publications;this.storage=storage;this.tx=new TransactionTemplate(transactionManager);
    }

    /** 幂等受理并冻结全部未删除current来源；与所有内容写共享KB行锁。 */
    public Snapshot create(String tenantId,long kbId,String modelId,long modelRevision,String key,long expectedBaseRevision) {
        if(key==null||key.isBlank()||key.length()>128) throw invalid("knowledge_rebuild_key_invalid");
        PlatformModelConfig model=target(modelId,modelRevision);
        return tx.execute(status->{
            long tenant=Long.parseLong(tenantId);KnowledgeBaseDO base=lockBase(tenant,kbId);
            String fingerprint=TokenHash.sha256Hex(kbId+":"+modelId+":"+modelRevision);
            KnowledgeRebuildJobDO replay=jobs.selectOne(new LambdaQueryWrapper<KnowledgeRebuildJobDO>()
                    .eq(KnowledgeRebuildJobDO::getTenantId,tenant).eq(KnowledgeRebuildJobDO::getKbId,kbId)
                    .eq(KnowledgeRebuildJobDO::getIdempotencyKey,key));
            if(replay!=null){if(!fingerprint.equals(replay.getFingerprint()))throw conflict("knowledge_rebuild_idempotency_conflict");return snapshot(replay);}
            if(!Objects.equals(base.getControlRevision(),expectedBaseRevision))throw conflict("knowledge_base_revision_conflict");
            if(base.getRebuildJobId()!=null)throw conflict("knowledge_rebuild_write_locked");
            if(!"active".equals(base.getStatus()))throw conflict("knowledge_base_archived");
            List<KnowledgeDocumentDO> source=documents.selectList(new LambdaQueryWrapper<KnowledgeDocumentDO>()
                    .eq(KnowledgeDocumentDO::getTenantId,tenant).eq(KnowledgeDocumentDO::getKbId,kbId)
                    .isNull(KnowledgeDocumentDO::getDeletedAt).orderByAsc(KnowledgeDocumentDO::getId));
            if(source.isEmpty())throw invalid("knowledge_rebuild_empty");
            KnowledgeRebuildJobDO job=new KnowledgeRebuildJobDO();job.setId(SnowflakeIds.nextId());job.setTenantId(tenant);job.setKbId(kbId);
            job.setIdempotencyKey(key);job.setFingerprint(fingerprint);job.setStatus("QUEUED");job.setRevision(1L);job.setFence(0L);
            job.setTargetModelId(Long.parseLong(modelId));job.setTargetModelRevision(modelRevision);job.setTargetDimensions(model.dimensions());
            job.setOriginalActiveRevisionId(base.getActiveRevisionId());job.setOriginalModelId(base.getEmbeddingModelConfigId());
            job.setOriginalModelRevision(base.getEmbeddingModelRevision());job.setCreatedAt(Instant.now());job.setUpdatedAt(job.getCreatedAt());
            captureMeteringOrigin(job);jobs.insert(job);
            for(KnowledgeDocumentDO document:source){
                KnowledgeDocumentVersionDO original=version(tenant,kbId,document.getCurrentSourceVersion());
                List<KnowledgeChunkDO> originalChunks=sourceChunks(tenant,document.getId(),original.getSourceVersion());
                if(!"ready".equals(original.getStatus())||original.getChunkCount()==null||original.getChunkCount()<=0
                        ||originalChunks.size()!=original.getChunkCount())throw invalid("knowledge_rebuild_source_incomplete");
                KnowledgeRebuildItemDO item=new KnowledgeRebuildItemDO();item.setId(SnowflakeIds.nextId());item.setTenantId(tenant);item.setJobId(job.getId());
                item.setDocumentId(document.getId());item.setSourceVersion(original.getSourceVersion());item.setTargetSourceVersion("kbv-"+SnowflakeIds.nextId());
                item.setStatus("PENDING");item.setChunkCount(originalChunks.size());items.insert(item);
                for(int index=0;index<(originalChunks.size()+BATCH_SIZE-1)/BATCH_SIZE;index++){
                    KnowledgeRebuildBatchDO batch=new KnowledgeRebuildBatchDO();batch.setId(SnowflakeIds.nextId());batch.setTenantId(tenant);batch.setJobId(job.getId());
                    batch.setItemId(item.getId());batch.setBatchIndex(index);batch.setStatus("PENDING");batch.setInvocationId("knowledge-rebuild:"+job.getId()+":"+item.getId()+":"+index);batches.insert(batch);
                }
            }
            base.setRebuildJobId(job.getId());bases.updateById(base);return snapshot(job);
        });
    }

    public Snapshot latest(String tenantId,long kbId){
        requireBase(Long.parseLong(tenantId),kbId);
        KnowledgeRebuildJobDO job=jobs.selectOne(new LambdaQueryWrapper<KnowledgeRebuildJobDO>()
                .eq(KnowledgeRebuildJobDO::getTenantId,Long.parseLong(tenantId)).eq(KnowledgeRebuildJobDO::getKbId,kbId)
                .orderByDesc(KnowledgeRebuildJobDO::getCreatedAt).last("LIMIT 1"));
        if(job!=null)return snapshot(job);
        KnowledgeBaseDO base=requireBase(Long.parseLong(tenantId),kbId);
        return new Snapshot(null,kbId,"IDLE",null,null,null,null,0,0,0,null,null,false,base.getControlRevision());
    }
    public List<Long> due(String tenantId){return jobs.due(Long.parseLong(tenantId)).stream().map(KnowledgeRebuildJobDO::getId).toList();}

    /** 只调度可领取任务；过期STARTED保持UNKNOWN，禁止自动重发外调。 */
    public void run(String tenantId,long jobId){
        long tenant=Long.parseLong(tenantId);
        KnowledgeRebuildJobDO claimed=tx.execute(status->{
            KnowledgeRebuildJobDO found=requireJob(tenant,jobId);KnowledgeBaseDO base=lockBase(tenant,found.getKbId());KnowledgeRebuildJobDO job=jobs.lock(tenant,jobId);
            if(!Objects.equals(base.getRebuildJobId(),jobId)||!("QUEUED".equals(job.getStatus())||"RUNNING".equals(job.getStatus())
                    &&job.getLeaseUntil()!=null&&job.getLeaseUntil().isBefore(Instant.now())))return null;
            job.setFence(job.getFence()+1);job.setRevision(job.getRevision()+1);job.setStatus("RUNNING");job.setLeaseUntil(Instant.now().plusSeconds(300));jobs.updateById(job);
            List<KnowledgeRebuildBatchDO> started=jobBatches(job).stream().filter(b->"STARTED".equals(b.getStatus())).toList();
            if(!started.isEmpty()){
                for(var batch:started){batch.setStatus("UNKNOWN");batch.setErrorCode("embedding_result_unknown");batches.updateById(batch);}
                finish(job,base,"UNKNOWN","embedding_result_unknown");return null;
            }
            return job;
        });
        if(claimed==null)return;
        try {
            target(claimed.getTargetModelId().toString(),claimed.getTargetModelRevision());
            for(var item:jobItems(claimed)){
                if("READY".equals(item.getStatus()))continue;
                List<KnowledgeChunkDO> input=sourceChunks(tenant,item.getDocumentId(),item.getSourceVersion());
                for(var batch:itemBatches(item)){
                    if("READY".equals(batch.getStatus()))continue;
                    boolean started=Boolean.TRUE.equals(tx.execute(status->{
                        KnowledgeRebuildJobDO current=owned(claimed);if(current==null)return false;
                        if(!"PENDING".equals(batch.getStatus()))throw conflict("knowledge_rebuild_batch_not_pending");
                        target(current.getTargetModelId().toString(),current.getTargetModelRevision());
                        batch.setStatus("STARTED");batch.setErrorCode(null);batches.updateById(batch);renew(current);return true;
                    }));
                    if(!started)return;
                    int offset=batch.getBatchIndex()*BATCH_SIZE;
                    List<String> texts=input.subList(offset,Math.min(offset+BATCH_SIZE,input.size())).stream().map(KnowledgeChunkDO::getContent).toList();
                    List<float[]> result;
                    // 后台任务使用受理时冻结的来源；线程残留的 MeterScope 不得覆盖持久身份。
                    try(MeterScope scope=MeterScope.openIsolated(restoreMeteringOrigin(claimed,batch.getInvocationId()))){
                        result=embedding.embedBatch(tenantId,claimed.getTargetModelId().toString(),claimed.getTargetModelRevision(),texts);
                    }
                    List<String> vectors=strictVectors(result,texts.size(),claimed.getTargetDimensions());
                    boolean saved=Boolean.TRUE.equals(tx.execute(status->{
                        KnowledgeRebuildJobDO current=owned(claimed);if(current==null)return false;
                        KnowledgeDocumentVersionDO targetVersion=new KnowledgeDocumentVersionDO();
                        targetVersion.setTenantId(current.getTenantId());
                        targetVersion.setEmbeddingModelConfigId(current.getTargetModelId());
                        targetVersion.setEmbeddingModelRevision(current.getTargetModelRevision());
                        targetVersion.setEmbeddingDimensions(current.getTargetDimensions());
                        storage.saveBatch(targetVersion,batch.getId(),input.subList(offset,offset+vectors.size()),vectors);
                        batch.setStatus("READY");batches.updateById(batch);renew(current);return true;
                    }));
                    if(!saved)return;
                }
                tx.executeWithoutResult(status->{
                    if(owned(claimed)==null)return;
                    stageVersion(claimed,item,input,itemBatches(item));
                });
            }
            tx.executeWithoutResult(status->{
                KnowledgeRebuildJobDO current=owned(claimed);if(current!=null){current.setStatus("READY");current.setLeaseUntil(null);
                    current.setRevision(current.getRevision()+1);jobs.updateById(current);}
            });
        } catch(RuntimeException failure){
            log.warn("知识库重建未完成 jobId={} reason=knowledge_rebuild_failed diagnostic={}",jobId,com.skillforge.core.shared.logging.SafeFailureDiagnostics.of(failure));
            tx.executeWithoutResult(status->{
                KnowledgeRebuildJobDO current=owned(claimed);if(current==null)return;
                boolean unknown=false;
                for(var batch:jobBatches(current))if("STARTED".equals(batch.getStatus())){batch.setStatus("UNKNOWN");batch.setErrorCode("embedding_result_unknown");batches.updateById(batch);unknown=true;}
                finish(current,lockBase(tenant,current.getKbId()),unknown?"UNKNOWN":"FAILED",unknown?"embedding_result_unknown":"knowledge_rebuild_failed");
            });
        }
    }

    public Snapshot cancel(String tenantId,long kbId,long jobId,long expectedRevision){
        return tx.execute(status->{
            long tenant=Long.parseLong(tenantId);KnowledgeBaseDO base=lockBase(tenant,kbId);KnowledgeRebuildJobDO job=command(tenant,kbId,jobId,expectedRevision);
            if("CANCELLED".equals(job.getStatus()))return snapshot(job);
            if("PUBLISHED".equals(job.getStatus()))throw conflict("knowledge_rebuild_already_published");
            for(var batch:jobBatches(job))if("STARTED".equals(batch.getStatus())){batch.setStatus("UNKNOWN");batch.setErrorCode("embedding_cancelled_result_unknown");batches.updateById(batch);}
            finish(job,base,"CANCELLED",null);return snapshot(job);
        });
    }

    /** 显式重算未知批次可能再次产生费用；成功批次保持原回执且不得重新调用。 */
    public Snapshot retry(String tenantId,long kbId,long jobId,long expectedRevision,boolean confirmUnknownCost){
        return tx.execute(status->{
            long tenant=Long.parseLong(tenantId);KnowledgeBaseDO base=lockBase(tenant,kbId);KnowledgeRebuildJobDO job=command(tenant,kbId,jobId,expectedRevision);
            if(!Set.of("FAILED","UNKNOWN","CANCELLED").contains(job.getStatus()))throw conflict("knowledge_rebuild_not_retryable");
            if(base.getRebuildJobId()!=null)throw conflict("knowledge_rebuild_write_locked");assertUnchanged(job,base);
            target(job.getTargetModelId().toString(),job.getTargetModelRevision());
            for(var batch:jobBatches(job))if(!"READY".equals(batch.getStatus())){
                if("UNKNOWN".equals(batch.getStatus())&&!confirmUnknownCost)throw conflict("knowledge_rebuild_unknown_cost_confirmation_required");
                // 保留旧UNKNOWN回执，通过新的逻辑批次身份显式重算；原batch记录不覆盖。
                if("UNKNOWN".equals(batch.getStatus())){
                    List<Map<String,Object>> history=new ArrayList<>(batch.getReceiptHistory()==null?List.of():batch.getReceiptHistory());
                    history.add(Map.of("invocation_id",batch.getInvocationId(),"status",batch.getStatus(),"error_code",batch.getErrorCode()==null?"embedding_result_unknown":batch.getErrorCode()));
                    batch.setReceiptHistory(List.copyOf(history));
                    batch.setInvocationId("knowledge-rebuild:"+job.getId()+":"+batch.getItemId()+":"+batch.getBatchIndex()+":retry:"+job.getRevision());
                }
                batch.setStatus("PENDING");batch.setErrorCode(null);batches.updateById(batch);
            }
            job.setStatus("QUEUED");job.setErrorCode(null);job.setRevision(job.getRevision()+1);job.setFence(job.getFence()+1);jobs.updateById(job);
            base.setRebuildJobId(jobId);bases.updateById(base);return snapshot(job);
        });
    }

    /** 发布和所有指针切换共用同一事务；普通revision入口仍受冻结约束。 */
    public Snapshot publish(String tenantId,long kbId,long jobId,long expectedRevision,long expectedBaseRevision,KnowledgeCapabilityPolicyRequest policy){
        return tx.execute(status->{
            long tenant=Long.parseLong(tenantId);KnowledgeBaseDO base=lockBase(tenant,kbId);KnowledgeRebuildJobDO job=command(tenant,kbId,jobId,expectedRevision);
            if("PUBLISHED".equals(job.getStatus()))return snapshot(job);
            if(!Objects.equals(base.getControlRevision(),expectedBaseRevision))throw conflict("knowledge_base_revision_conflict");
            if(!"READY".equals(job.getStatus())||!Objects.equals(base.getRebuildJobId(),jobId))throw conflict("knowledge_rebuild_not_ready");
            target(job.getTargetModelId().toString(),job.getTargetModelRevision());assertUnchanged(job,base);
            job.setStatus("PUBLISHING");jobs.updateById(job);
            // KB行锁在事务提交前持续持有；解除字段锁只供同事务的既有发布服务使用。
            base.setRebuildJobId(null);base.setEmbeddingModelConfigId(job.getTargetModelId());base.setEmbeddingModelRevision(job.getTargetModelRevision());
            base.setEmbeddingDimensions(job.getTargetDimensions());bases.updateById(base);
            for(var item:jobItems(job)){
                KnowledgeDocumentVersionDO target=version(tenant,kbId,item.getTargetSourceVersion());
                if(!"READY".equals(item.getStatus())||!"ready".equals(target.getStatus())
                        ||!Objects.equals(target.getEmbeddingModelConfigId(),job.getTargetModelId())
                        ||!Objects.equals(target.getEmbeddingModelRevision(),job.getTargetModelRevision())
                        ||!Objects.equals(target.getEmbeddingDimensions(),job.getTargetDimensions())
                        ||versions.countCompleteVectorVersion(tenant,kbId,item.getTargetSourceVersion(),job.getTargetDimensions())!=1)
                    throw conflict("knowledge_rebuild_target_incomplete");
                if(documents.compareAndSetCurrentVersion(tenant,item.getDocumentId(),item.getSourceVersion(),item.getTargetSourceVersion())!=1)throw conflict("knowledge_rebuild_source_changed");
            }
            var draft=publications.create(tenantId,kbId);
            publications.markReady(tenantId,kbId,draft.revision(),jobItems(job).stream().map(KnowledgeRebuildItemDO::getTargetSourceVersion).toList(),policy);
            publications.publish(tenantId,kbId,draft.revision());
            job.setPublishedRevision(draft.revision());job.setStatus("PUBLISHED");job.setRevision(job.getRevision()+1);job.setFence(job.getFence()+1);jobs.updateById(job);
            storage.releasePublishedStaging(tenant,jobId);
            return snapshot(job);
        });
    }

    private void stageVersion(KnowledgeRebuildJobDO job,KnowledgeRebuildItemDO item,List<KnowledgeChunkDO> input,List<KnowledgeRebuildBatchDO> result){
        List<Long> vectorIds=result.stream().flatMap(b->{
            if(!"READY".equals(b.getStatus()))throw conflict("knowledge_rebuild_batch_incomplete");
            List<Long> ids=storage.batchEmbeddings(job.getTenantId(),b.getId());
            int expected=Math.min(BATCH_SIZE,input.size()-b.getBatchIndex()*BATCH_SIZE);
            if(ids.size()!=expected)throw conflict("knowledge_rebuild_batch_incomplete");
            return ids.stream();
        }).toList();
        if(vectorIds.size()!=input.size())throw conflict("knowledge_rebuild_vectors_incomplete");
        KnowledgeDocumentVersionDO original=version(job.getTenantId(),job.getKbId(),item.getSourceVersion());
        KnowledgeDocumentVersionDO next=new KnowledgeDocumentVersionDO();org.springframework.beans.BeanUtils.copyProperties(original,next);
        next.setId(SnowflakeIds.nextId());next.setSourceVersion(item.getTargetSourceVersion());next.setStatus("processing");next.setCreatedAt(Instant.now());
        next.setChunkSetId(null);
        next.setEmbeddingModelConfigId(job.getTargetModelId());next.setEmbeddingModelRevision(job.getTargetModelRevision());next.setEmbeddingDimensions(job.getTargetDimensions());
        List<KnowledgeChunkDO> targetChunks=new ArrayList<>();
        for(int i=0;i<input.size();i++){
            KnowledgeChunkDO chunk=new KnowledgeChunkDO();org.springframework.beans.BeanUtils.copyProperties(input.get(i),chunk);
            chunk.setId(SnowflakeIds.nextId());chunk.setSourceVersion(next.getSourceVersion());
            chunk.setEmbedding(null);chunk.setEmbeddingId(vectorIds.get(i));chunk.setCreatedAt(next.getCreatedAt());targetChunks.add(chunk);
        }
        storage.prepareVersion(next,targetChunks);versions.insert(next);
        next.setStatus("ready");versions.updateById(next);item.setStatus("READY");items.updateById(item);
    }

    /** 只从服务端计量作用域捕获因果来源，应用身份仍由持久 Run/ControlOperation 解析。 */
    static void captureMeteringOrigin(KnowledgeRebuildJobDO job) {
        log.debug("知识重建计量来源冻结进入");
        CostContext source=MeterScope.current();
        if(source!=null) {
            job.setMeteringRequestId(source.requestId());job.setMeteringRootInputId(source.rootInputId());
            job.setMeteringSessionId(source.sessionId());job.setMeteringRunId(source.runId());
            job.setMeteringScopeId(source.scopeId());job.setMeteringScopeType(source.scopeType());
            job.setMeteringEndUserId(source.endUserId());job.setMeteringAgentId(source.agentId());
            job.setMeteringChannelId(source.channelId());
        }
        log.debug("知识重建计量来源冻结退出 hasRun={} hasOperation={}",job.getMeteringRunId()!=null,"control_operation".equals(job.getMeteringScopeType()));
    }

    /** 从任务来源快照恢复身份并为每个真实批次提供稳定调用事件键。 */
    static CostContext restoreMeteringOrigin(KnowledgeRebuildJobDO job,String invocationId) {
        log.debug("知识重建计量来源恢复进入 hasJob={}",job!=null);
        var builder=CostContext.builder().eventId(invocationId).callPoint("knowledge.rebuild.embed");
        if(job!=null)builder.requestId(job.getMeteringRequestId()).rootInputId(job.getMeteringRootInputId())
                .sessionId(job.getMeteringSessionId()).runId(job.getMeteringRunId())
                .scopeId(job.getMeteringScopeId()).scopeType(job.getMeteringScopeType())
                .endUserId(job.getMeteringEndUserId()).agentId(job.getMeteringAgentId())
                .channelId(job.getMeteringChannelId());
        var result=builder.build();
        log.debug("知识重建计量来源恢复退出 hasRun={}",result.runId()!=null);
        return result;
    }

    static List<String> strictVectors(List<float[]> vectors,int count,int dimensions){
        if(vectors==null||vectors.size()!=count)throw conflict("knowledge_rebuild_embedding_incomplete");
        List<String> result=new ArrayList<>();for(float[] vector:vectors){
            if(vector==null||vector.length!=dimensions)throw conflict("embedding_dimensions_mismatch");
            StringJoiner text=new StringJoiner(",","[","]");for(float value:vector){if(!Float.isFinite(value))throw conflict("embedding_nonfinite");text.add(Float.toString(value));}result.add(text.toString());
        }return List.copyOf(result);
    }
    private PlatformModelConfig target(String id,long revision){
        models.requireEnabled(id,"EMBEDDING");PlatformModelConfig model=models.readRevision(id,revision);
        if(!model.enabled()||!"EMBEDDING".equals(model.kind())||model.dimensions()==null||model.dimensions()<=0)throw invalid("knowledge_rebuild_model_invalid");return model;
    }
    private void assertUnchanged(KnowledgeRebuildJobDO job,KnowledgeBaseDO base){
        if(!"active".equals(base.getStatus())||!Objects.equals(base.getActiveRevisionId(),job.getOriginalActiveRevisionId())
                ||!Objects.equals(base.getEmbeddingModelConfigId(),job.getOriginalModelId())||!Objects.equals(base.getEmbeddingModelRevision(),job.getOriginalModelRevision()))throw conflict("knowledge_rebuild_source_changed");
        List<KnowledgeDocumentDO> current=documents.selectList(new LambdaQueryWrapper<KnowledgeDocumentDO>().eq(KnowledgeDocumentDO::getTenantId,job.getTenantId())
                .eq(KnowledgeDocumentDO::getKbId,job.getKbId()).isNull(KnowledgeDocumentDO::getDeletedAt));
        List<KnowledgeRebuildItemDO> frozen=jobItems(job);
        if(current.size()!=frozen.size()||frozen.stream().anyMatch(item->current.stream().noneMatch(d->d.getId().equals(item.getDocumentId())&&Objects.equals(d.getCurrentSourceVersion(),item.getSourceVersion()))))throw conflict("knowledge_rebuild_source_changed");
    }
    private KnowledgeRebuildJobDO owned(KnowledgeRebuildJobDO claim){
        KnowledgeBaseDO base=lockBase(claim.getTenantId(),claim.getKbId());KnowledgeRebuildJobDO current=jobs.lock(claim.getTenantId(),claim.getId());
        if(current==null||!"RUNNING".equals(current.getStatus())||!Objects.equals(current.getFence(),claim.getFence())
                ||!Objects.equals(base.getRebuildJobId(),claim.getId())||current.getLeaseUntil()==null||current.getLeaseUntil().isBefore(Instant.now()))return null;return current;
    }
    private void renew(KnowledgeRebuildJobDO job){job.setLeaseUntil(Instant.now().plusSeconds(300));job.setUpdatedAt(Instant.now());jobs.updateById(job);}
    private void finish(KnowledgeRebuildJobDO job,KnowledgeBaseDO base,String state,String error){
        job.setStatus(state);job.setErrorCode(error);job.setLeaseUntil(null);job.setFence(job.getFence()+1);job.setRevision(job.getRevision()+1);job.setUpdatedAt(Instant.now());jobs.updateById(job);
        if(Objects.equals(base.getRebuildJobId(),job.getId())){base.setRebuildJobId(null);bases.updateById(base);}
    }
    private KnowledgeRebuildJobDO command(long tenant,long kb,long id,long revision){KnowledgeRebuildJobDO job=jobs.lock(tenant,id);
        if(job==null||job.getKbId()!=kb)throw missing();if(job.getRevision()!=revision)throw conflict("knowledge_rebuild_revision_conflict");return job;}
    private KnowledgeBaseDO requireBase(long tenant,long kb){KnowledgeBaseDO base=bases.selectOne(new LambdaQueryWrapper<KnowledgeBaseDO>().eq(KnowledgeBaseDO::getTenantId,tenant).eq(KnowledgeBaseDO::getId,kb));if(base==null)throw missing();return base;}
    private KnowledgeBaseDO lockBase(long tenant,long kb){KnowledgeBaseDO base=revisions.lockBase(tenant,kb);if(base==null)throw missing();return base;}
    private KnowledgeRebuildJobDO requireJob(long tenant,long id){KnowledgeRebuildJobDO job=jobs.selectOne(new LambdaQueryWrapper<KnowledgeRebuildJobDO>().eq(KnowledgeRebuildJobDO::getTenantId,tenant).eq(KnowledgeRebuildJobDO::getId,id));if(job==null)throw missing();return job;}
    private KnowledgeDocumentVersionDO version(long tenant,long kb,String source){KnowledgeDocumentVersionDO v=versions.selectOne(new LambdaQueryWrapper<KnowledgeDocumentVersionDO>().eq(KnowledgeDocumentVersionDO::getTenantId,tenant).eq(KnowledgeDocumentVersionDO::getKbId,kb).eq(KnowledgeDocumentVersionDO::getSourceVersion,source));if(v==null)throw missing();return v;}
    private List<KnowledgeChunkDO> sourceChunks(long tenant,long document,String source){return chunks.selectList(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getTenantId,tenant).eq(KnowledgeChunkDO::getDocumentId,document).eq(KnowledgeChunkDO::getSourceVersion,source).orderByAsc(KnowledgeChunkDO::getChunkIndex));}
    private List<KnowledgeRebuildItemDO> jobItems(KnowledgeRebuildJobDO job){return items.selectList(new LambdaQueryWrapper<KnowledgeRebuildItemDO>().eq(KnowledgeRebuildItemDO::getTenantId,job.getTenantId()).eq(KnowledgeRebuildItemDO::getJobId,job.getId()).orderByAsc(KnowledgeRebuildItemDO::getId));}
    private List<KnowledgeRebuildBatchDO> jobBatches(KnowledgeRebuildJobDO job){return batches.selectList(new LambdaQueryWrapper<KnowledgeRebuildBatchDO>().eq(KnowledgeRebuildBatchDO::getTenantId,job.getTenantId()).eq(KnowledgeRebuildBatchDO::getJobId,job.getId()));}
    private List<KnowledgeRebuildBatchDO> itemBatches(KnowledgeRebuildItemDO item){return batches.selectList(new LambdaQueryWrapper<KnowledgeRebuildBatchDO>().eq(KnowledgeRebuildBatchDO::getTenantId,item.getTenantId()).eq(KnowledgeRebuildBatchDO::getItemId,item.getId()).orderByAsc(KnowledgeRebuildBatchDO::getBatchIndex));}
    private Snapshot snapshot(KnowledgeRebuildJobDO job){var progress=jobs.progress(job.getTenantId(),job.getId());
        return new Snapshot(job.getId(),job.getKbId(),job.getStatus(),job.getRevision(),job.getTargetModelId(),job.getTargetModelRevision(),job.getTargetDimensions(),progress.documentCount(),progress.readyDocuments(),progress.unknownBatches(),job.getErrorCode(),job.getPublishedRevision(),!TERMINAL.contains(job.getStatus()),requireBase(job.getTenantId(),job.getKbId()).getControlRevision());}
    public record Snapshot(Long jobId,Long kbId,String status,Long revision,Long targetModelId,Long targetModelRevision,Integer targetDimensions,int documentCount,long readyDocuments,long unknownBatches,String errorCode,Long publishedRevision,boolean writeLocked,Long baseRevision){}
    private static ApiException conflict(String code){return new ApiException(HttpStatus.CONFLICT,code);}
    private static ApiException invalid(String code){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,code);}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"knowledge_rebuild_not_found");}
}
