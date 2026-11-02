package com.skillforge.runtime.adapter.model.protocol;

import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;

import com.skillforge.runtime.adapter.model.provider.ModelProvider.DiscoveryRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationResult;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.StreamEvent;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.DiscoveredModel;
import java.util.List;
import java.util.concurrent.Flow;

/** Provider 内部复用的协议与 HTTP 实现；不作为宿主或 Core 的旁路调用入口。 */
public interface ModelProviderTransport {

    List<DiscoveredModel> discover(DiscoveryRequest request);

    InvocationResult generate(
            InvocationRequest request, ModelProvider.ChatCompletionsPolicy policy);

    Flow.Publisher<StreamEvent> stream(
            InvocationRequest request, ModelProvider.ChatCompletionsPolicy policy);

    static ModelProviderTransport unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements ModelProviderTransport {
        INSTANCE;

        @Override
        public List<DiscoveredModel> discover(DiscoveryRequest request) {
            throw new ModelProvider.ProviderOperationException(
                    "model_provider_transport_not_configured");
        }

        @Override
        public InvocationResult generate(
                InvocationRequest request, ModelProvider.ChatCompletionsPolicy policy) {
            return new InvocationResult.Rejected("model_provider_transport_not_configured");
        }

        @Override
        public Flow.Publisher<StreamEvent> stream(
                InvocationRequest request, ModelProvider.ChatCompletionsPolicy policy) {
            return subscriber -> subscriber.onError(
                    new ModelProvider.ProviderOperationException(
                            "model_provider_transport_not_configured"));
        }
    }
}
