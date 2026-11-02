package com.skillforge.core.knowledge;

import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.model.embedding.EmbeddingClient;
import com.skillforge.core.billing.metering.CostContext;
import com.skillforge.core.billing.metering.MeterScope;
import com.skillforge.core.shared.config.SnowflakeIds;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** 知识模型选择与显式词法降级；实际Embedding协议、计量和单次派发复用共享客户端。 */
@Component
public class EmbeddingProvider {
    private final KnowledgeProviderConfigService configService;
    private final EmbeddingClient client;

    public EmbeddingProvider(KnowledgeProviderConfigService configService, EmbeddingClient client) {
        this.configService = configService;
        this.client = client;
    }

    public List<float[]> embedBatch(String tenantId, List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        return embedBatch(tenantId, config(tenantId), texts);
    }

    /** 索引只使用已冻结的模型修订；不读取后来变更的机构默认模型。 */
    public List<float[]> embedBatch(String tenantId, String modelConfigId, long revision, List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        return embedBatch(tenantId, configService.embedding(modelConfigId, revision), texts);
    }

    private List<float[]> embedBatch(String tenantId, PlatformModelConfig config, List<String> texts) {
        CostContext context = MeterScope.current();
        String baseId = context != null && context.eventId() != null && !context.eventId().isBlank()
                ? context.eventId()
                : context != null && context.traceContext() != null
                ? context.traceContext().inputId() + ":model.embedding"
                : SnowflakeIds.nextId() + ":model.embedding";
        List<float[]> vectors = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += EmbeddingClient.MAX_BATCH_SIZE) {
            var result = client.embedBatch(tenantId, config,
                    baseId + ":embedding:" + start / EmbeddingClient.MAX_BATCH_SIZE,
                    texts.subList(start, Math.min(start + EmbeddingClient.MAX_BATCH_SIZE, texts.size())));
            if (!(result instanceof EmbeddingClient.Outcome.Available available)) return null;
            vectors.addAll(available.vectors());
        }
        return List.copyOf(vectors);
    }

    public float[] embed(String tenantId, String text) {
        if (text == null || text.isBlank()) return null;
        var vectors = embedBatch(tenantId, List.of(text));
        return vectors == null || vectors.isEmpty() ? null : vectors.getFirst();
    }

    public float[] embed(String tenantId, String modelConfigId, long revision, String text) {
        if (text == null || text.isBlank()) return null;
        var vectors = embedBatch(tenantId, modelConfigId, revision, List.of(text));
        return vectors == null || vectors.isEmpty() ? null : vectors.getFirst();
    }

    public boolean usable(String tenantId) {
        try { return config(tenantId) != null; }
        catch (RuntimeException unavailable) { return false; }
    }

    public PlatformModelConfig config(String tenantId) { return configService.embedding(tenantId); }
}
