package com.skillforge.core.chat.contract;

import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.core.capability.connector.ToolSchemaContributor;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** chat 所有的公开呈现与上下文回读 schema，由工具目录显式装配。 */
@Component
public final class ChatToolSchemaContributor implements ToolSchemaContributor {
    @Override
    public PlatformToolCatalog.Entry contextReferenceEntry() {
        return PlatformToolCatalog.Entry.builder()
                .name("read_context_reference")
                .title("读取引用资料")
                .publishedVersionKey("builtin:16")
                .description(contextReferenceDescription())
                .parametersSchema(contextReferenceParametersSchema())
                .outputSchema(contextReferenceOutputSchema())
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(10_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.forEffect(ToolEffect.READ_ONLY))
                .build();
    }

    private static String contextReferenceDescription() {
        return "解释先前执行过程时依据可见记录。mode=history 在当前会话内定位执行短记录，可选 history_run_id；after_sequence 默认0，limit 默认扫描20条原事件、最多100条；end=false 时将 next_sequence 作为 after_sequence 继续，即使记录为空也要继续。"
                + "query 是不区分大小写的字面关键词（最多200 Unicode字符），匹配安全记录的完整内容。记录 content 是最多"
                + ContextReferenceLimits.MAX_HISTORY_RECORD_CHARACTERS + "个 Unicode 字符的开头预览，整页正文最多"
                + ContextReferenceLimits.MAX_HISTORY_PAGE_CHARACTERS + "字符并按记录数均分；content_truncated 表示是否省略，原文用该记录的 source_event_id 分页读取。"
                + "有 query 时 query_match_in_content 表示关键词是否出现在预览中；false 仍是完整内容命中，应回读原文。from_time/to_time 使用含时区的 ISO8601 时间，按记录实际发生时间包含两端筛选。"
                + "around_source_event_id 配合 window（默认20、1至50）读取锚点前后会话序号范围，不能与 after_sequence、query、时间筛选或 limit 同传；window 不是保证返回的记录数，此时 end 仅表示窗口读完。"
                + "读取原文时仅提供 reference_id 或 source_event_id 之一，不传 mode。offset 和 limit 按 Unicode 字符，limit默认"
                + ContextReferenceLimits.DEFAULT_PAGE_CHARACTERS + "、允许请求上界"
                + ContextReferenceLimits.MAX_PAGE_CHARACTERS + "；limit是上界而非保证页大小，实际单页最多"
                + ContextReferenceLimits.MAX_MODEL_PAGE_CHARACTERS
                + "字符。找特定机构或对象时可传find：1至200 Unicode字符、区分大小写的精确字面文本；仅在当前授权原文从offset向后找首次命中并从命中处返回一页，offset字段是实际页起点，match_found表示是否找到。先按机构名定位，"
                + "再按next_offset续页，不反复提高limit。未命中match_found=false、content为空、offset和next_offset为原文长度、end=true，只表示搜索抵达末尾，不证明正文已读完。"
                + "end=false时按真实next_offset继续读取，不能据短页推断全文结束；只需具体证据时找到后停止。要求全文阅读时从offset=0开始，按next_offset连续覆盖全部区间并取得end=true；跳到末页的end=true不证明中间正文已读。"
                + "source_complete只表示原来源采集完整，不表示正文已呈现或已读完。普通分页没有累计读取总量上限。已成功采集且仅缺预览正文时回读真实引用，不重做原动作。每次重验当前租户、用户、Agent 和会话权限，仅开放安全投影；拒绝时不换引用绕过，不接受URL或文件路径。";
    }

