package com.skillforge.core.chat.tracing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 公开结果解析与账本入参脱敏。 */
class ToolCallIoProjectionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsSafeToolResultBody() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("output")
                .put("schemaId", "public.v1")
                .put("canonicalJson", "{\"temperature\":26}");

        assertEquals(Map.of("temperature", 26),
                ToolCallIoProjection.result(root, MAPPER).orElseThrow());
    }

    @Test
    void redactsSensitiveArgumentKeysFromInvocationSnapshot() {
        String snapshot = """
                {"arguments":{"canonicalJson":"{\\"city\\":\\"上海\\",\\"token\\":\\"secret\\"}","schemaId":"application/json"},\
                "target":{"sensitiveFields":["token"]}}
                """;

        Optional<Object> arguments = ToolCallIoProjection.argumentsFromInvocationSnapshot(snapshot, MAPPER);

        assertEquals(Map.of("city", "上海"), arguments.orElseThrow());
    }

    @Test
    void extractsInvocationSnapshotFromLedgerStringBlob() throws Exception {
        String snapshot = """
                {"arguments":{"canonicalJson":"{\\"timezone\\":\\"Asia/Shanghai\\"}","schemaId":"application/json"},\
                "target":{"sensitiveFields":[]}}
                """;

        Optional<Object> arguments = ToolCallIoProjection.argumentsFromCallData(sfa1String(snapshot), MAPPER);

        assertEquals(Map.of("timezone", "Asia/Shanghai"), arguments.orElseThrow());
        assertTrue(ToolCallIoProjection.argumentsFromCallData("not-codec", MAPPER).isEmpty());
    }

    private static String sfa1String(String text) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(new byte[] {'S', 'F', 'A', '1'});
            output.writeByte(1);
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            output.writeInt(utf8.length);
            output.write(utf8);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
    }
}
