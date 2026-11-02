package com.skillforge.runtime.adapter.model.aliyun_bailian;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.adapter.model.discovery.ModelDiscoveryHttp;
import com.skillforge.runtime.adapter.model.discovery.ModelDiscoveryMetadataParser;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.DiscoveredModel;
import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.ModelDiscoveryException;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.DiscoveryRequest;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.AuthenticationScheme;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** 百炼原生模型目录：显式发现、同源分页、总期限与累计字节有界，不在生成路径查询。 */
public final class AliyunBailianModelDiscoveryClient {
    public static final String DIALECT = "aliyun-native-model-list-v1";
    private static final int PAGE_SIZE = 20;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ModelDiscoveryHttp http;
    private final int maximumResponseBytes;

    public AliyunBailianModelDiscoveryClient(HttpClient http, int maximumResponseBytes) {
        this.http = new ModelDiscoveryHttp(http, maximumResponseBytes);
        this.maximumResponseBytes = maximumResponseBytes;
    }

    public List<DiscoveredModel> discover(DiscoveryRequest request) {
        var target = request.target();
        if (!DIALECT.equals(target.dialectVersion()) || target.authenticationScheme() != AuthenticationScheme.BEARER_API_KEY)
            throw rejected("model_discovery_profile_not_supported");
        URI endpoint = URI.create(target.modelsEndpoint());
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !"/api/v1/models".equals(endpoint.getPath())) throw rejected("model_discovery_profile_not_supported");
        long started = System.nanoTime();
        long budget;
        try { budget = request.timeout().toNanos(); }
        catch (ArithmeticException overflow) { budget = Long.MAX_VALUE; }
        int bytes = 0, page = 1, rows = 0;
        var models = new LinkedHashMap<String, DiscoveredModel>();
        try {
            while (true) {
                long remaining = budget - (System.nanoTime() - started);
                if (remaining <= 0) throw rejected("model_discovery_timeout");
                byte[] body = http.get(URI.create(endpoint + "?page_no=" + page + "&page_size=" + PAGE_SIZE),
                        request.credential(), Duration.ofNanos(remaining));
                if ((long) bytes + body.length > maximumResponseBytes) throw rejected("model_discovery_response_too_large");
                bytes += body.length;
                JsonNode root = JSON.readTree(body);
                if (root == null || !root.isObject() || !root.path("success").isBoolean() || !root.path("success").booleanValue())
                    throw rejected("model_discovery_response_invalid");
                var output = root.path("output");
                int total = integer(output, "total"), reportedPage = integer(output, "page_no"), size = integer(output, "page_size");
                var entries = output.get("models");
                if (reportedPage != page || size <= 0 || size > PAGE_SIZE || entries == null || !entries.isArray() || entries.size() > size)
                    throw rejected("model_discovery_response_invalid");
                for (var entry : entries) {
                    if (!entry.isObject()) throw rejected("model_discovery_response_invalid");
                    String id = ModelDiscoveryMetadataParser.string(entry, "model");
                    if (id == null || id.isBlank()) throw rejected("model_discovery_response_invalid");
                    var value = new DiscoveredModel(id, ModelDiscoveryMetadataParser.string(entry, "provider"),
                            Optional.of(ModelDiscoveryMetadataParser.aliyun(entry)));
                    // 候选目录没有快照分页保证；后页的同 ID 声明覆盖先前观测，不重新请求页面。
                    models.put(id, value);
                }
                rows = Math.addExact(rows, entries.size());
                if (entries.isEmpty() || rows >= total) break;
                page = Math.incrementExact(page);
            }
            var result = new ArrayList<>(models.values()); result.sort(Comparator.comparing(DiscoveredModel::modelId));
            return List.copyOf(result);
        } catch (ModelDiscoveryException rejected) { throw rejected; }
        catch (IOException | IllegalArgumentException | ArithmeticException invalid) {
            // 不将远端响应、模型内容或凭据放入异常链。
            throw rejected("model_discovery_response_invalid");
        }
    }

    private static int integer(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0)
            throw rejected("model_discovery_response_invalid");
        return value.intValue();
    }
    private static ModelDiscoveryException rejected(String reason) { return new ModelDiscoveryException(reason); }
}
