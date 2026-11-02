package com.skillforge.runtime.core;

import com.skillforge.runtime.api.EventId;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.SourceMessageRef;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.GroupBindingRef;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.ActionRef;
import com.skillforge.runtime.api.port.IdempotencyKey;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 将 Session 冻结的运行、Loop 和迭代绑定为一次逻辑模型准备，不承载 lease 或调用许可。 */
public record ModelPreparationIdentity(RunIdentity runIdentity, String loopId, int iteration) {

    static final String KEY_SCHEMA = "skillforge.runtime.model-operation.v4";

    /** 拒绝不完整身份或非正迭代，并在进入持久边界前校验所有身份的 Unicode 编码。 */
    public ModelPreparationIdentity {
        Objects.requireNonNull(runIdentity, "runIdentity");
        Objects.requireNonNull(loopId, "loopId");
        if (loopId.isBlank() || iteration <= 0) {
            throw new IllegalArgumentException("loopId must be nonblank and iteration must be positive");
        }
        // 构造期只验证数据可规范编码，不读取时钟、持久化或签发新身份。
        operation(runIdentity, loopId, iteration);
    }

    /** 返回精确 schema 对象；独立 golden-byte 测试拥有字段、编码和排序预期。 */
    CanonicalJson.Value canonicalValue() {
        return operation(runIdentity, loopId, iteration);
    }

    /** 严格恢复 operation 字段闭包，禁止回退默认 identity 或从其他对象推断缺省字段。 */
    static ModelPreparationIdentity decode(CanonicalJson.Value value) {
        value.requireFields("runIdentity", "loopId", "iteration");
        return new ModelPreparationIdentity(decodeRun(value.field("runIdentity")),
                value.field("loopId").stringValue(), value.field("iteration").intValue());
    }

    /** 同一冻结身份得到同一摘要；本方法不另接收 worker 或当前调用 span 参数。 */
    public String key() {
        return CanonicalJson.hash(KEY_SCHEMA, canonicalValue());
    }

    /** 生成租户内模型 Action 引用；此引用不是已 claim 或已授权的证明。 */
    public ActionRef actionRef() {
        return new ActionRef(runIdentity.owner().sessionRef().tenantId(), "model-action:" + key());
    }

    /** invocation 保持逻辑稳定，与后续每个 attemptId 分离。 */
    public String invocationId() {
        return "model-invocation:" + key();
    }

    /** 幂等键绑定原 action，不因内容变化生成新键绕过冲突。 */
    public IdempotencyKey idempotencyKey() {
        return new IdempotencyKey("model-action:" + key());
    }

    /** context 策略事件身份稳定，payload fingerprint 另行绑定实际决策。 */
    public EventId contextPolicyEventId() {
        return new EventId("model-context-policy:" + key());
    }

    /** 快照事件身份不包含快照内容摘要；同键异内容应由 SessionLog 拒绝。 */
    public EventId contextSnapshotEventId() {
        return new EventId("model-context-snapshot:" + key());
    }

    /** model 策略与 context 策略保持不同事件身份。 */
    public EventId modelPolicyEventId() {
        return new EventId("model-model-policy:" + key());
    }

