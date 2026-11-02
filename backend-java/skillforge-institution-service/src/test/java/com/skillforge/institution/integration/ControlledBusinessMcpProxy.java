package com.skillforge.institution.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试独占 MCP 传输屏障：保留后端真实业务处理，只控制响应时点与丢失。 */
final class ControlledBusinessMcpProxy implements AutoCloseable {
    private final ObjectMapper json;
    private final URI upstream;
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor=Executors.newFixedThreadPool(4);
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ConcurrentHashMap<String,Control> controls=new ConcurrentHashMap<>();

    ControlledBusinessMcpProxy(ObjectMapper json,String upstream) throws IOException {
        this.json=json;this.upstream=URI.create(upstream+"/mcp");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(executor);server.createContext("/mcp",this::forward);server.start();
    }
    String origin() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    Control continuation(String requestId,boolean dropResponse) {
        var control=new Control(dropResponse);
        if(controls.putIfAbsent(requestId,control)!=null)throw new IllegalArgumentException("duplicate_business_transport_control");
        return control;
    }

    private void forward(HttpExchange exchange) throws IOException {
        try {
            byte[] body=exchange.getRequestBody().readAllBytes();
            var request=json.readValue(body,RpcRequest.class);
            Control control=request.params()!=null&&request.params().arguments()!=null
                    &&request.params().requestState()!=null?controls.get(request.params().arguments().requestId()):null;
            if(control!=null) {
                control.calls.incrementAndGet();control.entered.countDown();
                if(!control.release.await(45,TimeUnit.SECONDS))throw new IllegalStateException("business_proxy_barrier_timeout");
            }
            var outbound=HttpRequest.newBuilder(upstream).timeout(Duration.ofSeconds(30));
            for(String header:java.util.List.of("Content-Type","Authorization","MCP-Protocol-Version","Accept")) {
                String value=exchange.getRequestHeaders().getFirst(header);if(value!=null)outbound.header(header,value);
            }
            var response=client.send(outbound.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
            if(control!=null) {
                control.upstreamStatus=response.statusCode();control.committed.countDown();
                // 后端已处理，故意不给回执；不返回伪成功，也不代替 Runtime 重试。
                if(control.dropResponse)return;
            }
            response.headers().firstValue("Content-Type").ifPresent(value->exchange.getResponseHeaders().set("Content-Type",value));
            exchange.sendResponseHeaders(response.statusCode(),response.body().length);
            exchange.getResponseBody().write(response.body());
        } catch(InterruptedException stopped) {
            Thread.currentThread().interrupt();throw new IOException("business_proxy_interrupted",stopped);
        } finally { exchange.close(); }
    }

    @Override public void close() throws InterruptedException {
        controls.values().forEach(Control::release);server.stop(0);executor.shutdownNow();
        if(!executor.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("business_proxy_not_quiescent");
    }

    static final class Control {
        private final boolean dropResponse;
        private final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),committed=new CountDownLatch(1);
        private final AtomicInteger calls=new AtomicInteger();
        private volatile int upstreamStatus;
        private Control(boolean dropResponse) { this.dropResponse=dropResponse; }
        boolean awaitEntered() throws InterruptedException { return entered.await(20,TimeUnit.SECONDS); }
        boolean awaitCommitted() throws InterruptedException { return committed.await(20,TimeUnit.SECONDS); }
        void release() { release.countDown(); }
        int calls() { return calls.get(); }
        int upstreamStatus() { return upstreamStatus; }
    }

    @JsonIgnoreProperties(ignoreUnknown=true)
    private record RpcRequest(String method,Params params) { }
    @JsonIgnoreProperties(ignoreUnknown=true)
    private record Params(Arguments arguments,@JsonProperty("requestState") String requestState) { }
    @JsonIgnoreProperties(ignoreUnknown=true)
    private record Arguments(@JsonProperty("request_id") String requestId) { }
}
