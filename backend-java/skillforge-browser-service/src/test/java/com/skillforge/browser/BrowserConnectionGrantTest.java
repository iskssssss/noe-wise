package com.skillforge.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.browser.BrowserConnectionGrant;
import com.skillforge.execution.browser.BrowserConnectionGrant.Status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 使用准确配对摘要覆盖反序授权、个人隔离、持久化未知窗口与内部查询契约。 */
class BrowserConnectionGrantTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    /** 只替代 daemon I/O，摘要绑定和所有持久状态迁移使用生产实现。 */
    private static final class PairingEngine extends BrowserEngine {
        private String pairingToken;
        PairingEngine(Path root) throws IOException { super(root.toString(), "unused", "wss://browser.example", new ObjectMapper()); }
        @Override public synchronized int daemon(Path home) throws IOException {
            Files.createDirectories(home);
            Files.writeString(home.resolve("owner.json"), "{\"port\":52800}");
            return 52800;
        }
        @Override JsonNode run(Path home, List<String> args, int seconds) {
            var mapper = new ObjectMapper();
            if (args.contains("devices")) return mapper.createArrayNode();
            if (args.contains("pair")) return mapper.createObjectNode().put("pairing_url", "wss://browser.example/extension/" + home.getFileName() + "#" + pairingToken);
            throw new AssertionError("unexpected CLI operation");
        }
    }

    @Test void originalBearerAssociatesExactRequestAcrossReverseOrderAndRestartWithoutSecrets() throws Exception {
        var engine = new PairingEngine(root);
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        String firstToken = "A".repeat(43), secondToken = "B".repeat(43);
        engine.pairingToken = firstToken;
        engine.device("1", "2", "pair", null, null, first);
        engine.pairingToken = secondToken;
        engine.device("1", "2", "pair", null, null, second);
        String key = engine.home("1", "2").getFileName().toString();
        assertEquals(Status.WAITING, engine.connectionGrant("1", "2", first).status());
        assertEquals(second, engine.connectionExchangeStarted(key, "pair", "Bearer " + secondToken));
        engine.connectionExchangeFinished(key, second, 200, response("device-second"));
        assertEquals(Status.WAITING, engine.connectionGrant("1", "2", first).status());
        assertEquals(first, engine.connectionExchangeStarted(key, "pair", "Bearer " + firstToken));
        assertEquals(Status.UNKNOWN, engine.connectionGrant("1", "2", first).status());
        engine.connectionExchangeFinished(key, first, 200, response("device-first"));
        // 相同原始 exchange 重复到达保持同一关联，后续官方拒绝也不会抹除已经确认的成功。
        assertEquals(first, engine.connectionExchangeStarted(key, "pair", "Bearer " + firstToken));
        engine.connectionExchangeFinished(key, first, 200, response("device-first"));
        engine.connectionExchangeFinished(key, first, 401, new byte[0]);
        var restarted = new PairingEngine(root);
        assertEquals(new BrowserConnectionGrant(first, Status.PAIRED, "device-first"), restarted.connectionGrant("1", "2", first));
        assertEquals("device-second", restarted.connectionGrant("1", "2", second).deviceId());
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String persisted = Files.readString(path);
                assertFalse(persisted.contains(firstToken));assertFalse(persisted.contains(secondToken));
                assertFalse(persisted.contains("PRIVATE_NEXT_TOKEN"));assertFalse(persisted.contains("PRIVATE_RESPONSE_BODY"));
            }
        }
    }

    @Test void wrongBearerOtherOwnerAndRenewCannotAssociateAndUntrackedPairStaysIndependent() throws Exception {
        var engine = new PairingEngine(root);
        String id = UUID.randomUUID().toString(), token = "C".repeat(43);
        engine.pairingToken = token;engine.device("1", "2", "pair", null, null, id);
        engine.daemon(engine.home("1", "3"));
        String key = engine.home("1", "2").getFileName().toString(), otherKey = engine.home("1", "3").getFileName().toString();
        assertNull(engine.connectionExchangeStarted(key, "pair", "Bearer " + "D".repeat(43)));
        assertNull(engine.connectionExchangeStarted(otherKey, "pair", "Bearer " + token));
        assertNull(engine.connectionExchangeStarted(key, "renew", "Bearer " + token));
        engine.connectionExchangeFinished(key, null, 200, response("unrelated-device"));
        assertEquals(Status.WAITING, engine.connectionGrant("1", "2", id).status());
        assertEquals(Status.NOT_FOUND, engine.connectionGrant("1", "3", id).status());
        engine.connectionExchangeStarted(key, "pair", "Bearer " + token);
        engine.connectionExchangeFinished(key, id, 401, new byte[0]);
        assertEquals(Status.UNKNOWN, engine.connectionGrant("1", "2", id).status());
        engine.pairingToken = "E".repeat(43);engine.device("1", "2", "pair", null, null);
        assertNull(engine.connectionExchangeStarted(key, "pair", "Bearer " + engine.pairingToken));
        assertEquals(Status.NOT_FOUND, engine.connectionGrant("1", "2", UUID.randomUUID().toString()).status());
    }

    @Test void successfulAssociationWriteFailureLeavesDurableUnknownAndNoDeviceGuess() throws Exception {
        ObjectMapper failing = spy(new ObjectMapper());
        var store = new BrowserConnectionGrantStore(failing);
        String id = UUID.randomUUID().toString(), digest = BrowserEngine.hash("F".repeat(43));
        store.bind(root, id, digest);assertEquals(id, store.beginExchange(root, digest));
        doThrow(new com.fasterxml.jackson.core.JsonGenerationException("disk full", (com.fasterxml.jackson.core.JsonGenerator)null)).when(failing).writeValueAsBytes(argThat(value -> value instanceof BrowserConnectionGrantStore.Binding binding && binding.status() == Status.PAIRED));
        assertThrows(IOException.class, () -> store.finishExchange(root, id, 200, "official-device"));
        var afterRestart = new BrowserConnectionGrantStore(new ObjectMapper()).detail(root, id);
        assertEquals(Status.UNKNOWN, afterRestart.status());assertNull(afterRestart.deviceId());
    }

    @Test void bindingMustPersistBeforePairingLinkCanBeDelivered() throws Exception {
        var engine = new PairingEngine(root);engine.pairingToken = "H".repeat(43);
        Path home = engine.home("1", "2");Files.createDirectories(home);
        Files.writeString(home.resolve("connection-grants"), "not-a-directory");
        assertThrows(IOException.class, () -> engine.device("1", "2", "pair", null, null, UUID.randomUUID().toString()));
    }

    @Test void detailIsStronglyTypedOwnerScopedAndRequiresServiceCredential() throws Exception {
        var engine = new PairingEngine(root);
        String id = UUID.randomUUID().toString();engine.pairingToken = "G".repeat(43);
        engine.device("1", "2", "pair", null, null, id);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new BrowserController(engine, "synthetic-service-token")).build();
        String uri = "/internal/browser/connection-grants/detail?tenant=1&user=2&request_id=" + id;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri).header("X-Browser-Service-Token", "synthetic-service-token"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        JsonNode body = json.readTree(response.getContentAsByteArray());
        assertEquals(id, body.path("connection_request_id").asText());assertEquals("WAITING", body.path("status").asText());
        assertEquals(new BrowserConnectionGrant(id, Status.WAITING, null), json.readValue(response.getContentAsByteArray(), BrowserConnectionGrant.class));
        assertThrows(IllegalArgumentException.class, () -> engine.connectionGrant("1", "2", "../foreign"));
    }

    private byte[] response(String device) throws IOException {
        return json.createObjectNode().put("device_id", device).put("next_token", "PRIVATE_NEXT_TOKEN").put("other", "PRIVATE_RESPONSE_BODY").toString().getBytes(StandardCharsets.UTF_8);
    }
}
