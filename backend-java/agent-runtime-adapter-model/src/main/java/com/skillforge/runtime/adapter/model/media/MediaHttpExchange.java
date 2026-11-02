package com.skillforge.runtime.adapter.model.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 媒体调用的总期限和取消覆盖整个响应正文；关闭后不保留读取线程。 */
final class MediaHttpExchange implements AutoCloseable {
    private final CompletableFuture<HttpResponse<InputStream>> future;
    private final AtomicReference<InputStream> body = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread watchdog;

    MediaHttpExchange(HttpClient client, Clock clock, DomesticMediaProvider.Connection connection, HttpRequest request,
            Instant deadline) {
        future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        watchdog = Thread.startVirtualThread(() -> {
            try {
                while (!closed.get()) {
                    if (connection.cancelled().getAsBoolean() || !clock.instant().isBefore(deadline)) {
                        close(); return;
                    }
                    Thread.sleep(20);
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
    }

    HttpResponse<InputStream> response() throws InterruptedException, ExecutionException, IOException {
        var response = future.get();
        body.set(response.body());
        if (closed.get()) {
            response.body().close();
            throw new IOException("media_exchange_closed");
        }
        return response;
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            future.cancel(true);
            var stream = body.get();
            if (stream != null) try { stream.close(); } catch (IOException ignored) { }
            var watcher = watchdog;
            if (watcher != null) watcher.interrupt();
        }
    }
}
