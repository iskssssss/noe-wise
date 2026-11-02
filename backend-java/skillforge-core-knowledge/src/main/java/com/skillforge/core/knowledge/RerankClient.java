package com.skillforge.core.knowledge;

import com.skillforge.core.model.PlatformModelConfig;
import com.skillforge.core.billing.metering.LlmUsage;
import java.util.List;
import java.util.Objects;

/**
 * Rerank 协议执行策略（策略模式）。
 *
 * <p>按冻结的提供方与服务身份选择协议实现：阿里云百炼原生协议与 OpenAI 兼容
 * 协议各自一个策略，由 {@link RerankClientFactory} 分派；两协议差异仅在
 * 请求体形状（响应解析与鉴权共用，见 {@link AbstractRerankClient}）。
 */
public interface RerankClient {

    /**
     * 调用 rerank 端点。
     *
     * @param config    配置
     * @param query     查询文本
     * @param documents 候选文本
     * @return 重排结果与供应商实际报告的用量；缺结果时 results 为 null，缺用量保持未知
     */
    Response postRerank(
            PlatformModelConfig config, String query, List<String> documents);

    /** 排名载荷与计量观察独立保存；排名成功不能补造缺失 Token。 */
    record Response(List<RerankProvider.RerankResult> results, LlmUsage usage) {
        public Response {
            results = results == null ? null : List.copyOf(results);
            Objects.requireNonNull(usage, "usage");
        }
    }
}