    /** 显式投影 operation，禁止反射将后续临时字段或宿主对象带入稳定指纹。 */
    private static CanonicalJson.Value operation(RunIdentity identity, String loopId, int iteration) {
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("runIdentity", run(identity)),
                new CanonicalJson.Field("loopId", CanonicalJson.string(loopId)),
                new CanonicalJson.Field("iteration", CanonicalJson.integer(iteration))));
    }

    /** 完整保存运行归属、因果身份和原始 trace，不使用当前 worker 的运行上下文。 */
    static CanonicalJson.Value run(RunIdentity identity) {
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("owner", owner(identity.owner())),
                new CanonicalJson.Field("agentId", CanonicalJson.string(identity.agentId().value())),
                new CanonicalJson.Field("causationInputId", CanonicalJson.string(identity.causationInputId().value())),
                new CanonicalJson.Field("runRef", CanonicalJson.object(List.of(
                        new CanonicalJson.Field("tenantId", CanonicalJson.string(identity.runRef().tenantId().value())),
                        new CanonicalJson.Field("runId", CanonicalJson.string(identity.runRef().runId()))))),
                new CanonicalJson.Field("trace", trace(identity.trace())),
                new CanonicalJson.Field("causationIdentityRef", CanonicalJson.string(identity.causationIdentityRef().value())),
                new CanonicalJson.Field("requester", requester(identity.requester())),
                new CanonicalJson.Field("sourceMessageRef", identity.sourceMessageRef().map(source -> CanonicalJson.object(List.of(
                        new CanonicalJson.Field("sessionRef", CanonicalJson.object(List.of(
                                new CanonicalJson.Field("tenantId", CanonicalJson.string(source.sessionRef().tenantId().value())),
                                new CanonicalJson.Field("sessionId", CanonicalJson.string(source.sessionRef().sessionId()))))),
                        new CanonicalJson.Field("messageId", CanonicalJson.string(source.messageId()))))).orElseGet(CanonicalJson::nil)),
                new CanonicalJson.Field("originRef", CanonicalJson.object(List.of(
                        new CanonicalJson.Field("tenantId", CanonicalJson.string(identity.originRef().tenantId().value())),
                        new CanonicalJson.Field("originId", CanonicalJson.string(identity.originRef().originId())))))));
    }

    static CanonicalJson.Value requester(RunRequester requester) {
        return switch (requester) {
            case RunRequester.EndUser user -> CanonicalJson.object(List.of(
                    new CanonicalJson.Field("kind", CanonicalJson.string("END_USER")),
                    new CanonicalJson.Field("endUserId", CanonicalJson.integer(user.endUserId().value()))));
            case RunRequester.System system -> CanonicalJson.object(List.of(
                    new CanonicalJson.Field("kind", CanonicalJson.string("SYSTEM")),
                    new CanonicalJson.Field("principal", CanonicalJson.object(List.of(
                            new CanonicalJson.Field("tenantId", CanonicalJson.string(system.principal().tenantId().value())),
                            new CanonicalJson.Field("principalId", CanonicalJson.string(system.principal().principalId())))))));
        };
    }

    /** SessionOwner 的封闭 subject、target、Session 和机构均参与身份隔离。 */
    static CanonicalJson.Value owner(SessionOwner owner) {
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("sessionRef", CanonicalJson.object(List.of(
                        new CanonicalJson.Field("tenantId", CanonicalJson.string(owner.sessionRef().tenantId().value())),
                        new CanonicalJson.Field("sessionId", CanonicalJson.string(owner.sessionRef().sessionId()))))),
                new CanonicalJson.Field("target", CanonicalJson.object(List.of(
                        new CanonicalJson.Field("kind", CanonicalJson.string(owner.target().kind().name())),
                        new CanonicalJson.Field("id", CanonicalJson.string(owner.target().id()))))),
                new CanonicalJson.Field("subject", subject(owner.subject()))));
    }

    /** 群归属只编码内部生命周期引用，不把成员或某个服务 Agent 写成 owner。 */
    private static CanonicalJson.Value subject(SessionSubject subject) {
        return switch (subject) {
            case SessionSubject.User user -> CanonicalJson.object(List.of(
                    new CanonicalJson.Field("kind", CanonicalJson.string("USER")),
                    new CanonicalJson.Field("endUserId", CanonicalJson.integer(user.endUserId().value()))));
            case SessionSubject.Group group -> CanonicalJson.object(List.of(
                    new CanonicalJson.Field("kind", CanonicalJson.string("GROUP")),
                    new CanonicalJson.Field("groupBindingRef", CanonicalJson.object(List.of(
                            new CanonicalJson.Field("tenantId", CanonicalJson.string(group.groupBindingRef().tenantId().value())),
                            new CanonicalJson.Field("groupBindingId", CanonicalJson.string(group.groupBindingRef().groupBindingId())))))));
        };
    }

    /** 严格解码两个归属分支；未知、混合、旧字段或错机构/错目录不回退为个人。 */
    static SessionOwner decodeOwner(CanonicalJson.Value value) {
        value.requireFields("sessionRef", "subject", "target");
        var session = value.field("sessionRef").requireFields("tenantId", "sessionId");
        var target = value.field("target").requireFields("kind", "id");
        var subject = value.field("subject");
        SessionSubject decoded = switch (subject.field("kind").stringValue()) {
            case "USER" -> {
                subject.requireFields("kind", "endUserId");
                yield SessionSubject.user(new EndUserId(subject.field("endUserId").longValue()));
            }
            case "GROUP" -> {
                subject.requireFields("kind", "groupBindingRef");
                var group = subject.field("groupBindingRef").requireFields("tenantId", "groupBindingId");
                yield SessionSubject.group(new GroupBindingRef(new TenantId(group.field("tenantId").stringValue()),
                        group.field("groupBindingId").stringValue()));
            }
            default -> throw new IllegalArgumentException("unknown Session subject kind");
        };
        return new SessionOwner(new SessionRef(new TenantId(session.field("tenantId").stringValue()), session.field("sessionId").stringValue()),
                decoded, new SessionTarget(SessionTarget.Kind.valueOf(target.field("kind").stringValue()), target.field("id").stringValue()));
    }

    /** 因果事件缺省为显式 null，原始 root input 和 span 不因新 worker 接管而丢失。 */
    static CanonicalJson.Value trace(TraceEnvelope trace) {
        return CanonicalJson.object(List.of(
                new CanonicalJson.Field("traceId", CanonicalJson.string(trace.traceId())),
                new CanonicalJson.Field("spanId", CanonicalJson.string(trace.spanId())),
                new CanonicalJson.Field("rootInputId", CanonicalJson.string(trace.rootInputId().value())),
                new CanonicalJson.Field("causedByEventId", trace.causedByEventId()
                        .map(event -> CanonicalJson.string(event.value())).orElseGet(CanonicalJson::nil))));
    }

    /** 用公开 API 构造器再次验证跨租户 Run/owner 和正终端用户身份。 */
    static RunIdentity decodeRun(CanonicalJson.Value value) {
        value.requireFields("owner", "agentId", "causationInputId", "runRef", "trace", "causationIdentityRef", "requester", "sourceMessageRef", "originRef");
        var owner = decodeOwner(value.field("owner"));
        var run = value.field("runRef").requireFields("tenantId", "runId");
        var trace = value.field("trace").requireFields("traceId", "spanId", "rootInputId", "causedByEventId");
        var cause = trace.field("causedByEventId");
        RunRequester decodedRequester = decodeRequester(value.field("requester"));
        var source = value.field("sourceMessageRef");
        Optional<SourceMessageRef> decodedSource = Optional.empty();
        if (!source.isNull()) {
            source.requireFields("sessionRef", "messageId");
            var session = source.field("sessionRef").requireFields("tenantId", "sessionId");
            decodedSource = Optional.of(new SourceMessageRef(new SessionRef(new TenantId(session.field("tenantId").stringValue()),
                    session.field("sessionId").stringValue()), source.field("messageId").stringValue()));
        }
        var origin = value.field("originRef").requireFields("tenantId", "originId");
        return new RunIdentity(owner,
                new AgentId(value.field("agentId").stringValue()),
                new InputId(value.field("causationInputId").stringValue()),
                new RunRef(new TenantId(run.field("tenantId").stringValue()), run.field("runId").stringValue()),
                new TraceEnvelope(trace.field("traceId").stringValue(), trace.field("spanId").stringValue(),
                        new InputId(trace.field("rootInputId").stringValue()),
                        cause.isNull() ? Optional.empty() : Optional.of(new EventId(cause.stringValue()))),
                new ResultRef(value.field("causationIdentityRef").stringValue()), decodedRequester, decodedSource,
                new RunOriginRef(new TenantId(origin.field("tenantId").stringValue()), origin.field("originId").stringValue()));
    }
    static RunRequester decodeRequester(CanonicalJson.Value requester) {
        return switch (requester.field("kind").stringValue()) {
            case "END_USER" -> {
                requester.requireFields("kind", "endUserId");
                yield new RunRequester.EndUser(new EndUserId(requester.field("endUserId").longValue()));
            }
            case "SYSTEM" -> {
                requester.requireFields("kind", "principal");
                var principal = requester.field("principal").requireFields("tenantId", "principalId");
                yield new RunRequester.System(new PrincipalRef.SystemPrincipal(new TenantId(principal.field("tenantId").stringValue()),
                        principal.field("principalId").stringValue()));
            }
            default -> throw new IllegalArgumentException("unknown Run requester kind");
        };
    }

}
