package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.Set;

/** 作为 Session sequence 的唯一分配边界追加和读取不可变会话事件。 */
public interface SessionLogPort {

    /**
     * 普通展示历史的明确退休状态。readAfter 仍只返回真实原事件，退休区间形成稳定sequence空隙；
     * 恢复事实、首/末助手快照和完整adoption不改正文或事件类型。没有退休事实返回空。
     */
    default Optional<HistoryRetention> historyRetention(SessionOwner owner) { java.util.Objects.requireNonNull(owner); return Optional.empty(); }

    /**
     * @param sourceSessionRevision 事实所有者已CLOSED时的精确修订，不接受调用者声明
     * @param closedAt 原Session关闭事实的数据库时间
     * @param retiredThroughSequence 已回收展示流的最高原sequence，绝不重新分配
     * @param retiredEventCount 已回收展示事件数，幂等身份/摘要墓碑仍受保护
     * @param retiredEventTypes 已退休的展示类型，推理退休不能冒充完整推理
     * @param complete 当前普通展示范围本轮已处理完；必要恢复/重放正文仍保留
     */
    record HistoryRetention(long sourceSessionRevision, Instant closedAt, long retiredThroughSequence,
            long retiredEventCount, Set<String> retiredEventTypes, boolean complete) {
        public HistoryRetention {
            if (sourceSessionRevision <= 0 || retiredThroughSequence < 0 || retiredEventCount < 0)
                throw new IllegalArgumentException("session_history_retention_invalid");
            java.util.Objects.requireNonNull(closedAt);
            retiredEventTypes = Set.copyOf(retiredEventTypes);
        }
    }

    /** 以稳定 eventId/fingerprint 追加事件，由适配器分配唯一正 sequence。 */
    AppendResult append(AppendSessionEvent command);

    int MAX_APPEND_BATCH = 64;

    /**
     * 在完整相同 owner 内有界保序追加；回执与请求逐项对应，首个 Conflict 后停止。
     * 已接受前缀保持提交，重试仍使用原 eventId/fingerprint；技术失败可能响应未知。
     * 批量适配器可共用事务和 sequence 锁，不能改变单条幂等与冲突语义。
     */
    default List<AppendResult> appendBatch(List<AppendSessionEvent> commands) {
        var batch = checkedAppendBatch(commands);
        var results = new java.util.ArrayList<AppendResult>(batch.size());
        for (var command : batch) {
            var result = append(command);
            results.add(result);
            if (result instanceof AppendResult.Conflict) break;
        }
        return List.copyOf(results);
    }

    static List<AppendSessionEvent> checkedAppendBatch(List<AppendSessionEvent> commands) {
        var batch = List.copyOf(commands);
        if (batch.isEmpty() || batch.size() > MAX_APPEND_BATCH)
            throw new IllegalArgumentException("session_append_batch_size_invalid");
        var owner = batch.getFirst().target().owner();
        for (var command : batch) if (!command.target().owner().equals(owner))
            throw new IllegalArgumentException("session_append_batch_owner_invalid");
        return batch;
    }

    /** 按完整 SessionOwner 与 eventId 读取已提交事件。 */
    Optional<CommittedSessionEvent> findByEvent(SessionOwner owner, EventId eventId);

    /**
     * 仅供已获持久 USER 事实治理授权的调用方核验原已提交来源，不授予查询历史或新提案证据权限。
     * 按 sourceSession 的租户与会话、真实 USER subject 和 eventId 精确读取，保留原 owner/target/Agent；
     * 不以当前调用方 Agent 重建旧来源归属，缺失、不匹配或不唯一时返回空。未支持此能力的适配器失败关闭。
     */
    default Optional<CommittedSessionEvent> findUserSource(SessionRef sourceSession, EndUserId sourceUser, EventId eventId) {
        PortContracts.requireNonNull(sourceSession, "sourceSession");
        PortContracts.requireNonNull(sourceUser, "sourceUser");
        PortContracts.requireNonNull(eventId, "eventId");
        throw new UnsupportedOperationException("session_user_source_read_unsupported");
    }

    /** 批量读取不可变来源；缺失项不返回，所有项仍按完整 owner 隔离。 */
    default java.util.Map<EventId, CommittedSessionEvent> findByEvents(SessionOwner owner, java.util.Set<EventId> eventIds) {
        var found = new java.util.HashMap<EventId, CommittedSessionEvent>();
        for (var id : java.util.Set.copyOf(eventIds)) findByEvent(owner, id).ifPresent(event -> found.put(id, event));
        return java.util.Map.copyOf(found);
    }

    /** 严格读取给定 sequence 之后的有序事件页。 */
    List<CommittedSessionEvent> readAfter(ReadSessionLog query);

    /** 按完整 owner 与 Run 保序分页，不限制事件类型；limit 约束该 Run 的匹配事件数。 */
    default List<CommittedSessionEvent> readAfter(ReadSessionLog query, RunRef run) {
        if (!query.owner().sessionRef().tenantId().equals(run.tenantId()))
            throw new IllegalArgumentException("session_log_run_scope_invalid");
        var result = new java.util.ArrayList<CommittedSessionEvent>();
        long cursor = query.afterSequence();
        while (result.size() < query.limit()) {
            var page = readAfter(new ReadSessionLog(query.owner(), cursor, query.limit()));
            for (var event : page) {
                if (!event.owner().equals(query.owner()) || event.sessionSequence() <= cursor)
                    throw new IllegalStateException("session_log_page_invalid");
                cursor = event.sessionSequence();
                if (event.runRef().equals(Optional.of(run))) result.add(event);
                if (result.size() == query.limit()) return List.copyOf(result);
            }
            if (page.size() < query.limit()) break;
        }
        return List.copyOf(result);
    }

