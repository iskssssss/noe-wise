package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TraceEnvelope;
import java.time.Instant;
import java.util.Optional;

/** 保存 Session 的固定归属、人格版本与独立生命周期状态。 */
public interface SessionStorePort {

    /** 创建一个尚未进入 ACTIVE 的 Session，重复提交由适配器按稳定身份收敛。 */
    SessionCreateResult create(CreateSession command);

    /** 按完整 SessionOwner 查询当前 Session 记录。 */
    Optional<SessionRecord> find(SessionLookup query);

    /** 使用 expected status/revision 原子推进 Session 生命周期。 */
    SessionCasResult compareAndSet(ChangeSessionStatus command);

    /** 固定 Session 已创建、可受理输入与已关闭三种生命周期状态。 */
    enum SessionStatus {
        CREATED,
        ACTIVE,
        CLOSED
    }

    /** 保存 Session 的固定归属、渠道、scope、人格版本、状态与版本时间事实。 */
    record SessionRecord(
            SessionOwner owner,
            String channel,
            String scopeKey,
            String personaVersion,
            SessionStatus status,
            long sessionRevision,
            long lastSequence,
            Instant createdAt,
            Instant updatedAt) {

        /** 拒绝不完整身份、非正 revision、负 sequence 或倒置更新时间。 */
        public SessionRecord {
            owner = PortContracts.requireNonNull(owner, "owner");
            owner.requireUser();
            channel = PortContracts.requireText(channel, "channel");
            scopeKey = PortContracts.requireText(scopeKey, "scopeKey");
            personaVersion = PortContracts.requireText(personaVersion, "personaVersion");
            status = PortContracts.requireNonNull(status, "status");
            sessionRevision = PortContracts.requirePositive(sessionRevision, "sessionRevision");
            if (lastSequence < 0) {
                throw new IllegalArgumentException("lastSequence must not be negative");
            }
            createdAt = PortContracts.requireNonNull(createdAt, "createdAt");
            updatedAt = PortContracts.requireNonNull(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not be before createdAt");
            }
        }
    }

    /** 描述创建 Session 所需的固定归属、入口事实与审计时间。 */
    record CreateSession(
            SessionOwner owner,
            String channel,
            String scopeKey,
            String personaVersion,
            InputId causationInputId,
            TraceEnvelope trace,
            Instant requestedAt) {

        /** 拒绝缺失归属、入口配置、因果输入、trace 或审计时间。 */
        public CreateSession {
            owner = PortContracts.requireNonNull(owner, "owner");
            owner.requireUser();
            channel = PortContracts.requireText(channel, "channel");
            scopeKey = PortContracts.requireText(scopeKey, "scopeKey");
            personaVersion = PortContracts.requireText(personaVersion, "personaVersion");
            causationInputId = PortContracts.requireNonNull(causationInputId, "causationInputId");
            trace = PortContracts.requireNonNull(trace, "trace");
            requestedAt = PortContracts.requireNonNull(requestedAt, "requestedAt");
        }
    }

    /** 以完整 SessionOwner 定位一个 Session。 */
    record SessionLookup(SessionOwner owner) {

        /** 拒绝缺失 Session 归属。 */
        public SessionLookup {
            owner = PortContracts.requireNonNull(owner, "owner");
            owner.requireUser();
        }
    }

