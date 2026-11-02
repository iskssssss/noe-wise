package com.skillforge.core.knowledge;

import com.skillforge.core.shared.sandbox.SandboxGuard;
import com.skillforge.runtime.api.KnowledgeCapacity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 链接读取服从原请求剩余窗口，无重定向，保留超限裁剪事实。 */
@Component
public class HttpKnowledgeLinkFetcher implements KnowledgeLinkFetcher {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final int READ_BUFFER_CHARACTERS = 4096;
    private final RestClient.Builder client;
    private final SandboxGuard sandboxGuard;

    public HttpKnowledgeLinkFetcher(RestClient.Builder restClientBuilder, SandboxGuard sandboxGuard) {
        this.client = restClientBuilder.clone();
        this.sandboxGuard = sandboxGuard;
    }

    @Override public FetchResult fetch(String url, int maxChars, Duration timeout) {
        if (maxChars <= 0 || timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("knowledge_link_window_invalid");
        String safeUrl = sandboxGuard.assertSafe(url);
        Duration window = timeout.compareTo(Duration.ofSeconds(KnowledgeCapacity.DEFAULT_TIMEOUT_SECONDS)) > 0
                ? Duration.ofSeconds(KnowledgeCapacity.DEFAULT_TIMEOUT_SECONDS) : timeout;
        int millis = Math.toIntExact(Math.max(1, window.toMillis()));
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(millis); factory.setReadTimeout(millis);
        long deadline = System.nanoTime() + window.toNanos();
        return client.clone().requestFactory(factory).build().get().uri(safeUrl).exchange((request, response) -> {
            Body body = readBody(response.getBody(), maxChars, deadline);
            int status = response.getStatusCode().value();
            return new FetchResult(safeUrl, status, response.getHeaders().getFirst("Content-Type"),
                    body.text(), status >= 300 && status < 400, body.truncated());
        });
    }

    private static Body readBody(InputStream input, int maxChars, long deadline) throws IOException {
        if (input == null) return new Body("", false);
        var bounded = new java.io.FilterInputStream(input) {
            private long count;
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (System.nanoTime() - deadline >= 0) throw new IOException("knowledge_link_timeout");
                int read = super.read(bytes, offset, (int)Math.min(length, MAX_RESPONSE_BYTES - count + 1));
                if (read > 0 && (count += read) > MAX_RESPONSE_BYTES) throw new IOException("knowledge_link_response_limit");
                return read;
            }
            @Override public int read() throws IOException {
                byte[] byteValue = new byte[1]; int read = read(byteValue, 0, 1);
                return read < 0 ? -1 : byteValue[0] & 0xff;
            }
        };
        try (var reader = new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)), READ_BUFFER_CHARACTERS)) {
            var result = new StringBuilder(Math.min(maxChars, READ_BUFFER_CHARACTERS));
            int first; int characters = 0;
            while ((first = reader.read()) != -1) {
                if (System.nanoTime() - deadline >= 0) throw new IOException("knowledge_link_timeout");
                int second = -1;
                if (Character.isHighSurrogate((char) first)) {
                    second = reader.read();
                    if (second < 0 || !Character.isLowSurrogate((char) second))
                        throw new IOException("knowledge_link_utf8_invalid");
                }
                if (characters++ >= maxChars) return new Body(result.toString(), true);
                result.append((char) first);
                if (second >= 0) result.append((char) second);
            }
            return new Body(result.toString(), false);
        }
    }
    private record Body(String text, boolean truncated) { }
}
