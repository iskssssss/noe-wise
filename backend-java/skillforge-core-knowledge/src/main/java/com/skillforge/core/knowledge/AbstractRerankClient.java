package com.skillforge.core.knowledge;

import com.skillforge.core.model.embedding.PlatformModelHttpSupport;

import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.billing.metering.LlmUsage;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;

/**
 * Rerank 协议策略抽象基类（模板方法模式）。
 *
 * <p>模板方法 {@link #postRerank} 固定调用骨架：构建带 Bearer 鉴权头的
 * REST 客户端 → POST {@code {base}{path}} → 解析响应（支持百炼
 * {@code output.results} 形状）；两协议的差异点仅
 * 请求体形状，由子类 {@link #buildBody} 实现。
 */
public abstract class AbstractRerankClient implements RerankClient {

    /** 平台知识模型 HTTP 支撑。 */
    private final PlatformModelHttpSupport http;
    private final boolean nativeRanking;

    /**
     * 构造协议策略基类。
     *
     * @param llmHttpSupport LLM 外部调用公共支撑
     */
    protected AbstractRerankClient(PlatformModelHttpSupport http, boolean nativeRanking) {
        this.http = http;
        this.nativeRanking = nativeRanking;
    }

    /**
     * 模板方法：调用 rerank 端点。
     *
     * @param config    配置
     * @param query     查询文本
     * @param documents 候选文本
     * @return 排名与实际用量；缺结果时 results 为 null
     */
    @Override
    public final Response postRerank(
            PlatformModelConfig config, String query, List<String> documents) {
        RerankProtocolResponse response = http.client(config).post()
                .uri(http.endpoint(config, "/rerank"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(buildBody(config, query, documents))
                .retrieve()
                .body(RerankProtocolResponse.class);
        return parse(response, documents.size());
    }

    /**
     * 子类实现请求体形状（协议差异点）。
     *
     * @param config    配置
     * @param query     查询文本
     * @param documents 候选文本
     * @return 请求体
     */
    protected abstract Object buildBody(
            PlatformModelConfig config, String query, List<String> documents);

    /**
     * 解析重排响应（results[{index, relevance_score}]，支持百炼 output.results
     * 嵌套形状）。
     *
     * @param response 响应
     * @return 排名与实际用量
     */
    private Response parse(RerankProtocolResponse response, int documentCount) {
        if (response == null) {
            return new Response(null, LlmUsage.empty());
        }
        LlmUsage usage = response.usage() == null ? LlmUsage.empty() : response.usage().measured();
        List<RerankProvider.RerankResult> result = new ArrayList<>();
        List<RerankProtocolResponse.Item> rawResults = response.results();
        if (rawResults == null && response.output() != null) {
            // 阿里云百炼原生响应：results 位于 output 下
            rawResults = response.output().results();
        }
        if (rawResults != null) {
            boolean[] seen = new boolean[documentCount];
            double previous = Double.POSITIVE_INFINITY;
            for (RerankProtocolResponse.Item item : rawResults) {
                if (item == null || item.index() == null || item.index() < 0 || item.index() >= documentCount
                        || seen[item.index()] || item.relevanceScore() == null || !Double.isFinite(item.relevanceScore())
                        || nativeRanking && (item.relevanceScore() < 0 || item.relevanceScore() > 1 || item.relevanceScore() > previous))
                    return new Response(null, usage);
                seen[item.index()] = true; previous = item.relevanceScore();
                result.add(new RerankProvider.RerankResult(item.index(), item.relevanceScore()));
            }
            return new Response(result, usage);
        }
        return new Response(null, usage);
    }
}
