package com.skillforge.institution.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillforge.execution.browser.BrowserFailure;
import com.skillforge.execution.browser.BrowserReceipt;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模型正文来自浏览器操作的明确输出协议；续接回执和完整结果独立保留。 */
final class BrowserToolResults {
    private static final int MAX_TASK_SUMMARIES = 8;
    private static final int MAX_NEW_TAB_SUMMARIES = 3;

    private BrowserToolResults() { }

    /** 启动等既有调用保留稳定错误协议；不从错误码猜测真实调用工具。 */
    static Map<String, Object> rejected(String code) {
        return rejected(code, null);
    }

    /** 准确工具名由 Handler 提供；纠正说明不派发操作、不回显原参数或改变拒绝事实。 */
    static Map<String, Object> rejected(String code, String toolName) {
        String message = "browser_task_source_unreadable".equals(code)
                ? "浏览器任务来源当前不可读，未下发操作。请使用 browser_tasks 返回的当前可读任务，不重放旧任务动作。error_code=" + code
                : "浏览器参数已拒绝，未下发操作。error_code=" + code;
        if (java.util.Set.of("browser_task_invalid", "browser_call_parameters", "browser_stop_parameters").contains(code)) {
            message += "。task 必须原样使用成功启动回执或 browser_tasks 返回的完整 task UUID；Skill 句柄不是 task，binding.session_id 不是 task，run_id 和 browser_id 也不是 task。";
            if ("browser_call".equals(toolName))
                message += "browser_call 必须同时提供 task 和对象型 command。当前目标尚无成功启动的可用任务时，先以 {} 调用 browser_start；已有任务但缺少引用时，查询 browser_tasks 或回读原工具完整来源。连接等待中不要再次启动或构造 task；UNKNOWN 先核对原回执，不重放未知动作。";
            else if ("browser_stop".equals(toolName))
                message += "browser_stop 只接收既有任务的 task；请查询 browser_tasks 或回读原工具完整来源，不为停止而调用 browser_start 新建任务。UNKNOWN 先核对原停止回执，不重放未知动作。";
            else message += "请查询 browser_tasks 或回读完整工具结果后纠正。";
        }
        else if ("browser_command_parameters".equals(code))
            message += "。browser_call 的 command 必须是包含 operation、parameters 的 JSON 对象，parameters 也必须是对象；无参数时传 {}，不能传字符串或省略。示例结构：{\"task\":\"<工具返回的完整 task UUID>\",\"command\":{\"operation\":\"observe\",\"parameters\":{}}}。导航地址应放在 command.parameters.url 中，不放在 command 外层。";
        else if (java.util.Set.of("browser_start_parameters", "browser_device_not_owned").contains(code))
            message += "。browser 仅接受本人设备目录返回的真实 browser_id；未指定设备时应省略 browser 字段，以 {} 调用 browser_start，让系统选择默认设备或展示连接卡。空字符串和 default 不能选择默认设备。本次拒绝没有创建浏览器任务，不能构造 task 继续操作。";
        else message += "。请按工具的封闭参数契约纠正。";
        return Map.of("isError", true, "status", "rejected", "error_code", code,
                "dispatched", false, "content", List.of(text(message)));
    }

    static Map<String, String> text(String value) { return Map.of("type", "text", "text", value); }

    /** 不按页面内容猜业务重点，也不复制任意回执字段充当正文。 */
    static List<Map<String, String>> contents(BrowserReceipt receipt) {
        JsonNode result = receipt.result();
        JsonNode body = null;
        if ("succeeded".equals(receipt.status()) && result != null) {
            body = switch (receipt.operation()) {
                case "observe", "snapshot", "get-text" -> result.get("text");
                case "get-html" -> result.get("html");
                case "evaluate" -> result.path("ok").asBoolean() ? result.get("value") : null;
                default -> null;
            };
        }
        String value = body == null || body.isNull() ? "" : body.isTextual() ? body.textValue() : body.toString();
        String details = summary(receipt);
        // 固定上游观察只展示外链域名；在模型消费正文旁说明取完整链接的真实能力，避免停止后补猜。
        if ("observe".equals(receipt.operation()) && "succeeded".equals(receipt.status()) && !value.isBlank())
            details += "\n链接能力说明：[→ 域名] 不是完整 URL。交付链接前须在 browser_stop 前取得完整 href；多个已观察目标的链接或字段优先在准确 tab_id 上一次只读 evaluate 批量返回标题、完整 href 与所需字段，依据当前实际观察定位，不猜测选择器、不点击、不赋值、不触发导航。单个目标定位、源码或 HTML 核验可按当前真实 ref 调用 get-html（无 selector 参数）。不得猜测链接或使用 url=... 占位；真实重定向 href 可原样交付，未核对不声称最终跳转目标；材料截断时先取得完整 href。";
        return value.isBlank() ? List.of(text(details)) : List.of(text(value), text(details));
    }

