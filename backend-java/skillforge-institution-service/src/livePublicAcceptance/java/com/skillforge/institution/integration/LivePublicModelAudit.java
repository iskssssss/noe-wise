package com.skillforge.institution.integration;

import com.skillforge.runtime.adapter.model.ModelInvocationAudit;
import com.skillforge.runtime.adapter.model.ModelInvocationAudit.Provider;
import com.skillforge.runtime.adapter.jdbc.model.JdbcModelBindings;
import com.skillforge.runtime.api.port.ModelPort.ModelCall;
import com.skillforge.runtime.api.port.ModelPort.ModelUsage;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.spi.SessionDelegations;
import com.skillforge.institution.runtime.AgentDelegationHost;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 网络发送前保守预留预算；失败、未知及不可靠 usage 保持预留并禁止场景续调。 */
final class LivePublicModelAudit implements LiveDispatchedModelAudit.Callbacks {
    static final int MAX_CALLS = (int) LivePublicBudget.limit("LIVE_PUBLIC_MAX_CALLS", 64);
    static final int MAX_SCENES = 8;
    static final long MAX_TOKENS = LivePublicBudget.limit("LIVE_PUBLIC_MAX_TOKENS", 3_000_000);
    private final SessionDelegations.Store delegations;
    private final JdbcModelBindings bindings;
    private final Map<String, Scene> sessions = new HashMap<>();
    private final Map<String, Attempt> attempts = new HashMap<>();
    private final Map<String, SessionOwner> owners = new HashMap<>();
    private LivePublicSourceSnapshot.Snapshot source;
    private long chargedOrReserved;
    private long reliableTokens;
    private boolean closed;

    LivePublicModelAudit(SessionDelegations.Store delegations, JdbcModelBindings bindings) {
        this.delegations = delegations; this.bindings = bindings;
    }
    synchronized void freeze(LivePublicSourceSnapshot.Snapshot source) { this.source = source; }
    synchronized void register(SessionOwner owner, String scene) {
        String session = owner.sessionRef().sessionId();
        if (source == null || closed || sessions.size() >= MAX_SCENES || sessions.containsKey(session))
            throw new IllegalStateException("LIVE_SCENE_REGISTRATION_INVALID");
        sessions.put(session, new Scene(scene));
        owners.put(session, owner);
    }
    synchronized void stop(String session, String code) {
        Scene scene = sessions.get(session);
        if (scene != null) scene.stopCode = code;
    }
    synchronized void close() { closed = true; }

