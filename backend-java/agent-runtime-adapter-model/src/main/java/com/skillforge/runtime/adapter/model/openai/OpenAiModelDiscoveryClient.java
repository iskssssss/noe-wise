package com.skillforge.runtime.adapter.model.openai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.discovery.ModelDiscoveryHttp;
import com.skillforge.runtime.adapter.model.discovery.ModelDiscoveryMetadata;
import com.skillforge.runtime.adapter.model.discovery.ModelDiscoveryMetadataParser;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.ResolvedDiscovery;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * OpenAI-compatible {@code GET /models} 发现适配器。
 *
 * <p>发现只在宿主显式请求时执行，不发送推理请求、不重试、不跟随重定向，也不把
 * 返回模型解释为已经验证的能力。
 */
public final class OpenAiModelDiscoveryClient {

    private static final String DIALECT = "openai-model-list-v1";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ModelDiscoveryHttp http;

    public OpenAiModelDiscoveryClient(HttpClient http, int maximumResponseBytes) {
        this.http = new ModelDiscoveryHttp(http, maximumResponseBytes);
    }

    public List<DiscoveredModel> discover(ResolvedDiscovery target, String credential, Duration timeout) {
        Objects.requireNonNull(target, "target");
        if (target.authenticationScheme() != AuthenticationScheme.BEARER_API_KEY || !DIALECT.equals(target.dialectVersion()))
            throw rejected("model_discovery_profile_not_supported");
        return parse(http.get(URI.create(target.modelsEndpoint()), credential, timeout));
    }

    private List<DiscoveredModel> parse(byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode data = root == null ? null : root.get("data");
            if (root == null || !root.isObject() || data == null || !data.isArray()) {
                throw rejected("model_discovery_response_invalid");
            }
            Map<String, DiscoveredModel> unique = new LinkedHashMap<>();
            for (JsonNode item : data) {
                JsonNode idNode = item == null ? null : item.get("id");
                if (item == null || !item.isObject() || idNode == null
                        || !idNode.isTextual() || idNode.textValue().isBlank()) {
                    throw rejected("model_discovery_response_invalid");
                }
                String ownedBy = null;
                JsonNode ownerNode = item.get("owned_by");
                if (ownerNode != null && !ownerNode.isNull()) {
                    if (!ownerNode.isTextual()) {
                        throw rejected("model_discovery_response_invalid");
                    }
                    ownedBy = ownerNode.textValue();
                }
                String id = idNode.textValue();
                unique.putIfAbsent(id, new DiscoveredModel(id, ownedBy, ModelDiscoveryMetadataParser.openAi(item)));
            }
            ArrayList<DiscoveredModel> models = new ArrayList<>(unique.values());
            models.sort(Comparator.comparing(DiscoveredModel::modelId));
            return List.copyOf(models);
        } catch (ModelDiscoveryException invalid) {
            throw invalid;
        } catch (IOException | IllegalArgumentException invalidJson) {
            // Jackson 异常可能携带响应片段；供应商正文不能进入异常链或宿主日志。
            throw rejected("model_discovery_response_invalid");
        }
    }

    /** 模型发现候选；供应商归属字段只用于展示，不参与权限或能力判断。 */
    public record DiscoveredModel(String modelId, String ownedBy,
            Optional<ModelDiscoveryMetadata> metadata) {
        public DiscoveredModel(String modelId, String ownedBy) { this(modelId, ownedBy, Optional.empty()); }
        public DiscoveredModel {
            Objects.requireNonNull(metadata, "metadata");
            if (modelId == null || modelId.isBlank()
                    || modelId.chars().anyMatch(value ->
                            Character.isISOControl((char) value))) {
                throw new IllegalArgumentException("model_discovery_model_id_missing");
            }
        }
    }

    /** 稳定的发现失败，不携带供应商响应正文或凭据。 */
    public static final class ModelDiscoveryException extends RuntimeException {
        public ModelDiscoveryException(String reason) {
            super(reason);
        }

        public ModelDiscoveryException(String reason, Throwable cause) {
            super(reason, cause);
        }
    }

    private static ModelDiscoveryException rejected(String reason) {
        return new ModelDiscoveryException(reason);
    }

    private static ModelDiscoveryException rejected(String reason, Throwable cause) {
        return new ModelDiscoveryException(reason, cause);
    }

}
