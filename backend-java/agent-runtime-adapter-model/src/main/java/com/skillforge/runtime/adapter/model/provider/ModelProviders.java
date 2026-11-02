package com.skillforge.runtime.adapter.model.provider;

import com.skillforge.runtime.adapter.model.openai.CustomOpenAiCompatibleModelProvider;
import com.skillforge.runtime.adapter.model.aliyun_bailian.AliyunBailianModelProvider;
import com.skillforge.runtime.adapter.model.deepseek.DeepSeekModelProvider;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import com.skillforge.runtime.adapter.model.protocol.ModelProviderTransport;
import com.skillforge.runtime.adapter.model.openai.chat.OpenAiCompatibleProviderTransport;
import com.skillforge.runtime.adapter.model.deepseek.messages.DeepSeekMessagesTransport;
import com.skillforge.runtime.adapter.model.openai.responses.OpenAiResponsesTransport;

/** 标准供应商实现的唯一装配点。 */
final class ModelProviders {

    private ModelProviders() {
    }

    static List<ModelProvider> standard() {
        return List.of(
                new DeepSeekModelProvider(),
                new AliyunBailianModelProvider(),
                new com.skillforge.runtime.adapter.model.moonshot.MoonshotModelProvider(),
                new com.skillforge.runtime.adapter.model.minimax.MiniMaxModelProvider(),
                new com.skillforge.runtime.adapter.model.zhipu.ZhipuModelProvider(),
                new com.skillforge.runtime.adapter.model.stepfun.StepFunModelProvider(),
                new com.skillforge.runtime.adapter.model.tencent.TencentTokenHubModelProvider(),
                new com.skillforge.runtime.adapter.model.xiaomi.XiaomiModelProvider(),
                new CustomOpenAiCompatibleModelProvider());
    }

    static List<ModelProvider> runtime(
            HttpClient http, Clock clock, int maximumResponseBytes,
            com.skillforge.runtime.adapter.model.transport.SdkHttpResources resources) {
        ModelProviderTransport transport = new OpenAiCompatibleProviderTransport(
                http, clock, maximumResponseBytes, resources);
        return List.of(
                new DeepSeekModelProvider(transport, new DeepSeekMessagesTransport(http, clock, maximumResponseBytes)),
                new AliyunBailianModelProvider(transport,
                        new com.skillforge.runtime.adapter.model.aliyun_bailian.AliyunBailianModelDiscoveryClient(http, maximumResponseBytes)),
                new com.skillforge.runtime.adapter.model.moonshot.MoonshotModelProvider(transport),
                new com.skillforge.runtime.adapter.model.minimax.MiniMaxModelProvider(transport),
                new com.skillforge.runtime.adapter.model.zhipu.ZhipuModelProvider(transport),
                new com.skillforge.runtime.adapter.model.stepfun.StepFunModelProvider(transport),
                new com.skillforge.runtime.adapter.model.tencent.TencentTokenHubModelProvider(transport),
                new com.skillforge.runtime.adapter.model.xiaomi.XiaomiModelProvider(transport),
                new CustomOpenAiCompatibleModelProvider(transport,
                        new OpenAiResponsesTransport(http, clock, maximumResponseBytes, resources)));
    }
}
