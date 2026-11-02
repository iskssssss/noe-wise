package com.skillforge.core.knowledge;

import com.skillforge.core.model.embedding.PlatformModelHttpSupport;

import com.skillforge.core.model.PlatformModelConfig;

/**
 * Rerank 协议策略工厂。
 *
 * <p>按已冻结的提供方与服务身份选择协议，不通过域名片段或模型名称猜测。
 */
public class RerankClientFactory {

    private static final String BAILIAN_PROVIDER = "aliyun-bailian";
    private static final String BAILIAN_RERANK_SERVICE = "bailian-cn-beijing-rerank";

    /** 百炼原生协议策略。 */
    private final RerankClient dashScope;

    /** OpenAI 兼容协议策略。 */
    private final RerankClient openAiCompatible;

    /**
     * 构造协议策略工厂。
     *
     * @param llmHttpSupport LLM 外部调用公共支撑
     */
    public RerankClientFactory(PlatformModelHttpSupport http) {
        this.dashScope = new DashScopeRerankClient(http);
        this.openAiCompatible = new OpenAiCompatibleRerankClient(http);
    }

    /**
     * 按配置选择协议策略。
     *
     * @param config 租户配置
     * @return 对应协议策略（永不返回 null，非百炼回退 OpenAI 兼容）
     */
    public RerankClient forConfig(PlatformModelConfig config) {
        return BAILIAN_PROVIDER.equals(config.providerId()) && BAILIAN_RERANK_SERVICE.equals(config.serviceId())
                ? dashScope : openAiCompatible;
    }
}
