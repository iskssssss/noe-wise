package com.skillforge.runtime.adapter.jdbc.memory;

import com.skillforge.runtime.api.MemoryRetrievalPolicy.EmbeddingModel;
import com.skillforge.runtime.api.port.PortCallContext;
import java.util.List;
import java.util.Objects;

/** 宿主提供实际模型调用；适配器只在持久受理后、数据库事务外调用。 */
@FunctionalInterface
public interface MemorySearchEmbedding {
    Outcome embed(Request request);
    record Request(PortCallContext context, String invocationId, EmbeddingModel model,
            String text) {
        public Request { Objects.requireNonNull(context); Objects.requireNonNull(invocationId); Objects.requireNonNull(model); Objects.requireNonNull(text); }
    }
    sealed interface Outcome {
        record Available(List<Double> vector) implements Outcome { public Available { vector = List.copyOf(vector); } }
        record Rejected(String reasonCode) implements Outcome { public Rejected { Objects.requireNonNull(reasonCode); } }
        record OutcomeUnknown(String reasonCode) implements Outcome { public OutcomeUnknown { Objects.requireNonNull(reasonCode); } }
    }
}
