package com.skillforge.core.knowledge;

import com.skillforge.core.model.embedding.PlatformModelHttpSupport;

import com.skillforge.core.model.PlatformModelConfig;
import java.util.List;

/**
 * OpenAI 兼容协议 Rerank 策略：请求体为
 * {@code {model, query, documents}}。
 */
public class OpenAiCompatibleRerankClient extends AbstractRerankClient {

    /**
     * 构造 OpenAI 兼容协议策略。
     *
     * @param cryptoService 加密服务
     */
    public OpenAiCompatibleRerankClient(PlatformModelHttpSupport http) {
        super(http, false);
    }

    @Override
    protected Object buildBody(
            PlatformModelConfig config, String query, List<String> documents) {
        return new Request(config.modelId(), query, List.copyOf(documents));
    }
    private record Request(String model, String query, List<String> documents) { }
}
