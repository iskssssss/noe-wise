package com.skillforge.core.capability.connector;

import static com.skillforge.core.capability.connector.ToolSchemas.arraySchema;
import static com.skillforge.core.capability.connector.ToolSchemas.objectSchema;
import static com.skillforge.core.capability.connector.ToolSchemas.property;

import com.skillforge.execution.ExecutionLimits;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 代码执行、工作区与成果转换共用的沙箱输入输出协议；公开业务呈现仍由 chat 提供。 */
final class ExecutionToolSchemas {
    private ExecutionToolSchemas() { }

    static String fixedResultDescription() { return fixedResultDescription(false); }

    static String fixedResultDescription(boolean internalStdout) {
        return (internalStdout ? "需要公开类型化业务结果时，" : "需要模型读取业务值时，") + "在同次程序写入固定 /workspace/output/" + ExecutionLimits.PUBLIC_RESULT_FILE
                + "，执行成功后自动采纳；文件缺失合法，纯 assert 校验无需写结果。最多8MiB，只接受类型化文字或表格，不接受任意JSON根对象或results字符串。文字结果kind=text，format仅支持plain或markdown，禁止json或text/plain；JSON业务对象使用format=plain，将json.dumps或JSON.stringify序列化字符串放入text。"
                + "最小JSON结构：{\"results\":[{\"kind\":\"text\",\"format\":\"plain\",\"text\":\"业务结果文本\"}]}。text填写本次实际读取或计算的值；表格使用kind=table、columns=[{key,label}]、rows标量列表。"
                + "无需结果路径参数。"
                + "固定结果文件不是下载成果，output_files禁止result.json及result.json/。";
    }

    static Map<String, Object> codeInputProperties(Map<String, Object> input) {
        var values = new java.util.LinkedHashMap<>(input);
        values.put("use_workspace", useWorkspaceSchema());
        return Map.copyOf(values);
    }

    static String workspaceLifecycleDescription() {
        return "/workspace/project 跨调用保留；/workspace/work 和 /workspace/output 每次独立，不继承前次文件。临时文件写入 /workspace/work，待收集候选写入本次 /workspace/output。"
                + "工作区工具不收集候选；需要文件交付时，使用当前已提供且支持 use_workspace 和 output_files 的代码工具，显式 use_workspace=true，在同次程序将项目文件复制到 /workspace/output 并以 output_files 收集；不调用未提供的工具。";
    }

    static Map<String, Object> useWorkspaceSchema() {
        return Map.of("type", "boolean",
                "default", false,
                "description", "可选，默认false；仅当前能力目录包含 workspace_files 时可选 true，宿主重新鉴权并挂载 /workspace/project；否则省略或 false。tools.call 子调用禁止启用。" + workspaceLifecycleDescription());
    }

    static Map<String, Object> workspaceRevisionSchema() {
        return Map.of("type", "integer",
                "minimum", 0);
    }

    static Map<String, Object> workspacePathSchema(boolean rootAllowed) {
        return Map.of("type", "string",
                "minLength", 1,
                "description", rootAllowed
                        ? "相对 /workspace/project 的安全路径，根目录为 .；禁止绝对路径、反斜线、. 或 .. 子路径段。"
                        : "相对 /workspace/project 的安全文件路径；禁止根目录、绝对路径、反斜线、. 或 .. 路径段。");
    }

    static Map<String, Object> workspaceFilesSchema() {
        return objectSchema(Map.ofEntries(
                Map.entry("op", Map.of("type", "string",
                        "enum", List.of("list", "read", "write", "patch", "mkdir", "move", "delete", "import"), "description", "list 根目录可省略 path 或填 .；read/write/patch/mkdir/delete 使用 path，move 使用 path 与 target_path；import 选择 artifact_inputs 或 attachment_ids 并指定 target_path。" + workspaceLifecycleDescription())),
                Map.entry("path", workspacePathSchema(true)),
                Map.entry("target_path", Map.of("type", "string", "description", "move 的项目内安全相对目标路径；import 的目标目录，不接受 URL、宿主路径或身份。")),
                Map.entry("content", Map.of("type", "string", "description", "write 的完整 UTF-8 文本内容。")),
                Map.entry("old_text", Map.of("type", "string",
                        "minLength", 1,
                        "description", "patch 的确切旧文本，必须恰好匹配一次。")),
                Map.entry("new_text", property("string")),
                Map.entry("expected_revision", Map.of("type", "integer", "minimum", 0, "description", "覆盖、patch、move、delete 必须提供当前 revision 或 expected_sha256。")),
                Map.entry("expected_sha256", Map.of("type", "string",
                        "pattern", "^[0-9a-f]{64}$")),
                Map.entry("offset", Map.of("type", "integer",
                        "minimum", 0,
                        "description", "读取文本的字符偏移，或目录列表的条目偏移。")),
                Map.entry("limit", Map.of("type", "integer",
                        "minimum", 1,
                        "maximum", 32_768,
                        "description", "read 最多32768字符；list 最多100条。")),
                Map.entry("artifact_inputs", Map.of("type", "array",
                        "minItems", 1,
                        "maxItems", ExecutionLimits.MAX_FILES, "uniqueItems", true,
                        "items", objectSchema(Map.of("artifact_id", Map.of("type", "string",
                                 "format", "uuid"),
                                "path", workspacePathSchema(false)), List.of("artifact_id", "path")),
                        "description", "import 的已授权成果选择器，宿主重新核验归属、委派关系、有效期和摘要。")),
                Map.entry("attachment_ids", attachmentIdsSchema())), List.of("op"));
    }

