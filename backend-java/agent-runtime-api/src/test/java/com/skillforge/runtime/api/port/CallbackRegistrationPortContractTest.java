package com.skillforge.runtime.api.port;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.PrincipalRef;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.AuthenticationRef;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.ExecutionRef;
import com.skillforge.runtime.api.InboundEnvelope;
import com.skillforge.runtime.api.InboundKind;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RequestId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunTarget;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.TypedPayload;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 冻结当前 Callback 注册、READY 交接与真实 Workflow 执行目标的公开契约。 */
class CallbackRegistrationPortContractTest {

    private static final TenantId TENANT = new TenantId("tenant-1");
    private static final TenantId OTHER_TENANT = new TenantId("tenant-2");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-1");
    private static final AgentId AGENT = new AgentId("agent-1");
    private static final SessionTarget TARGET = SessionTarget.agent(AGENT);
    private static final EndUserId END_USER = new EndUserId(7);
    private static final SessionOwner OWNER = SessionOwner.user(SESSION, TARGET, END_USER);
    private static final InputId INPUT = new InputId("input-1");
    private static final RunRef RUN = new RunRef(TENANT, "run-1");
    private static final ResultRef CAUSATION_REF = new ResultRef("identity-1");
    private static final Instant NOW = Instant.parse("2026-09-05T01:00:00Z");

    /** 冻结 CallbackRegistrationPort 的九个方法、全部 nested 类型与字段顺序。 */
    @Test
    void freezesCallbackRegistrationPortAbi() {
        assertExactPortMethods(CallbackRegistrationPort.class, List.of(
                "acceptVerified(VerifiedCallbackDelivery):CallbackDeliveryResult",
                "changeStatus(ChangeCallbackStatus):CallbackStatusResult",
                "claimReady(CallbackClaimRequest):CallbackClaimResult",
                "deadLetter(CallbackDeadLetter):CallbackMutationResult",
                "find(CallbackRef):java.util.Optional<CallbackRegistration>",
                "register(RegisterCallback):CallbackRegisterResult",
                "renew(CallbackRenew):CallbackMutationResult",
                "retry(CallbackRetry):CallbackMutationResult",
                "scanReady(TenantId,int):java.util.List<CallbackReadyDelivery>"));

        assertRecordComponents(SessionTarget.class,
                List.of("kind:SessionTarget.Kind", "id:String"));
        assertRecordComponents(SessionOwner.class,
                List.of("sessionRef:SessionRef", "subject:SessionSubject", "target:SessionTarget"));
        assertRecordComponents(RunIdentity.class, List.of(
                "owner:SessionOwner", "agentId:AgentId", "causationInputId:InputId",
                "runRef:RunRef", "trace:TraceEnvelope", "causationIdentityRef:ResultRef",
                "requester:RunRequester", "sourceMessageRef:java.util.Optional<SourceMessageRef>", "originRef:RunOriginRef"));

        // 精确递归集合避免未被方法引用的旁路 callback DTO 扩张公开 ABI。
        assertEquals(Set.of(
                        CallbackRegistrationPort.CallbackRegistrationStatus.class,
                        CallbackRegistrationPort.CallbackDeliveryStatus.class,
                        CallbackRegistrationPort.CallbackRef.class,
                        CallbackRegistrationPort.CallbackDeliveryRef.class,
                        CallbackRegistrationPort.CredentialRef.class,
                        CallbackRegistrationPort.CallbackTarget.class,
                        CallbackRegistrationPort.CallbackTarget.ExecutionTarget.class,
                        CallbackRegistrationPort.CallbackRegistrationDraft.class,
                        CallbackRegistrationPort.CallbackRegistration.class,
                        CallbackRegistrationPort.RegisterCallback.class,
                        CallbackRegistrationPort.CallbackRegisterResult.class,
                        CallbackRegistrationPort.CallbackRegisterResult.Registered.class,
                        CallbackRegistrationPort.CallbackRegisterResult.Replayed.class,
                        CallbackRegistrationPort.CallbackRegisterResult.Conflict.class,
                        CallbackRegistrationPort.CallbackRegisterResult.Rejected.class,
                        CallbackRegistrationPort.ChangeCallbackStatus.class,
                        CallbackRegistrationPort.CallbackStatusResult.class,
                        CallbackRegistrationPort.CallbackStatusResult.Applied.class,
                        CallbackRegistrationPort.CallbackStatusResult.Replayed.class,
                        CallbackRegistrationPort.CallbackStatusResult.Conflict.class,
                        CallbackRegistrationPort.CallbackStatusResult.NotFound.class,
                        CallbackRegistrationPort.CallbackStatusResult.Rejected.class,
                        CallbackRegistrationPort.VerifiedCallbackDelivery.class,
                        CallbackRegistrationPort.CallbackDeliveryReceipt.class,
                        CallbackRegistrationPort.CallbackDeliveryResult.class,
                        CallbackRegistrationPort.CallbackDeliveryResult.Ready.class,
                        CallbackRegistrationPort.CallbackDeliveryResult.Replayed.class,
                        CallbackRegistrationPort.CallbackDeliveryResult.Rejected.class,
                        CallbackRegistrationPort.CallbackDeliveryResult.Conflict.class,
                        CallbackRegistrationPort.CallbackReadyDelivery.class,
                        CallbackRegistrationPort.CallbackClaimRequest.class,
                        CallbackRegistrationPort.CallbackLease.class,
                        CallbackRegistrationPort.CallbackClaim.class,
                        CallbackRegistrationPort.CallbackClaimResult.class,
                        CallbackRegistrationPort.CallbackClaimResult.Claimed.class,
                        CallbackRegistrationPort.CallbackClaimResult.Empty.class,
                        CallbackRegistrationPort.CallbackRenew.class,
                        CallbackRegistrationPort.CallbackRetry.class,
                        CallbackRegistrationPort.CallbackDeadLetter.class,
                        CallbackRegistrationPort.CallbackMutationResult.class,
                        CallbackRegistrationPort.CallbackMutationResult.Applied.class,
                        CallbackRegistrationPort.CallbackMutationResult.AlreadyApplied.class,
                        CallbackRegistrationPort.CallbackMutationResult.StaleLease.class,
                        CallbackRegistrationPort.CallbackMutationResult.NotFound.class,
                        CallbackRegistrationPort.CallbackMutationResult.Terminal.class,
                        CallbackRegistrationPort.CallbackMutationResult.Conflict.class,
                        CallbackRegistrationPort.CallbackMutationResult.Rejected.class),
                publicNestedTypes(CallbackRegistrationPort.class));

        assertCallbackRecordComponents();
        assertCallbackPermittedTypes();
    }

