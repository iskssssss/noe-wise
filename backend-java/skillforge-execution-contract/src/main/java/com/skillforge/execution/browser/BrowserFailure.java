package com.skillforge.execution.browser;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/**
 * 固定 BrowserSkill 协议的公开错误投影；不携带自由文本、页面材料或任意错误 data。
 *
 * @param error 已确认的 CLI 或宿主错误码
 * @param reason 固定上游的稳定原因码，未提供或未识别时为 null
 * @param effectState 上游声明的副作用状态 none、committed、unknown，缺失时不推定
 * @param cleanupState 上游声明的清理状态 complete、failed，缺失时不推定
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BrowserFailure(String error, String reason,
        @JsonProperty("effect_state") String effectState,
        @JsonProperty("cleanup_state") String cleanupState) {
    /** 固定上游 RpcErrorReason；不将外部任意字符串当作诊断事实回显。 */
    private static final Set<String> REASONS = Set.of(
            "ui_lookup_failed", "task_unavailable", "target_unavailable", "task_stopping", "ui_deadline",
            "preview_busy", "agent_window_scope", "element_not_visible", "input_not_ready",
            "input_outcome_unknown", "input_paint_unconfirmed", "input_cleanup_failed", "ref_not_found",
            "ref_kind_unsupported", "visual_capture_stale", "visual_capture_invalid", "visual_coordinate_invalid",
            "visual_target_changed", "visual_pixel_budget_exceeded", "selector_not_found", "target_not_fillable",
            "fill_value_invalid", "fill_target_changed", "fill_focus_lost", "fill_value_mismatch", "fill_failed",
            "target_not_select", "option_not_found", "single_select_value_count", "tab_not_active",
            "restricted_tab_url", "cdp_extension_access_denied", "borrow_conflict", "borrow_in_progress",
            "user_denied", "confirmation_timeout", "confirmation_ui_unavailable", "borrow_outcome_unknown",
            "screenshot_capture_failed", "renderer_read_timeout", "user_cancelled", "page_hidden", "navigation",
            "watchdog_timeout", "stale_frame", "loading_stalled", "file_input_probe_failed",
            "file_input_not_activated", "set_file_input_failed", "upload_mechanism_unsupported",
            "file_drop_target_unavailable", "file_drop_failed", "download_capture_failed",
            "transfer_outcome_unknown", "transfer_timeout", "cleanup_failed");

    /** 只提取固定协议的原因及效果状态，原始 message、hint、stderr 和扩展 data 均不传播。 */
    public static BrowserFailure fromCli(String code, JsonNode reply) {
        JsonNode data = reply.path("data");
        return new BrowserFailure(code, known(data.path("reason"), REASONS),
                known(data.path("effect_state"), Set.of("none", "committed", "unknown")),
                known(data.path("cleanup_state"), Set.of("complete", "failed")));
    }

    /** 稳定原因可进入模型正文；历史或未知原因保持未说明，不猜测失败原因。 */
    public static String knownReason(JsonNode value) {
        return known(value, REASONS);
    }

    /** 明确未知效果或未知结果原因时不能降为确定失败，避免模型重放可能已发生的动作。 */
    public boolean unknownEffect() {
        return Set.of("timeout", "protocol_error").contains(error) || "unknown".equals(effectState) || Set.of("input_outcome_unknown", "borrow_outcome_unknown",
                "transfer_outcome_unknown").contains(reason == null ? "" : reason);
    }

    /** 仅返回约定集合中的原始文本；类型错误和未识别值均不构造默认事实。 */
    private static String known(JsonNode value, Set<String> allowed) {
        return value.isTextual() && allowed.contains(value.textValue()) ? value.textValue() : null;
    }
}