    static Map<String, Object> transientInputSchema() {
        return Map.of("type", "object",
                "maxProperties", ExecutionLimits.MAX_FILES,
                "additionalProperties", property("string"),
                "description", "本次执行的临时 UTF-8 文本输入：安全相对文件名到内容，合计服从1GiB传输预算；挂载 /input/data/<path>，不发布为产物");
    }

    static Map<String, Object> attachmentIdsSchema() {
        return Map.of("type", "array",
                "maxItems", ExecutionLimits.MAX_ATTACHMENT_INPUTS,
                "uniqueItems", true,
                "items", Map.of("type", "string",
                        "format", "uuid"),
                "description", "当前会话上传文件的 ID。宿主校验归属与可读权限后通过文件清单和流式ZIP送入沙箱；附件元数据 input_path 决定其在 /input/data/<input_path> 的实际路径，不要复制文件正文。");
    }

    static Map<String, Object> codeAttachmentIdsSchema() {
        var schema = new TreeMap<String, Object>(attachmentIdsSchema());
        schema.put("description", "本次代码读取的正式会话附件 ID；从消息附件清单原样选择。消息中出现附件或 input_path 不会自动装载文件，凡需读取附件，每次调用都必须填写本字段；上一调用选择不继承。宿主重新校验归属与权限后装载，按同一附件元数据从 /input/data/<input_path> 读取，禁止靠扫描目录猜测原件，不要复制CSV正文到 input_files。");
        return Map.copyOf(schema);
    }

    static Map<String, Object> outputFilesSchema() {
        return Map.of("type", "array",
                "maxItems", ExecutionLimits.MAX_FILES,
                "uniqueItems", true,
                "items", property("string"),
                "description", "显式收集本次 /workspace/output 下的受管候选文件；目录每次独立，列路径不会导入前次文件。可列最终目标文件，或无工作区分步生成时供 artifact_inputs 复用的必要中间文件。非空收集经过相同权限与公开检查，宿主自动准入收尾预算。候选 ready 不代表正式发布；finalize_task_outputs 只选择已完成的最终成果，不得选择中间片段、草稿或调试文件。网站同时列 index.html 及必要资源。末尾 / 表示完整资源目录（如 assets/）；禁止根目录、通配符和无关临时输入、摘要、调试文件。"
                                    + "固定内部结果 result.json 及 result.json/ 禁止列入；未传或空数组不收集任何候选文件");
    }

    static Map<String, Object> programOutputSchema(ToolSchemaContributor schemas, boolean includeAttachmentInputs) {
        var logEntry = objectSchema(Map.of(
                "stream", property("string"),
                "characters", property("integer"),
                "truncated", property("boolean")), List.of("stream", "characters", "truncated"));
        var properties = new java.util.LinkedHashMap<>(Map.ofEntries(
                Map.entry("success", property("boolean")),
                Map.entry("error", property("string")),
                Map.entry("state", property("string")),
                Map.entry("phase", property("string")),
                Map.entry("exit_code", property("integer")),
                Map.entry("diagnostic", codeDiagnosticSchema()),
                Map.entry("logs", arraySchema(logEntry)),
                Map.entry("stdout_truncated", property("boolean")),
                Map.entry("stderr_truncated", property("boolean")),
                Map.entry("delivery_status", property("string")),
                Map.entry("delivery_error", property("string")),
                Map.entry("artifacts", arraySchema(property("object"))),
                Map.entry("public_presentation", schemas.codePublicPresentationSchema())));
        if (includeAttachmentInputs) {
            properties.put("attachment_inputs", attachmentInputsOutputSchema());
            properties.put("input_provenance", artifactProvenanceOutputSchema());
        }
        return objectSchema(Map.copyOf(properties), List.of("success"));
    }

    static Map<String, Object> codeDiagnosticSchema() {
        return objectSchema(Map.of(
                "status", Map.of("type", "string",
                        "enum", List.of("available", "unavailable", "withheld")),
                "code", property("string"),
                "field", property("string"),
                "message", property("string"),
                "hint", property("string"),
                "stream", Map.of("type", "string",
                        "enum", List.of("stderr")),
                "text", Map.of("type", "string",
                        "maxLength", 4096),
                "truncated", property("boolean")), List.of("status"));
    }

    static Map<String, Object> attachmentInputsOutputSchema() {
        return arraySchema(objectSchema(Map.of(
                "id", Map.of("type", "string",
                        "minLength", 36,
                        "maxLength", 36),
                "name", property("string"),
                "input_path", property("string"),
                "size", property("integer"),
                "sha256", property("string"),
                "expires_at", Map.of("type", "string")),
                List.of("id", "name", "input_path", "size", "sha256", "expires_at")));
    }

    static Map<String, Object> artifactProvenanceOutputSchema() {
        return arraySchema(objectSchema(Map.of("artifact_id", property("string"),
                "path", property("string"),
                "size", property("integer"), "sha256", property("string")),
                List.of("artifact_id", "path", "size", "sha256")));
    }
}
