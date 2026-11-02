package com.skillforge.runtime.starter;

import com.skillforge.runtime.adapter.jdbc.memory.MemorySearchEmbedding;
import com.skillforge.runtime.adapter.jdbc.memory.search.MemorySearchDocument;
import com.skillforge.runtime.api.MemoryFact;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import com.skillforge.runtime.api.TenantId;
import java.util.Objects;
import java.util.function.Function;

/** 宿主显式提供类型正文、完整策略和模型边界；Starter不推断业务JSON或补默认embedding。 */
public record AgentRuntimeMemorySearchBindings(Function<MemoryFact,MemorySearchDocument> projector,
        Function<TenantId,MemoryRetrievalPolicy> indexingPolicy,MemorySearchEmbedding embedding) {
    public AgentRuntimeMemorySearchBindings {
        Objects.requireNonNull(projector,"projector"); Objects.requireNonNull(indexingPolicy,"indexingPolicy");
        Objects.requireNonNull(embedding,"embedding");
    }
}
