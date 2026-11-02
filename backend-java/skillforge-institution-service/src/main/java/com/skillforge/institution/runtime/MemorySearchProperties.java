package com.skillforge.institution.runtime;

import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 宿主显式检索选择；每次新查询冻结完整策略，恢复不读取当前配置。 */
@ConfigurationProperties("skillforge.memory.search")
public class MemorySearchProperties {
    public enum Mode { LEXICAL, HYBRID }
    private Mode mode=Mode.LEXICAL;
    private String policyVersion="1";
    private int candidateLimit=64;
    private int rrfRankConstant=60;
    private double minimumCosineSimilarity=0.7;
    private String modelConfigId;
    private Long modelRevision;
    private Integer dimensions;
    private MemoryRetrievalPolicy.VectorFailurePolicy vectorFailurePolicy=MemoryRetrievalPolicy.VectorFailurePolicy.FAIL;
    private Duration workerLease=Duration.ofMinutes(2);
    private Duration invocationTimeout=Duration.ofSeconds(30);
    private int indexBatchSize=64;

    @PostConstruct
    public void validate() {
        freeze();
        if(indexBatchSize<1)throw new IllegalArgumentException("memory_search_index_batch_size_invalid");
        if(workerLease==null||workerLease.isNegative()||workerLease.isZero()
                ||invocationTimeout==null||invocationTimeout.isNegative()||invocationTimeout.isZero())
            throw new IllegalArgumentException("memory_search_duration_invalid");
    }

    public MemoryRetrievalPolicy freeze() {
        if(mode==null)throw new IllegalArgumentException("memory_search_mode_required");
        Optional<MemoryRetrievalPolicy.EmbeddingModel> model=Optional.empty();
        if(mode==Mode.HYBRID) {
            if(modelConfigId==null||modelConfigId.isBlank()||modelRevision==null||dimensions==null)
                throw new IllegalArgumentException("memory_search_hybrid_model_required");
            model=Optional.of(new MemoryRetrievalPolicy.EmbeddingModel(modelConfigId,modelRevision,dimensions));
        } else if(modelConfigId!=null||modelRevision!=null||dimensions!=null) {
            throw new IllegalArgumentException("memory_search_lexical_model_forbidden");
        }
        return new MemoryRetrievalPolicy(mode==Mode.LEXICAL?"memory-lexical":"memory-hybrid",policyVersion,
                "memory-body-v1","lucene-cjk-9.12.3-v1",candidateLimit,rrfRankConstant,
                minimumCosineSimilarity,model,vectorFailurePolicy);
    }
    public Mode getMode(){return mode;} public void setMode(Mode value){mode=value;}
    public String getPolicyVersion(){return policyVersion;} public void setPolicyVersion(String value){policyVersion=value;}
    public int getCandidateLimit(){return candidateLimit;} public void setCandidateLimit(int value){candidateLimit=value;}
    public int getRrfRankConstant(){return rrfRankConstant;} public void setRrfRankConstant(int value){rrfRankConstant=value;}
    public double getMinimumCosineSimilarity(){return minimumCosineSimilarity;} public void setMinimumCosineSimilarity(double value){minimumCosineSimilarity=value;}
    public String getModelConfigId(){return modelConfigId;} public void setModelConfigId(String value){modelConfigId=value;}
    public Long getModelRevision(){return modelRevision;} public void setModelRevision(Long value){modelRevision=value;}
    public Integer getDimensions(){return dimensions;} public void setDimensions(Integer value){dimensions=value;}
    public MemoryRetrievalPolicy.VectorFailurePolicy getVectorFailurePolicy(){return vectorFailurePolicy;}
    public void setVectorFailurePolicy(MemoryRetrievalPolicy.VectorFailurePolicy value){vectorFailurePolicy=value;}
    public Duration getWorkerLease(){return workerLease;} public void setWorkerLease(Duration value){workerLease=value;}
    public Duration getInvocationTimeout(){return invocationTimeout;} public void setInvocationTimeout(Duration value){invocationTimeout=value;}
    public int getIndexBatchSize(){return indexBatchSize;} public void setIndexBatchSize(int value){indexBatchSize=value;}
}
