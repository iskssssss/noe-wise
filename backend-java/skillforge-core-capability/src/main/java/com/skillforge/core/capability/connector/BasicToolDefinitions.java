package com.skillforge.core.capability.connector;

import static com.skillforge.core.capability.connector.ToolSchemas.arraySchema;
import static com.skillforge.core.capability.connector.ToolSchemas.objectSchema;
import static com.skillforge.core.capability.connector.ToolSchemas.property;

import com.skillforge.core.foundation.settings.OperationalSettingsDefaults;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 时间、计算、结构化数据及 HTTP 工具的发布定义。 */
final class BasicToolDefinitions {
    private BasicToolDefinitions() { }

    static PlatformToolCatalog.Entry getCurrentTime() {
        return PlatformToolCatalog.Entry.builder()
                .name("get_current_time")
                .title("查询当前时间")
                .publishedVersionKey("builtin:5")
                .description("返回当前时间，默认北京时间；可显式转换到指定 IANA 时区")
                .parametersSchema(getCurrentTimeInput())
                .outputSchema(getCurrentTimeOutput())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(5_000)
                .modelInvocable(true)
                .userInvocable(true)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static Map<String, Object> getCurrentTimeInput() {
        return objectSchema(Map.of("timezone", property("string")), List.of());
    }

    private static Map<String, Object> getCurrentTimeOutput() {
        return objectSchema(
                Map.of(
                    "success", property("boolean"),
                    "timezone", property("string"),
                    "iso_8601", property("string"),
                    "epoch_millis", property("integer"),
                    "error", property("string")),
                List.of("success"));
    }

    static PlatformToolCatalog.Entry calculateExpression() {
        return PlatformToolCatalog.Entry.builder()
                .name("calculate_expression")
                .title("计算表达式")
                .publishedVersionKey("builtin:5")
                .description("以受限四则运算计算十进制结果")
                .parametersSchema(calculateExpressionInput())
                .outputSchema(calculateExpressionOutput())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(5_000)
                .modelInvocable(true)
                .userInvocable(true)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static Map<String, Object> calculateExpressionInput() {
        return objectSchema(
                Map.of(
                    "expression", Map.of("type","string","maxLength", CalculateExpressionToolHandler.MAX_EXPRESSION_LENGTH)),
                List.of("expression"));
    }

    private static Map<String, Object> calculateExpressionOutput() {
        return objectSchema(
                Map.of("success", property("boolean"), "value", property("string"), "error", property("string")),
                List.of("success"));
    }

    static PlatformToolCatalog.Entry convertDatetime() {
        return PlatformToolCatalog.Entry.builder()
                .name("convert_datetime")
                .title("转换日期时间")
                .publishedVersionKey("builtin:5")
                .description("解析 ISO 8601 时间并转换时区，未指定源或目标时区时均按北京时间")
                .parametersSchema(convertDatetimeInput())
                .outputSchema(convertDatetimeOutput())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(5_000)
                .modelInvocable(true)
                .userInvocable(true)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static Map<String, Object> convertDatetimeInput() {
        return objectSchema(
                Map.of(
                    "datetime", property("string"),
                    "target_timezone", property("string"),
                    "source_timezone", property("string"),
                    "output_format", Map.of(
                        "enum", List.of( "ISO_OFFSET_DATE_TIME", "ISO_LOCAL_DATE_TIME", "ISO_LOCAL_DATE", "EPOCH_MILLIS"))),
                List.of("datetime"));
    }

    private static Map<String, Object> convertDatetimeOutput() {
        return objectSchema(
                Map.of(
                    "success", property("boolean"),
                    "timezone", property("string"),
                    "value", property("string"),
                    "error", property("string")),
                List.of("success"));
    }

    static PlatformToolCatalog.Entry jsonQuery() {
        return PlatformToolCatalog.Entry.builder()
                .name("json_query")
                .title("读取结构化数据")
                .publishedVersionKey("builtin:4")
                .description("以 RFC 6901 JSON Pointer 读取 JSON 节点")
                .parametersSchema(jsonQueryInput())
                .outputSchema(jsonQueryOutput())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(5_000)
                .modelInvocable(true)
                .userInvocable(true)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static Map<String, Object> jsonQueryInput() {
        return objectSchema(Map.of("value", Map.of(), "pointer", property("string")), List.of("value", "pointer"));
    }

    private static Map<String, Object> jsonQueryOutput() {
        return objectSchema(
                Map.of(
                    "success", property("boolean"),
                    "found", property("boolean"),
                    "value", Map.of(),
                    "error", property("string")),
                List.of("success"));
    }

    static PlatformToolCatalog.Entry jsonSchemaValidate() {
        return PlatformToolCatalog.Entry.builder()
                .name("json_schema_validate")
                .title("校验数据格式")
                .publishedVersionKey("builtin:4")
                .description("按平台支持的 JSON Schema 子集校验 JSON 值")
                .parametersSchema(jsonSchemaValidateInput())
                .outputSchema(jsonSchemaValidateOutput())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(5_000)
                .modelInvocable(true)
                .userInvocable(true)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static Map<String, Object> jsonSchemaValidateInput() {
        return objectSchema(Map.of("value", Map.of(), "schema", property("object")), List.of("value", "schema"));
    }

    private static Map<String, Object> jsonSchemaValidateOutput() {
        return objectSchema(
                Map.of(
                    "success", property("boolean"),
                    "valid", property("boolean"),
                    "errors", arraySchema(property("string")),
                    "error", property("string")),
                List.of("success"));
    }

    static PlatformToolCatalog.Entry templateRender() {
        return PlatformToolCatalog.Entry.builder()
                .name("template_render")
                .title("填充文本模板")
                .publishedVersionKey("builtin:5")
                .description("使用 {{path.to.value}} 占位符渲染纯文本")
                .parametersSchema(templateRenderInput())
                .outputSchema(templateRenderOutput())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(5_000)
                .modelInvocable(true)
                .userInvocable(true)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static Map<String, Object> templateRenderInput() {
        return objectSchema(
                Map.of(
                    "template", Map.of("type","string","maxLength", TemplateRenderToolHandler.MAX_TEMPLATE_BYTES),
                    "data", property("object")),
                List.of("template"));
    }

    private static Map<String, Object> templateRenderOutput() {
        return objectSchema(
                Map.of("success", property("boolean"), "rendered", property("string"), "error", property("string")),
                List.of("success"));
    }

    static PlatformToolCatalog.Entry httpRequest() {
        return PlatformToolCatalog.Entry.builder()
                .name("http_request")
                .title("请求外部服务")
                .publishedVersionKey("builtin:7")
                .description("向已授权的 HTTPS 地址发起 GET 或 POST 请求；仅 2xx 响应为成功，非成功响应仍保留状态码和正文，失败不表示可安全重试")
                .parametersSchema(httpRequestInput())
                .outputSchema(httpRequestOutput())
                .effect(ToolEffect.NON_IDEMPOTENT_WRITE)
                .idempotent(false)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(OperationalSettingsDefaults.HTTP_MAX_TIMEOUT_SECONDS * 1000L)
                .modelInvocable(true)
                .userInvocable(true)
                .parallelSafe(false)
                // 仅脱敏入参 headers；响应 body 必须保留给模型观察。
                .sensitiveFields(Set.of("headers"))
                .callIdentityPolicy(ToolCallIdentityPolicy.HANDLE)
                .build();
    }

    private static Map<String, Object> httpRequestInput() {
        return objectSchema(
                Map.of(
                    "method", Map.of("type", "string", "enum", List.of("GET", "POST")),
                    "url", property("string"),
                    "query", Map.of(
                        "type", "object",
                        "additionalProperties", Map.of("type", List.of("string", "number", "boolean"))),
                    "headers", Map.of("type", "object", "additionalProperties", property("string")),
                    "body", Map.of("type", "object")),
                List.of("method", "url"));
    }

    private static Map<String, Object> httpRequestOutput() {
        return objectSchema(
                Map.of(
                    "success", Map.of("type", "boolean", "description", "仅 HTTP 2xx 响应为 true；非成功响应为 false，不表示可安全重试"),
                    "status", property("integer"),
                    "content_type", property("string"),
                    "body", property("string"),
                    "truncated", property("boolean"),
                    "error", property("string")),
                List.of("success"));
    }
}
