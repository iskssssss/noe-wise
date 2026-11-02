package com.skillforge.runtime.adapter.model.discovery;

import com.skillforge.runtime.adapter.model.openai.OpenAiModelDiscoveryClient.ModelDiscoveryException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 发现请求共用的无重试 GET；总 timeout 覆盖正文，取消时停止原 future，返回字节始终有界。 */
public final class ModelDiscoveryHttp {
    private final HttpClient http;
    private final int maximumResponseBytes;

    public ModelDiscoveryHttp(HttpClient http, int maximumResponseBytes) {
        this.http = Objects.requireNonNull(http, "http");
        if (maximumResponseBytes <= 0 || http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("model_discovery_transport_configuration_invalid");
        this.maximumResponseBytes = maximumResponseBytes;
    }

    public byte[] get(URI endpoint, String credential, Duration timeout) {
        Objects.requireNonNull(endpoint); Objects.requireNonNull(timeout);
        if (credential == null || credential.isBlank()) {
            throw rejected("model_discovery_credential_missing");
        }
        if (credential.chars().anyMatch(value -> Character.isISOControl((char) value))) {
            throw rejected("model_discovery_credential_invalid");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw rejected("model_discovery_timeout_invalid");
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + credential)
                .GET()
                .build();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(
                request, response -> response.statusCode() >= 200
                        && response.statusCode() < 300
                        ? new BoundedBodySubscriber(maximumResponseBytes)
                        : HttpResponse.BodySubscribers.replacing(new byte[0]));
        try {
            HttpResponse<byte[]> response = pending.get(timeoutNanos(timeout),
                    TimeUnit.NANOSECONDS);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw rejected("model_discovery_http_" + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw rejected("model_discovery_interrupted", interrupted);
        } catch (TimeoutException timeoutFailure) {
            pending.cancel(true);
            throw rejected("model_discovery_timeout");
        } catch (ExecutionException failed) {
            Throwable cause = unwrap(failed.getCause());
            if (cause instanceof ModelDiscoveryException rejected) {
                throw rejected;
            }
            if (cause instanceof HttpTimeoutException) {
                throw rejected("model_discovery_timeout");
            }
            if (cause instanceof IOException transportFailure) {
                throw rejected("model_discovery_transport_failed", transportFailure);
            }
            throw rejected("model_discovery_transport_failed");
        }
    }

    private static long timeoutNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ModelDiscoveryException rejected(String reason) {
        return new ModelDiscoveryException(reason);
    }

    private static ModelDiscoveryException rejected(String reason, Throwable cause) {
        return new ModelDiscoveryException(reason, cause);
    }

    /** 在 HttpClient 完成响应前限制累计字节，避免无界缓冲并让请求 deadline 覆盖正文。 */
    private static final class BoundedBodySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {

        private static final int COPY_BUFFER_BYTES = 8_192;

        private final int maximumBytes;
        private final ByteArrayOutputStream result = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private BoundedBodySubscriber(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription candidate) {
            if (subscription != null) {
                candidate.cancel();
                return;
            }
            subscription = candidate;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int count = buffer.remaining();
                if ((long) result.size() + count > maximumBytes) {
                    subscription.cancel();
                    body.completeExceptionally(
                            rejected("model_discovery_response_too_large"));
                    return;
                }
                byte[] copy = new byte[Math.min(COPY_BUFFER_BYTES, count)];
                while (buffer.hasRemaining()) {
                    int length = Math.min(copy.length, buffer.remaining());
                    buffer.get(copy, 0, length);
                    result.write(copy, 0, length);
                }
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(result.toByteArray());
        }
    }
}
