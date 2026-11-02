package com.skillforge.core.knowledge;

import com.skillforge.core.model.embedding.PlatformModelHttpSupport;

import com.skillforge.core.model.PlatformModelConfig;
import java.util.List;

/**
 * 阿里云百炼原生协议 Rerank 策略：请求体为
 * {@code {model, input: {query, documents}}}。
 */
public class DashScopeRerankClient extends AbstractRerankClient {

    /**
     * 构造百炼协议策略。
     *
     * @param cryptoService 加密服务
     */
    public DashScopeRerankClient(PlatformModelHttpSupport http) {
        super(http, true);
    }

    @Override
    protected Object buildBody(
            PlatformModelConfig config, String query, List<String> documents) {
        return new Request(config.modelId(), new Input(query, List.copyOf(documents)));
    }
    private record Request(String model, Input input) { }
    private record Input(String query, List<String> documents) { }
}
