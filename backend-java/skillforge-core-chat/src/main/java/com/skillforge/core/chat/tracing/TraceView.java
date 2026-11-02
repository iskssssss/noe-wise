package com.skillforge.core.chat.tracing;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 从 Session/Execution/Usage 权威账本重建的只读过程轨迹投影。 */
public record TraceView(
        String rootInputId,
        List<TraceEntry> timeline,
        UsageSummary usageSummary,
        List<String> unresolvedItems) {

    public TraceView {
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        usageSummary = usageSummary == null ? UsageSummary.empty() : usageSummary;
        unresolvedItems = unresolvedItems == null ? List.of() : List.copyOf(unresolvedItems);
    }

    public record TraceEntry(
            String sourceDomain,
            String sourceEventId,
            String eventType,
            String status,
            String traceId,
            Instant startedAt,
            Instant endedAt,
            List<String> usageRefs,
            Map<String, Object> sanitizedDetail) {
        public TraceEntry {
            usageRefs = usageRefs == null ? List.of() : List.copyOf(usageRefs);
            sanitizedDetail = sanitizedDetail == null ? Map.of() : Map.copyOf(sanitizedDetail);
        }
    }

    public record UsageSummary(
            long calls,
            String tokens,
            String promptTokens,
            String completionTokens,
            Map<String, Long> amountByCurrency,
            long unknownUsageCalls) {
        public UsageSummary {
            amountByCurrency = amountByCurrency == null ? Map.of() : Map.copyOf(amountByCurrency);
        }

        public static UsageSummary empty() {
            return new UsageSummary(0, null, null, null, Map.of(), 0);
        }
    }
}
