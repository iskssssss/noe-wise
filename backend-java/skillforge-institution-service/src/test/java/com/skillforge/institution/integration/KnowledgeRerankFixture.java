package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.model.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 本地真实 HTTP 重排响应与可切换服务故障，不访问外部供应商。 */
final class KnowledgeRerankFixture implements AutoCloseable {
    final HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    volatile boolean fail;
    String modelId;
    KnowledgeRerankFixture(PlatformModelCatalogService catalog, String tenant) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var json = new ObjectMapper();
        server.createContext("/rerank", exchange -> { try(exchange) {
            calls.incrementAndGet();
            var body = json.readTree(exchange.getRequestBody());
            var results = new ArrayList<Map<String,Object>>();
            for (int i = body.path("documents").size() - 1; i >= 0; i--)
                results.add(Map.of("index", i, "relevance_score", 0.95 - results.size() * 0.1));
            byte[] bytes = json.writeValueAsBytes(fail ? Map.of("error", "controlled_rerank_unavailable") : Map.of("results", results));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(fail ? 503 : 200, bytes.length); exchange.getResponseBody().write(bytes);
        }});
        server.createContext("/fail", exchange -> {try(exchange){fail=true;exchange.sendResponseHeaders(200,-1);}});
        server.start();
        try {
            var model=catalog.create(new ModelWrite(null,"Controlled rerank","RERANK",true,"custom","custom-rerank",
                    "custom-v1","openai-rerank","bearer-api-key",endpoint(),"fixture-rerank","fixture-key",
                    null,null,null,null,null,30,null,"/rerank",java.math.BigDecimal.ZERO,null,null,"CNY"),"knowledge-rerank-fixture");
            catalog.publish(model.id(),model.revision(),"knowledge-rerank-fixture");
            modelId=model.id();select(catalog,tenant);
        } catch(Exception failure){server.stop(0);throw failure;}
    }
    String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    void select(PlatformModelCatalogService catalog,String tenant){var old=catalog.selections(tenant);catalog.select(tenant,new ModelSelections(old.llmModelConfigId(),old.embeddingModelConfigId(),modelId));}
    public void close(){server.stop(0);}
}