    static String summary(BrowserReceipt receipt) {
        var output = new StringBuilder();
        field(output, "task", receipt.task(), 128);
        field(output, "operation", receipt.operation(), 32);
        field(output, "status", receipt.status(), 32);
        JsonNode result = receipt.result();
        if (result != null) {
            numericField(output, "tab_id", result.path("tab_id"));
            if (!result.path("tab_id").isIntegralNumber())
                numericField(output, "tab_id", result.path("page").path("tab_id"));
            JsonNode navigation = result.path("navigation");
            if (navigation.isObject()) {
                field(output, "navigation", navigation.path("status").asText(), 32);
                numericField(output, "source_tab_id", navigation.path("source_tab_id"));
                numericField(output, "active_tab_id", navigation.path("active_tab_id"));
                JsonNode tabs = navigation.path("new_tabs");
                if (tabs.isArray()) {
                    output.append(" new_tabs=[");
                    int shown = 0;
                    for (JsonNode tab : tabs) {
                        if (!tab.path("tab_id").isIntegralNumber()) continue;
                        if (shown == MAX_NEW_TAB_SUMMARIES) { output.append("; 更多见完整结果"); break; }
                        if (shown++ > 0) output.append("; ");
                        output.append("tab_id=").append(tab.path("tab_id").asText());
                        field(output, "title", tab.path("title").asText(), 60);
                        field(output, "url", safeUrl(tab.path("url").asText()), 120);
                        if (tab.path("active").isBoolean()) output.append(" active=").append(tab.path("active").asBoolean());
                    }
                    output.append(']');
                }
            }
            if (result.path("error").isTextual() && result.path("error").asText().matches("[a-z0-9_]{1,96}"))
                field(output, "error_code", result.path("error").asText(), 96);
            failureFacts(output, result);
        }
        field(output, "browser", receipt.browser(), 128);
        if (receipt.binding() != null) {
            field(output, "browser_instance_id", receipt.binding().browserInstanceId(), 128);
            if (receipt.binding().agentWindowId() != null)
                output.append(" agent_window_id=").append(receipt.binding().agentWindowId());
        }
        return output.toString().stripLeading();
    }

    static Map<String, Object> tasks(List<BrowserReceipt> states, String session) {
        var ordered = states.stream().sorted(Comparator
                .comparing((BrowserReceipt receipt) -> !session.equals(receipt.session()))
                .thenComparing(BrowserReceipt::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        var output = new StringBuilder();
        for (BrowserReceipt receipt : ordered.stream().limit(MAX_TASK_SUMMARIES).toList()) {
            if (!output.isEmpty()) output.append('\n');
            field(output, "task", receipt.task(), 128);
            field(output, "operation", receipt.operation(), 32);
            field(output, "status", receipt.status(), 32);
            if (receipt.result() != null)
                field(output, "error_code", errorCode(receipt.result()), 96);
            output.append(" current_session=").append(session.equals(receipt.session()));
            field(output, "invocation", receipt.invocation(), 128);
            field(output, "created_at", receipt.createdAt(), 40);
        }
        if (states.isEmpty()) output.append("当前个人没有浏览器任务。");
        else output.append("\n共 ").append(states.size()).append(" 个个人任务；status 是当前绑定状态，active 才可继续使用。inactive/binding_lost 需按当前目标创建新任务，unknown 不代表可用或已清理。完整页面结果按工具来源回读。按会话和任务核对，不能默认采用最新任务或将 binding.session_id 当作 task。");
        return Map.of("success", true, "isError", false, "items", ordered.stream().map(BrowserToolResults::taskMetadata).toList(),
                "content", List.of(text(output.toString().stripLeading())));
    }

    /** 任务目录只呈现续接所需的公开元数据，页面正文保留在各调用的完整来源中。 */
    private static Map<String, Object> taskMetadata(BrowserReceipt receipt) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("task", receipt.task());
        metadata.put("invocation", receipt.invocation());
        metadata.put("operation", receipt.operation());
        metadata.put("status", receipt.status());
        metadata.put("session", receipt.session());
        metadata.put("run", receipt.run());
        metadata.put("browser", receipt.browser());
        metadata.put("created_at", receipt.createdAt());
        if (receipt.binding() != null) {
            var binding = new LinkedHashMap<String, Object>();
            binding.put("browser_instance_id", receipt.binding().browserInstanceId());
            binding.put("agent_window_id", receipt.binding().agentWindowId());
            metadata.put("binding", binding);
        }
        if (receipt.result() != null) {
            String error = errorCode(receipt.result());
            if (error != null) metadata.put("error_code", error);
            if (receipt.result().path("cleanup_complete").isBoolean())
                metadata.put("cleanup_complete", receipt.result().path("cleanup_complete").asBoolean());
        }
        return metadata;
    }

    private static String errorCode(JsonNode result) {
        return result.path("error").isTextual() && result.path("error").asText().matches("[a-z0-9_]{1,96}")
                ? result.path("error").asText() : null;
    }

    /** 操作回执只呈现固定协议的安全字段；未知动作本身仍走 Runtime 未知边界。 */
    private static void failureFacts(StringBuilder output, JsonNode result) {
        field(output, "reason", BrowserFailure.knownReason(result.path("reason")), 64);
        String effect = result.path("effect_state").asText();
        if (java.util.Set.of("none", "committed", "unknown").contains(effect))
            field(output, "effect_state", effect, 16);
        String cleanup = result.path("cleanup_state").asText();
        if (java.util.Set.of("complete", "failed").contains(cleanup))
            field(output, "cleanup_state", cleanup, 16);
    }

    private static void numericField(StringBuilder output, String name, JsonNode value) {
        if (value.isIntegralNumber()) field(output, name, value.asText(), 32);
    }

    private static void field(StringBuilder output, String name, String value, int limit) {
        if (value == null || value.isBlank()) return;
        String cleaned = value.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (cleaned.length() > limit) cleaned = cleaned.substring(0, limit) + "…";
        output.append(' ').append(name).append('=').append(cleaned);
    }

    /** 导航摘要不复制可能含令牌的查询、片段或 URL 用户信息；原始页面结果仍可按来源回读。 */
    private static String safeUrl(String value) {
        if ("about:blank".equals(value)) return value;
        try {
            var uri = URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) return null;
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
        } catch (IllegalArgumentException | URISyntaxException invalid) {
            return null;
        }
    }
}
