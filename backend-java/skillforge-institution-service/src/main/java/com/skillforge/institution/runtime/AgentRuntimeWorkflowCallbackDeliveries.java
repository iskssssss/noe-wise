package com.skillforge.institution.runtime;

import com.skillforge.core.chat.session.SessionService;
import com.skillforge.runtime.adapter.jdbc.callback.CallbackPayloads;
import com.skillforge.runtime.adapter.jdbc.callback.JdbcCallbackRegistrationPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** HMAC ingress的真实业务交付；只接受原精确Workflow等待，不经过旧Inbox或Recovery envelope。 */
@Component
public final class AgentRuntimeWorkflowCallbackDeliveries {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AgentRuntimeWorkflowCallbackDeliveries.class);
    private final JdbcCallbackRegistrationPort callbacks;
    private final ExecutionStore store;
    private final AgentRuntimeWorkflowRecoveryWorker recovery;
    private final SessionService sessions;
    private final java.util.Map<TenantId,String> scanCursors = new java.util.concurrent.ConcurrentHashMap<>();

    public AgentRuntimeWorkflowCallbackDeliveries(JdbcCallbackRegistrationPort callbacks, ExecutionStore store,
            AgentRuntimeWorkflowRecoveryWorker recovery, SessionService sessions) {
        this.callbacks = Objects.requireNonNull(callbacks); this.store = Objects.requireNonNull(store);
        this.recovery = Objects.requireNonNull(recovery);
        this.sessions = Objects.requireNonNull(sessions);
    }

    public boolean owns(VerifiedCallbackDelivery delivery) {
        return callbacks.find(delivery.callbackRef()).map(value -> value.draft().target() instanceof CallbackTarget.ExecutionTarget).orElse(false);
    }

    /** 验签证明先落真实READY账，再将原receipt与Run响应在共享事务内确认。 */
    public CallbackDeliveryResult acceptVerified(VerifiedCallbackDelivery delivery) {
        if (!owns(delivery)) throw rejected();
        var result = callbacks.acceptVerified(delivery);
        if (result instanceof CallbackDeliveryResult.Ready ready) deliver(ready.receipt());
        else if (result instanceof CallbackDeliveryResult.Replayed replayed && replayed.receipt().status() == CallbackDeliveryStatus.READY) deliver(replayed.receipt());
        return result;
    }

    /** 复用宿主五秒有界恢复，只读取ExecutionTarget原账，不领取其他callback业务。 */
    public void recover(TenantId tenant, int limit) {
        if (limit <= 0 || limit > 100) throw new IllegalArgumentException("callback_recovery_limit_invalid");
        var page = callbacks.scanReadyAfter(tenant, scanCursors.getOrDefault(tenant,""), limit);
        if(page.size()<limit)scanCursors.remove(tenant);else scanCursors.put(tenant,page.getLast().deliveryRef().deliveryId());
        for (var candidate : page) {
            var registration = callbacks.find(candidate.callbackRef());
            if (registration.isEmpty() || !(registration.orElseThrow().draft().target() instanceof CallbackTarget.ExecutionTarget)) continue;
            try { callbacks.delivery(candidate.deliveryRef()).ifPresent(this::deliver); }
            catch (RuntimeException unavailable) {
                LOG.warn("Workflow回调交付暂缓 tenant={} delivery={} failureType={}", tenant.value(), candidate.deliveryRef().deliveryId(), unavailable.getClass().getSimpleName(), unavailable);
            }
        }
    }

    private void deliver(CallbackDeliveryReceipt receipt) {
        if (receipt.status() == CallbackDeliveryStatus.DELIVERED) return;
        if (receipt.status() != CallbackDeliveryStatus.READY && receipt.status() != CallbackDeliveryStatus.PROCESSING) throw rejected();
        var registration = callbacks.find(receipt.callbackRef()).orElseThrow(AgentRuntimeWorkflowCallbackDeliveries::rejected);
        if (registration.status() != CallbackRegistrationStatus.ACTIVE || !(registration.draft().target() instanceof CallbackTarget.ExecutionTarget target)) throw rejected();
        var envelope = receipt.envelope().orElseThrow(AgentRuntimeWorkflowCallbackDeliveries::rejected);
        var parent = store.load(target.owningRunRef()).orElseThrow(AgentRuntimeWorkflowCallbackDeliveries::rejected);
        if (!parent.identity().owner().equals(registration.draft().owner()) || !parent.identity().agentId().equals(registration.draft().agentId())
                || !parent.identity().causationIdentityRef().equals(registration.causationIdentityRef()) || parent.cancelled()
                || parent.phase() != ExecutionStore.Phase.WAITING || parent.waiting().isEmpty()) throw rejected();
        var waiting = store.waiting(parent.identity().runRef(), parent.waiting().orElseThrow()).orElseThrow(AgentRuntimeWorkflowCallbackDeliveries::rejected);
        if (waiting.kind() != ExecutionStore.WaitKind.WORKFLOW || !(waiting.internal().orElse(null) instanceof ExecutionStore.WorkflowWait exact)
                || !exact.execution().equals(target.executionRef()) || !exact.actionKey().equals(target.actionKey())
                || exact.executionRevision() != target.expectedExecutionRevision()) throw rejected();
        var session = sessions.requireForSystem(parent.identity().runRef().tenantId().value(), parent.identity().owner().sessionRef().sessionId());
        if (sessions.isOpenPlatformClosed(session)) throw rejected();
        requireEnvelope(registration,envelope,parent.identity());
        var actor = recovery.currentActor(parent.identity(),envelope.inputId());
        var response = CallbackPayloads.businessPayload(registration, envelope.payload().canonicalJson().getBytes(StandardCharsets.UTF_8));
        recovery.respond(parent, actor, envelope.inputId(), waiting.reference(), parent.revision(), response, (sql, execution) -> {
            if (!execution.executionRef().equals(target.executionRef()) || !execution.owner().equals(parent.identity())
                    || execution.executionRevision() != target.expectedExecutionRevision()
                    || execution.waiting().filter(value -> value.actionKey().equals(target.actionKey())).isEmpty()) throw rejected();
            callbacks.confirmWorkflowDelivery(sql, receipt.deliveryRef(), registration, envelope);
        });
    }

    private static void requireEnvelope(CallbackRegistration registration, InboundEnvelope envelope,com.skillforge.runtime.api.port.RunIdentity original) {
        var draft = registration.draft();
        if (!new SessionOwner(envelope.sessionRef(),envelope.subject(),envelope.sessionTarget()).equals(draft.owner())
                || !envelope.requester().equals(original.requester()) || !envelope.sourceMessageRef().equals(original.sourceMessageRef())
                || !envelope.originRef().equals(original.originRef()) || !envelope.agentId().equals(draft.agentId()) || !envelope.causationIdentityRef().equals(Optional.of(registration.causationIdentityRef()))) throw rejected();
    }
    private static IllegalArgumentException rejected() { return new IllegalArgumentException("workflow_callback_delivery_rejected"); }
}
