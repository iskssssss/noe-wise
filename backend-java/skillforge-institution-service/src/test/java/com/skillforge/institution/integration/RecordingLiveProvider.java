package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Records body-only evidence and transparently forwards every request once to the real provider. */
final class RecordingLiveProvider implements AutoCloseable {
    final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> responses = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper json;
    private final Path report;
    private final java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();

    RecordingLiveProvider(ObjectMapper json, String endpoint, String key, Path report) throws IOException {
        this.json = json;
        this.report = report;
        URI target = URI.create(endpoint.replaceFirst("/$", "") + "/chat/completions");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            int index = sequence.incrementAndGet();
            Path receipt = report.resolve("provider-" + index + ".receipt.json");
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                requests.add(new String(body, StandardCharsets.UTF_8));
                Files.write(report.resolve("provider-" + index + ".request.json"), body);
                Files.writeString(receipt, "{\"status\":\"UNKNOWN\",\"phase\":\"before_provider_send\"}");
                var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(150))
                        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
                // No retry: a lost response may represent a completed paid request.
                var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                responses.add(new String(response.body(), StandardCharsets.UTF_8));
                Files.write(report.resolve("provider-" + index + ".response.txt"), response.body());
                Files.writeString(receipt, json.writeValueAsString(java.util.Map.of("status", "RESPONSE_RECEIVED", "http_status", response.statusCode())));
                exchange.getResponseHeaders().set("Content-Type", response.headers()
                        .firstValue("Content-Type").orElse("application/json"));
                exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                exchange.getResponseBody().write(response.body());
                if (response.statusCode() >= 400) failures.add(new AssertionError("Provider HTTP " + response.statusCode()));
            } catch (Throwable failure) {
                failures.add(failure);
                try { exchange.sendResponseHeaders(502, -1); } catch (IOException ignored) { }
            } finally { exchange.close(); }
        });
        server.start();
    }

    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    void healthy() {
        if (!failures.isEmpty()) throw new AssertionError("Real provider first failure; see private evidence", failures.getFirst());
    }
    boolean called(String name) throws IOException {
        // Later provider requests contain the runtime's fully reconstructed assistant calls.
        // Resolve deployment wire names from the exact catalog metadata sent to this model.
        var wireNames = new java.util.HashSet<String>();
        wireNames.add(name); // Native skill control functions retain their published names.
        for (String request : requests) {
            var node = json.readTree(request);
            for (var tool : node.path("tools")) {
                var function = tool.path("function");
                if (function.path("description").asText().contains("logical_name=\"" + name + "\"\n"))
                    wireNames.add(function.path("name").asText());
            }
        }
        for (String request : requests)
            for (var message : json.readTree(request).path("messages"))
                if ("assistant".equals(message.path("role").asText()))
                    for (var call : message.path("tool_calls"))
                        if (wireNames.contains(call.path("function").path("name").asText())) return true;
        return false;
    }
    @Override public void close() throws Exception {
        server.stop(0);
        workers.shutdownNow();
        if (!workers.awaitTermination(10, TimeUnit.SECONDS)) throw new AssertionError("Live provider workers not stopped");
        Files.writeString(report.resolve("requests.json"), "[" + String.join(",\n", requests) + "]", StandardCharsets.UTF_8);
        Files.writeString(report.resolve("responses.json"), json.writeValueAsString(responses), StandardCharsets.UTF_8);
        Files.writeString(report.resolve("provider-errors.txt"), failures.stream().map(failure -> failure.getClass().getName()
                + ": " + failure.getMessage()).collect(java.util.stream.Collectors.joining("\n")), StandardCharsets.UTF_8);
    }
}
