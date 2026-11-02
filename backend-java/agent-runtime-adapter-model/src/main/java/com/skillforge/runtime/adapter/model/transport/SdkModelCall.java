package com.skillforge.runtime.adapter.model.transport;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ObjectMappers;
import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

/** SDK 接管 HTTP 与 SSE；这里只保留一次调用的预算、取消及资源所有权。 */
public final class SdkModelCall implements AutoCloseable {
    private final OpenAIClient client;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicReference<okhttp3.Call> activeCall =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final Thread watchdog;
    private volatile String preDispatchRejection;
    private com.fasterxml.jackson.databind.JsonNode errorBody;

    /** 只继承宿主声明的连接超时；代理和 TLS 不从 JDK 客户端复制。 */
    public SdkModelCall(InvocationRequest request, Clock clock, int maximumResponseBytes,
            java.net.http.HttpClient http, SdkHttpResources resources) {
        var connectTimeout = http.connectTimeout().orElse(request.timeout());
        var mapper = ObjectMappers.jsonMapper().copy();
        mapper.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        var deadline = clock.instant().plus(request.timeout());
        var transport = resources.client().newBuilder()
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .connectTimeout(connectTimeout).callTimeout(request.timeout())
                .addInterceptor(chain -> {
                    activeCall.set(chain.call());
                    if (request.cancelled().getAsBoolean() || !clock.instant().isBefore(deadline)) {
                        preDispatchRejection = request.cancelled().getAsBoolean() ? "model_exchange_cancelled" : "model_deadline_exceeded";
                        throw new IOException(preDispatchRejection);
                    }
                    var body = chain.request().body();
                    if (body != null && body.contentLength() > request.maximumRequestBytes()) {
                        preDispatchRejection = "model_request_bytes_exceeded";
                        throw new IOException(preDispatchRejection);
                    }
                    try { request.beforeSend().run(); }
                    catch (RuntimeException denied) {
                        preDispatchRejection = "model_pre_dispatch_rejected";
                        throw new IOException(preDispatchRejection);
                    }
                    var response = chain.proceed(chain.request());
                    // SDK 会提升 error 对象；保留原始 envelope 供现有供应商分类边界使用。
                    if (!response.isSuccessful()) {
                        try {
                            var raw = response.peekBody(maximumResponseBytes + 1L).bytes();
                            if (raw.length <= maximumResponseBytes) {
                                errorBody = mapper.readTree(raw);
                            }
                        } catch (IOException malformed) { errorBody = null; }
                    }
                    var sourceBody = response.body();
                    if (sourceBody == null) return response;
                    var bounded = Okio.buffer(new ForwardingSource(sourceBody.source()) {
                        private long bytes;
                        @Override public long read(Buffer sink, long byteCount) throws IOException {
                            // 先把预算内已有完整帧交给 SDK，下一次读取才拒绝超限字节。
                            long remaining = maximumResponseBytes - bytes;
                            long count = super.read(sink, Math.min(byteCount, remaining > 0 ? remaining : 1));
                            if (count > 0 && (bytes += count) > maximumResponseBytes) {
                                throw new IOException("model_response_bytes_exceeded");
                            }
                            return count;
                        }
                    });
                    return response.newBuilder().body(new ResponseBody() {
                        @Override public MediaType contentType() { return sourceBody.contentType(); }
                        @Override public long contentLength() { return sourceBody.contentLength(); }
                        @Override public BufferedSource source() { return bounded; }
                    }).build();
                }).build();
        client = OpenAIOkHttpClient.builder().okHttpClient(transport)
                .baseUrl(request.endpoint()).apiKey(request.credential())
                .maxRetries(0).timeout(com.openai.core.Timeout.builder()
                        .connect(connectTimeout).request(request.timeout()).build())
                .jsonMapper(mapper)
                .build();
        watchdog = Thread.startVirtualThread(() -> {
            try {
                while (!closed.get()) {
                    if (request.cancelled().getAsBoolean() || !clock.instant().isBefore(deadline)) {
                        var call = activeCall.get();
                        if (call != null) call.cancel();
                        return;
                    }
                    Thread.sleep(20);
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
    }

    public OpenAIClient client() { return client; }

    public com.fasterxml.jackson.databind.JsonNode errorBody() { return errorBody; }

    public java.util.Optional<String> preDispatchRejection() {
        return java.util.Optional.ofNullable(preDispatchRejection);
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            watchdog.interrupt();
            var call = activeCall.get();
            if (call != null) call.cancel();
            // SDK 4.94.0 supplied-client 使用 borrow：close 不释放装配拥有的池。
            client.close();
        }
    }
}
