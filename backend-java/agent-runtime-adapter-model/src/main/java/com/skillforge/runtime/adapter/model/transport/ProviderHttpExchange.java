package com.skillforge.runtime.adapter.model.transport;

import com.skillforge.runtime.adapter.model.provider.ModelProvider.InvocationRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 取消和 deadline 覆盖响应正文读取，并在返回前主动关闭连接。 */
public final class ProviderHttpExchange implements AutoCloseable {
    private final InvocationRequest request;
    private final CompletableFuture<HttpResponse<InputStream>> future;
    private final AtomicReference<InputStream> responseBody = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread watchdog;

    public ProviderHttpExchange(HttpClient http, Clock clock, InvocationRequest request, HttpRequest httpRequest) {
        this.request = request;
        if (request.cancelled().getAsBoolean()) {
            throw new IllegalArgumentException("model_exchange_cancelled");
        }
        try { request.beforeSend().run(); }
        catch (RuntimeException denied) { throw new PreDispatchRejected(denied); }
        future = http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        var deadline = clock.instant().plus(request.timeout());
        watchdog = Thread.startVirtualThread(() -> {
            try {
                while (!closed.get()) {
                    if (request.cancelled().getAsBoolean()
                            || !clock.instant().isBefore(deadline)) {
                        close();
                        return;
                    }
                    Thread.sleep(20);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** 只由实际 HTTP 委派前的授权回调产生，不能用来覆盖发送后的异常。 */
    public static final class PreDispatchRejected extends RuntimeException {
        private PreDispatchRejected(RuntimeException cause) { super("model_pre_dispatch_rejected", cause); }
    }

    public HttpResponse<InputStream> response()
            throws InterruptedException, ExecutionException, IOException {
        HttpResponse<InputStream> response = future.get();
        responseBody.set(response.body());
        if (closed.get()) {
            response.body().close();
            throw new IOException("model_exchange_closed");
        }
        return response;
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            future.cancel(true);
            InputStream body = responseBody.get();
            if (body != null) try { body.close(); } catch (IOException ignored) { }
        }
    }
}
