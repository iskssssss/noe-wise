package com.skillforge.core.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.foundation.settings.OperationalSettingsService;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeOperationalSettingsTest {

    @Test
    void capturesAllKnowledgeFieldsFromOneImmutableSnapshot() {
        OperationalSettingsService service = mock(OperationalSettingsService.class);
        when(service.read("knowledge")).thenReturn(new OperationalSettingsService.Snapshot(4, Map.ofEntries(
                Map.entry("chunk_size", 700),
                Map.entry("chunk_overlap", 70),
                Map.entry("max_upload_mb", 20),
                Map.entry("max_chunks_per_document", 3000),
                Map.entry("default_indexing_mode", "high_quality"),
                Map.entry("default_retrieval_mode", "hybrid"),
                Map.entry("default_top_k", 8),
                Map.entry("default_score_threshold", new BigDecimal("0.6")),
                Map.entry("default_vector_weight", new BigDecimal("0.7")))));

        KnowledgeOperationalSettings.Snapshot snapshot =
                new KnowledgeOperationalSettings(service).capture();

        assertThat(snapshot.revision()).isEqualTo(4);
        assertThat(snapshot.chunkSize()).isEqualTo(700);
        assertThat(snapshot.defaultRetrievalMode()).isEqualTo("hybrid");
        assertThat(snapshot.defaultVectorWeight()).isEqualTo(.7);
        assertThat(snapshot.maxChunksPerDocument()).isEqualTo(3000);
    }
}
