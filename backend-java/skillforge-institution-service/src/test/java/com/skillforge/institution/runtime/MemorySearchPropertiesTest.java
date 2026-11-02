package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.runtime.api.MemoryRetrievalPolicy;
import org.junit.jupiter.api.Test;

class MemorySearchPropertiesTest {
    @Test void indexBatchSizeMustBePositive() {
        var settings=new MemorySearchProperties();settings.setIndexBatchSize(0);
        assertEquals("memory_search_index_batch_size_invalid",assertThrows(IllegalArgumentException.class,settings::validate).getMessage());
    }
    @Test void defaultsAreExplicitLexicalAndHybridRequiresAnExactModel() {
        var settings=new MemorySearchProperties();
        settings.validate();
        assertEquals(MemoryRetrievalPolicy.lexicalV1(),settings.freeze());
        settings.setMode(MemorySearchProperties.Mode.HYBRID);
        assertEquals("memory_search_hybrid_model_required",assertThrows(IllegalArgumentException.class,settings::freeze).getMessage());
        settings.setModelConfigId("901");settings.setModelRevision(3L);settings.setDimensions(1024);
        settings.setVectorFailurePolicy(MemoryRetrievalPolicy.VectorFailurePolicy.LEXICAL_FALLBACK);
        var frozen=settings.freeze();
        assertEquals(new MemoryRetrievalPolicy.EmbeddingModel("901",3,1024),frozen.embeddingModel().orElseThrow());
        settings.setModelRevision(4L);
        assertEquals(3,frozen.embeddingModel().orElseThrow().revision());
        assertEquals(4,settings.freeze().embeddingModel().orElseThrow().revision());
    }
}
