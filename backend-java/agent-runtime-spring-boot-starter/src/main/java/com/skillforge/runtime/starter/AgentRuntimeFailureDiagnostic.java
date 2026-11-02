package com.skillforge.runtime.starter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import org.slf4j.Logger;

/** 失败处理边界的有界安全诊断；不读取异常消息、正文或原始文件名，不创建 SDK 公共契约。 */
final class AgentRuntimeFailureDiagnostic {
    private static final int MAX_FAILURES = 8;
    private static final int MAX_FRAMES = 8;
    private static final int MAX_SYMBOL_LENGTH = 120;

    private AgentRuntimeFailureDiagnostic() { }

    static void warn(Logger logger, String event, String source, String subject, String reason, Throwable failure) {
        logger.warn("{} source={} tag={} reason={} diagnostic={}", event, symbol(source), tag(subject),
                reason != null && reason.matches("[a-z][a-z0-9_]{0,79}") ? reason : "runtime_failure", details(failure));
    }

    static String tag(String subject) {
        if (subject == null) return "unavailable";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(subject.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("runtime_diagnostic_digest_unavailable", impossible);
        }
    }

    /** 保留类、方法、行号以及 cause/suppressed 的关系，避免 Throwable.toString 隐式打印敏感消息。 */
    static String details(Throwable failure) {
        if (failure == null) return "none";
        var pending = new ArrayDeque<Failure>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var output = new StringBuilder();
        pending.add(new Failure("failure", failure));
        int count = 0;
        while (!pending.isEmpty() && count < MAX_FAILURES) {
            var item = pending.removeFirst();
            var current = item.failure();
            if (!seen.add(current)) continue;
            count++;
            if (!output.isEmpty()) output.append("; ");
            output.append(item.relation()).append('=').append(symbol(current.getClass().getName()));
            try {
                var frames = current.getStackTrace();
                for (int index = 0; index < Math.min(frames.length, MAX_FRAMES); index++) {
                    var frame = frames[index];
                    output.append(" at ").append(symbol(frame.getClassName())).append('.')
                            .append(symbol(frame.getMethodName())).append(':').append(frame.getLineNumber());
                }
                var cause = current.getCause();
                if (cause != null && pending.size() < MAX_FAILURES) pending.addLast(new Failure("cause", cause));
                for (var suppressed : current.getSuppressed()) {
                    if (pending.size() >= MAX_FAILURES) break;
                    pending.addLast(new Failure("suppressed", suppressed));
                }
            } catch (RuntimeException unavailable) {
                output.append(" metadata_unavailable");
            }
        }
        if (!pending.isEmpty()) output.append("; truncated");
        return output.toString();
    }

    private static String symbol(String value) {
        if (value == null) return "unknown";
        var output = new StringBuilder();
        for (int index = 0; index < Math.min(value.length(), MAX_SYMBOL_LENGTH); index++) {
            char character = value.charAt(index);
            output.append(character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                    || character >= '0' && character <= '9' || ".$<>_-".indexOf(character) >= 0 ? character : '_');
        }
        return output.toString();
    }

    private record Failure(String relation, Throwable failure) { }
}
