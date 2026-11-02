package com.skillforge.core.knowledge;

import com.skillforge.core.foundation.settings.OperationalSettingsService;
import org.springframework.stereotype.Component;

/** 在知识库任务入口冻结一次平台运营参数。 */
@Component
public class KnowledgeOperationalSettings {

    private final OperationalSettingsService settings;

    public KnowledgeOperationalSettings(OperationalSettingsService settings) {
        this.settings = settings;
    }

    public Snapshot capture() {
        OperationalSettingsService.Snapshot source = settings.read("knowledge");
        return new Snapshot(source.revision(),
                source.integer("chunk_size"),
                source.integer("chunk_overlap"),
                source.integer("max_upload_mb"),
                source.integer("max_chunks_per_document"),
                source.text("default_indexing_mode"),
                source.text("default_retrieval_mode"),
                source.integer("default_top_k"),
                source.decimal("default_score_threshold").doubleValue(),
                source.decimal("default_vector_weight").doubleValue());
    }

    /** 原列为空时沿用当前运营默认；非空原值不被覆盖。 */
    public String effectiveIndexingMode(String configured) {
        return resolveIndexingMode(configured,configured==null||configured.isBlank()?capture():null);
    }
    /** 仅空列需锁定当前运营指针；调用方须处于业务短事务。 */
    public String lockedIndexingMode(String configured) {
        return configured==null||configured.isBlank()?settings.readLocked("knowledge").text("default_indexing_mode"):configured;
    }
    static String resolveIndexingMode(String configured,Snapshot snapshot) {
        return configured==null||configured.isBlank()?snapshot.defaultIndexingMode():configured;
    }

    /** 单个知识库任务使用的不可变参数快照。 */
    public record Snapshot(
            long revision,
            int chunkSize,
            int chunkOverlap,
            int maxUploadMb,
            int maxChunksPerDocument,
            String defaultIndexingMode,
            String defaultRetrievalMode,
            int defaultTopK,
            double defaultScoreThreshold,
            double defaultVectorWeight) {
    }
}
