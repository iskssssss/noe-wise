package com.skillforge.core.knowledge.dto.response;

import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.DiscoveredModel;
import java.util.List;

/** 用户显式获取的当前账号可见模型候选；不代表模型能力已经验证。 */
public record LlmModelDiscoveryResponse(List<Model> models) {

    public LlmModelDiscoveryResponse {
        models = List.copyOf(models);
    }

    /** 从 SDK 发现结果创建安全响应，不包含凭据或供应商原始响应。 */
    public static LlmModelDiscoveryResponse from(List<DiscoveredModel> discovered) {
        return new LlmModelDiscoveryResponse(discovered.stream()
                .map(model -> new Model(model.modelId(), model.ownedBy()))
                .toList());
    }

    public record Model(String modelId, String ownedBy) {
    }
}