    /** 返回 input/Run 在完整 owner 内的首个不可变来源；后续事实不改变其受理身份。 */
    default Optional<CommittedSessionEvent> findFirst(SessionOwner owner, InputId inputId, RunRef run) {
        java.util.Objects.requireNonNull(inputId, "inputId");
        long cursor = 0;
        while (true) {
            var page = readAfter(new ReadSessionLog(owner, cursor, 256), run);
            for (var event : page) {
                if (event.inputId().equals(inputId)) return Optional.of(event);
                cursor = event.sessionSequence();
            }
            if (page.size() < 256) return Optional.empty();
        }
    }

    /** 最新已提交来源；适配器可使用倒序索引读取，不能用未提交序号推断。 */
    default Optional<CommittedSessionEvent> latest(SessionOwner owner) {
        CommittedSessionEvent last = null;
        while (true) {
            var page = readAfter(new ReadSessionLog(owner, last == null ? 0 : last.sessionSequence(), 256));
            for (var event : page) {
                if (!event.owner().equals(owner) || (last != null && event.sessionSequence() <= last.sessionSequence()))
                    throw new IllegalStateException("session_log_page_invalid");
                last = event;
            }
            if (page.size() < 256) return Optional.ofNullable(last);
        }
    }

    /** 按完整 owner、当前 Run 与类型读取；匹配数决定页大小，游标仍是 Session sequence。 */
    default List<CommittedSessionEvent> readAfter(ReadSessionLog query,
            com.skillforge.runtime.api.RunRef run, java.util.Set<String> eventTypes) {
        if (!query.owner().sessionRef().tenantId().equals(run.tenantId()))
            throw new IllegalArgumentException("session_log_run_scope_invalid");
        var result = new java.util.ArrayList<CommittedSessionEvent>();
        long cursor = query.afterSequence();
        while (result.size() < query.limit()) {
            var page = readAfter(new ReadSessionLog(query.owner(), cursor, query.limit()), eventTypes);
            for (var event : page) {
                if (!event.owner().equals(query.owner()) || event.sessionSequence() <= cursor)
                    throw new IllegalStateException("session_log_page_invalid");
                cursor = event.sessionSequence();
                if (event.runRef().equals(Optional.of(run))) result.add(event);
                if (result.size() == query.limit()) return List.copyOf(result);
            }
            if (page.size() < query.limit()) break;
        }
        return List.copyOf(result);
    }

    /** 在完整 owner 边界内按类型分页；limit 约束匹配事件数，游标仍使用原 Session sequence。 */
    default List<CommittedSessionEvent> readAfter(ReadSessionLog query, java.util.Set<String> eventTypes) {
        var types = java.util.Set.copyOf(eventTypes);
        if (types.isEmpty()) throw new IllegalArgumentException("eventTypes must not be empty");
        var result = new java.util.ArrayList<CommittedSessionEvent>();
        long cursor = query.afterSequence();
        while (result.size() < query.limit()) {
            var page = readAfter(new ReadSessionLog(query.owner(), cursor, query.limit()));
            for (var event : page) {
                if (!event.owner().equals(query.owner()) || event.sessionSequence() <= cursor)
                    throw new IllegalStateException("session_log_page_invalid");
                cursor = event.sessionSequence();
                if (types.contains(event.eventType())) result.add(event);
                if (result.size() == query.limit()) return java.util.List.copyOf(result);
            }
            if (page.size() < query.limit()) break;
        }
        return java.util.List.copyOf(result);
    }

    /** 将尚未分配 sequence 的事件绑定到不可变 Session 投影目标。 */
    record AppendSessionEvent(SessionProjectionTarget target, SessionEventDraft event) {

        /** 拒绝缺失投影目标或事件；跨根输入链由 trace 因果事实表达。 */
        public AppendSessionEvent {
            target = PortContracts.requireNonNull(target, "target");
            event = PortContracts.requireNonNull(event, "event");
        }
    }

    /** 表达 SessionLog 首次追加、同指纹重放或异指纹冲突。 */
    sealed interface AppendResult
            permits AppendResult.Appended, AppendResult.Replayed,
                    AppendResult.Conflict {

        /** 返回首次分配 sequence 的已提交事件。 */
        record Appended(CommittedSessionEvent event) implements AppendResult {

            /** 拒绝缺失已提交事件。 */
            public Appended {
                event = PortContracts.requireNonNull(event, "event");
            }
        }

        /** 返回同 eventId/同 fingerprint 已存在的事件且不增加 sequence。 */
        record Replayed(CommittedSessionEvent event) implements AppendResult {

            /** 拒绝缺失已提交事件。 */
            public Replayed {
                event = PortContracts.requireNonNull(event, "event");
            }
        }

        /** 返回同 eventId/异 fingerprint 的稳定冲突。 */
        record Conflict(PortConflict conflict) implements AppendResult {

            /** 拒绝缺失冲突事实。 */
            public Conflict {
                conflict = PortContracts.requireNonNull(conflict, "conflict");
            }
        }
    }

    /** 描述从零或既有 Session sequence 之后读取的有界事件页。 */
    record ReadSessionLog(SessionOwner owner, long afterSequence, int limit) {

        /** 拒绝缺失 owner、负 cursor 或非正读取上限。 */
        public ReadSessionLog {
            owner = PortContracts.requireNonNull(owner, "owner");
            if (afterSequence < 0) {
                throw new IllegalArgumentException("afterSequence must not be negative");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("limit must be positive");
            }
        }
    }
}
