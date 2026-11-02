package com.skillforge.core.chat.observation;

import com.skillforge.core.chat.observation.SessionObservationPublicFacts.Facts;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.core.chat.observation.SessionObservationPublicFacts.ReadScope;
import com.skillforge.core.chat.observation.SessionObservationPublicFacts.SessionKey;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/** 平台会话观测的批量只读查询；不创建或回写任何会话权威状态。 */
@Repository
public class SessionObservationRepository {
    private static final Logger LOG = LoggerFactory.getLogger(SessionObservationRepository.class);
    private static final AtomicLong CALLS = new AtomicLong();

    private final SessionObservationMapper mapper;
    private final PlatformObservationAccess reads;

    /** 装配当前领域的 Mapper 依赖；不在构造阶段获取连接或开启事务。 */
    public SessionObservationRepository(SessionObservationMapper mapper, PlatformObservationAccess reads) {
        LOG.debug("持久化组件装配进入 component={}", "SessionObservationRepository");
        this.mapper = mapper;
        this.reads = reads;
        LOG.debug("持久化组件装配退出 component={}", "SessionObservationRepository");
    }

    /** 读取数据库当前时刻作为观测时间；每次执行重新读取，避免事务内缓存旧时刻。 */
    Instant readAt() {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "readAt");
        try {
            return mapper.readAt();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "readAt", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "readAt", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 按授权范围读取非状态条件的候选；公开归并后由服务统一筛选、计数和分页。 */
    PagedSessions list(SessionObservationScope scope, SessionObservationQuery.Sessions query) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "list");
        try {
            Map<String, Object> parameters = parameters(
                    "scopeTenantId", scope.kind() == SessionObservationScope.Kind.TENANT ? scope.tenantId() : null,
                    "tenantId", query.tenantId(), "sessionId", blankToNull(query.sessionId()),
                    "userId", query.userId(), "agentId", query.agentId(), "teamId", blankToNull(query.teamId()),
                    "channel", blankToNull(query.channel()), "summary", blankToNull(query.summary()),
                    "startedFrom", query.startedFrom(), "startedTo", query.startedTo());
            List<SessionRow> rows = new java.util.ArrayList<>();
            Long target = scope.kind() == SessionObservationScope.Kind.TENANT ? scope.tenantId() : query.tenantId();
            if (target != null) {
                parameters.put("tenantId", target);
                rows.addAll(reads.read(target, () -> mapper.list(parameters)).stream().map(this::sessionRow).toList());
            } else {
                long after = 0;
                for (;;) {
                    var targets = reads.targets(null, after, 100);
                    for (long tenant : targets) {
                        parameters.put("tenantId", tenant);
                        rows.addAll(reads.read(tenant, () -> mapper.list(parameters)).stream().map(this::sessionRow).toList());
                    }
                    if (targets.size() < 100) break;
                    after = targets.getLast();
                }
            }
            rows.sort(java.util.Comparator.comparing(SessionRow::lastActivityAt,
                    java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())).reversed()
                    .thenComparingLong(SessionRow::tenantId).thenComparing(SessionRow::sessionId));
            long total = rows.isEmpty() ? 0 : rows.get(0).total();
            return new PagedSessions(rows, total);
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "list", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "list", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 验证 scope 中的租户权限后读取会话只读投影；无匹配记录返回空值。 */
    Optional<SessionRow> find(SessionObservationScope scope, long tenantId, String sessionId) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "find");
        try {
            scope.requireTenant(tenantId);
            return reads.read(tenantId, () -> mapper.find(parameters("tenantId", tenantId, "sessionId", sessionId)))
                    .stream().map(this::sessionRow).findFirst();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "find", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "find", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 读取指定会话在冻结序列上界内的根输入；游标和数量只限制当前页。 */
    List<RoundRow> rounds(
            long tenantId, String sessionId, String targetRootInputId,
            Long beforeSequence, long upperBound, int limit) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "rounds");
        try {
            Map<String, Object> parameters = parameters("tenantId", Long.toString(tenantId), "sessionId", sessionId, "targetRootInputId", blankToNull(targetRootInputId), "beforeSequence", beforeSequence, "upperBound", upperBound, "limit", limit);
            return reads.read(tenantId, () -> mapper.rounds(parameters)).stream().map(rs -> new RoundRow(
                    string(rs, "root_input_id"), number(rs, "round_number").longValue(), number(rs, "first_sequence").longValue(),
                    number(rs, "last_sequence").longValue(), instant(rs, "started_at"), instant(rs, "updated_at"))).toList();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "rounds", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "rounds", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 读取会话的已提交事件序列上界；无事件返回零。 */
    long sessionSequenceUpperBound(long tenantId, String sessionId) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "sessionSequenceUpperBound");
        try {
            Long value = reads.read(tenantId, () -> mapper.sessionSequenceUpperBound(parameters("tenantId", Long.toString(tenantId), "sessionId", sessionId)));
            return value == null ? 0 : value;
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "sessionSequenceUpperBound", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "sessionSequenceUpperBound", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 批量读取根输入关联的 Run；空根输入集合不执行数据库查询。 */
    List<RunRow> runs(long tenantId, String sessionId, List<String> rootInputIds) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "runs");
        try {
            if (rootInputIds.isEmpty()) return List.of();
            return reads.read(tenantId, () -> mapper.runs(parameters("tenantId", Long.toString(tenantId), "sessionId", sessionId, "roots", rootInputIds))).stream().map(rs -> new RunRow(
                    string(rs, "run_id"), string(rs, "root_input_id"), string(rs, "status"),
                    Instant.ofEpochSecond(number(rs, "created_epoch_second").longValue(), number(rs, "created_nano").intValue()),
                    Instant.ofEpochSecond(number(rs, "updated_epoch_second").longValue(), number(rs, "updated_nano").intValue()))).toList();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "runs", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "runs", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 批量读取指定 Run 的冻结准备和操作身份；不推断未持久化身份。 */
    List<FrozenKeyRow> frozenKeys(long tenantId, List<String> runIds) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "frozenKeys");
        try {
            if (runIds.isEmpty()) return List.of();
            Map<String, Object> parameters = parameters("tenantId", Long.toString(tenantId), "runIds", runIds);
            return reads.read(tenantId, () -> mapper.frozenKeys(parameters)).stream().map(rs -> new FrozenKeyRow(
                    string(rs, "source_kind"), string(rs, "run_id"),
                    string(rs, "operation_key"), string(rs, "loop_id"),
                    nullableInteger(rs, "iteration"), string(rs, "action_id"))).toList();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "frozenKeys", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "frozenKeys", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 批量读取已授权范围的公开事件及精确 winner 证明；不读取整个会话私有历史。 */
    Map<SessionKey, Facts> publicFacts(List<ReadScope> scopes) {
        LOG.debug("公开会话事实批量读取进入 scopeCount={}", scopes.size());
        try {
            if (scopes.isEmpty()) return Map.of();
            return SessionObservationPublicFacts.read(scopedRows(scopes, batch -> mapper.publicFacts(parameters("scopes", batch))));
        } finally { LOG.debug("公开会话事实批量读取退出 scopeCount={}", scopes.size()); }
    }

    /** 原消息高水位与事件高水位独立冻结；合法主动助手消息保持同一公开来源。 */
    Map<SessionKey, List<MessageRow>> messages(List<ReadScope> scopes) {
        LOG.debug("会话消息批量读取进入 scopeCount={}", scopes.size());
        try {
            if (scopes.isEmpty()) return Map.of();
            Map<SessionKey, List<MessageRow>> result = new LinkedHashMap<>();
            for (var row : scopedRows(scopes, batch -> mapper.messages(parameters("scopes", batch)))) {
                var key = new SessionKey(number(row, "tenant_id").longValue(), string(row, "session_id"));
                result.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(new MessageRow(
                        number(row, "id").longValue(), string(row, "message_id"), string(row, "role"),
                        string(row, "content"), string(row, "root_input_id"), string(row, "run_id"),
                        string(row, "phase"), string(row, "state"), number(row, "message_version").longValue(),
                        string(row, "team_answer_ref"), string(row, "metadata_json"), string(row, "references_json"), instant(row, "created_at")));
            }
            result.replaceAll((key, rows) -> List.copyOf(rows));
            return Map.copyOf(result);
        } finally { LOG.debug("会话消息批量读取退出 scopeCount={}", scopes.size()); }
    }

    /** 捕获原消息ID上界；零仍是有效冻结值，下一页不得重新初始化。 */
    long messageUpperBound(long tenantId, String sessionId) {
        LOG.debug("会话消息高水位读取进入");
        try {
            Long upper = reads.read(tenantId, () -> mapper.messageUpperBound(parameters("tenantId", tenantId, "sessionId", sessionId)));
            return upper == null ? 0 : upper;
        } finally { LOG.debug("会话消息高水位读取退出"); }
    }

    /** 检查根输入是否属于指定租户与会话；不存在返回 false。 */
    boolean rootExists(long tenantId, String sessionId, String rootInputId) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "rootExists");
        try {
            Boolean exists = reads.read(tenantId, () -> mapper.rootExists(parameters("tenantId", Long.toString(tenantId), "sessionId", sessionId, "rootInputId", rootInputId)));
            return Boolean.TRUE.equals(exists);
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "rootExists", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "rootExists", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 读取指定根输入的已提交轨迹上界；无事件返回零。 */
    long traceUpperBound(long tenantId, String sessionId, String rootInputId) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "traceUpperBound");
        try {
            Long value = reads.read(tenantId, () -> mapper.traceUpperBound(parameters("tenantId", Long.toString(tenantId), "sessionId", sessionId, "rootInputId", rootInputId)));
            return value == null ? 0 : value;
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "traceUpperBound", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "traceUpperBound", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 在冻结上界内解析事件节点序列；不属于当前根输入时返回空值。 */
    Optional<Long> traceNodeSequence(
            long tenantId, String sessionId, String rootInputId, String nodeId, long upperBound) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "traceNodeSequence");
        try {
            List<Long> values = reads.read(tenantId, () -> mapper.traceNodeSequence(parameters("tenantId", Long.toString(tenantId), "sessionId", sessionId, "rootInputId", rootInputId, "nodeId", nodeId, "upperBound", upperBound)));
            return values.stream().findFirst();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "traceNodeSequence", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "traceNodeSequence", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 读取冻结上界内的后续事件及父节点关联；保持数据库事件顺序。 */
    List<TraceRow> trace(
            long tenantId, String sessionId, String rootInputId,
            long afterSequence, long upperBound, int limit) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "trace");
        try {
            return reads.read(tenantId, () -> mapper.trace(parameters("tenantId", Long.toString(tenantId), "sessionId", sessionId, "rootInputId", rootInputId, "afterSequence", afterSequence, "upperBound", upperBound, "limit", limit))).stream().map(rs -> new TraceRow(
                    number(rs, "session_sequence").longValue(), string(rs, "input_id"),
                    string(rs, "root_input_id"), string(rs, "run_id"),
                    string(rs, "execution_id"), string(rs, "action_id"),
                    string(rs, "event_id"), string(rs, "event_type"), string(rs, "trace_id"),
                    string(rs, "caused_by_event_id"), string(rs, "payload_schema_id"),
                    string(rs, "payload_canonical_json"), string(rs, "action_call_kind"),
                    string(rs, "parent_node_id"),
                    Instant.ofEpochSecond(number(rs, "occurred_at_epoch_second").longValue(),
                            number(rs, "occurred_at_nano").intValue()))).toList();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "trace", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "trace", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 批量读取根输入事件，可选序列上界固定读取范围；空集合返回空列表。 */
    List<RoundEventRow> roundEvents(long tenantId, String sessionId, List<String> rootInputIds) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "roundEvents");
        try {
            return roundEvents(tenantId, sessionId, rootInputIds, null);
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "roundEvents", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "roundEvents", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 批量读取根输入事件，可选序列上界固定读取范围；空集合返回空列表。 */
    List<RoundEventRow> roundEvents(
            long tenantId, String sessionId, List<String> rootInputIds, Long sequenceUpperBound) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "roundEvents");
        try {
            if (rootInputIds.isEmpty()) return List.of();
            return reads.read(tenantId, () -> mapper.roundEvents(parameters("tenantId", Long.toString(tenantId), "sessionId", sessionId, "roots", rootInputIds, "sequenceUpperBound", sequenceUpperBound))).stream().map(rs -> new RoundEventRow(
                            number(rs, "session_sequence").longValue(), string(rs, "root_input_id"),
                            string(rs, "run_id"), string(rs, "action_id"),
                            string(rs, "event_id"), string(rs, "payload_canonical_json"),
                            Instant.ofEpochSecond(number(rs, "occurred_at_epoch_second").longValue(),
                                    number(rs, "occurred_at_nano").intValue()))).toList();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "roundEvents", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "roundEvents", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 验证内容引用确属当前根输入；未知引用协议直接返回 false。 */
    boolean contentBelongsToRoot(long tenantId, String sessionId, String rootInputId, String contentRef) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "contentBelongsToRoot");
        try {
            if (contentRef == null || !contentRef.startsWith("session-event:")) return false;
            Integer count = reads.read(tenantId, () -> mapper.contentBelongsToRoot(parameters("tenantId", Long.toString(tenantId), "sessionId", sessionId, "rootInputId", rootInputId, "eventId", contentRef.substring("session-event:".length()))));
            return count != null && count == 1;
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "contentBelongsToRoot", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "contentBelongsToRoot", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 读取指定会话的有效模型计量；可选根输入和账本上界约束观测范围。 */
    List<UsageRow> usage(
            long tenantId, String sessionId, String rootInputId, Long attemptUpperBound) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "usage");
        try {
            return reads.read(tenantId, () -> mapper.usage(parameters("tenantId", tenantId, "sessionId", sessionId, "rootInputId", blankToNull(rootInputId), "attemptUpperBound", attemptUpperBound,
                            "modelRequestSchema", AgentExecutionCodec.MODEL_REQUEST_SCHEMA, "modelManifestSchema", AgentExecutionCodec.MODEL_MANIFEST_SCHEMA))).stream().map(rs -> new UsageRow(
                            number(rs, "attempt_ledger_id").longValue(), string(rs, "attempt_id"),
                            string(rs, "invocation_id"),
                            string(rs, "trace_node_id"), string(rs, "run_id"),
                            string(rs, "provider"), string(rs, "model"), string(rs, "call_point"),
                            string(rs, "usage_source"), nullableLong(rs, "prompt_tokens"),
                            nullableLong(rs, "completion_tokens"), nullableLong(rs, "cached_input_tokens"),
                            nullableLong(rs, "cache_write_input_tokens"),
                            nullableLong(rs, "reasoning_output_tokens"), instant(rs, "ingested_at"),
                            Boolean.TRUE.equals(rs.get("diagnostic_available")), string(rs,"source_session_id"), string(rs,"source_root_input_id"))).toList();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "usage", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "usage", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 将显式列映射转换为会话投影；必填列缺失不使用默认值掩盖数据错误。 */
    private SessionRow sessionRow(Map<String, Object> rs) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "sessionRow");
        try {
            return new SessionRow(
                    number(rs, "tenant_id").longValue(), string(rs, "tenant_name"), string(rs, "session_id"),
                    number(rs, "user_id").longValue(), string(rs, "user_name"), string(rs, "target_type"),
                    nullableLong(rs, "agent_id"), string(rs, "agent_name"), string(rs, "team_id"),
                    string(rs, "team_name"), nullableLong(rs, "channel_id"), string(rs, "channel_type"),
                    string(rs, "channel_name"), string(rs, "answer_status"),
                    string(rs, "execution_status"), string(rs, "summary_text"),
                    number(rs, "message_count").intValue(), number(rs, "root_input_count").intValue(),
                    string(rs, "active_execution_id"), instant(rs, "expired_at"),
                    instant(rs, "first_message_at"), instant(rs, "last_message_at"),
                    instant(rs, "created_at"), instant(rs, "updated_at"),
                    instant(rs, "last_activity_at"), number(rs, "total_count").longValue(),
                    string(rs, "latest_root_input_id"), number(rs, "event_upper_bound").longValue(),
                    number(rs, "message_upper_bound").longValue());
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "sessionRow", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "sessionRow", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 查询参数仅属于当前观测 Mapper；绝对时刻由 MyBatis InstantTypeHandler 绑定。 */
    private static Map<String, Object> parameters(Object... pairs) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "parameters");
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
            return values;
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "parameters", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "parameters", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 将可选过滤字符串去除首尾空白；空白值转换为 null 表示不限制。 */
    private static String blankToNull(String value) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "blankToNull");
        try {
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "blankToNull", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "blankToNull", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 读取可空文本列；SQL NULL 保持为 null，不记录内容。 */
    private static String string(Map<String, Object> row, String column) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "string");
        try {
            Object value = row.get(column);
            return value == null ? null : value.toString();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "string", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "string", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 读取数值列并保留其数值类型；SQL NULL 返回 null，类型错误直接失败。 */
    private static Number number(Map<String, Object> row, String column) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "number");
        try {
            return (Number) row.get(column);
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "number", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "number", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 读取可空长整数列；SQL NULL 保持为 null，不转换为零。 */
    private static Long nullableLong(Map<String, Object> row, String column) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "nullableLong");
        try {
            Number value = number(row, column);
            return value == null ? null : value.longValue();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "nullableLong", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "nullableLong", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 读取可空整数列；SQL NULL 保持为 null，不转换为零。 */
    private static Integer nullableInteger(Map<String, Object> row, String column) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "nullableInteger");
        try {
            Number value = number(row, column);
            return value == null ? null : value.intValue();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "nullableInteger", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "nullableInteger", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 将数据库时间转换为绝对时刻；SQL NULL 返回 null，保留驱动提供的精度。 */
    private static Instant instant(Map<String, Object> row, String column) {
        long invocation = CALLS.incrementAndGet();
        long started = LOG.isDebugEnabled() ? System.nanoTime() : 0;
        LOG.debug("持久化方法进入 invocation={} method={}", invocation, "instant");
        try {
            Object value = row.get(column);
            if (value == null) return null;
            if (value instanceof Instant instant) return instant;
            if (value instanceof java.time.OffsetDateTime timestamp) return timestamp.toInstant();
            return ((java.sql.Timestamp) value).toInstant();
        } catch (RuntimeException | Error failure) {
            LOG.debug("持久化方法异常 invocation={} method={} errorType={}",
                    invocation, "instant", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            LOG.debug("持久化方法退出 invocation={} method={} elapsedNanos={}",
                    invocation, "instant", started == 0 ? 0 : System.nanoTime() - started);
        }
    }

    /** 每个批次只绑定一个明确目标，公开消息多来源仍按同一数据库快照归并。 */
    private List<Map<String, Object>> scopedRows(List<ReadScope> scopes,
            java.util.function.Function<List<ReadScope>, List<Map<String, Object>>> query) {
        var groups = scopes.stream().collect(java.util.stream.Collectors.groupingBy(ReadScope::tenantId,
                LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        groups.forEach((tenant, batch) -> rows.addAll(reads.read(tenant, () -> query.apply(batch))));
        return rows;
    }

    record PagedSessions(List<SessionRow> rows, long total) {
    }

    record SessionRow(
            long tenantId, String tenantName, String sessionId, long userId, String userName,
            String targetType, Long agentId, String agentName, String teamId, String teamName,
            Long channelId, String channelType, String channelName, String answerStatus,
            String executionStatus, String summary, int messageCount, int rootInputCount,
            String activeExecutionId, Instant expiredAt, Instant firstMessageAt, Instant lastMessageAt,
            Instant createdAt, Instant updatedAt, Instant lastActivityAt, long total,
            String latestRootInputId, long eventUpperBound, long messageUpperBound) {
    }

    record RoundRow(
            String rootInputId, long roundNumber, long firstSequence, long lastSequence, Instant startedAt, Instant updatedAt) {
    }

    record RunRow(
            String runId, String rootInputId, String status, Instant startedAt, Instant updatedAt) {
    }

    record FrozenKeyRow(
            String sourceKind, String runId, String operationKey,
            String loopId, Integer iteration, String actionId) {
    }

    record MessageRow(
            long id, String messageId, String role, String content, String rootInputId,
            String runId, String phase, String state, long messageVersion, String teamAnswerRef,
            String metadataCanonicalJson, String referencesCanonicalJson, Instant createdAt) {
    }

    record TraceRow(
            long sequence, String inputId, String rootInputId, String runId, String executionId,
            String actionId, String eventId, String eventType, String traceId,
            String causedByEventId, String payloadSchemaId, String payloadCanonicalJson,
            String actionCallKind, String parentNodeId, Instant occurredAt) {
    }

    record RoundEventRow(
            long sequence, String rootInputId, String runId, String actionId,
            String eventId, String payloadCanonicalJson, Instant occurredAt) {
    }

    record UsageRow(
            long attemptSequence, String attemptId, String invocationId, String traceNodeId,
            String runId, String provider, String model, String callPoint, String usageSource,
            Long promptTokens, Long completionTokens,
            Long cachedInputTokens, Long cacheWriteInputTokens, Long reasoningOutputTokens,
            Instant ingestedAt, boolean diagnosticAvailable, String sourceSessionId, String sourceRootInputId) {
    }

}
