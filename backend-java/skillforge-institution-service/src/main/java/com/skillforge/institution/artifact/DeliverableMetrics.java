package com.skillforge.institution.artifact;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** 成果工具调用的低基数指标边界；标签只接受固定操作、状态和错误码白名单。 */
public final class DeliverableMetrics {
    private static final String COUNTER = "skillforge.deliverable.tool.calls";
    private static final String TIMER = "skillforge.deliverable.tool.duration";
    private static final Set<String> ERROR_CODES = Set.of(
            "OUTPUT_PLAN_REQUIRED",
            "OUTPUT_PLAN_FROZEN",
            "OUTPUT_PLAN_IMMUTABLE_USE_NEW_RUN",
            "DUPLICATE_DELIVERABLE_KEY",
            "INVALID_DELIVERABLE_KIND",
            "BASE_REVISION_CONFLICT",
            "REQUIRED_DELIVERABLE_MISSING",
            "OUTPUT_FILE_NOT_FOUND",
            "OUTPUT_FILE_EXPIRED",
            "OUTPUT_FILE_SCOPE_DENIED",
            "OUTPUT_SUBMISSION_CANCELLED",
            "OUTPUT_FILE_UNREADABLE",
            "OUTPUT_FILE_ACCESS_DENIED",
            "BUNDLE_MANIFEST_REQUIRED",
            "BUNDLE_MANIFEST_INVALID",
            "BUNDLE_MANIFEST_MISMATCH");

    private DeliverableMetrics() {
    }

    /** 执行工具并记录终态；指标故障不能改变工具回包或覆盖原异常。 */
    public static Map<String, Object> invoke(String operation, Callable<Map<String, Object>> action) throws Exception {
        String metricOperation = switch (operation) {
            case "plan" -> "plan";
            case "finalize" -> "finalize";
            default -> throw new IllegalArgumentException("deliverable_metric_operation_invalid");
        };
        long started = System.nanoTime();
        String status = "error";
        String errorCode = "other";
        try {
            Map<String, Object> result = action.call();
            if (result != null && Boolean.FALSE.equals(result.get("success"))) {
                status = "rejected";
                errorCode = errorCode(result.get("error"));
            } else {
                status = "success";
                errorCode = "none";
            }
            return result;
        } catch (Exception failure) {
            errorCode = errorCode(failure.getMessage());
            throw failure;
        } catch (Error failure) {
            errorCode = errorCode(failure.getMessage());
            throw failure;
        } finally {
            record(metricOperation, status, errorCode, System.nanoTime() - started);
        }
    }

    private static String errorCode(Object candidate) {
        if (!(candidate instanceof String value) || value.isBlank()) {
            return "other";
        }
        int separator = value.indexOf(':');
        String code = separator < 0 ? value : value.substring(0, separator);
        return ERROR_CODES.contains(code) ? code : "other";
    }

    private static void record(String operation, String status, String errorCode, long elapsedNanos) {
        try {
            Tags tags = Tags.of("operation", operation, "status", status, "error_code", errorCode);
            Metrics.globalRegistry.counter(COUNTER, tags).increment();
            Metrics.globalRegistry.timer(TIMER, tags).record(elapsedNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // 可观测性后端异常不能覆盖领域回包或原始失败。
        }
    }
}
