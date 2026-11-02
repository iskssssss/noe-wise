package com.skillforge.runtime.api.port;
import com.skillforge.runtime.api.PrincipalRef;

import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.InboundEnvelope;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RequestId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 保存 tenant-scoped callback registration 与已验证 delivery 的 durable READY 交接。
 *
 * <p>签名、schema 和当前授权在进入该 Port 前验证；正式 Workflow 消费者将原 callback
 * envelope 与 typed Wait 的接受结果在同一业务事务闭合，不使用旧输入准入 receipt。
 */
public interface CallbackRegistrationPort {

    /** 使用本次当前身份幂等注册 callback。 */
    CallbackRegisterResult register(RegisterCallback command);

    /** 按 tenant-scoped callback 身份读取注册事实。 */
    Optional<CallbackRegistration> find(CallbackRef callbackRef);

    /** 使用当前身份和 expected revision 修改注册状态。 */
    CallbackStatusResult changeStatus(ChangeCallbackStatus command);

    /** 原子校验并持久化一个已经完成签名/schema 验证的 delivery。 */
    CallbackDeliveryResult acceptVerified(VerifiedCallbackDelivery delivery);

    /** 按租户发现可领取的 READY delivery；发现结果不授予写权。 */
    List<CallbackReadyDelivery> scanReady(TenantId tenantId, int limit);

    /** 批量领取 READY delivery 并获得 fencing lease。 */
    CallbackClaimResult claimReady(CallbackClaimRequest request);

    /** 使用当前 callback delivery lease 续期。 */
    CallbackMutationResult renew(CallbackRenew command);

    /** 以稳定 InputId 重新安排原 callback envelope。 */
    CallbackMutationResult retry(CallbackRetry command);

    /** 将无法继续投递的 callback 写入 dead-letter。 */
    CallbackMutationResult deadLetter(CallbackDeadLetter command);

    /** 定义 callback registration 的三个且仅三个持久状态。 */
    enum CallbackRegistrationStatus {
        /** callback 可接收新 delivery。 */
        ACTIVE,
        /** callback 暂停接收新 delivery但可再次激活。 */
        SUSPENDED,
        /** callback 已永久撤销且不可复活。 */
        REVOKED
    }

    /** 定义 callback delivery 从交接到终结的五个持久状态。 */
    enum CallbackDeliveryStatus {
        /** 已验证 envelope 已持久化并等待领取。 */
        READY,
        /** 原 envelope 已由当前 lease holder 领取。 */
        PROCESSING,
        /** 原 envelope 已与实际 Workflow 等待的接受结果原子确认。 */
        DELIVERED,
        /** delivery 在进入 durable worker 前被稳定拒绝。 */
        REJECTED,
        /** delivery 已经确定不再重试。 */
        DEAD_LETTER
    }

    /** 固定一个租户内 callback registration 的稳定身份。 */
    record CallbackRef(TenantId tenantId, String callbackId) {
        /** 拒绝缺失租户或空白 callback 身份。 */
        public CallbackRef {
            tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
            callbackId = PortContracts.requireText(callbackId, "callbackId");
        }
    }

    /** 固定一个租户内 callback delivery 的稳定身份。 */
    record CallbackDeliveryRef(TenantId tenantId, String deliveryId) {
        /** 拒绝缺失租户或空白 delivery 身份。 */
        public CallbackDeliveryRef {
            tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
            deliveryId = PortContracts.requireText(deliveryId, "deliveryId");
        }
    }

    /** 引用租户凭据命名空间内不可反解的签名凭据。 */
    record CredentialRef(TenantId tenantId, String value) {
        /** 拒绝缺失租户或空白凭据引用。 */
        public CredentialRef {
            tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
            value = PortContracts.requireText(value, "value");
        }
    }

