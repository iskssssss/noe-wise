package com.skillforge.core.capability.dto.connector;

import com.skillforge.core.capability.ToolDefinition;
import java.util.List;
import java.util.Map;

/**
 * MCP server 暴露的工具项响应 DTO（tools/list 发现结果）。
 *
 * @param qualifiedName 跨 Server 稳定消歧名称；不作为调用授权
 * @param name Tool 可调用名
 * @param title 面向人的标题，可空
 * @param description Tool 描述
 * @param inputSchema 输入 JSON Schema
 * @param outputSchema 输出 JSON Schema，可空
 * @param icons 原始 MCP 图标字段
 * @param annotations 不可信的远端行为提示，可空
 * @param unsupportedReason 当前平台无法接入时的原因；可接入时为空
 */
public record McpToolItemResponse(
        String qualifiedName,
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        List<IconResponse> icons,
        Map<String, Object> annotations,
        String unsupportedReason) {

    /** MCP Tool icon 响应，保留协议字段。 */
    public record IconResponse(
            String src,
            String mimeType,
            List<String> sizes,
            String theme) {
    }

    public static McpToolItemResponse from(
            String qualifiedName, ToolDefinition definition) {
        return new McpToolItemResponse(
                qualifiedName,
                definition.name(),
                definition.title().orElse(null),
                definition.description(),
                definition.inputSchema(),
                definition.outputSchema().orElse(null),
                definition.icons().stream()
                        .map(icon -> new IconResponse(
                                icon.src(), icon.mimeType().orElse(null),
                                icon.sizes(), icon.theme().orElse(null)))
                        .toList(),
                definition.annotations()
                        .map(ToolDefinition.UntrustedAnnotations::value)
                        .orElse(null), null);
    }
}
