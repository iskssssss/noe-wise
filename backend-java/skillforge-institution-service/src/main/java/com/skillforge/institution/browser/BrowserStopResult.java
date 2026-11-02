package com.skillforge.institution.browser;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.browser.BrowserFailure;
import com.skillforge.execution.browser.BrowserReceipt;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 停止工具的有界模型投影；完整上游回执先持久化，本类型不改变其状态或补写清理成功。
 * @param invocation 原始停止调用标识，最多 512 字符
 * @param operation 固定为 stop
 * @param task 原回执任务标识，缺失保持 NULL，非空最多 128 字符
 * @param status 原回执的确定状态
 * @param success 仅原状态 succeeded 为 true
 * @param isError 原状态不为 succeeded 时为 true
 * @param result 仅固定安全结果字段，未知字段留在原持久回执
 * @param content 仅由本固定投影生成的模型文字
 */
record BrowserStopResult(String invocation, String operation, String task, String status,
                         boolean success, boolean isError, Outcome result, List<Map<String, String>> content) {
    /** 模型停止回执实际序列化字节硬上界，为预算声明留下 Runtime 封装余量。 */
    private static final int MAX_BYTES = 8_192;
    /** 确定回执只投影这些终态；unknown 继续由 Handler 走结果未知路径。 */
    private static final Set<String> STATUSES = Set.of("succeeded", "failed", "rejected", "cancelled", "cleanup_failed");

    /**
     * 固定结果字段保留原值，缺失为 NULL；不从 stopped、trace 或 ok 推断清理成功。
     * @param ok 原上游布尔结果
     * @param error 原稳定错误码，最多 96 个 ASCII 字符
     * @param reason 已知的安全原因码
     * @param effectState 原 none、committed 或 unknown 效果状态
     * @param cleanupState 原 complete 或 failed 清理状态
     * @param cleanupComplete 原明确清理布尔值
     */
    record Outcome(Boolean ok, String error, String reason,
                   @JsonProperty("effect_state") String effectState,
                   @JsonProperty("cleanup_state") String cleanupState,
                   @JsonProperty("cleanup_complete") Boolean cleanupComplete) { }

    /** 原回执已持久后生成投影，非法身份或非确定状态失败关闭，不截断身份也不自动重放。 */
    static Map<String, Object> project(BrowserReceipt receipt, ObjectMapper json) throws IOException {
        if (!"stop".equals(receipt.operation()) || !STATUSES.contains(receipt.status())
                || receipt.invocation() == null || receipt.invocation().isBlank() || receipt.invocation().length() > 512
                || receipt.task() != null && receipt.task().length() > 128)
            throw new IOException("browser_stop_receipt_invalid");
        var raw = receipt.result();
        var outcome = new Outcome(raw != null && raw.path("ok").isBoolean() ? raw.path("ok").booleanValue() : null,
                raw != null && raw.path("error").isTextual() && raw.path("error").asText().matches("[a-z0-9_]{1,96}")
                        ? raw.path("error").asText() : null,
                raw == null ? null : BrowserFailure.knownReason(raw.path("reason")),
                raw != null && Set.of("none", "committed", "unknown").contains(raw.path("effect_state").asText())
                        ? raw.path("effect_state").asText() : null,
                raw != null && Set.of("complete", "failed").contains(raw.path("cleanup_state").asText())
                        ? raw.path("cleanup_state").asText() : null,
                raw != null && raw.path("cleanup_complete").isBoolean() ? raw.path("cleanup_complete").booleanValue() : null);
        boolean success = "succeeded".equals(receipt.status());
        String text = "browser_stop 原停止回执：task=" + receipt.task() + " status=" + receipt.status()
                + "；清理状态仅依据 result 中明确字段，缺失不表示已清理；失败或未知不得声称任务已结束或自动重放。";
        var projected = new BrowserStopResult(receipt.invocation(), "stop", receipt.task(), receipt.status(),
                success, !success, outcome, List.of(BrowserToolResults.text(text)));
        byte[] encoded = json.writeValueAsBytes(projected);
        if (encoded.length > MAX_BYTES) throw new IOException("browser_stop_receipt_too_large");
        return json.readValue(encoded, new TypeReference<Map<String, Object>>() { });
    }
}