    /** 回调只对应真实Workflow Execution等待；原Run身份必须在注册时固定。 */
    sealed interface CallbackTarget
            permits CallbackTarget.ExecutionTarget {

        /** 固定 execution、owning Run、action key 与 expected execution revision。 */
        record ExecutionTarget(
                ExecutionRef executionRef,
                RunRef owningRunRef,
                String actionKey,
                long expectedExecutionRevision) implements CallbackTarget {
            /** Execution 与 owning Run 必须同租户且 revision 为正。 */
            public ExecutionTarget {
                executionRef = PortContracts.requireNonNull(executionRef, "executionRef");
                owningRunRef = PortContracts.requireNonNull(owningRunRef, "owningRunRef");
                actionKey = PortContracts.requireText(actionKey, "actionKey");
                expectedExecutionRevision = PortContracts.requirePositive(
                        expectedExecutionRevision, "expectedExecutionRevision");
                PortContracts.requireSameTenant(
                        executionRef.tenantId(),
                        owningRunRef.tenantId(),
                        "owningRunRef.tenantId");
            }
        }

    }

    /** 冻结 callback owner、目标、来源、payload schema、签名策略与凭据引用。 */
    record CallbackRegistrationDraft(
            CallbackRef callbackRef,
            SessionOwner owner,
            AgentId agentId,
            CallbackTarget target,
            String source,
            String payloadSchemaId,
            String signaturePolicyId,
            CredentialRef signingCredentialRef) {
        /** 注册身份、owner、target 与凭据引用必须封闭在同一 tenant。 */
        public CallbackRegistrationDraft {
            callbackRef = PortContracts.requireNonNull(callbackRef, "callbackRef");
            owner = PortContracts.requireNonNull(owner, "owner");
            owner.requireUser();
            agentId = PortContracts.requireNonNull(agentId, "agentId");
            target = PortContracts.requireNonNull(target, "target");
            source = PortContracts.requireText(source, "source");
            payloadSchemaId = PortContracts.requireText(payloadSchemaId, "payloadSchemaId");
            signaturePolicyId = PortContracts.requireText(
                    signaturePolicyId, "signaturePolicyId");
            signingCredentialRef = PortContracts.requireNonNull(
                    signingCredentialRef, "signingCredentialRef");
            TenantId tenantId = owner.sessionRef().tenantId();
            PortContracts.requireSameTenant(
                    tenantId, callbackRef.tenantId(), "callbackRef.tenantId");
            PortContracts.requireSameTenant(
                    tenantId,
                    signingCredentialRef.tenantId(),
                    "signingCredentialRef.tenantId");
            if (target instanceof CallbackTarget.ExecutionTarget executionTarget) {
                PortContracts.requireSameTenant(
                        tenantId,
                        executionTarget.executionRef().tenantId(),
                        "target.executionRef.tenantId");
            }
        }

        /** 直连 Agent callback 的快捷入口；团队 callback 必须显式提供实际 Agent。 */
        public CallbackRegistrationDraft(CallbackRef callbackRef, SessionOwner owner,
                CallbackTarget target, String source, String payloadSchemaId,
                String signaturePolicyId, CredentialRef signingCredentialRef) {
            this(callbackRef, owner, owner.target().requireAgent(), target, source, payloadSchemaId,
                    signaturePolicyId, signingCredentialRef);
        }
    }

    /** 保存 callback 注册草稿、原身份、状态、revision 与审计时间。 */
    record CallbackRegistration(
            CallbackRegistrationDraft draft,
            ResultRef causationIdentityRef,
            CallbackRegistrationStatus status,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        /** 拒绝缺失注册事实、非正 revision 或倒退的更新时间。 */
        public CallbackRegistration {
            draft = PortContracts.requireNonNull(draft, "draft");
            causationIdentityRef = PortContracts.requireNonNull(
                    causationIdentityRef, "causationIdentityRef");
            status = PortContracts.requireNonNull(status, "status");
            revision = PortContracts.requirePositive(revision, "revision");
            createdAt = PortContracts.requireNonNull(createdAt, "createdAt");
            updatedAt = PortContracts.requireNonNull(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not be before createdAt");
            }
        }
    }

