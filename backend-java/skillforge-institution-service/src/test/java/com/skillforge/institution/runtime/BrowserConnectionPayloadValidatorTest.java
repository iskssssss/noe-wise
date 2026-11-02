package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.institution.browser.BrowserConnectionService;
import com.skillforge.runtime.api.TypedPayload;
import org.junit.jupiter.api.Test;

/** 验证宿主连接正文固定字段；通用 JSON 资源限制由 Starter 测试覆盖。 */
class BrowserConnectionPayloadValidatorTest {
    private static final String REQUEST_ID = "053f65c1-f8af-3894-ace7-921c38a5a2eb";
    private final BrowserConnectionPayloadValidator validator = new BrowserConnectionPayloadValidator();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void acceptsActualHostRequestReadyResponseAndCancellation() throws Exception {
        validator.validate(new TypedPayload(BrowserConnectionService.REQUEST_SCHEMA,
                json.writeValueAsString(new BrowserConnectionService.Request(REQUEST_ID, "NO_SELECTION", "连接浏览器"))));
        validator.validate(response(new BrowserConnectionService.Response(REQUEST_ID, "exact-device", "browser-1", false)));
        validator.validate(response(new BrowserConnectionService.Response(REQUEST_ID, null, null, true)));
        validator.validate(response(new BrowserConnectionService.Response(REQUEST_ID, "offline-device", "browser-1", true)));
    }

    @Test
    void rejectsInvalidReferenceSelectionTypesAndUndeclaredFields() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                response(new BrowserConnectionService.Response("request-1", "device", "browser", false))));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                response(new BrowserConnectionService.Response(REQUEST_ID, null, null, false))));
        var body = json.valueToTree(new BrowserConnectionService.Response(REQUEST_ID, "device", "browser", false));
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("cancelled", "false");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                new TypedPayload(BrowserConnectionService.RESPONSE_SCHEMA, body.toString())));
        ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("cancelled", false).put("tenant_id", "1");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(
                new TypedPayload(BrowserConnectionService.RESPONSE_SCHEMA, body.toString())));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new TypedPayload(
                BrowserConnectionService.REQUEST_SCHEMA, json.writeValueAsString(
                        new BrowserConnectionService.Request(REQUEST_ID, "CONNECTED", "连接浏览器")))));
    }

    @Test
    void declaresOnlyBrowserSchemasAndRejectsOtherPayloads() {
        assertEquals(java.util.Set.of(BrowserConnectionService.REQUEST_SCHEMA,
                BrowserConnectionService.RESPONSE_SCHEMA), validator.schemaIds());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(new TypedPayload(
                "unknown.host.schema", "{}")));
    }

    /** 使用正式宿主 DTO 生成响应，避免测试另造不一致的字段名称。 */
    private TypedPayload response(BrowserConnectionService.Response value) throws Exception {
        return new TypedPayload(BrowserConnectionService.RESPONSE_SCHEMA, json.writeValueAsString(value));
    }
}
