package com.skillforge.institution.memory;

import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.runtime.MemorySearchProperties;
import com.skillforge.runtime.adapter.jdbc.memory.JdbcMemoryPort;
import com.skillforge.runtime.adapter.jdbc.memory.MemoryEmbeddingWork;
import com.skillforge.runtime.adapter.jdbc.memory.MemorySearchEmbedding;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryFactRef;
import com.skillforge.runtime.api.FactStatus;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.port.MemoryPort;
import com.skillforge.runtime.memory.MemoryAccessPolicy;
import com.skillforge.runtime.starter.AgentRuntimeMemorySearchBindings;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 持久派生索引单发Worker：领取与完成为短事务，模型IO不占事实事务。 */
@Service
public final class MemoryEmbeddingIndexWorker {
    private static final Logger log=LoggerFactory.getLogger(MemoryEmbeddingIndexWorker.class);
    private final JdbcMemoryPort store;
    private final MemorySearchEmbedding embedding;
    private final MemorySearchProperties settings;
    private final ActiveMemoryTenantScan tenants;
    private final Clock clock;
    private final MemoryAccessPolicy access;
    private final AgentRuntimeMemorySearchBindings search;
    private final String workerId="memory-index:"+UUID.randomUUID();

    public MemoryEmbeddingIndexWorker(JdbcMemoryPort store,MemorySearchEmbedding embedding,
            MemorySearchProperties settings,ActiveMemoryTenantMapper tenants,Clock clock,
            MemoryAccessPolicy access,AgentRuntimeMemorySearchBindings search) {
        this.store=Objects.requireNonNull(store);this.embedding=Objects.requireNonNull(embedding);
        this.settings=Objects.requireNonNull(settings);this.tenants=new ActiveMemoryTenantScan(Objects.requireNonNull(tenants));
        this.clock=Objects.requireNonNull(clock);
        this.access=Objects.requireNonNull(access);this.search=Objects.requireNonNull(search);
    }

    @Scheduled(fixedDelayString="${skillforge.memory.search.index-poll-interval:2000}")
    public void poll() {
        for(String tenant:tenants.next())TenantContext.callWithTenant(tenant,()->{
            try {
                var tenantId=new TenantId(tenant);
                // 策略切换只补当前缺失的派生材料；存储以原来源恢复责任并保留已UNKNOWN调用。
                store.enqueueMissing(tenantId,settings.freeze(),settings.getIndexBatchSize());
                store.claimEmbedding(tenantId,workerId,settings.getWorkerLease()).ifPresent(this::process);
            }catch(RuntimeException failure) {
                // 不以调度失败断言原网络未发送，也不创建另一个任务或invocation。
                log.warn("Memory index deferred tenant={} failure={}",tenant,failure.getClass().getSimpleName());
            }
            return null;
        });
    }

    private void process(MemoryEmbeddingWork work) {
        Instant now=clock.instant();
        Instant requestedDeadline=now.plus(settings.getInvocationTimeout());
        Instant deadline=requestedDeadline.isBefore(work.leaseUntil())?requestedDeadline:work.leaseUntil();
        if(!now.isBefore(deadline)) {store.failEmbedding(work,"memory_index_lease_expired",false);return;}
        var context=new PortCallContext(work.runIdentity(),work.identity(),new InvocationControl(deadline,()->false));
        if(!authorize(work,context,now))return;
        final MemorySearchEmbedding.Outcome outcome;
        try {
            outcome=embedding.embed(new MemorySearchEmbedding.Request(context,work.invocationId(),work.model(),work.text()));
        }catch(RuntimeException uncertain) {
            // 外调边界没有返回明确结果，不能把它退回READY后重新派发。
            store.failEmbedding(work,"memory_index_outcome_unknown",true);
            return;
        }
        switch(outcome) {
            case MemorySearchEmbedding.Outcome.Available available -> store.completeEmbedding(work,available.vector());
            case MemorySearchEmbedding.Outcome.Rejected rejected -> store.failEmbedding(work,rejected.reasonCode(),false);
            case MemorySearchEmbedding.Outcome.OutcomeUnknown unknown -> store.failEmbedding(work,unknown.reasonCode(),true);
        }
    }

    /** 原责任快照不是当前授权；在模型派发前重新读取事实、时态和现有访问策略。 */
    private boolean authorize(MemoryEmbeddingWork work,PortCallContext context,Instant now) {
        final String denied;
        try {
            denied=currentDenial(work,context,now);
        }catch(RuntimeException unavailable) {
            return refuse(work,"memory_index_current_access_unavailable");
        }
        if(denied==null)return true;
        return refuse(work,denied);
    }

    private String currentDenial(MemoryEmbeddingWork work,PortCallContext context,Instant now) {
        var ref=new MemoryFactRef(work.tenantId(),work.factId());
        var facts=store.findCurrent(new MemoryPort.CurrentFactReadBatch(List.of(new MemoryPort.CurrentFactRead(context,ref))));
        if(facts.size()!=1||facts.getFirst().isEmpty())return "memory_index_current_fact_missing";
        MemoryFact current=facts.getFirst().orElseThrow();
        if(!current.ref().equals(ref)||current.status()!=FactStatus.ACTIVE||current.revision()<work.contentRevision()
                ||now.isBefore(current.validFrom())||current.validTo().filter(end->!now.isBefore(end)).isPresent()
                ||current.expiresAt().filter(end->!now.isBefore(end)).isPresent())return "memory_index_source_inactive";
        var projected=search.projector().apply(current);
        if(!projected.projectionVersion().equals(work.projectionVersion())||!projected.text().equals(work.text()))
            return "memory_index_source_changed";
        var decision=access.authorize(new MemoryAccessPolicy.Request(context,current.owner(),MemoryAccessPolicy.Operation.QUERY,
                Set.of(current.scope()),Set.of(current.type()),Optional.empty()));
        return decision instanceof MemoryAccessPolicy.Decision.Allowed?null:"memory_index_current_access_denied";
    }

    private boolean refuse(MemoryEmbeddingWork work,String reason) {
        store.failEmbedding(work,reason,false);
        return false;
    }
}