    /** 使用当前身份和可选 owning Run 注册 callback。 */
    record RegisterCallback(
            CallbackRegistrationDraft registration,
            VerifiedIdentity requester,
            Optional<RunIdentity> owningRun,
            InputId causationInputId,
            TraceEnvelope trace,
            ResultRef causationIdentityRef,
            IdempotencyKey idempotencyKey,
            String requestFingerprint) {
        /** owner、target、owning Run 与因果身份必须形成不可替换的注册闭包。 */
        public RegisterCallback {
            registration = PortContracts.requireNonNull(registration, "registration");
            requester = PortContracts.requireNonNull(requester, "requester");
            owningRun = PortContracts.requireOptional(owningRun, "owningRun");
            causationInputId = PortContracts.requireNonNull(
                    causationInputId, "causationInputId");
            trace = PortContracts.requireNonNull(trace, "trace");
            causationIdentityRef = PortContracts.requireNonNull(
                    causationIdentityRef, "causationIdentityRef");
            idempotencyKey = PortContracts.requireNonNull(idempotencyKey, "idempotencyKey");
            requestFingerprint = PortContracts.requireText(
                    requestFingerprint, "requestFingerprint");
            if (!requester.owner().equals(registration.owner())) {
                throw new IllegalArgumentException("requester owner must match registration owner");
            }
            if (owningRun.isPresent()) {
                requireOwningRun(registration.owner(), owningRun.orElseThrow());
                if (!registration.agentId().equals(owningRun.orElseThrow().agentId())) {
                    throw new IllegalArgumentException("registration agentId must match owningRun");
                }
            }
            if (registration.target() instanceof CallbackTarget.ExecutionTarget executionTarget) {
                RunIdentity run = owningRun.orElseThrow(() -> new IllegalArgumentException(
                        "ExecutionTarget registration requires owningRun"));
                if (!run.runRef().equals(executionTarget.owningRunRef())) {
                    throw new IllegalArgumentException(
                            "owningRun runRef must match ExecutionTarget");
                }
            }
            if (owningRun.isPresent()) {
                if (!causationIdentityRef.equals(
                        owningRun.orElseThrow().causationIdentityRef())) {
                    throw new IllegalArgumentException(
                            "causationIdentityRef must match owningRun");
                }
            } else {
                if (requester.principal() instanceof PrincipalRef.SystemPrincipal) {
                    throw new IllegalArgumentException(
                            "SystemPrincipal registration without owningRun is forbidden");
                }
                if (!causationIdentityRef.equals(requester.verificationRef())) {
                    throw new IllegalArgumentException(
                            "causationIdentityRef must match requester verification");
                }
            }
        }
    }