    private static Map<String, Object> contextReferenceParametersSchema() {
        return objectSchema(Map.ofEntries(Map.entry("reference_id", Map.of("type", "string", "pattern", "^context:[a-f0-9]{64}$",
                        "description", "仅接受上下文裁剪结果明确给出的 context: 加 64 位小写十六进制引用，不能传 tool-result、artifact 或 resultRef。没有此引用时先用 mode=history 定位记录。")),
                Map.entry("source_event_id", Map.of("type", "string", "minLength", 1, "maxLength", 512,
                        "description", "从 mode=history 返回 records 中原样复制 source_event_id；不是 resultRef 或 reference_id。不知道事件 ID 时先查询历史。")),
                Map.entry("mode", Map.of("type", "string", "enum", List.of("history"))),
                Map.entry("history_run_id", property("string")),
                Map.entry("after_sequence", Map.of("type", "integer", "minimum", 0)),
                Map.entry("query", Map.of("type", "string", "minLength", 1, "maxLength", 200)),
                Map.entry("find", Map.of("type", "string", "minLength", 1, "maxLength", 200,
                        "description", "原文内精确字面定位；不是history查询，不支持正则。仅用于reference_id/source_event_id原文读取。")),
                Map.entry("from_time", Map.of("type", "string", "format", "date-time")),
                Map.entry("to_time", Map.of("type", "string", "format", "date-time")),
                Map.entry("around_source_event_id", Map.of("type", "string", "minLength", 1, "maxLength", 512)),
                Map.entry("window", Map.of("type", "integer", "minimum", 1, "maximum", 50)),
                Map.entry("offset", Map.of("type", "integer", "minimum", 0)),
                Map.entry("limit", Map.of("type", "integer", "minimum", 1, "maximum", ContextReferenceLimits.MAX_PAGE_CHARACTERS))), List.of());
    }

    private static Map<String, Object> contextReferenceOutputSchema() {
        return objectSchema(Map.ofEntries(Map.entry("success", property("boolean")),
                Map.entry("error", property("string")),
                Map.entry("field_errors", Map.of("type", "object", "additionalProperties", property("string"))),
                Map.entry("reference_id", property("string")),
                Map.entry("content", property("string")),
                Map.entry("next_offset", property("integer")),
                Map.entry("end", property("boolean")),
                Map.entry("source_complete", property("boolean")),
                Map.entry("source_characters", property("integer")),
                Map.entry("offset", property("integer")),
                Map.entry("match_found", property("boolean")),
                Map.entry("next_sequence", property("integer")),
                Map.entry("records", Map.of("type", "array", "items", objectSchema(Map.ofEntries(
                        Map.entry("source_event_id", property("string")), Map.entry("run_id", property("string")),
                        Map.entry("sequence", property("integer")), Map.entry("occurred_at", property("string")),
                        Map.entry("content", property("string")), Map.entry("content_truncated", property("boolean")),
                        Map.entry("query_match_in_content", property("boolean"))),
                        List.of("source_event_id", "run_id", "sequence", "occurred_at", "content", "content_truncated"))))),
                List.of("success"));
    }

    @Override
    public Map<String,Object> codePublicPresentationSchema() {
        var text=objectSchema(Map.of("kind",Map.of("type","string","enum",List.of(ProcessPresentation.TEXT_KIND)),
                "format",Map.of("type","string","enum",List.of(ProcessPresentation.Text.PLAIN,ProcessPresentation.Text.MARKDOWN)),"text",property("string")),
                List.of("kind","format","text"));
        var file=objectSchema(Map.of("artifact_id",property("string"),"path",property("string")),List.of("artifact_id","path"));
        var table=objectSchema(Map.of("kind",Map.of("type","string","enum",List.of(ProcessPresentation.TABLE_KIND)),
                "columns",arraySchema(objectSchema(Map.of("key",property("string"),"label",property("string")),List.of("key","label"))),
                "rows",arraySchema(arraySchema(Map.of("type",List.of("string","number","boolean","null"))))),List.of("kind","columns","rows"));
        var source=objectSchema(Map.of("kind",Map.of("type","string","enum",List.of(ChatInputReference.ARTIFACT_FILES_KIND)),
                "files",Map.of("type","array","minItems",1,"maxItems",ChatInputReference.MAX_ARTIFACT_FILES,"items",file)),List.of("kind","files"));
        var reference=objectSchema(Map.of("label",property("string"),"source",source),List.of("label","source"));
        return objectSchema(Map.of("title",property("string"),"summary",property("string"),
                "results",arraySchema(Map.of("oneOf",List.of(text,table))),"references",arraySchema(reference)),List.of("title","summary","results","references"));
    }
    private static Map<String, Object> objectSchema(
            Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required,
                "additionalProperties", false);
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static Map<String, Object> property(String type) {
        return Map.of("type", type);
    }

}
