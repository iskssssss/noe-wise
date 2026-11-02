package com.skillforge.institution.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.institution.browser.BrowserConnectionService;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.starter.AgentRuntimePayloadSchema;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 在机构宿主登记浏览器连接正文；通用 JSON 限额由 Starter 在调用前检查。
 * 这里只验证格式；设备授权和准确等待归属仍由连接服务与 Session journal 核验。
 */
@Component
public final class BrowserConnectionPayloadValidator implements AgentRuntimePayloadSchema {
    private final ObjectMapper json = new ObjectMapper();

    /** 独立解析器不继承 HTTP 字段命名或宽松反序列化配置。 */
    public BrowserConnectionPayloadValidator() { }

    /** 仅声明宿主拥有的连接请求与连接结果，不覆盖 Runtime 自有 schema。 */
    @Override
    public Set<String> schemaIds() {
        return Set.of(BrowserConnectionService.REQUEST_SCHEMA, BrowserConnectionService.RESPONSE_SCHEMA);
    }

    /** 保留宿主 schema 身份，在有界 JSON 解析后校验明确业务字段。 */
    @Override
    public void validate(TypedPayload payload) {
        if (payload == null || !schemaIds().contains(payload.schemaId())) throw rejected();
        JsonNode body;
        try {
            body = json.readTree(payload.canonicalJson());
        } catch (JsonProcessingException invalid) {
            throw rejected();
        }
        if (!body.isObject()) throw rejected();
        if (BrowserConnectionService.REQUEST_SCHEMA.equals(payload.schemaId())) {
            fields(body, Set.of("connection_request_id", "reason", "message"));
            if (!text(body.get("reason")) || !Set.of("NO_SELECTION", "AUTHORIZATION_REQUIRED", "OFFLINE")
                    .contains(body.get("reason").textValue()) || !text(body.get("message"))) throw rejected();
        } else {
            fields(body, Set.of("connection_request_id", "device_id", "browser_id", "cancelled"));
            if (!body.get("cancelled").isBoolean()) throw rejected();
            boolean cancelled = body.get("cancelled").booleanValue();
            if (!selector(body.get("device_id"), cancelled) || !selector(body.get("browser_id"), cancelled))
                throw rejected();
        }
        if (!text(body.get("connection_request_id"))) throw rejected();
        String reference = body.get("connection_request_id").textValue();
        try {
            if (!UUID.fromString(reference).toString().equals(reference)) throw rejected();
        } catch (IllegalArgumentException invalid) {
            throw rejected();
        }
    }

    /** 固定正文不接受身份、凭据或其他未声明字段。 */
    private static void fields(JsonNode body, Set<String> expected) {
        if (body.size() != expected.size() || !expected.stream().allMatch(body::has)) throw rejected();
    }

    /** 取消未选择设备的等待允许 NULL；成功连接必须包含准确设备选择器。 */
    private static boolean selector(JsonNode value, boolean cancelled) {
        return text(value) || cancelled && value.isNull();
    }

    /** 已声明文本不可通过空白字符串伪造有效值。 */
    private static boolean text(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank();
    }

    /** 错误只携带稳定代码，不回显连接正文。 */
    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException("browser_connection_payload_invalid");
    }
}
