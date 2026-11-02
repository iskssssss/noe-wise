package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.ModelPort.ModelMessage;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** 独立系统用途只生成背景摘要；不能调用业务工具或写入任务。 */
public interface SessionContextSummary {
    Outcome summarize(Request request);

    /** AVAILABLE 仅表示摘要候选可交给 Runtime 校验，并不表示已采用。 */
    enum Status { AVAILABLE, UNAVAILABLE, FAILED, REJECTED }

    /** 原因只使用安全固定码；已受理调用的配置与 invocation 引用在失败时仍保留。 */
    record Outcome(Status status, String reason, Optional<ResultRef> configurationRef,
                   Optional<ResultRef> invocationRef, Optional<Result> result) {
        public Outcome {
            Objects.requireNonNull(status); Objects.requireNonNull(reason);
            Objects.requireNonNull(configurationRef); Objects.requireNonNull(invocationRef);
            Objects.requireNonNull(result);
            if (!reason.matches("[A-Z][A-Z0-9_]{0,95}")
                    || (status == Status.AVAILABLE) != result.isPresent()
                    || invocationRef.isPresent() && configurationRef.isEmpty())
                throw new IllegalArgumentException("context_summary_outcome_invalid");
            if (result.isPresent() && (!configurationRef.equals(Optional.of(result.orElseThrow().configurationRef()))
                    || !invocationRef.equals(Optional.of(result.orElseThrow().invocationRef()))))
                throw new IllegalArgumentException("context_summary_outcome_refs_invalid");
        }

        public static Outcome available(Result result) {
            Objects.requireNonNull(result);
            return new Outcome(Status.AVAILABLE, "SUMMARY_AVAILABLE", Optional.of(result.configurationRef()),
                    Optional.of(result.invocationRef()), Optional.of(result));
        }

        public static Outcome unavailable(String reason) {
            return new Outcome(Status.UNAVAILABLE, reason, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public static Outcome unavailable(String reason, ResultRef configurationRef, ResultRef invocationRef) {
            return new Outcome(Status.UNAVAILABLE, reason, Optional.of(configurationRef), Optional.of(invocationRef),
                    Optional.empty());
        }

        public static Outcome failed(String reason) {
            return new Outcome(Status.FAILED, reason, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public static Outcome failed(String reason, ResultRef configurationRef, ResultRef invocationRef) {
            return new Outcome(Status.FAILED, reason, Optional.of(configurationRef), Optional.of(invocationRef),
                    Optional.empty());
        }

        public static Outcome rejected(String reason, ResultRef configurationRef, ResultRef invocationRef) {
            return new Outcome(Status.REJECTED, reason, Optional.of(configurationRef), Optional.of(invocationRef),
                    Optional.empty());
        }

        @Override public String toString() { return "ContextSummaryOutcome[status=" + status + ",reason=" + reason + "]"; }
    }

    record Request(SessionPreparations.Key key, VerifiedIdentity actor, String sourceDigest,
                   List<ModelMessage> history, List<ModelMessage> currentContext, int maxOutputTokens, Duration remaining,
                   BooleanSupplier cancelled) {
        public Request {
            Objects.requireNonNull(key); Objects.requireNonNull(actor); Objects.requireNonNull(sourceDigest);
            history = List.copyOf(history); currentContext = List.copyOf(currentContext);
            Objects.requireNonNull(remaining); Objects.requireNonNull(cancelled);
            if (!actor.owner().equals(key.identity().owner()) || sourceDigest.isBlank() || history.isEmpty()
                    || maxOutputTokens <= 0 || remaining.isNegative() || remaining.isZero()
                    || currentContext.stream().anyMatch(message -> !message.toolCalls().isEmpty() || message.toolCallId().isPresent()))
                throw new IllegalArgumentException("context_summary_request_invalid");
        }
        @Override public String toString() { return "ContextSummaryRequest[REDACTED]"; }
    }

    /** 只覆盖 history 的连续前缀；currentContext 只提供当前目标与约束，永不进入摘要覆盖。 */
    record Result(ResultRef configurationRef, ResultRef invocationRef, String sourceDigest,
                  int coveredMessages, String content) {
        public Result {
            Objects.requireNonNull(configurationRef); Objects.requireNonNull(invocationRef);
            Objects.requireNonNull(sourceDigest); Objects.requireNonNull(content);
            if (sourceDigest.isBlank() || coveredMessages <= 0 || content.isBlank())
                throw new IllegalArgumentException("context_summary_result_invalid");
        }
        @Override public String toString() { return "ContextSummaryResult[REDACTED]"; }
    }

    static SessionContextSummary disabled() { return request -> Outcome.unavailable("SUMMARY_DISABLED"); }
}
