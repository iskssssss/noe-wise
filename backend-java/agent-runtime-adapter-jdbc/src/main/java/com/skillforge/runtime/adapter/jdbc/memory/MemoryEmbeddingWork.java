package com.skillforge.runtime.adapter.jdbc.memory;

import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.MemoryRetrievalPolicy.EmbeddingModel;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.time.Instant;

/** 已提交索引任务的原固定责任与精确租约，不能替换为调度线程当前身份。 */
public record MemoryEmbeddingWork(TenantId tenantId, String factId, long contentRevision,
        String projectionVersion, String analyzerVersion, EmbeddingModel model, String textDigest,
        String text, String invocationId, String leaseToken, Instant leaseUntil,
        RunIdentity runIdentity, VerifiedIdentity identity) { }
