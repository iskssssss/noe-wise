package com.skillforge.core.knowledge;

import com.skillforge.core.model.embedding.PlatformModelHttpSupport;
import com.skillforge.core.model.embedding.EmbeddingClient;
import com.skillforge.core.billing.metering.LlmUsage;
import com.skillforge.core.billing.metering.UsageSource;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.skillforge.core.model.PlatformModelConfig;
import java.util.List;
import org.springframework.http.MediaType;

/** 平台 Embedding/Rerank 模型连接探测，不依赖机构配置、账本或业务租户。 */
public class PlatformKnowledgeModelProbe {
    private static final List<String> EMBEDDING_INPUT = List.of("ping");
    private static final String RERANK_QUERY = "中国的首都是哪里？";
    private static final List<String> RERANK_DOCUMENTS = List.of("巴黎是法国的首都。", "北京是中国的首都。", "香蕉是一种水果。");
    private final PlatformModelHttpSupport http;
    private final RerankClientFactory rerank;

    public PlatformKnowledgeModelProbe(PlatformModelHttpSupport http) {
        this.http = http;
        this.rerank = new RerankClientFactory(http);
    }

    /** 使用平台模型快照发起一次最小真实调用。 */
    public Result test(PlatformModelConfig config) {
        long start = System.nanoTime();
        try {
            return switch (config.kind()) {
                case "EMBEDDING" -> probeEmbedding(config, start);
                case "RERANK" -> probeRerank(config, start);
                default -> Result.failed(elapsed(start),
                        "platform_model_kind_mismatch");
            };
        } catch (Exception exception) {
            return Result.failed(elapsed(start),
                    http.friendlyError(exception));
        }
    }

    private Result probeEmbedding(PlatformModelConfig config, long start) {
        var response = http.client(config).post()
                .uri(http.endpoint(config, "/embeddings"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbeddingClient.EmbeddingRequest(config.modelId(), EMBEDDING_INPUT, config.dimensions()))
                .retrieve()
                .body(EmbeddingClient.EmbeddingResponse.class);
        Usage usage = response == null ? null : Usage.from(response.measuredUsage());
        try {
            var vectors = EmbeddingClient.vectors(response, EMBEDDING_INPUT.size(), config.dimensions());
            return new Result(true, elapsed(start), null, vectors.getFirst().length, null, usage);
        } catch (IllegalStateException invalidVector) {
            return new Result(false, elapsed(start), "接口返回的向量不符合当前维度或输入序号", null, null, usage);
        }
    }

    private Result probeRerank(PlatformModelConfig config, long start) {
        var response = rerank.forConfig(config).postRerank(config, RERANK_QUERY, RERANK_DOCUMENTS);
        Usage usage = Usage.from(response.usage());
        if (response.results() == null || response.results().size() != RERANK_DOCUMENTS.size()) {
            return new Result(false, elapsed(start), "接口未返回完整有效的重排结果", null, null, usage);
        }
        var ranking = response.results().stream().map(item -> new Ranking(item.index(), item.relevanceScore())).toList();
        return new Result(true, elapsed(start), null, null, ranking, usage);
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }

    /** 只公开输入序号和真实得分，不回显供应商正文或候选原文。 */
    public record Ranking(int index, double score) { }

    /** 缺失用量为null；总量不能替代输入计量，Long按十进制字符串无损投影。 */
    public record Usage(@JsonFormat(shape = JsonFormat.Shape.STRING) Long inputTokens,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Long totalTokens) {
        private static Usage from(LlmUsage value) {
            return value.usageSource() == UsageSource.UNKNOWN ? null : new Usage(value.promptTokens(), value.totalTokens());
        }
    }

    /** 平台连接探测结果。 */
    public record Result(boolean ok, long latencyMs, String error, Integer dimensions, List<Ranking> ranking, Usage usage) {
        public Result { ranking = ranking == null ? null : List.copyOf(ranking); }
        private static Result failed(long latencyMs, String error) {
            return new Result(false, latencyMs, error, null, null, null);
        }
    }
}