    /** 冻结 registration 三态和 delivery 五态，不允许隐藏扩张。 */
    @Test
    void freezesCallbackStates() {
        assertEquals(
                List.of("ACTIVE", "SUSPENDED", "REVOKED"),
                Arrays.stream(CallbackRegistrationPort.CallbackRegistrationStatus.values())
                        .map(Enum::name)
                        .toList());
        assertEquals(
                List.of("READY", "PROCESSING", "DELIVERED", "REJECTED", "DEAD_LETTER"),
                Arrays.stream(CallbackRegistrationPort.CallbackDeliveryStatus.values())
                        .map(Enum::name)
                        .toList());
    }

    /** 验证 callback owner、target、credential 与注册当前身份形成同租户闭包。 */
    @Test
    void validatesCallbackRegistrationClosure() {
        CallbackRegistrationPort.CallbackRegistrationDraft executionDraft = executionDraft();
        CallbackRegistrationPort.RegisterCallback executionRegistration =
                register(executionDraft, identity(), Optional.of(runIdentity()), CAUSATION_REF);
        assertEquals(Optional.of(runIdentity()), executionRegistration.owningRun());

        // ExecutionTarget 必须携带同 owner、同 Run 的完整 owningRun。
        assertThrows(IllegalArgumentException.class, () -> register(
                executionDraft, identity(), Optional.empty(), identity().verificationRef()));
        RunIdentity wrongRun = new RunIdentity(OWNER, AGENT, INPUT, new RunRef(TENANT, "run-other"), trace(), CAUSATION_REF, new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), INPUT.value()));
        assertThrows(IllegalArgumentException.class, () -> register(
                executionDraft, identity(), Optional.of(wrongRun), CAUSATION_REF));
        assertThrows(IllegalArgumentException.class, () -> register(
                executionDraft, identity(), Optional.of(runIdentity()),
                new ResultRef("identity-other")));

        // 无 owning Run 时 SystemPrincipal 不能用当前系统身份补造历史 causation。
        assertThrows(IllegalArgumentException.class, () -> register(
                executionDraft(), systemIdentity(), Optional.empty(),
                systemIdentity().verificationRef()));

        // callback、Execution/Run 与 credential 任一跨租户都必须在构造期失败。
        assertThrows(IllegalArgumentException.class, () -> new CallbackRegistrationPort.CredentialRef(
                TENANT, " "));
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackTarget.ExecutionTarget(
                        new ExecutionRef(TENANT, "execution-1"),
                        new RunRef(OTHER_TENANT, "run-other"),
                        "action-1",
                        1));
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackTarget.ExecutionTarget(
                        new ExecutionRef(TENANT,"execution-1"),RUN," ",1));
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackTarget.ExecutionTarget(
                        new ExecutionRef(TENANT,"execution-1"),RUN,"action-1",0));
        assertThrows(IllegalArgumentException.class, () -> draft(
                new CallbackRegistrationPort.CallbackTarget.ExecutionTarget(new ExecutionRef(TENANT,"execution-1"),RUN,"action-1",1),
                new CallbackRegistrationPort.CredentialRef(
                        OTHER_TENANT, "credential-other")));
        assertThrows(IllegalArgumentException.class, () -> register(
                executionDraft, otherOwnerIdentity(), Optional.of(runIdentity()), CAUSATION_REF));

        CallbackRegistrationPort.CallbackRegistration registration = registration(
                CallbackRegistrationPort.CallbackRegistrationStatus.ACTIVE);
        assertEquals(CAUSATION_REF, registration.causationIdentityRef());
        assertThrows(IllegalArgumentException.class, () -> new CallbackRegistrationPort.CallbackRegistration(
                executionDraft,
                CAUSATION_REF,
                CallbackRegistrationPort.CallbackRegistrationStatus.ACTIVE,
                1,
                NOW,
                NOW.minusSeconds(1)));
    }

    /** 验证 callback 注册/状态结果与控制命令只承载已冻结的事实。 */
    @Test
    void validatesCallbackRegistrationResultsAndStatusCommands() {
        CallbackRegistrationPort.CallbackRegistration active = registration(
                CallbackRegistrationPort.CallbackRegistrationStatus.ACTIVE);
        CallbackRegistrationPort.CallbackRegistration revoked = registration(
                CallbackRegistrationPort.CallbackRegistrationStatus.REVOKED);
        PortFailure failure = permanentFailure();
        ResultRef auditRef = new ResultRef("callback-audit-1");

        assertDoesNotThrow(() -> new CallbackRegistrationPort.CallbackRegisterResult.Registered(active));
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackRegisterResult.Registered(revoked));
        assertEquals(active,
                new CallbackRegistrationPort.CallbackRegisterResult.Replayed(active).registration());
        assertEquals(callbackRef(),
                new CallbackRegistrationPort.CallbackRegisterResult.Conflict(
                        callbackRef(), auditRef).callbackRef());
        assertEquals(failure,
                new CallbackRegistrationPort.CallbackRegisterResult.Rejected(failure).failure());

        CallbackRegistrationPort.ChangeCallbackStatus command =
                new CallbackRegistrationPort.ChangeCallbackStatus(
                        callbackRef(),
                        identity(),
                        1,
                        CallbackRegistrationPort.CallbackRegistrationStatus.SUSPENDED,
                        INPUT,
                        trace(),
                        new RequestId("callback-status-op-1"),
                        "callback-status-fingerprint-1");
        assertEquals(1, command.expectedRevision());
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.ChangeCallbackStatus(
                        new CallbackRegistrationPort.CallbackRef(
                                OTHER_TENANT, "callback-other"),
                        identity(),
                        1,
                        CallbackRegistrationPort.CallbackRegistrationStatus.SUSPENDED,
                        INPUT,
                        trace(),
                        new RequestId("callback-status-op-2"),
                        "callback-status-fingerprint-2"));

        assertEquals(active,
                new CallbackRegistrationPort.CallbackStatusResult.Applied(active).registration());
        assertEquals(active,
                new CallbackRegistrationPort.CallbackStatusResult.Replayed(active).registration());
        assertEquals(active,
                new CallbackRegistrationPort.CallbackStatusResult.Conflict(
                        active, auditRef).current());
        assertEquals(failure,
                new CallbackRegistrationPort.CallbackStatusResult.NotFound(failure).failure());
        assertEquals(failure,
                new CallbackRegistrationPort.CallbackStatusResult.Rejected(failure).failure());
    }

    /** 验证已校验 callback 与 READY/PROCESSING/终态 receipt 的精确字段矩阵。 */
    @Test
    void validatesCallbackDeliveryReceiptMatrix() {
        CallbackRegistrationPort.VerifiedCallbackDelivery delivery =
                new CallbackRegistrationPort.VerifiedCallbackDelivery(
                        callbackRef(), 1, callbackDeliveryRef(),
                        "callback-delivery-fingerprint-1", callbackEnvelope(), NOW);
        assertEquals(InboundKind.CALLBACK, delivery.envelope().kind());
        assertEquals(INPUT, delivery.envelope().inputId());
        var ready = callbackReceipt(CallbackRegistrationPort.CallbackDeliveryStatus.READY,
                Optional.of(delivery.envelope()), Optional.empty(), Optional.empty());
        assertEquals(INPUT, ready.envelope().orElseThrow().inputId());
        assertEquals(new RunTarget.ExistingRun(RUN), ready.envelope().orElseThrow().runTarget());

        // 已验证 delivery 必须携带 CALLBACK envelope、同租户引用与历史 causation。
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.VerifiedCallbackDelivery(
                        callbackRef(), 1, callbackDeliveryRef(),
                        "callback-delivery-fingerprint-2",
                        envelope(InboundKind.MESSAGE, TENANT, Optional.of(CAUSATION_REF)),
                        NOW));
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.VerifiedCallbackDelivery(
                        callbackRef(), 1,
                        new CallbackRegistrationPort.CallbackDeliveryRef(
                                OTHER_TENANT, "delivery-other"),
                        "callback-delivery-fingerprint-3", callbackEnvelope(), NOW));
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.VerifiedCallbackDelivery(
                        callbackRef(), 1, callbackDeliveryRef(),
                        "callback-delivery-fingerprint-4",
                        envelope(InboundKind.CALLBACK, TENANT, Optional.empty()),
                        NOW));

        // READY/PROCESSING/DELIVERED 仅保留原 envelope，不携带拒绝字段。
        for (CallbackRegistrationPort.CallbackDeliveryStatus status : List.of(
                CallbackRegistrationPort.CallbackDeliveryStatus.READY,
                CallbackRegistrationPort.CallbackDeliveryStatus.PROCESSING,
                CallbackRegistrationPort.CallbackDeliveryStatus.DELIVERED)) {
            assertDoesNotThrow(() -> callbackReceipt(
                    status, Optional.of(callbackEnvelope()), Optional.empty(), Optional.empty()));
            assertThrows(IllegalArgumentException.class, () -> callbackReceipt(
                    status,
                    Optional.of(callbackEnvelope()),
                    Optional.of("unexpected_failure"),
                    Optional.of(new ResultRef("unexpected-audit"))));
        }

        // REJECTED 不保存 envelope；DEAD_LETTER 保留 envelope 和最终失败引用。
        assertDoesNotThrow(() -> callbackReceipt(
                CallbackRegistrationPort.CallbackDeliveryStatus.REJECTED,
                Optional.empty(),
                Optional.of("registration_rejected"),
                Optional.of(new ResultRef("callback-rejected-audit"))));
        assertDoesNotThrow(() -> callbackReceipt(
                CallbackRegistrationPort.CallbackDeliveryStatus.DEAD_LETTER,
                Optional.of(callbackEnvelope()),
                Optional.of("callback_dead_letter"),
                Optional.of(new ResultRef("callback-dead-audit"))));
        assertThrows(IllegalArgumentException.class, () -> callbackReceipt(
                CallbackRegistrationPort.CallbackDeliveryStatus.REJECTED,
                Optional.of(callbackEnvelope()),
                Optional.of("registration_rejected"),
                Optional.of(new ResultRef("callback-rejected-audit"))));
        assertThrows(IllegalArgumentException.class, () -> callbackReceipt(
                CallbackRegistrationPort.CallbackDeliveryStatus.DEAD_LETTER,
                Optional.of(callbackEnvelope()), Optional.empty(), Optional.empty()));
    }

    /** 验证 callback scan/claim、renew、retry、dead-letter 与七叶 mutation 结果。 */
    @Test
    void validatesCallbackClaimAndMutationClosure() {
        CallbackRegistrationPort.CallbackReadyDelivery ready =
                new CallbackRegistrationPort.CallbackReadyDelivery(
                        callbackDeliveryRef(), callbackRef(), OWNER, 1, NOW);
        assertEquals(OWNER, ready.owner());
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackReadyDelivery(
                        new CallbackRegistrationPort.CallbackDeliveryRef(
                                OTHER_TENANT, "delivery-other"),
                        callbackRef(), OWNER, 1, NOW));

        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackClaimRequest(
                        TENANT, "worker-1", 0, Duration.ofSeconds(30)));
        CallbackRegistrationPort.CallbackLease lease = callbackLease();
        CallbackRegistrationPort.CallbackDeliveryReceipt processing = callbackReceipt(
                CallbackRegistrationPort.CallbackDeliveryStatus.PROCESSING,
                Optional.of(callbackEnvelope()), Optional.empty(), Optional.empty());
        CallbackRegistrationPort.CallbackClaim claim =
                new CallbackRegistrationPort.CallbackClaim(processing, lease);
        assertEquals(lease, claim.lease());
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackClaim(
                        callbackReceipt(
                                CallbackRegistrationPort.CallbackDeliveryStatus.READY,
                                Optional.of(callbackEnvelope()),
                                Optional.empty(),
                                Optional.empty()),
                        lease));

        List<CallbackRegistrationPort.CallbackClaim> mutable = new ArrayList<>();
        mutable.add(claim);
        CallbackRegistrationPort.CallbackClaimResult.Claimed copied =
                new CallbackRegistrationPort.CallbackClaimResult.Claimed(mutable);
        mutable.clear();
        assertEquals(List.of(claim), copied.claims());
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackClaimResult.Claimed(List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackClaimResult.Claimed(List.of(claim, claim)));
        assertDoesNotThrow(CallbackRegistrationPort.CallbackClaimResult.Empty::new);

        CallbackRegistrationPort.CallbackRenew renew =
                new CallbackRegistrationPort.CallbackRenew(
                        lease, Duration.ofSeconds(15),
                        new RequestId("callback-renew-op-1"), "callback-renew-fingerprint-1");
        assertEquals(Duration.ofSeconds(15), renew.extension());

        assertDoesNotThrow(() -> new CallbackRegistrationPort.CallbackRetry(
                lease, NOW.plusSeconds(30), retryableFailure(),
                new RequestId("callback-retry-op-1"), "callback-retry-fingerprint-1"));
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackRetry(
                        lease, NOW.plusSeconds(30), permanentFailure(),
                        new RequestId("callback-retry-op-2"), "callback-retry-fingerprint-2"));
        assertDoesNotThrow(() -> new CallbackRegistrationPort.CallbackDeadLetter(
                lease, permanentFailure(),
                new RequestId("callback-dead-op-1"), "callback-dead-fingerprint-1"));
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackDeadLetter(
                        lease, retryableFailure(),
                        new RequestId("callback-dead-op-2"), "callback-dead-fingerprint-2"));

        CallbackRegistrationPort.CallbackDeliveryReceipt delivered = callbackReceipt(
                CallbackRegistrationPort.CallbackDeliveryStatus.DELIVERED,
                Optional.of(callbackEnvelope()), Optional.empty(), Optional.empty());
        ResultRef audit = new ResultRef("callback-mutation-audit");
        assertEquals(processing,
                new CallbackRegistrationPort.CallbackMutationResult.Applied(processing).current());
        assertEquals(processing,
                new CallbackRegistrationPort.CallbackMutationResult.AlreadyApplied(processing).current());
        assertEquals(processing,
                new CallbackRegistrationPort.CallbackMutationResult.StaleLease(
                        processing, permanentFailure()).current());
        assertEquals(permanentFailure(),
                new CallbackRegistrationPort.CallbackMutationResult.NotFound(
                        permanentFailure()).failure());
        assertEquals(delivered,
                new CallbackRegistrationPort.CallbackMutationResult.Terminal(delivered).current());
        assertEquals(processing,
                new CallbackRegistrationPort.CallbackMutationResult.Conflict(
                        processing, audit).current());
        assertEquals(permanentFailure(),
                new CallbackRegistrationPort.CallbackMutationResult.Rejected(
                        permanentFailure()).failure());
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackMutationResult.Terminal(processing));
        assertThrows(IllegalArgumentException.class, () ->
                new CallbackRegistrationPort.CallbackMutationResult.StaleLease(
                        delivered, permanentFailure()));
    }

    /** 禁止 callback SPI 回流 secret、HTTP/header、业务 cursor、Map 或宿主类型。 */
    @Test
    void rejectsForbiddenCallbackSurface() {
        Set<Class<?>> publicTypes = new HashSet<>();
        publicTypes.add(CallbackRegistrationPort.class);
        publicTypes.addAll(publicNestedTypes(CallbackRegistrationPort.class));

        // 名称扫描用于阻止敏感载荷、协议对象和 cursor 责任进入公开 SPI。
        for (Class<?> type : publicTypes) {
            assertFalse(type.getSimpleName().matches(
                    "(?i).*(secret|header|http|cursor|repository|mapper|entity|do)$"),
                    type.getName());
            RecordComponent[] components = type.getRecordComponents();
            if (components != null) {
                for (RecordComponent component : components) {
                    assertFalse(component.getName().matches(
                            "(?i).*(secret|header|http|cursor|rawpayload).*"),
                            type.getName() + "." + component.getName());
                    assertFalse(stableTypeName(component).contains("java.util.Map"),
                            type.getName() + "." + component.getName());
                }
            }
        }
    }

    /** 逐项冻结 Callback records 的字段名称、顺序与泛型参数。 */
    private static void assertCallbackRecordComponents() {
        assertRecordComponents(CallbackRegistrationPort.CallbackRef.class,
                List.of("tenantId:TenantId", "callbackId:String"));
        assertRecordComponents(CallbackRegistrationPort.CallbackDeliveryRef.class,
                List.of("tenantId:TenantId", "deliveryId:String"));
        assertRecordComponents(CallbackRegistrationPort.CredentialRef.class,
                List.of("tenantId:TenantId", "value:String"));
        assertRecordComponents(CallbackRegistrationPort.CallbackTarget.ExecutionTarget.class,
                List.of("executionRef:ExecutionRef", "owningRunRef:RunRef",
                        "actionKey:String", "expectedExecutionRevision:long"));
        assertRecordComponents(CallbackRegistrationPort.CallbackRegistrationDraft.class, List.of(
                "callbackRef:CallbackRef", "owner:SessionOwner", "agentId:AgentId", "target:CallbackTarget",
                "source:String", "payloadSchemaId:String", "signaturePolicyId:String",
                "signingCredentialRef:CredentialRef"));
        assertRecordComponents(CallbackRegistrationPort.CallbackRegistration.class, List.of(
                "draft:CallbackRegistrationDraft", "causationIdentityRef:ResultRef",
                "status:CallbackRegistrationStatus", "revision:long",
                "createdAt:java.time.Instant", "updatedAt:java.time.Instant"));
        assertRecordComponents(CallbackRegistrationPort.RegisterCallback.class, List.of(
                "registration:CallbackRegistrationDraft", "requester:VerifiedIdentity",
                "owningRun:java.util.Optional<RunIdentity>", "causationInputId:InputId",
                "trace:TraceEnvelope", "causationIdentityRef:ResultRef",
                "idempotencyKey:IdempotencyKey", "requestFingerprint:String"));
        assertRecordComponents(CallbackRegistrationPort.ChangeCallbackStatus.class, List.of(
                "callbackRef:CallbackRef", "requester:VerifiedIdentity",
                "expectedRevision:long", "targetStatus:CallbackRegistrationStatus",
                "causationInputId:InputId", "trace:TraceEnvelope",
                "operationId:RequestId", "requestFingerprint:String"));
        assertRecordComponents(CallbackRegistrationPort.VerifiedCallbackDelivery.class, List.of(
                "callbackRef:CallbackRef", "expectedRegistrationRevision:long",
                "deliveryRef:CallbackDeliveryRef", "requestFingerprint:String",
                "envelope:InboundEnvelope", "verifiedAt:java.time.Instant"));
        assertRecordComponents(CallbackRegistrationPort.CallbackDeliveryReceipt.class, List.of(
                "callbackRef:CallbackRef", "deliveryRef:CallbackDeliveryRef",
                "status:CallbackDeliveryStatus", "ledgerRevision:long",
                "envelope:java.util.Optional<InboundEnvelope>",
                "rejectionReasonCode:java.util.Optional<String>",
                "auditRef:java.util.Optional<ResultRef>", "recordedAt:java.time.Instant"));
        assertRecordComponents(CallbackRegistrationPort.CallbackReadyDelivery.class, List.of(
                "deliveryRef:CallbackDeliveryRef", "callbackRef:CallbackRef",
                "owner:SessionOwner", "ledgerRevision:long", "recordedAt:java.time.Instant"));
        assertRecordComponents(CallbackRegistrationPort.CallbackClaimRequest.class, List.of(
                "tenantId:TenantId", "workerId:String", "limit:int",
                "leaseDuration:java.time.Duration"));
        assertRecordComponents(CallbackRegistrationPort.CallbackLease.class, List.of(
                "deliveryRef:CallbackDeliveryRef", "fence:LeaseFence", "ledgerRevision:long"));
        assertRecordComponents(CallbackRegistrationPort.CallbackClaim.class,
                List.of("receipt:CallbackDeliveryReceipt", "lease:CallbackLease"));
        assertRecordComponents(CallbackRegistrationPort.CallbackRenew.class, List.of(
                "lease:CallbackLease", "extension:java.time.Duration",
                "operationId:RequestId", "requestFingerprint:String"));
        assertRecordComponents(CallbackRegistrationPort.CallbackRetry.class, List.of(
                "lease:CallbackLease", "availableAt:java.time.Instant", "failure:PortFailure",
                "operationId:RequestId", "requestFingerprint:String"));
        assertRecordComponents(CallbackRegistrationPort.CallbackDeadLetter.class, List.of(
                "lease:CallbackLease", "failure:PortFailure",
                "operationId:RequestId", "requestFingerprint:String"));
        assertCallbackResultRecordComponents();
    }

    /** 冻结 Callback 各 sealed result 叶子 records 的精确字段。 */
    private static void assertCallbackResultRecordComponents() {
        assertRecordComponents(CallbackRegistrationPort.CallbackRegisterResult.Registered.class,
                List.of("registration:CallbackRegistration"));
        assertRecordComponents(CallbackRegistrationPort.CallbackRegisterResult.Replayed.class,
                List.of("registration:CallbackRegistration"));
        assertRecordComponents(CallbackRegistrationPort.CallbackRegisterResult.Conflict.class,
                List.of("callbackRef:CallbackRef", "auditRef:ResultRef"));
        assertRecordComponents(CallbackRegistrationPort.CallbackRegisterResult.Rejected.class,
                List.of("failure:PortFailure"));
        assertRecordComponents(CallbackRegistrationPort.CallbackStatusResult.Applied.class,
                List.of("registration:CallbackRegistration"));
        assertRecordComponents(CallbackRegistrationPort.CallbackStatusResult.Replayed.class,
                List.of("registration:CallbackRegistration"));
        assertRecordComponents(CallbackRegistrationPort.CallbackStatusResult.Conflict.class,
                List.of("current:CallbackRegistration", "auditRef:ResultRef"));
        assertRecordComponents(CallbackRegistrationPort.CallbackStatusResult.NotFound.class,
                List.of("failure:PortFailure"));
        assertRecordComponents(CallbackRegistrationPort.CallbackStatusResult.Rejected.class,
                List.of("failure:PortFailure"));
        assertRecordComponents(CallbackRegistrationPort.CallbackDeliveryResult.Ready.class,
                List.of("receipt:CallbackDeliveryReceipt"));
        assertRecordComponents(CallbackRegistrationPort.CallbackDeliveryResult.Replayed.class,
                List.of("receipt:CallbackDeliveryReceipt"));
        assertRecordComponents(CallbackRegistrationPort.CallbackDeliveryResult.Rejected.class,
                List.of("receipt:CallbackDeliveryReceipt"));
        assertRecordComponents(CallbackRegistrationPort.CallbackDeliveryResult.Conflict.class,
                List.of("deliveryRef:CallbackDeliveryRef", "auditRef:ResultRef"));
        assertRecordComponents(CallbackRegistrationPort.CallbackClaimResult.Claimed.class,
                List.of("claims:java.util.List<CallbackClaim>"));
        assertRecordComponents(CallbackRegistrationPort.CallbackClaimResult.Empty.class, List.of());
        for (Class<? extends Record> type : List.of(
                CallbackRegistrationPort.CallbackMutationResult.Applied.class,
                CallbackRegistrationPort.CallbackMutationResult.AlreadyApplied.class,
                CallbackRegistrationPort.CallbackMutationResult.Terminal.class)) {
            assertRecordComponents(type, List.of("current:CallbackDeliveryReceipt"));
        }
        assertRecordComponents(CallbackRegistrationPort.CallbackMutationResult.StaleLease.class,
                List.of("current:CallbackDeliveryReceipt", "failure:PortFailure"));
        assertRecordComponents(CallbackRegistrationPort.CallbackMutationResult.NotFound.class,
                List.of("failure:PortFailure"));
        assertRecordComponents(CallbackRegistrationPort.CallbackMutationResult.Conflict.class,
                List.of("current:CallbackDeliveryReceipt", "auditRef:ResultRef"));
        assertRecordComponents(CallbackRegistrationPort.CallbackMutationResult.Rejected.class,
                List.of("failure:PortFailure"));
    }

    /** 冻结 Callback 各 sealed union 的 permits 集合。 */
    private static void assertCallbackPermittedTypes() {
        assertPermitted(CallbackRegistrationPort.CallbackTarget.class, Set.of(
                CallbackRegistrationPort.CallbackTarget.ExecutionTarget.class));
        assertPermitted(CallbackRegistrationPort.CallbackRegisterResult.class, Set.of(
                CallbackRegistrationPort.CallbackRegisterResult.Registered.class,
                CallbackRegistrationPort.CallbackRegisterResult.Replayed.class,
                CallbackRegistrationPort.CallbackRegisterResult.Conflict.class,
                CallbackRegistrationPort.CallbackRegisterResult.Rejected.class));
        assertPermitted(CallbackRegistrationPort.CallbackStatusResult.class, Set.of(
                CallbackRegistrationPort.CallbackStatusResult.Applied.class,
                CallbackRegistrationPort.CallbackStatusResult.Replayed.class,
                CallbackRegistrationPort.CallbackStatusResult.Conflict.class,
                CallbackRegistrationPort.CallbackStatusResult.NotFound.class,
                CallbackRegistrationPort.CallbackStatusResult.Rejected.class));
        assertPermitted(CallbackRegistrationPort.CallbackDeliveryResult.class, Set.of(
                CallbackRegistrationPort.CallbackDeliveryResult.Ready.class,
                CallbackRegistrationPort.CallbackDeliveryResult.Replayed.class,
                CallbackRegistrationPort.CallbackDeliveryResult.Rejected.class,
                CallbackRegistrationPort.CallbackDeliveryResult.Conflict.class));
        assertPermitted(CallbackRegistrationPort.CallbackClaimResult.class, Set.of(
                CallbackRegistrationPort.CallbackClaimResult.Claimed.class,
                CallbackRegistrationPort.CallbackClaimResult.Empty.class));
        assertPermitted(CallbackRegistrationPort.CallbackMutationResult.class, Set.of(
                CallbackRegistrationPort.CallbackMutationResult.Applied.class,
                CallbackRegistrationPort.CallbackMutationResult.AlreadyApplied.class,
                CallbackRegistrationPort.CallbackMutationResult.StaleLease.class,
                CallbackRegistrationPort.CallbackMutationResult.NotFound.class,
                CallbackRegistrationPort.CallbackMutationResult.Terminal.class,
                CallbackRegistrationPort.CallbackMutationResult.Conflict.class,
                CallbackRegistrationPort.CallbackMutationResult.Rejected.class));
    }

    /** 构造 callback registration 引用。 */
    private static CallbackRegistrationPort.CallbackRef callbackRef() {
        return new CallbackRegistrationPort.CallbackRef(TENANT, "callback-1");
    }

    /** 构造 callback delivery 引用。 */
    private static CallbackRegistrationPort.CallbackDeliveryRef callbackDeliveryRef() {
        return new CallbackRegistrationPort.CallbackDeliveryRef(TENANT, "delivery-1");
    }

    /** 构造绑定 Workflow Execution 与 owning Run 的 callback 草稿。 */
    private static CallbackRegistrationPort.CallbackRegistrationDraft executionDraft() {
        return draft(
                new CallbackRegistrationPort.CallbackTarget.ExecutionTarget(
                        new ExecutionRef(TENANT, "execution-1"), RUN, "action-1", 3),
                new CallbackRegistrationPort.CredentialRef(TENANT, "credential-1"));
    }

    /** 构造可变 target/credential 的 callback 草稿。 */
    private static CallbackRegistrationPort.CallbackRegistrationDraft draft(
            CallbackRegistrationPort.CallbackTarget target,
            CallbackRegistrationPort.CredentialRef credentialRef) {
        return new CallbackRegistrationPort.CallbackRegistrationDraft(
                callbackRef(), OWNER, target, "callback-source", "callback.payload.v1",
                "callback-signature-v1", credentialRef);
    }

    /** 构造完整 callback 注册命令。 */
    private static CallbackRegistrationPort.RegisterCallback register(
            CallbackRegistrationPort.CallbackRegistrationDraft draft,
            VerifiedIdentity requester,
            Optional<RunIdentity> owningRun,
            ResultRef causationIdentityRef) {
        return new CallbackRegistrationPort.RegisterCallback(
                draft, requester, owningRun, INPUT, trace(), causationIdentityRef,
                new IdempotencyKey("callback-key-1"), "callback-register-fingerprint-1");
    }

    /** 构造指定状态的 callback 注册事实。 */
    private static CallbackRegistrationPort.CallbackRegistration registration(
            CallbackRegistrationPort.CallbackRegistrationStatus status) {
        return new CallbackRegistrationPort.CallbackRegistration(
                executionDraft(), CAUSATION_REF, status, 1, NOW, NOW);
    }

    /** 构造指定五态字段组合的 callback delivery receipt。 */
    private static CallbackRegistrationPort.CallbackDeliveryReceipt callbackReceipt(
            CallbackRegistrationPort.CallbackDeliveryStatus status,
            Optional<InboundEnvelope> envelope,
            Optional<String> reasonCode,
            Optional<ResultRef> auditRef) {
        return new CallbackRegistrationPort.CallbackDeliveryReceipt(
                callbackRef(), callbackDeliveryRef(), status, 1,
                envelope, reasonCode, auditRef, NOW);
    }

    /** 构造当前 callback delivery lease。 */
    private static CallbackRegistrationPort.CallbackLease callbackLease() {
        return new CallbackRegistrationPort.CallbackLease(
                callbackDeliveryRef(), fence(TENANT), 1);
    }

    /** 构造已验证终端用户身份。 */
    private static VerifiedIdentity identity() {
        return new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(TENANT, END_USER),
                OWNER,
                Set.of("callback:register", "callback:manage"),
                1,
                new ResultRef("identity-current"));
    }

    /** 构造无 owning Run 时禁止注册的系统身份。 */
    private static VerifiedIdentity systemIdentity() {
        return new VerifiedIdentity(
                new PrincipalRef.SystemPrincipal(TENANT, "callback-system"),
                OWNER,
                Set.of("callback:register"),
                1,
                new ResultRef("identity-system"));
    }

    /** 构造 owner 不匹配的当前身份。 */
    private static VerifiedIdentity otherOwnerIdentity() {
        SessionOwner otherOwner = SessionOwner.user(
                new SessionRef(TENANT, "session-other"), TARGET, END_USER);
        return new VerifiedIdentity(
                new PrincipalRef.EndUserPrincipal(TENANT, END_USER),
                otherOwner,
                Set.of("callback:register"),
                1,
                new ResultRef("identity-other-owner"));
    }

    /** 构造 callback 所属 Run 的完整身份。 */
    private static RunIdentity runIdentity() {
        return new RunIdentity(OWNER, AGENT, INPUT, RUN, trace(), CAUSATION_REF, new com.skillforge.runtime.api.RunRequester.EndUser(OWNER.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(OWNER.sessionRef().tenantId(), INPUT.value()));
    }

    /** 构造带历史 causation 的 CALLBACK envelope。 */
    private static InboundEnvelope callbackEnvelope() {
        return envelope(InboundKind.CALLBACK, TENANT, Optional.of(CAUSATION_REF));
    }

    /** 构造可变 kind/tenant/causation 的测试 envelope。 */
    private static InboundEnvelope envelope(
            InboundKind kind,
            TenantId tenantId,
            Optional<ResultRef> causationIdentityRef) {
        SessionRef sessionRef = new SessionRef(tenantId, "session-1");
        RunRef runRef = new RunRef(tenantId, "run-1");
        return new InboundEnvelope(new AuthenticationRef("system-auth-1"), causationIdentityRef, sessionRef, TARGET, AGENT, com.skillforge.runtime.api.SessionSubject.user(END_USER), new com.skillforge.runtime.api.RunRequester.EndUser(END_USER), INPUT, new EventId("callback-event-1"), kind, new RunTarget.ExistingRun(runRef), "runtime", "callback-source", trace(), payload("callback.payload.v1"), NOW, java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(sessionRef.tenantId(), INPUT.value()));
    }

    /** 构造 tenant-scoped fencing lease。 */
    private static LeaseFence fence(TenantId tenantId) {
        return new LeaseFence(
                tenantId, "claim-1", "worker-1", 1, NOW.plusSeconds(30));
    }

    /** 构造仅允许稳定键重投的失败。 */
    private static PortFailure retryableFailure() {
        return new PortFailure(
                "delivery_retryable",
                RetryMode.IDEMPOTENT_BY_STABLE_KEY,
                new ResultRef("retryable-failure-1"));
    }

    /** 构造不可继续重试的确定失败。 */
    private static PortFailure permanentFailure() {
        return new PortFailure(
                "delivery_permanent",
                RetryMode.NONE,
                new ResultRef("permanent-failure-1"));
    }

    /** 构造稳定 trace。 */
    private static TraceEnvelope trace() {
        return new TraceEnvelope("trace-1", "span-1", INPUT, Optional.empty());
    }

    /** 构造 provider-neutral typed payload。 */
    private static TypedPayload payload(String schemaId) {
        return new TypedPayload(schemaId, "{}");
    }

    /** 验证 sealed union 的 permits 精确集合。 */
    private static void assertPermitted(Class<?> type, Set<Class<?>> expected) {
        assertTrue(type.isSealed(), type.getName());
        assertEquals(expected, Set.of(type.getPermittedSubclasses()));
    }

    /** 验证 record components 的名称、顺序与完整泛型签名。 */
    private static void assertRecordComponents(
            Class<? extends Record> type,
            List<String> expected) {
        assertTrue(type.isRecord(), type.getName());
        assertEquals(expected, componentSignatures(type));
    }

    /** 精确冻结非泛型纯接口及其全部 public abstract 方法。 */
    private static void assertExactPortMethods(
            Class<?> type,
            List<String> expectedSignatures) {
        assertTrue(type.isInterface(), type.getName());
        assertFalse(type.isAnnotation(), type.getName());
        assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
        assertEquals(null, type.getEnclosingClass(), type.getName());
        assertFalse(type.isSealed(), type.getName());
        assertEquals(0, type.getTypeParameters().length, type.getName());
        assertEquals(0, type.getInterfaces().length, type.getName());
        List<Method> methods = Arrays.stream(type.getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                .toList();
        for (Method method : methods) {
            assertTrue(Modifier.isPublic(method.getModifiers()), method.toString());
            assertTrue(Modifier.isAbstract(method.getModifiers()), method.toString());
            assertFalse(method.isDefault(), method.toString());
            assertFalse(Modifier.isStatic(method.getModifiers()), method.toString());
            assertEquals(0, method.getTypeParameters().length, method.toString());
            assertEquals(0, method.getGenericExceptionTypes().length, method.toString());
        }
        assertEquals(expectedSignatures, methods.stream()
                .map(CallbackRegistrationPortContractTest::methodSignature)
                .sorted()
                .toList());
    }

    /** 递归收集 Port 下全部 public nested 类型，包括所有 sealed result 叶子。 */
    private static Set<Class<?>> publicNestedTypes(Class<?> root) {
        Set<Class<?>> result = new HashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(List.of(root));
        // 广度优先遍历保证结果与声明层级无关，仅比较完整类型集合。
        while (!pending.isEmpty()) {
            Class<?> current = pending.removeFirst();
            for (Class<?> nested : current.getDeclaredClasses()) {
                if (Modifier.isPublic(nested.getModifiers()) && result.add(nested)) {
                    pending.addLast(nested);
                }
            }
        }
        return result;
    }

    /** 生成保留参数与返回泛型的稳定方法签名。 */
    private static String methodSignature(Method method) {
        String parameters = Arrays.stream(method.getGenericParameterTypes())
                .map(CallbackRegistrationPortContractTest::stableTypeName)
                .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + "):"
                + stableTypeName(method.getGenericReturnType());
    }

    /** 返回 record component 的名称与完整泛型类型。 */
    private static List<String> componentSignatures(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + stableTypeName(component))
                .toList();
    }

    /** 压缩 record component 中稳定 SDK 与 java.lang 前缀。 */
    private static String stableTypeName(RecordComponent component) {
        return stableTypeName(component.getGenericType());
    }

    /** 压缩任意反射类型中的稳定 SDK 与 java.lang 前缀。 */
    private static String stableTypeName(Type type) {
        return type.getTypeName()
                .replace("com.skillforge.runtime.api.port.CallbackRegistrationPort$", "")
                .replace("com.skillforge.runtime.api.port.", "")
                .replace("com.skillforge.runtime.api.", "")
                .replace("java.lang.", "")
                .replace('$', '.');
    }
}