    /** 封闭首次注册、稳定重放、幂等冲突和确定拒绝。 */
    sealed interface CallbackRegisterResult
            permits CallbackRegisterResult.Registered,
                    CallbackRegisterResult.Replayed,
                    CallbackRegisterResult.Conflict,
                    CallbackRegisterResult.Rejected {

        /** 表示 callback 首次注册为 ACTIVE。 */
        record Registered(CallbackRegistration registration)
                implements CallbackRegisterResult {
            /** 首次注册必须返回 ACTIVE 事实。 */
            public Registered {
                registration = PortContracts.requireNonNull(registration, "registration");
                if (registration.status() != CallbackRegistrationStatus.ACTIVE) {
                    throw new IllegalArgumentException("registered callback must be ACTIVE");
                }
            }
        }

        /** 表示同 callback、幂等键、指纹与原身份已经注册。 */
        record Replayed(CallbackRegistration registration)
                implements CallbackRegisterResult {
            /** 拒绝缺失已存在的注册事实。 */
            public Replayed {
                registration = PortContracts.requireNonNull(registration, "registration");
            }
        }

        /** 表示同一稳定注册身份被不同请求占用。 */
        record Conflict(CallbackRef callbackRef, ResultRef auditRef)
                implements CallbackRegisterResult {
            /** 拒绝缺失 callback 身份或审计事实。 */
            public Conflict {
                callbackRef = PortContracts.requireNonNull(callbackRef, "callbackRef");
                auditRef = PortContracts.requireNonNull(auditRef, "auditRef");
            }
        }

        /** 表示注册在形成事实前被确定拒绝。 */
        record Rejected(PortFailure failure) implements CallbackRegisterResult {
            /** 拒绝缺失失败事实。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }
    }

    /** 使用当前身份和 expected revision 修改 registration 状态。 */
    record ChangeCallbackStatus(
            CallbackRef callbackRef,
            VerifiedIdentity requester,
            long expectedRevision,
            CallbackRegistrationStatus targetStatus,
            InputId causationInputId,
            TraceEnvelope trace,
            RequestId operationId,
            String requestFingerprint) {
        /** 控制面身份必须与 callback 同租户且字段完整。 */
        public ChangeCallbackStatus {
            callbackRef = PortContracts.requireNonNull(callbackRef, "callbackRef");
            requester = PortContracts.requireNonNull(requester, "requester");
            expectedRevision = PortContracts.requirePositive(expectedRevision, "expectedRevision");
            targetStatus = PortContracts.requireNonNull(targetStatus, "targetStatus");
            causationInputId = PortContracts.requireNonNull(
                    causationInputId, "causationInputId");
            trace = PortContracts.requireNonNull(trace, "trace");
            operationId = PortContracts.requireNonNull(operationId, "operationId");
            requestFingerprint = PortContracts.requireText(
                    requestFingerprint, "requestFingerprint");
            PortContracts.requireSameTenant(
                    requester.owner().sessionRef().tenantId(),
                    callbackRef.tenantId(),
                    "callbackRef.tenantId");
        }
    }

    /** 封闭 registration 状态修改、重放、冲突、缺失与拒绝。 */
    sealed interface CallbackStatusResult
            permits CallbackStatusResult.Applied,
                    CallbackStatusResult.Replayed,
                    CallbackStatusResult.Conflict,
                    CallbackStatusResult.NotFound,
                    CallbackStatusResult.Rejected {

        /** 表示状态修改首次提交。 */
        record Applied(CallbackRegistration registration)
                implements CallbackStatusResult {
            /** 拒绝缺失注册事实。 */
            public Applied {
                registration = PortContracts.requireNonNull(registration, "registration");
            }
        }

        /** 表示相同 operationId 与 fingerprint 已提交。 */
        record Replayed(CallbackRegistration registration)
                implements CallbackStatusResult {
            /** 拒绝缺失注册事实。 */
            public Replayed {
                registration = PortContracts.requireNonNull(registration, "registration");
            }
        }

        /** 表示 expected revision 或 operation identity 冲突。 */
        record Conflict(CallbackRegistration current, ResultRef auditRef)
                implements CallbackStatusResult {
            /** 拒绝缺失当前注册或审计事实。 */
            public Conflict {
                current = PortContracts.requireNonNull(current, "current");
                auditRef = PortContracts.requireNonNull(auditRef, "auditRef");
            }
        }

        /** 表示指定 callback registration 不存在。 */
        record NotFound(PortFailure failure) implements CallbackStatusResult {
            /** 拒绝缺失失败事实。 */
            public NotFound {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }

        /** 表示当前授权或状态迁移被确定拒绝。 */
        record Rejected(PortFailure failure) implements CallbackStatusResult {
            /** 拒绝缺失失败事实。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }
    }

    /** 承载适配器已经完成原始签名与 schema 验证的 callback envelope。 */
    record VerifiedCallbackDelivery(
            CallbackRef callbackRef,
            long expectedRegistrationRevision,
            CallbackDeliveryRef deliveryRef,
            String requestFingerprint,
            InboundEnvelope envelope,
            Instant verifiedAt) {
        /** delivery、registration 与 CALLBACK envelope 必须同租户且字段完整。 */
        public VerifiedCallbackDelivery {
            callbackRef = PortContracts.requireNonNull(callbackRef, "callbackRef");
            expectedRegistrationRevision = PortContracts.requirePositive(
                    expectedRegistrationRevision, "expectedRegistrationRevision");
            deliveryRef = PortContracts.requireNonNull(deliveryRef, "deliveryRef");
            requestFingerprint = PortContracts.requireText(
                    requestFingerprint, "requestFingerprint");
            envelope = PortContracts.requireNonNull(envelope, "envelope");
            verifiedAt = PortContracts.requireNonNull(verifiedAt, "verifiedAt");
            PortContracts.requireSameTenant(
                    callbackRef.tenantId(), deliveryRef.tenantId(), "deliveryRef.tenantId");
            PortContracts.requireSameTenant(
                    callbackRef.tenantId(),
                    envelope.sessionRef().tenantId(),
                    "envelope.sessionRef.tenantId");
            if (envelope.kind() != InboundKind.CALLBACK) {
                throw new IllegalArgumentException("verified callback envelope must use CALLBACK kind");
            }
            if (envelope.causationIdentityRef().isEmpty()) {
                throw new IllegalArgumentException(
                        "verified callback envelope requires causationIdentityRef");
            }
        }
    }

    /** 保存 callback delivery 的状态、原 envelope 或稳定拒绝/dead-letter 事实。 */
    record CallbackDeliveryReceipt(
            CallbackRef callbackRef,
            CallbackDeliveryRef deliveryRef,
            CallbackDeliveryStatus status,
            long ledgerRevision,
            Optional<InboundEnvelope> envelope,
            Optional<String> rejectionReasonCode,
            Optional<ResultRef> auditRef,
            Instant recordedAt) {
        /** 五态字段矩阵必须防止被拒绝 payload 进入 durable worker。 */
        public CallbackDeliveryReceipt {
            callbackRef = PortContracts.requireNonNull(callbackRef, "callbackRef");
            deliveryRef = PortContracts.requireNonNull(deliveryRef, "deliveryRef");
            status = PortContracts.requireNonNull(status, "status");
            ledgerRevision = PortContracts.requirePositive(ledgerRevision, "ledgerRevision");
            envelope = PortContracts.requireOptional(envelope, "envelope");
            rejectionReasonCode = PortContracts.requireOptional(
                    rejectionReasonCode, "rejectionReasonCode")
                    .map(value -> PortContracts.requireText(value, "rejectionReasonCode"));
            auditRef = PortContracts.requireOptional(auditRef, "auditRef");
            recordedAt = PortContracts.requireNonNull(recordedAt, "recordedAt");
            PortContracts.requireSameTenant(
                    callbackRef.tenantId(), deliveryRef.tenantId(), "deliveryRef.tenantId");
            if (envelope.isPresent()) {
                PortContracts.requireSameTenant(
                        callbackRef.tenantId(),
                        envelope.orElseThrow().sessionRef().tenantId(),
                        "envelope.sessionRef.tenantId");
                if (envelope.orElseThrow().kind() != InboundKind.CALLBACK
                        || envelope.orElseThrow().causationIdentityRef().isEmpty()) {
                    throw new IllegalArgumentException(
                            "stored callback envelope must use CALLBACK kind and causation identity");
                }
            }
            requireDeliveryFields(status, envelope, rejectionReasonCode, auditRef);
        }
    }

    /** 封闭 READY、稳定重放、确定拒绝与 delivery 身份冲突。 */
    sealed interface CallbackDeliveryResult
            permits CallbackDeliveryResult.Ready,
                    CallbackDeliveryResult.Replayed,
                    CallbackDeliveryResult.Rejected,
                    CallbackDeliveryResult.Conflict {

        /** 表示已验证 delivery 首次形成 READY 记录。 */
        record Ready(CallbackDeliveryReceipt receipt) implements CallbackDeliveryResult {
            /** READY wrapper 只接受 READY receipt。 */
            public Ready {
                receipt = requireDeliveryStatus(
                        receipt, CallbackDeliveryStatus.READY, "ready receipt");
            }
        }

        /** 表示同 delivery 身份与 fingerprint 已经稳定处理。 */
        record Replayed(CallbackDeliveryReceipt receipt) implements CallbackDeliveryResult {
            /** 拒绝缺失原 delivery receipt。 */
            public Replayed {
                receipt = PortContracts.requireNonNull(receipt, "receipt");
            }
        }

        /** 表示 delivery 被注册状态、revision 或绑定规则稳定拒绝。 */
        record Rejected(CallbackDeliveryReceipt receipt) implements CallbackDeliveryResult {
            /** Rejected wrapper 只接受 REJECTED receipt。 */
            public Rejected {
                receipt = requireDeliveryStatus(
                        receipt, CallbackDeliveryStatus.REJECTED, "rejected receipt");
            }
        }

        /** 表示同一 delivery 身份被不同内容占用。 */
        record Conflict(CallbackDeliveryRef deliveryRef, ResultRef auditRef)
                implements CallbackDeliveryResult {
            /** 拒绝缺失 delivery 身份或审计事实。 */
            public Conflict {
                deliveryRef = PortContracts.requireNonNull(deliveryRef, "deliveryRef");
                auditRef = PortContracts.requireNonNull(auditRef, "auditRef");
            }
        }
    }

    /** 返回 READY 扫描所需的稳定身份、owner、revision 与记录时间。 */
    record CallbackReadyDelivery(
            CallbackDeliveryRef deliveryRef,
            CallbackRef callbackRef,
            SessionOwner owner,
            long ledgerRevision,
            Instant recordedAt) {
        /** 扫描结果的 delivery、callback 与 owner 必须同租户。 */
        public CallbackReadyDelivery {
            deliveryRef = PortContracts.requireNonNull(deliveryRef, "deliveryRef");
            callbackRef = PortContracts.requireNonNull(callbackRef, "callbackRef");
            owner = PortContracts.requireNonNull(owner, "owner");
            owner.requireUser();
            ledgerRevision = PortContracts.requirePositive(ledgerRevision, "ledgerRevision");
            recordedAt = PortContracts.requireNonNull(recordedAt, "recordedAt");
            TenantId tenantId = owner.sessionRef().tenantId();
            PortContracts.requireSameTenant(
                    tenantId, callbackRef.tenantId(), "callbackRef.tenantId");
            PortContracts.requireSameTenant(
                    tenantId, deliveryRef.tenantId(), "deliveryRef.tenantId");
        }
    }

    /** 限定一个租户、worker、批量上限和 lease 时长的 READY 领取请求。 */
    record CallbackClaimRequest(
            TenantId tenantId,
            String workerId,
            int limit,
            Duration leaseDuration) {
        /** 拒绝缺失租户/worker、非正批量上限或非正 lease。 */
        public CallbackClaimRequest {
            tenantId = PortContracts.requireNonNull(tenantId, "tenantId");
            workerId = PortContracts.requireText(workerId, "workerId");
            limit = requirePositiveInt(limit, "limit");
            leaseDuration = PortContracts.requirePositive(leaseDuration, "leaseDuration");
        }
    }

    /** 将 delivery 引用、通用 fence 与领取时 ledger revision 绑定为写权。 */
    record CallbackLease(
            CallbackDeliveryRef deliveryRef,
            LeaseFence fence,
            long ledgerRevision) {
        /** 拒绝跨租户 fence 或非正领取 revision。 */
        public CallbackLease {
            deliveryRef = PortContracts.requireNonNull(deliveryRef, "deliveryRef");
            fence = PortContracts.requireNonNull(fence, "fence");
            ledgerRevision = PortContracts.requirePositive(ledgerRevision, "ledgerRevision");
            PortContracts.requireSameTenant(
                    deliveryRef.tenantId(), fence.tenantId(), "fence.tenantId");
        }
    }

    /** 返回彼此精确绑定的 PROCESSING receipt 与当前 lease。 */
    record CallbackClaim(CallbackDeliveryReceipt receipt, CallbackLease lease) {
        /** receipt 的 delivery、revision 与 PROCESSING 状态必须匹配 lease。 */
        public CallbackClaim {
            receipt = PortContracts.requireNonNull(receipt, "receipt");
            lease = PortContracts.requireNonNull(lease, "lease");
            if (receipt.status() != CallbackDeliveryStatus.PROCESSING
                    || !receipt.deliveryRef().equals(lease.deliveryRef())
                    || receipt.ledgerRevision() != lease.ledgerRevision()) {
                throw new IllegalArgumentException(
                        "claimed callback receipt must match lease and be PROCESSING");
            }
        }
    }

    /** 封闭批量领取结果或当前无 READY delivery。 */
    sealed interface CallbackClaimResult
            permits CallbackClaimResult.Claimed,
                    CallbackClaimResult.Empty {

        /** 返回至少一个防御复制的 callback claim。 */
        record Claimed(List<CallbackClaim> claims) implements CallbackClaimResult {
            /** Claimed 不能用空列表冒充 Empty。 */
            public Claimed {
                claims = PortContracts.immutableDistinctList(claims, "claims");
                if (claims.isEmpty()) {
                    throw new IllegalArgumentException("claimed callback list must not be empty");
                }
            }
        }

        /** 表示当前租户没有可领取 delivery。 */
        record Empty() implements CallbackClaimResult {
        }
    }

    /** 使用幂等操作身份延长当前 callback lease。 */
    record CallbackRenew(
            CallbackLease lease,
            Duration extension,
            RequestId operationId,
            String requestFingerprint) {
        /** 拒绝缺失 lease/操作身份或非正续期时长。 */
        public CallbackRenew {
            lease = PortContracts.requireNonNull(lease, "lease");
            extension = PortContracts.requirePositive(extension, "extension");
            operationId = PortContracts.requireNonNull(operationId, "operationId");
            requestFingerprint = PortContracts.requireText(
                    requestFingerprint, "requestFingerprint");
        }
    }

    /** 使用稳定 InputId 与下一可用时间重新安排原 callback envelope。 */
    record CallbackRetry(
            CallbackLease lease,
            Instant availableAt,
            PortFailure failure,
            RequestId operationId,
            String requestFingerprint) {
        /** retry 只接受可按稳定键安全收敛的失败。 */
        public CallbackRetry {
            lease = PortContracts.requireNonNull(lease, "lease");
            availableAt = PortContracts.requireNonNull(availableAt, "availableAt");
            failure = requireRetryableFailure(failure, "failure");
            operationId = PortContracts.requireNonNull(operationId, "operationId");
            requestFingerprint = PortContracts.requireText(
                    requestFingerprint, "requestFingerprint");
        }
    }

    /** 使用确定不可重试失败终结当前 callback delivery。 */
    record CallbackDeadLetter(
            CallbackLease lease,
            PortFailure failure,
            RequestId operationId,
            String requestFingerprint) {
        /** dead-letter 只接受 RetryMode.NONE。 */
        public CallbackDeadLetter {
            lease = PortContracts.requireNonNull(lease, "lease");
            failure = requirePermanentFailure(failure, "failure");
            operationId = PortContracts.requireNonNull(operationId, "operationId");
            requestFingerprint = PortContracts.requireText(
                    requestFingerprint, "requestFingerprint");
        }
    }

    /** 封闭 delivery mutation 成功、重放、旧 lease、缺失与终态拒绝。 */
    sealed interface CallbackMutationResult
            permits CallbackMutationResult.Applied,
                    CallbackMutationResult.AlreadyApplied,
                    CallbackMutationResult.StaleLease,
                    CallbackMutationResult.NotFound,
                    CallbackMutationResult.Terminal,
                    CallbackMutationResult.Conflict,
                    CallbackMutationResult.Rejected {

        /** 表示 delivery mutation 首次提交。 */
        record Applied(CallbackDeliveryReceipt current)
                implements CallbackMutationResult {
            /** 拒绝缺失当前 delivery。 */
            public Applied {
                current = PortContracts.requireNonNull(current, "current");
            }
        }

        /** 表示相同 operationId 与 fingerprint 已提交。 */
        record AlreadyApplied(CallbackDeliveryReceipt current)
                implements CallbackMutationResult {
            /** 拒绝缺失当前 delivery。 */
            public AlreadyApplied {
                current = PortContracts.requireNonNull(current, "current");
            }
        }

        /** 表示输入 lease 已失去写权。 */
        record StaleLease(CallbackDeliveryReceipt current, PortFailure failure)
                implements CallbackMutationResult {
            /** 终态必须使用 Terminal，非终态才可返回 StaleLease。 */
            public StaleLease {
                current = PortContracts.requireNonNull(current, "current");
                failure = PortContracts.requireNonNull(failure, "failure");
                if (isDeliveryTerminal(current.status())) {
                    throw new IllegalArgumentException(
                            "terminal callback delivery must use Terminal result");
                }
            }
        }

        /** 表示指定 callback delivery 不存在。 */
        record NotFound(PortFailure failure) implements CallbackMutationResult {
            /** 拒绝缺失失败事实。 */
            public NotFound {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }

        /** 表示 delivery 已处于不可继续 mutation 的终态。 */
        record Terminal(CallbackDeliveryReceipt current)
                implements CallbackMutationResult {
            /** 终态结果只允许 DELIVERED、REJECTED 或 DEAD_LETTER。 */
            public Terminal {
                current = PortContracts.requireNonNull(current, "current");
                if (!isDeliveryTerminal(current.status())) {
                    throw new IllegalArgumentException(
                            "terminal callback delivery must be terminal");
                }
            }
        }

        /** 表示 operationId、fingerprint、delivery state 或 revision 冲突。 */
        record Conflict(CallbackDeliveryReceipt current, ResultRef auditRef)
                implements CallbackMutationResult {
            /** 拒绝缺失当前 delivery 或审计事实。 */
            public Conflict {
                current = PortContracts.requireNonNull(current, "current");
                auditRef = PortContracts.requireNonNull(auditRef, "auditRef");
            }
        }

        /** 表示 receipt、当前授权或 Runtime 策略被确定拒绝。 */
        record Rejected(PortFailure failure) implements CallbackMutationResult {
            /** 拒绝缺失失败事实。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }
    }

    /** 校验 owning Run 的完整 owner。 */
    private static void requireOwningRun(SessionOwner expectedOwner, RunIdentity owningRun) {
        owningRun = PortContracts.requireNonNull(owningRun, "owningRun");
        if (!expectedOwner.equals(owningRun.owner())) {
            throw new IllegalArgumentException("owningRun owner must match registration owner");
        }
    }

    /** 校验 delivery wrapper 与唯一允许的 receipt 状态一致。 */
    private static CallbackDeliveryReceipt requireDeliveryStatus(
            CallbackDeliveryReceipt receipt,
            CallbackDeliveryStatus expected,
            String field) {
        receipt = PortContracts.requireNonNull(receipt, field);
        if (receipt.status() != expected) {
            throw new IllegalArgumentException(field + " has wrong status");
        }
        return receipt;
    }

    /** 校验 callback delivery 五态对 envelope 与拒绝字段的封闭矩阵。 */
    private static void requireDeliveryFields(
            CallbackDeliveryStatus status,
            Optional<InboundEnvelope> envelope,
            Optional<String> rejectionReasonCode,
            Optional<ResultRef> auditRef) {
        if (status == CallbackDeliveryStatus.REJECTED) {
            if (envelope.isPresent()
                    || rejectionReasonCode.isEmpty()
                    || auditRef.isEmpty()) {
                throw new IllegalArgumentException(
                        "REJECTED delivery requires rejection facts without envelope");
            }
            return;
        }
        if (envelope.isEmpty()) {
            throw new IllegalArgumentException(status + " delivery requires envelope");
        }
        if (status == CallbackDeliveryStatus.DEAD_LETTER) {
            if (rejectionReasonCode.isEmpty() || auditRef.isEmpty()) {
                throw new IllegalArgumentException(
                        "DEAD_LETTER delivery requires failure reason and auditRef");
            }
        } else if (rejectionReasonCode.isPresent() || auditRef.isPresent()) {
            throw new IllegalArgumentException(
                    "non-rejected active delivery must not carry rejection fields");
        }
    }

    /** 校验 int 形式的 limit 为正。 */
    private static int requirePositiveInt(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    /** 校验 callback retry 只依赖稳定 InputId 幂等。 */
    private static PortFailure requireRetryableFailure(PortFailure failure, String field) {
        failure = PortContracts.requireNonNull(failure, field);
        if (failure.retryMode() != RetryMode.IDEMPOTENT_BY_STABLE_KEY) {
            throw new IllegalArgumentException(
                    field + " must use IDEMPOTENT_BY_STABLE_KEY");
        }
        return failure;
    }

    /** 校验 callback dead-letter 不再授权重试。 */
    private static PortFailure requirePermanentFailure(PortFailure failure, String field) {
        failure = PortContracts.requireNonNull(failure, field);
        if (failure.retryMode() != RetryMode.NONE) {
            throw new IllegalArgumentException(field + " must use NONE retry mode");
        }
        return failure;
    }

    /** 判断 callback delivery 是否已经终结。 */
    private static boolean isDeliveryTerminal(CallbackDeliveryStatus status) {
        return status == CallbackDeliveryStatus.DELIVERED
                || status == CallbackDeliveryStatus.REJECTED
                || status == CallbackDeliveryStatus.DEAD_LETTER;
    }
}
