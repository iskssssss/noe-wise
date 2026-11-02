package com.skillforge.runtime.adapter.model.transport;

import java.util.concurrent.atomic.AtomicBoolean;

/** 装配拥有物理连接池；单次 SDK client 仅借用，不携带跨请求凭据或取消状态。 */
public final class SdkHttpResources implements AutoCloseable {
    private final okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
    private final AtomicBoolean closed = new AtomicBoolean();

    okhttp3.OkHttpClient client() {
        if (closed.get()) throw new IllegalStateException("model_transport_closed");
        return client;
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
