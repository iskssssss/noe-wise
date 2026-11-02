package com.skillforge.runtime.session.spi;

import com.skillforge.runtime.api.port.ContextReference;
import com.skillforge.runtime.api.port.RunIdentity;
import java.time.Instant;

/** 宿主受控工具读取安全证据的入口；实现每次按真实调用身份重新授权。 */
public interface SessionContextReferenceReader {
    Page read(RunIdentity run, String referenceId, long offset, int limit);

    default LocatedPage find(RunIdentity run, String referenceId, String literal, long offset, int limit) {
        throw new IllegalStateException("context_reference_find_unavailable");
    }

    default LocatedPage findSource(RunIdentity run, String sourceEventId, String literal, long offset, int limit) {
        throw new IllegalStateException("context_reference_source_find_unavailable");
    }

    default Page readSource(RunIdentity run, String sourceEventId, long offset, int limit) {
        throw new IllegalStateException("context_reference_source_read_unavailable");
    }

    default HistoryPage history(RunIdentity run, HistoryQuery query) {
        throw new IllegalStateException("context_reference_history_unavailable");
    }

    /** 历史扫描与锚点窗口共用的显式查询；时间范围为闭区间，锚点不能与扫描过滤条件混用。 */
    record HistoryQuery(java.util.Optional<String> runId, long afterSequence, int limit,
                        java.util.Optional<String> query,
                        java.util.Optional<Instant> fromTime, java.util.Optional<Instant> toTime,
                        java.util.Optional<String> aroundSourceEventId, int window) {
        public HistoryQuery {
            runId = java.util.Objects.requireNonNull(runId);
            query = java.util.Objects.requireNonNull(query);
            fromTime = java.util.Objects.requireNonNull(fromTime);
            toTime = java.util.Objects.requireNonNull(toTime);
            aroundSourceEventId = java.util.Objects.requireNonNull(aroundSourceEventId);
            if (fromTime.isPresent() && toTime.isPresent() && fromTime.get().isAfter(toTime.get()))
                throw new IllegalArgumentException("history_time_range_invalid");
        }
    }

    record HistoryEntry(String sourceEventId, String runId, long sequence, Instant occurredAt, String content) { }

    record HistoryPage(java.util.List<HistoryEntry> records, long nextSequence, boolean end) {
        public HistoryPage { records = java.util.List.copyOf(records); }
    }

    record Page(ContextReference reference, String content, long nextOffset, boolean end) {
        @Override public String toString() { return "ContextReferencePage[REDACTED]"; }
    }
    /** offset为实际返回页的Unicode起点；未命中不表示正文已读完。 */
    record LocatedPage(Page page, long offset, boolean matched) { }
}
