package com.skillforge.core.capability.connector;

import static com.skillforge.core.capability.connector.ExecutionToolSchemas.programOutputSchema;
import static com.skillforge.core.capability.connector.ToolSchemas.arraySchema;
import static com.skillforge.core.capability.connector.ToolSchemas.objectSchema;
import static com.skillforge.core.capability.connector.ToolSchemas.property;

import com.skillforge.execution.ExecutionLimits;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 图片读取、成果查询、转换及交付工具的发布定义。 */
final class ArtifactToolDefinitions {
    private ArtifactToolDefinitions() { }

    static PlatformToolCatalog.Entry readImage() {
        return PlatformToolCatalog.Entry.builder()
                .name("read_image")
                .title("查看图片")
                .publishedVersionKey("builtin:4")
                .description("读取当前会话的受管图片，可指定像素裁剪；返回真实模型图片内容")
                .parametersSchema(readImageInput())
                .outputSchema(property("object"))
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(30_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static Map<String, Object> readImageInput() {
        return objectSchema(
            Map.of(
                "media_id", property("string"),
                "crop", objectSchema(
                    Map.of(
                        "x", property("integer"),
                        "y", property("integer"),
                        "width", property("integer"),
                        "height", property("integer")),
                    List.of("x", "y", "width", "height"))),
            List.of("media_id"));
    }

    static PlatformToolCatalog.Entry finalizeTaskOutputs() {
        return PlatformToolCatalog.Entry.builder()
                .name("finalize_task_outputs")
                .title("确认交付清单")
                .publishedVersionKey("builtin:11")
                .description(finalizeTaskOutputsDescription())
                .parametersSchema(outputFinalizationSchema())
                .outputSchema(outputFinalizationResultSchema())
                .effect(ToolEffect.IDEMPOTENT_WRITE)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(60_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(false)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static String finalizeTaskOutputsDescription() {
        return "对已生成、收集并检查的候选提交完整交付清单；只选本轮最终成果，用户明确要求的成果与格式须齐全，宿主核验真实文件引用、读取权限、格式与版本等事实。"
                + "success 且 state=finalizing 仅表示冻结待发布；冻结后只最终回复，不再执行工具。completion_pending 时 Goal 仍 ACTIVE，Run 成功且成果正式发布后由宿主结算。";
    }

    static PlatformToolCatalog.Entry renderArtifact(ToolSchemaContributor schemas) {
        return PlatformToolCatalog.Entry.builder()
                .name("render_artifact")
                .title("转换成果文件")
                .publishedVersionKey("builtin:11")
                .description(renderArtifactDescription())
                .parametersSchema(renderArtifactInput())
                .outputSchema(programOutputSchema(schemas, false))
                .effect(ToolEffect.NON_IDEMPOTENT_WRITE)
                .idempotent(false)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(ExecutionLimits.MAX_TIMEOUT_SECONDS * 1000L)
                .modelInvocable(true)
                .userInvocable(true)
                .parallelSafe(false)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.HANDLE)
                .build();
    }

    private static String renderArtifactDescription() {
        return "仅在用户需要格式转换时，将授权可读、未过期且公开检查通过的单个 ready 候选或产物转换为 PDF、页面 PNG 或静态表格预览，无需先正式发布。"
                + "宿主重新核验权限，结果作为带真实来源的新候选，原文件不变；只交付目标文件，不发布转换临时文件。";
    }

    private static Map<String, Object> renderArtifactInput() {
        return objectSchema(
            Map.of(
                "artifact_id", Map.of("type", "string", "description", "真实 ready 候选或产物 ID，不接受 URL、本机路径或命令。"),
                "path", Map.of("type", "string", "description", "候选清单内的相对文件路径。"),
                "format", Map.of("type", "string",
                        "enum", List.of("pdf", "pages", "table")),
                "output_name", Map.of(
                    "type", "string",
                    "minLength", 1,
                    "maxLength", 200,
                    "description", "可选实际输出文件名；必须为安全单段basename，pdf以.pdf结尾，table以.html结尾；pages为多文件集合，不接受此字段。省略使用默认文件名，title不参与命名。")),
            List.of("artifact_id", "path", "format"));
    }

    static PlatformToolCatalog.Entry listArtifacts() {
        return PlatformToolCatalog.Entry.builder()
                .name("list_artifacts")
                .title("查找已有成果")
                .publishedVersionKey("builtin:7")
                .description(listArtifactsDescription())
                .parametersSchema(listArtifactsInput())
                .outputSchema(listArtifactsOutput())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(10_000)
                .modelInvocable(true)
                .userInvocable(true)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }

    private static String listArtifactsDescription() {
        return "仅在需要查找、复用或修改当前会话已有产物时分页查询候选与正式成果引用；创建新产物且上下文足够时无需先查清单。"
                + "不读取正文，选定后用当前已提供且支持 artifact_inputs 的代码工具读取真实文件。结合任务来源核对数据，不能仅凭最新文件认定真实；has_more 只表示可继续读取，定位所需产物后停止翻页。";
    }

    private static Map<String, Object> listArtifactsInput() {
        return objectSchema(
            Map.of(
                "offset", Map.of("type", "integer",
                        "minimum", 0,
                        "maximum", 10000),
                "limit", Map.of("type", "integer",
                        "minimum", 1,
                        "maximum", 100)),
            List.of());
    }

    private static Map<String, Object> listArtifactsOutput() {
        return objectSchema(
            Map.of(
                "success", property("boolean"),
                "total", property("integer"),
                "offset", property("integer"),
                "has_more", property("boolean"),
                "current_deliverables", arraySchema(property("object")),
                "artifacts", arraySchema(
                    objectSchema(
                        Map.of(
                            "id", property("string"),
                            "name", property("string"),
                            "type", property("string"),
                            "run_id", property("string"),
                            "expires_at", property("string"),
                            "artifact_inputs", arraySchema(
                                objectSchema(
                                    Map.of(
                                        "artifact_id", property("string"),
                                        "path", property("string"),
                                        "size", property("integer"),
                                        "sha256", property("string")),
                                    List.of("artifact_id", "path", "size", "sha256"))),
                            "files", arraySchema(
                                objectSchema(
                                    Map.of(
                                        "file_id", property("string"),
                                        "path", property("string"),
                                        "size", property("integer"),
                                        "sha256", property("string")),
                                    List.of("path", "size", "sha256")))),
                        List.of("id", "name", "type", "run_id", "expires_at", "files", "artifact_inputs")))),
            List.of("success", "total", "offset", "has_more", "artifacts"));
    }

    private static Map<String, Object> outputFinalizationSchema() {
        var file = objectSchema(Map.of("artifact_id", Map.of("type", "string", "description", "真实候选回执或清单中的 artifact_id；与 path 成对使用，或使用 file_id。"),
                "path", Map.of("type", "string", "description", "同一候选清单内的真实相对文件路径。"),
                "file_id", Map.of("type", "string", "description", "真实文件引用；不能编造或以本机路径替代。")), List.of());
        var representation = objectSchema(Map.of("role", Map.of("type", "string",
                "enum", List.of("source", "export", "bundle"),
                        "description", "source为原生文件；export为从source_files实际转换的文件；bundle仅用于zip，format=zip也必须role=bundle。HTML及其本地CSS/JS资源使用source，不因包含多文件而使用bundle。"),
                "format", Map.of("type", "string",
                        "description", "真实文件扩展名的小写形式，不含点，例如 md、pdf、docx、xlsx、pptx、html、zip；Markdown 文件填写 md。"),
                "primary_preview", Map.of("type", "boolean",
                        "description", "每项成果的全部表示中恰有一个为true。"),
                "entry_path", Map.of("type", "string",
                        "description", "HTML必填，必须等于files中真实入口HTML的path；本地CSS、JS、图片引用必须包含在同一files集合内。允许检查通过的本地资源集合；用户明确单文件时必须内联为一个HTML。"),
                "files", Map.of("type", "array",
                        "minItems", 1,
                        "items", file),
                "source_files", Map.of("type", "array",
                        "items", file,
                        "description", "export必须提供实际转换所用的真实源文件；其他角色可省略。不能凭路径推断或伪造来源。")), List.of("role", "format", "primary_preview", "files"));
        var item = objectSchema(Map.of("key", Map.of("type", "string", "description", "一个逻辑成果一个稳定键；多格式归入同一项，改版复用现有 key。"),
                "title", property("string"),
                "kind", outputKindSchema(),
                "base_revision_id", outputBaseRevisionSchema(),
                "representations", arraySchema(representation)), List.of("key", "title", "kind", "representations"));
        var evidence = objectSchema(Map.of("artifact_id", property("string"),
                "path", property("string"),
                "summary", property("string")), List.of("artifact_id", "path", "summary"));
        var completion = objectSchema(Map.of("expected_revision", Map.of("type", "integer",
                "minimum", 1,
                "description", "本轮同时完成显式 Goal 时，先 get_goal 并完成其余验收，再填写其当前 ACTIVE revision；宿主绑定真实 Goal 与当前 Run，正式发布后结算。")), List.of("expected_revision"));
        return objectSchema(Map.of("deliverables", arraySchema(item),
                "evidence", Map.of("type", "array", "items", evidence, "description", "实际检查证据的真实文件引用与检查摘要。"),
                "abandon_reason", Map.of("type", "string", "description", "无法补齐交付清单时明确说明放弃原因；放弃时必须传 deliverables:[] 并省略 goal_completion，不能同时发布部分成果或完成 Goal。"), "goal_completion", completion), List.of("deliverables"));
    }

    private static Map<String, Object> outputKindSchema() {
        return Map.of("type", "string",
                "enum", PlatformToolCatalog.DELIVERABLE_KINDS,
                "description", "正式成果分类：报告使用 document，结构化数据使用 data；report 不是合法 kind。文件格式在 representations 声明。");
    }

    private static Map<String, Object> outputFinalizationResultSchema() {
        var correction = objectSchema(Map.of("key", property("string"),
                "field", property("string"),
                "allowed_values", arraySchema(property("string")), "omit_field", property("boolean")),
                List.of("key", "field", "allowed_values", "omit_field"));
        return objectSchema(Map.of("success", property("boolean"),
                "error", property("string"),
                "state", Map.of("type", "string",
                        "enum", List.of("finalizing", "finalized", "abandoned")),
                "reason", property("string"), "result_ref", property("string"), "instruction", property("string"),
                "files", arraySchema(property("string")), "completion_pending", property("boolean"),
                "corrections", arraySchema(correction)), List.of("success"));
    }

    private static Map<String, Object> outputBaseRevisionSchema() {
        return Map.of("type", "string",
                "minLength", 1,
                "pattern", ".*\\S.*",
                "description", "新成果省略此字段；修改已有成果使用当前 Run 输出上下文或 list_artifacts.current_deliverables 中同一 key 的真实 base_revision_id。artifact_id、file_id、Run ID 不能用作版本 ID。空字符串和纯空白无效。");
    }
}
