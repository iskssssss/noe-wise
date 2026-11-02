package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 只用于显式真实验收的固定目的转发器；尝试先落盘，超时和未知结果永不释放预留。 */
final class KnowledgeLiveMeteredProxy implements AutoCloseable {
    private static final String EMBEDDING_PATH = "/compatible-mode/v1/embeddings";
    private static final String RERANK_PATH = "/dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    /** 原两类外调预留上限为20次Embedding和10次Rerank，队列仅容纳本批排队请求。 */
    private static final int REQUEST_QUEUE_CAPACITY = 30;
    private final ObjectMapper json = new ObjectMapper();
    private final Path ledger;
    private final Path batch;
    private final String key;
    private final HttpServer server;
    /** 保持单线程转发；队列满时明确拒绝，不能退回调用线程并发执行真实外调。 */
    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(REQUEST_QUEUE_CAPACITY),
            Thread.ofPlatform().name("knowledge-live-metered-proxy").daemon(false).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    KnowledgeLiveMeteredProxy(Path batch, String key) throws IOException {
        this.batch = batch.toAbsolutePath().normalize();
        this.key = key;
        if (key == null || key.isBlank()) throw new IllegalArgumentException("DASHSCOPE_API_KEY required");
        Files.createDirectories(this.batch);
        ledger = this.batch.resolve("external-attempts.jsonl");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::forward);
        server.start();
    }

    String embeddingEndpoint() { return origin() + "/compatible-mode/v1"; }
    // 生产策略按 endpoint 中的百炼域名分派；路径保留该标记，网络目的仍只能是本地 listener。
    String rerankEndpoint() { return origin() + "/dashscope.aliyuncs.com"; }
    private String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private void forward(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
            String path = exchange.getRequestURI().toString();
            boolean embedding = EMBEDDING_PATH.equals(path);
            boolean rerank = RERANK_PATH.equals(path);
            if (!"POST".equals(exchange.getRequestMethod()) || (!embedding && !rerank)) {
                reject(exchange, 403, "fixed_destination_only"); return;
            }
            if (Files.exists(batch.resolve("external-failure.json"))) {
                reject(exchange, 409, "batch_stopped_after_external_failure"); return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(16_385);
            if (body.length > 16_384) { reject(exchange, 413, "request_byte_limit"); return; }
            JsonNode request = json.readTree(body);
            String kind = embedding ? "embedding" : "rerank";
            if (!(embedding ? "qwen3.7-text-embedding-flash" : "qwen3.7-text-rerank")
                    .equals(request.path("model").asText())) {
                reject(exchange, 403, "fixed_model_only"); return;
            }
            long reserved;
            try { reserved = tokenBound(request, embedding); }
            catch (IllegalArgumentException invalid) { reject(exchange, 400, "invalid_input"); return; }
            String attempt = UUID.randomUUID().toString();
            try { reserve(attempt, kind, reserved); }
            catch (IllegalStateException budget) { reject(exchange, 429, "persistent_budget_exhausted"); return; }
            URI destination = URI.create(embedding
                    ? "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"
                    : "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank");
            try {
                HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(destination)
                        .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + key)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
                append(Map.of("event", "response", "attempt", attempt, "kind", kind,
                        "http_status", response.statusCode(), "response_bytes", response.body().length,
                        "time", Instant.now().toString()));
                // 只存供应商成功回执；错误响应可能含鉴权上下文，向生产 Provider 返回固定错误正文。
                if (response.statusCode() == 200) {
                    Files.write(batch.resolve("response-" + attempt + ".json"), response.body(), StandardOpenOption.CREATE_NEW);
                    JsonNode receipt = json.readTree(response.body());
                    boolean payloadComplete = receipt != null && completeReceipt(receipt, request, embedding);
                    JsonNode usage = receipt == null ? json.createObjectNode() : receipt.path("usage");
                    long totalTokens = positiveUsage(usage.path("total_tokens"));
                    long promptTokens = embedding ? positiveUsage(usage.path("prompt_tokens")) : -1;
                    boolean usageComplete = totalTokens > 0 && (!embedding || (promptTokens > 0 && totalTokens >= promptTokens));
                    boolean usageWithinReservation = usageComplete && totalTokens <= reserved;
                    var metering = new java.util.LinkedHashMap<String, Object>();
                    metering.put("event", "metering_receipt"); metering.put("attempt", attempt); metering.put("kind", kind);
                    metering.put("reserved_tokens", reserved); metering.put("time", Instant.now().toString());
                    metering.put("payload_complete", payloadComplete); metering.put("usage_complete", usageComplete);
                    metering.put("usage_within_reservation", usageWithinReservation);
                    metering.put("complete_success", payloadComplete && usageWithinReservation);
                    if (totalTokens > 0) metering.put("total_tokens", totalTokens);
                    if (promptTokens > 0) metering.put("prompt_tokens", promptTokens);
                    append(metering);
                    if (!payloadComplete) stopBatch(attempt, "incomplete_receipt");
                    else if (!usageComplete) stopBatch(attempt, "usage_missing_or_invalid");
                    else if (!usageWithinReservation) stopBatch(attempt, "usage_exceeds_reservation");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.body().length);
                    exchange.getResponseBody().write(response.body());
                } else {
                    stopBatch(attempt, "http_" + response.statusCode());
                    reject(exchange, response.statusCode(), "upstream_rejected");
                }
            } catch (Exception failure) {
                append(Map.of("event", "unknown", "attempt", attempt, "kind", kind,
                        "failure_type", failure.getClass().getSimpleName(), "time", Instant.now().toString()));
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                stopBatch(attempt, "unknown");
                reject(exchange, 502, "upstream_outcome_unknown");
            }
            } catch (Exception handlerFailure) {
                // HTTP server 不保证回显 handler 异常；独立文件保存安全诊断，避免退化为空账本和向量断言。
                var diagnostic = Map.of("event", "handler_failure", "failure_type", handlerFailure.getClass().getName(),
                        "time", Instant.now().toString(), "frames", java.util.Arrays.stream(handlerFailure.getStackTrace())
                                .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber()).toList());
                try (FileChannel channel = FileChannel.open(batch.resolve("handler-failure-" + UUID.randomUUID() + ".json"),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { write(channel, diagnostic); }
                stopBatch("handler", "handler_failure");
                reject(exchange, 500, "local_proxy_handler_failure");
            }
        }
    }

    private long tokenBound(JsonNode request, boolean embedding) {
        JsonNode documents = embedding ? request.path("input") : request.path("input").path("documents");
        if (!documents.isArray() || documents.isEmpty() || documents.size() > 20) throw new IllegalArgumentException();
        long total = 0;
        for (JsonNode document : documents) total += textBound(document);
        if (!embedding) total += Math.multiplyExact(textBound(request.path("input").path("query")), documents.size());
        if (total > 5_000) throw new IllegalArgumentException();
        return total;
    }

    private long textBound(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException();
        // UTF-8 字节是文本 token 的保守上界；每条额外留 128 token 处理供应商 framing。
        return value.asText().getBytes(StandardCharsets.UTF_8).length + 128L;
    }

    private long positiveUsage(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0 ? value.longValue() : -1;
    }

    private boolean completeReceipt(JsonNode receipt, JsonNode request, boolean embedding) {
        JsonNode values = embedding ? receipt.path("data") : receipt.path("output").path("results");
        int count = embedding ? request.path("input").size() : request.path("input").path("documents").size();
        if (!values.isArray() || values.size() != count) return false;
        java.util.Set<Integer> indices = new java.util.HashSet<>();
        for (JsonNode value : values) {
            if (!value.path("index").isIntegralNumber()) return false;
            int index = value.path("index").asInt();
            if (index < 0 || index >= count || !indices.add(index)) return false;
            if (embedding) {
                JsonNode vector = value.path("embedding");
                if (!vector.isArray() || vector.size() != 1024) return false;
                for (JsonNode number : vector) if (!number.isNumber() || !Double.isFinite(number.asDouble())) return false;
            } else if (!value.path("relevance_score").isNumber()
                    || !Double.isFinite(value.path("relevance_score").asDouble())) return false;
        }
        return true;
    }

    private synchronized void reserve(String attempt, String kind, long tokens) throws IOException {
        try (FileChannel channel = FileChannel.open(ledger, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                var ignored = channel.lock()) {
            long count = 0, used = 0;
            // Windows 文件锁覆盖整个文件；必须用持锁 channel 读取，不能另开 Files.readAllLines 句柄。
            long size = channel.size();
            if (size > 1_048_576) throw new IOException("ledger_size_limit");
            ByteBuffer content = ByteBuffer.allocate(Math.toIntExact(size));
            channel.position(0);
            while (content.hasRemaining()) if (channel.read(content) < 0) throw new IOException("ledger_truncated");
            content.flip();
            for (String line : StandardCharsets.UTF_8.decode(content).toString().lines().toList()) {
                JsonNode entry = json.readTree(line);
                if ("reserved".equals(entry.path("event").asText()) && kind.equals(entry.path("kind").asText())) {
                    count++; used = Math.addExact(used, entry.path("reserved_tokens").asLong());
                }
            }
            if (count >= (kind.equals("embedding") ? 20 : 10) || used + tokens > 50_000)
                throw new IllegalStateException("budget exhausted");
            write(channel, Map.of("event", "reserved", "attempt", attempt, "kind", kind,
                    "reserved_tokens", tokens, "time", Instant.now().toString()));
        }
    }

    private synchronized void append(Map<String, Object> event) throws IOException {
        try (FileChannel channel = FileChannel.open(ledger, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var ignored = channel.lock()) { write(channel, event); }
    }
    private void stopBatch(String attempt, String outcome) throws IOException {
        Path failure = batch.resolve("external-failure.json");
        if (!Files.exists(failure)) Files.writeString(failure,
                json.writeValueAsString(Map.of("attempt",attempt,"outcome",outcome,"time",Instant.now().toString())),
                StandardOpenOption.CREATE_NEW);
    }
    private void write(FileChannel channel, Map<String, Object> event) throws IOException {
        channel.position(channel.size());
        ByteBuffer bytes = ByteBuffer.wrap((json.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8));
        while (bytes.hasRemaining()) channel.write(bytes);
        channel.force(true);
    }
    List<JsonNode> reservations() throws IOException {
        return events().stream().filter(row -> "reserved".equals(row.path("event").asText())).toList();
    }
    List<JsonNode> meteringReceipts() throws IOException {
        return events().stream().filter(row -> "metering_receipt".equals(row.path("event").asText())).toList();
    }
    private List<JsonNode> events() throws IOException {
        if (!Files.exists(ledger)) return List.of();
        return Files.readAllLines(ledger).stream().map(line -> {
            try { return json.readTree(line); } catch (IOException invalid) { throw new IllegalStateException(invalid); }
        }).toList();
    }
    private void reject(HttpExchange exchange, int code, String error) throws IOException {
        byte[] response = json.writeValueAsBytes(Map.of("error", error));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, response.length);
        exchange.getResponseBody().write(response);
    }
    @Override public void close() {
        server.stop(0); executor.shutdown(); client.close();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) throw new IllegalStateException("proxy cleanup unfinished");
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
}