    @Override public synchronized boolean start(ModelInvocationAudit delegate, ModelCall call, Provider provider) {
        Scene scene = scene(call.context().callContext().runIdentity());
        if (closed || source == null || scene == null || scene.stopCode != null) {
            if (scene != null) scene.deniedCalls++;
            return false;
        }
        String id = attemptId(call);
        if (attempts.containsKey(id)) return false;
        String frozenReference = "platform-model:" + source.model().id() + ":" + source.model().revision();
        if (!bindings.find(call.target().binding()).map(JdbcModelBindings.Binding::sourceReference)
                    .filter(frozenReference::equals).isPresent()
                || !provider.providerId().equals(source.model().providerId())
                || !provider.serviceId().equals(source.model().serviceId())
                || !provider.modelId().equals(source.model().modelId())
                || call.generation().maxOutputTokens().isEmpty()
                || call.generation().maxOutputTokens().getAsInt() != source.model().maxOutputTokens()) {
            scene.stopCode = "MODEL_CONFIGURATION_DRIFT"; return false;
        }
        long reservation = source.reservation();
        if (attempts.size() >= MAX_CALLS || reservation > MAX_TOKENS - chargedOrReserved) {
            scene.stopCode = "LIVE_BUDGET_EXHAUSTED"; return false;
        }
        // 原宿主 audit 仍负责唯一 attempt 账本与生产计量；未取得执行权时不消耗预留。
        if (!delegate.start(call, provider)) { scene.stopCode = "ATTEMPT_NOT_ACQUIRED"; return false; }
        attempts.put(id, new Attempt(scene, reservation));
        chargedOrReserved += reservation;
        scene.calls++;
        return true;
    }
    @Override public void usageReported(ModelInvocationAudit delegate, ModelCall call, Provider provider, long revision, ModelUsage usage) {
        delegate.usageReported(call, provider, revision, usage);
    }
    @Override public void succeeded(ModelInvocationAudit delegate, ModelCall call, Provider provider, ModelUsage usage) {
        delegate.succeeded(call, provider, usage);
        synchronized (this) {
            Attempt attempt = requiredAttempt(call);
            if (attempt.settled) return;
            attempt.settled = true;
            if (usage.inputTokens().isEmpty() || usage.outputTokens().isEmpty()) {
                attempt.scene.stopCode = "USAGE_UNRELIABLE"; return;
            }
            long input = usage.inputTokens().getAsLong(), output = usage.outputTokens().getAsLong();
            if (input < 0 || output < 0 || input > source.maxInputTokens()
                    || output > source.model().maxOutputTokens()) {
                attempt.scene.stopCode = "USAGE_EXCEEDS_RESERVATION"; closed = true; return;
            }
            long actual = Math.addExact(input, output);
            chargedOrReserved -= attempt.reservation - actual;
            reliableTokens += actual;
            attempt.scene.reliableTokens += actual;
        }
    }
    @Override public void failed(ModelInvocationAudit delegate, ModelCall call, Provider provider, String reasonCode) {
        stopAttempt(call, "MODEL_FAILED"); delegate.failed(call, provider, reasonCode);
    }
    @Override public void unresolved(ModelInvocationAudit delegate, ModelCall call, Provider provider, String reasonCode) {
        stopAttempt(call, "MODEL_OUTCOME_UNKNOWN"); delegate.unresolved(call, provider, reasonCode);
    }
    private synchronized void stopAttempt(ModelCall call, String code) {
        requiredAttempt(call).scene.stopCode = code;
    }
    private Attempt requiredAttempt(ModelCall call) {
        Attempt value = attempts.get(attemptId(call));
        if (value == null) throw new IllegalStateException("LIVE_ATTEMPT_NOT_RESERVED");
        return value;
    }
    private Scene scene(RunIdentity identity) {
        String session = identity.owner().sessionRef().sessionId();
        Scene own = sessions.get(session);
        if (own != null) return identity.owner().equals(owners.get(session)) ? own : null;
        // 子成员属于真实独立Session。只有持久委派完整owner/run闭合才能计入原场景，不能猜前缀或信任trace形状。
        for (var root : owners.entrySet()) {
            for (var entry : delegations.forSession(root.getValue(), com.skillforge.institution.team.TeamPublicViews.DELEGATION_HISTORY_LIMIT)) {
                if (entry.childRun().equals(identity.runRef()) && entry.target().agentId().equals(identity.agentId())
                        && AgentDelegationHost.childOwner(entry).equals(identity.owner())) return sessions.get(root.getKey());
            }
        }
        return null;
    }
    synchronized int calls(String session) { return sessions.get(session).calls; }
    synchronized int deniedCalls(String session) { return sessions.get(session).deniedCalls; }
    synchronized String stopCode(String session) { return sessions.get(session).stopCode; }
    synchronized Report report() {
        return new Report(attempts.size(), reliableTokens, chargedOrReserved,
                sessions.values().stream().map(scene -> new SceneReport(scene.name, scene.calls,
                        scene.reliableTokens, scene.deniedCalls, scene.stopCode)).sorted(java.util.Comparator.comparing(SceneReport::name)).toList());
    }
    private static String attemptId(ModelCall call) { return call.context().dispatch().attemptId(); }
    record Report(int calls, long reliableTokens, long chargedOrReservedTokens, List<SceneReport> scenes) { }
    record SceneReport(String name, int calls, long reliableTokens, int deniedCalls, String stopCode) { }
    private static final class Scene {
        final String name;
        int calls;
        int deniedCalls;
        long reliableTokens;
        String stopCode;
        Scene(String name) { this.name = name; }
    }
    private static final class Attempt {
        final Scene scene;
        final long reservation;
        boolean settled;
        Attempt(Scene scene, long reservation) { this.scene = scene; this.reservation = reservation; }
    }
}
