package com.skillforge.core.chat.observation;

import java.util.Set;

/** 会话概要、轮次与轨迹共用最终答复判据；失败和取消说明仍是可见消息。 */
public final class PublicAnswerStatus {
    private PublicAnswerStatus() { }

    public static boolean isCompletedAnswer(String role, String content, String phase, String state) {
        // 无 phase/state 的合法主动助手消息是宿主已提交正文。
        return "assistant".equals(role) && content != null && !content.isBlank()
                && (phase == null || "final".equals(phase))
                && (state == null || "completed".equals(state));
    }

    public static String from(String execution, boolean completedAnswer, boolean failedMessage) {
        if (Set.of("RUNNING", "QUEUED", "WAITING").contains(execution)) return "IN_PROGRESS";
        if (completedAnswer) return "COMPLETED";
        if (Set.of("FAILED", "TERMINATED", "OUTCOME_UNKNOWN").contains(execution) || failedMessage) return "FAILED";
        return "NONE";
    }
}