    /** 描述一次受 expected status/revision 保护的 Session 状态推进。 */
    record ChangeSessionStatus(
            SessionOwner owner,
            SessionStatus expectedStatus,
            SessionStatus targetStatus,
            long expectedRevision,
            InputId causationInputId,
            TraceEnvelope trace,
            Instant requestedAt) {

        /** 只接受 CREATED→ACTIVE 或 ACTIVE→CLOSED，并拒绝缺失 CAS/trace 事实。 */
        public ChangeSessionStatus {
            owner = PortContracts.requireNonNull(owner, "owner");
            owner.requireUser();
            expectedStatus = PortContracts.requireNonNull(expectedStatus, "expectedStatus");
            targetStatus = PortContracts.requireNonNull(targetStatus, "targetStatus");
            expectedRevision = PortContracts.requirePositive(expectedRevision, "expectedRevision");
            causationInputId = PortContracts.requireNonNull(causationInputId, "causationInputId");
            trace = PortContracts.requireNonNull(trace, "trace");
            requestedAt = PortContracts.requireNonNull(requestedAt, "requestedAt");
            boolean activating = expectedStatus == SessionStatus.CREATED
                    && targetStatus == SessionStatus.ACTIVE;
            boolean closing = expectedStatus == SessionStatus.ACTIVE
                    && targetStatus == SessionStatus.CLOSED;
            if (!activating && !closing) {
                throw new IllegalArgumentException("session status transition is not allowed");
            }
        }
    }

    /** 表达 Session 创建、幂等重放或稳定身份冲突。 */
    sealed interface SessionCreateResult
            permits SessionCreateResult.Created, SessionCreateResult.Replayed,
                    SessionCreateResult.Conflict {

        /** 返回首次创建的 Session 记录。 */
        record Created(SessionRecord session) implements SessionCreateResult {

            /** 拒绝缺失 Session 记录。 */
            public Created {
                session = PortContracts.requireNonNull(session, "session");
            }
        }

        /** 返回同一创建请求已经产生的 Session 记录。 */
        record Replayed(SessionRecord session) implements SessionCreateResult {

            /** 拒绝缺失 Session 记录。 */
            public Replayed {
                session = PortContracts.requireNonNull(session, "session");
            }
        }

        /**
         * 返回资源冲突可见 revision 与审计事实。
         *
         * <p>已有 Session 时取其 lifecycle revision；仅 owner root 已建立时固定为 1，
         * 该值只表示资源身份已占用，不表示 Session 记录已经存在。
         */
        record Conflict(long currentRevision, ResultRef auditRef) implements SessionCreateResult {

            /** 拒绝非正资源冲突可见 revision 或缺失审计引用。 */
            public Conflict {
                currentRevision = PortContracts.requirePositive(currentRevision, "currentRevision");
                auditRef = PortContracts.requireNonNull(auditRef, "auditRef");
            }
        }
    }

    /** 表达 Session CAS 已应用、重放、冲突、不存在或确定拒绝。 */
    sealed interface SessionCasResult
            permits SessionCasResult.Applied, SessionCasResult.Replayed,
                    SessionCasResult.Conflict, SessionCasResult.NotFound,
                    SessionCasResult.Rejected {

        /** 返回状态推进后的 Session 记录。 */
        record Applied(SessionRecord session) implements SessionCasResult {

            /** 拒绝缺失 Session 记录。 */
            public Applied {
                session = PortContracts.requireNonNull(session, "session");
            }
        }

        /** 返回同一 CAS 命令已经产生的 Session 记录。 */
        record Replayed(SessionRecord session) implements SessionCasResult {

            /** 拒绝缺失 Session 记录。 */
            public Replayed {
                session = PortContracts.requireNonNull(session, "session");
            }
        }

        /** 返回 CAS 冲突时的当前 Session 与审计事实。 */
        record Conflict(SessionRecord current, ResultRef auditRef) implements SessionCasResult {

            /** 拒绝缺失当前 Session 或审计引用。 */
            public Conflict {
                current = PortContracts.requireNonNull(current, "current");
                auditRef = PortContracts.requireNonNull(auditRef, "auditRef");
            }
        }

        /** 表示目标 Session 不存在。 */
        record NotFound(PortFailure failure) implements SessionCasResult {

            /** 拒绝缺失的稳定失败事实。 */
            public NotFound {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }

        /** 表示状态命令被策略或领域规则确定拒绝。 */
        record Rejected(PortFailure failure) implements SessionCasResult {

            /** 拒绝缺失的稳定失败事实。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }
    }
}
