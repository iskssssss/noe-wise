package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.ModelSelections;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.sun.net.httpserver.HttpServer;
import com.skillforge.runtime.adapter.model.provider.ModelProvider;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog;
import com.skillforge.runtime.adapter.model.provider.ModelProviderCatalog.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 真实平台发布与机构选择配合本地确定性 Embedding HTTP 响应，不访问外部模型。 */
final class KnowledgeEmbeddingFixture implements AutoCloseable {
    /** 本地HTTP fixture显式声明地域、模型类型与协议，不放宽生产自定义服务校验。 */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @org.springframework.context.annotation.Bean
        ModelProviderCatalog knowledgeEmbeddingHttpCatalog() {
            var unsupported = SupportLevel.UNSUPPORTED;
            var protocol = new ProtocolProfile(
                    ModelProtocol.OPENAI_EMBEDDINGS,
                    "openai-embeddings-v1", java.util.Set.of(
                            AuthenticationScheme.BEARER_API_KEY),
                    new CapabilitySupport(
                            unsupported, unsupported, unsupported, unsupported, unsupported),
                    VerificationStatus.VERIFIED);
            var service = new ServiceProfile(
                    "knowledge-embedding-fixture", "本地确定性Embedding", "knowledge-fixture-v1",
                    Availability.SUPPORTED, null,
                    EndpointMode.CUSTOM, null, List.of(protocol),
                    ServiceRegion.LOCAL,
                    java.util.Set.of(ModelKind.EMBEDDING));
            List<ModelProvider> providers = new ArrayList<>();
            ModelProviderCatalog.standard().presets()
                    .forEach(preset -> providers.add(() -> preset));
            providers.add(() -> new ProviderPreset(
                    "knowledge-fixture", "本地知识测试", ProviderCategory.CUSTOM_OR_SELF_HOSTED,
                    List.of(service)));
            return new ModelProviderCatalog(providers);
        }
    }
    private static final int DIMENSIONS = 1536;
    private final HttpServer server;
    private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

    KnowledgeEmbeddingFixture(PlatformModelCatalogService catalog, String tenantId) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var json = new ObjectMapper();
        server.createContext("/embeddings", exchange -> {
            try (exchange) {
                calls.incrementAndGet();
                var input = json.readTree(exchange.getRequestBody()).path("input");
                List<Map<String, Object>> data = new ArrayList<>();
                for (int index = 0; index < input.size(); index++) {
                    data.add(Map.of("index", index, "embedding", Collections.nCopies(DIMENSIONS, 0.01)));
                }
                byte[] response = json.writeValueAsBytes(Map.of("data", data,
                        "usage", Map.of("prompt_tokens", input.size(), "total_tokens", input.size())));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            select(catalog, tenantId, "http://127.0.0.1:" + server.getAddress().getPort());
        } catch (RuntimeException failure) {
            server.stop(0);
            throw failure;
        }
    }

    /** 只创建知识库的测试无需 HTTP 调用，也必须冻结合法的已发布模型版本。 */
    static void select(PlatformModelCatalogService catalog, String tenantId, String endpoint) {
        var model = catalog.create(new ModelWrite(null, "Knowledge integration embedding", "EMBEDDING", true,
                "knowledge-fixture", "knowledge-embedding-fixture", "knowledge-fixture-v1", "openai-embeddings", "bearer-api-key", endpoint,
                "knowledge-embedding-fixture", "fixture-key", null, null, null, null, null, 30,
                DIMENSIONS, null, java.math.BigDecimal.valueOf(0L), java.math.BigDecimal.valueOf(0L), java.math.BigDecimal.valueOf(0L), "CNY"), "knowledge-integration-fixture");
        catalog.publish(model.id(), model.revision(), "knowledge-integration-fixture");
        var current = catalog.selections(tenantId);
        catalog.select(tenantId, new ModelSelections(current.llmModelConfigId(), model.id(), current.rerankModelConfigId()));
    }

    @Override public void close() { server.stop(0); }
    int calls() { return calls.get(); }
}
