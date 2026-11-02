package com.skillforge.runtime.core;

import com.skillforge.runtime.json.CodecLimits;

import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.PortFailure;

/** 在分配规范 JSON 树之前计量精确编码成本，不复制 Provider 字符串或集合。 */
final class OutcomeEncodingBudget {
    void number(int depth, long value) { node(depth); units(Long.toString(value).length()); }
    private final CodecLimits limits;
    private long units;
    private long nodes;

    /** 每次编码独享预算，不能复用已消费的额度。 */
    OutcomeEncodingBudget(CodecLimits limits) { this.limits = limits; }

    /** 字段名占节点但不增加值深度；分隔符按实际成员数精确计量。 */
    void object(int depth, String... names) {
        node(depth);
        units(2L + names.length + Math.max(0, names.length - 1));
        for (String name : names) { node(depth); text(name); }
    }

    /** 在遍历外部集合前检查其最小节点数；子节点仍逐个精确扣减。 */
    void array(int depth, int size) {
        node(depth);
        if (size < 0 || size > limits.maxNodes() - nodes || (size > 0 && depth >= limits.maxDepth())) { capacity(); }
        units(2L + Math.max(0L, (long) size - 1));
    }

    /** 字符串转义后长度逐字符累计；达到上限即拒绝，不先构造转义副本。 */
    void string(int depth, String value) { node(depth); text(value); }

    /** 显式空值保留真实节点和四个字符的成本。 */
    void nil(int depth) { node(depth); units(4); }

    /** 布尔值是 JSON literal，不转换为字符串；false 比 true 多一个 code unit。 */
    void bool(int depth, boolean value) { node(depth); units(value ? 4 : 5); }

    /** TypedPayload 的内部 JSON 是不透明字符串；内部 schema 校验仍归注入 registry。 */
    void payload(int depth, TypedPayload payload) {
        object(depth, "schemaId", "canonicalJson");
        string(depth + 1, payload.schemaId());
        string(depth + 1, payload.canonicalJson());
    }

    /** 原 Run/Loop/iteration 的精确计量，深度相对调用方容器，不预先构造规范身份树。 */
    void operation(int depth, ModelPreparationIdentity operation) {
        var run = operation.runIdentity();
        var owner = run.owner();
        object(depth, "runIdentity", "loopId", "iteration");
        string(depth + 1, operation.loopId()); string(depth + 1, Integer.toString(operation.iteration()));
        object(depth + 1, "owner", "agentId", "causationInputId", "runRef", "trace", "causationIdentityRef", "requester", "sourceMessageRef", "originRef");
        object(depth + 2, "sessionRef", "subject", "target");
        object(depth + 3, "tenantId", "sessionId");
        string(depth + 4, owner.sessionRef().tenantId().value()); string(depth + 4, owner.sessionRef().sessionId());
        object(depth + 3, "kind", "id");
        string(depth + 4, owner.target().kind().name()); string(depth + 4, owner.target().id());
        switch (owner.subject()) {
            case com.skillforge.runtime.api.SessionSubject.User user -> {
                object(depth + 3, "kind", "endUserId");
                string(depth + 4, "USER"); string(depth + 4, Long.toString(user.endUserId().value()));
            }
            case com.skillforge.runtime.api.SessionSubject.Group group -> {
                object(depth + 3, "kind", "groupBindingRef"); string(depth + 4, "GROUP");
                object(depth + 4, "tenantId", "groupBindingId");
                string(depth + 5, group.groupBindingRef().tenantId().value()); string(depth + 5, group.groupBindingRef().groupBindingId());
            }
        }
        requester(depth + 2, run.requester());
        if (run.sourceMessageRef().isEmpty()) nil(depth + 2);
        else {
            var source = run.sourceMessageRef().orElseThrow();
            object(depth + 2, "sessionRef", "messageId"); object(depth + 3, "tenantId", "sessionId");
            string(depth + 4, source.sessionRef().tenantId().value()); string(depth + 4, source.sessionRef().sessionId());
            string(depth + 3, source.messageId());
        }
        object(depth + 2, "tenantId", "originId");
        string(depth + 3, run.originRef().tenantId().value()); string(depth + 3, run.originRef().originId());
        string(depth + 2, run.agentId().value());
        string(depth + 2, run.causationInputId().value()); string(depth + 2, run.causationIdentityRef().value());
        object(depth + 2, "tenantId", "runId");
        string(depth + 3, run.runRef().tenantId().value()); string(depth + 3, run.runRef().runId());
        object(depth + 2, "traceId", "spanId", "rootInputId", "causedByEventId");
        string(depth + 3, run.trace().traceId()); string(depth + 3, run.trace().spanId());
        string(depth + 3, run.trace().rootInputId().value());
        if (run.trace().causedByEventId().isPresent()) { string(depth + 3, run.trace().causedByEventId().orElseThrow().value()); }
        else { nil(depth + 3); }
    }

    void requester(int depth, com.skillforge.runtime.api.RunRequester requester) {
        switch (requester) {
            case com.skillforge.runtime.api.RunRequester.EndUser user -> {
                object(depth, "kind", "endUserId"); string(depth + 1, "END_USER"); string(depth + 1, Long.toString(user.endUserId().value()));
            }
            case com.skillforge.runtime.api.RunRequester.System system -> {
                object(depth, "kind", "principal"); string(depth + 1, "SYSTEM");
                object(depth + 1, "tenantId", "principalId");
                string(depth + 2, system.principal().tenantId().value()); string(depth + 2, system.principal().principalId());
            }
        }
    }

    /** 失败事实不丢失重试模式或业务审计引用。 */
    void failure(int depth, PortFailure failure) {
        object(depth, "reasonCode", "retryMode", "resultRef");
        string(depth + 1, failure.reasonCode());
        string(depth + 1, failure.retryMode().name());
        string(depth + 1, failure.resultRef().value());
    }

    /** long 累加在 int 配额内即停，节点累计不会溢出。 */
    private void node(int depth) {
        if (depth < 1 || depth > limits.maxDepth() || ++nodes > limits.maxNodes()) { capacity(); }
    }

    /** 拒绝未配对 surrogate；有效 Unicode 不正规化，按 UTF-16 code unit 计量。 */
    private void text(String value) {
        units(2);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) { invalid(); }
                units(2);
            } else if (Character.isLowSurrogate(c)) {
                invalid();
            } else {
                units(switch (c) {
                    case '"', '\\', '\b', '\f', '\n', '\r', '\t' -> 2;
                    default -> c < 0x20 ? 6 : 1;
                });
            }
        }
    }

    /** 每个增量均有界，检查先于后续分配。 */
    private void units(long count) { units += count; if (units > limits.maxCodeUnits()) { capacity(); } }

    /** 内部容量分类，由具体 codec 决定是否公开；其他结果不暴露 Tool 专属异常。 */
    static final class CapacityExceeded extends IllegalArgumentException {
        private CapacityExceeded() { super("outcome encoding resource or Unicode limit"); }
    }
    private static void capacity() { throw new CapacityExceeded(); }

    /** 不把 Provider 正文或计量中间值带入日志。 */
    private static void invalid() { throw new IllegalArgumentException("outcome encoding resource or Unicode limit"); }
}
