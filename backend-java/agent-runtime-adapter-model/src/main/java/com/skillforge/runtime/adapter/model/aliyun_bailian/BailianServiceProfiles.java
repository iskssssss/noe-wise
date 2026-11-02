package com.skillforge.runtime.adapter.model.aliyun_bailian;

import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.*;
import java.util.List;
import java.util.Set;

/** 百炼北京的独立模型服务；原生媒体和向量接口不作为普通对话调用。 */
final class BailianServiceProfiles {
    private BailianServiceProfiles() { }

    static List<ServiceProfile> media() {
        return List.of(
                service("bailian-cn-beijing-image", "百炼北京 · 图像生成与编辑", ModelKind.IMAGE_GENERATION, ModelProtocol.BAILIAN_NATIVE, false),
                service("bailian-cn-beijing-video", "百炼北京 · 视频生成", ModelKind.VIDEO_GENERATION, ModelProtocol.BAILIAN_NATIVE, false),
                service("bailian-cn-beijing-asr", "百炼北京 · 语音识别", ModelKind.SPEECH_RECOGNITION, ModelProtocol.BAILIAN_NATIVE, false),
                service("bailian-cn-beijing-qwen-asr", "百炼北京 · Qwen 语音识别", ModelKind.SPEECH_RECOGNITION, ModelProtocol.QWEN_ASR_NATIVE, false),
                service("bailian-cn-beijing-tts", "百炼北京 · 语音合成", ModelKind.SPEECH_SYNTHESIS, ModelProtocol.BAILIAN_NATIVE, true),
                service("bailian-cn-beijing-embedding", "百炼北京 · 向量模型", ModelKind.EMBEDDING, ModelProtocol.OPENAI_EMBEDDINGS, false),
                service("bailian-cn-beijing-rerank", "百炼北京 · 文本重排", ModelKind.RERANK, ModelProtocol.OPENAI_RERANK, false));
    }

    private static ServiceProfile service(String id, String name, ModelKind kind, ModelProtocol protocol, boolean streaming) {
        var capabilities = new CapabilitySupport(SupportLevel.UNSUPPORTED, SupportLevel.UNSUPPORTED,
                SupportLevel.UNSUPPORTED, streaming ? SupportLevel.SUPPORTED : SupportLevel.UNSUPPORTED, SupportLevel.UNSUPPORTED);
        String endpoint = protocol == ModelProtocol.OPENAI_EMBEDDINGS
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : "https://dashscope.aliyuncs.com";
        var endpointMode = protocol == ModelProtocol.BAILIAN_NATIVE || protocol == ModelProtocol.QWEN_ASR_NATIVE
                ? EndpointMode.SCOPED_SUBDOMAIN : EndpointMode.FIXED;
        return new ServiceProfile(id, name, "2026-10-25-cn-v1", Availability.SUPPORTED, endpoint,
                endpointMode, null, List.of(new ProtocolProfile(protocol, "bailian-native-v1",
                        Set.of(AuthenticationScheme.BEARER_API_KEY), capabilities, VerificationStatus.UNVERIFIED)),
                ServiceRegion.CHINA_MAINLAND, Set.of(kind));
    }
}
