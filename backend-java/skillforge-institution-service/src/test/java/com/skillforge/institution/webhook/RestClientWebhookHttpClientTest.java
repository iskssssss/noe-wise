package com.skillforge.institution.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.core.shared.sandbox.SandboxGuard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** RestClient 边界保留 HTTP 状态，网络故障只以 IOException 向账本层传播。 */
class RestClientWebhookHttpClientTest {

    private HttpServer server;
    private SandboxGuard sandbox;
    private RestClientWebhookHttpClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/accepted", exchange -> respond(exchange, 204));
        server.createContext("/rejected", exchange -> respond(exchange, 404));
        server.start();
        sandbox = mock(SandboxGuard.class);
        client = new RestClientWebhookHttpClient(sandbox);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void returnsBothSuccessfulAndErrorHttpStatuses() throws Exception {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        when(sandbox.assertSafe(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(204, client.post(base + "/accepted", "{}", Map.of("X-Test", "yes")));
        assertEquals(404, client.post(base + "/rejected", "{}", Map.of()));
    }

    @Test
    void wrapsTransportErrorsWithoutExposingRestClientDetails() {
        when(sandbox.assertSafe(anyString())).thenReturn("http://127.0.0.1:1/unreachable");

        assertThrows(IOException.class, () -> client.post("https://events.example.test/hook", "{}", Map.of()));
    }

    private void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
