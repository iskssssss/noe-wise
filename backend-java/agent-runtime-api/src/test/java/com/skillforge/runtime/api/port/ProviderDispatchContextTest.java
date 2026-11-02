package com.skillforge.runtime.api.port;
import com.skillforge.runtime.api.PrincipalRef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 派发上下文保留唯一原事实，校验运行归属、关键字段和值语义，不签发发送权。 */
class ProviderDispatchContextTest {
    private static final TenantId TENANT = new TenantId("tenant");
    private static final RunRef RUN = new RunRef(TENANT, "run");
    private static final ActionRef ACTION = new ActionRef(TENANT, "action");
    private static final ResultRef REQUEST = new ResultRef("request-private");
    private static final ResultRef CAPABILITY = new ResultRef("capability-private");

    /** 调用种类和完整派发身份按原值保留，敏感字段不进入普通日志。 */
    @Test
    void createsContextWithTheExactOriginalDispatch() {
        var call = callContext(TENANT, RUN);
        var dispatch = dispatch(RUN, ACTION);
        var context = ProviderCallContext.dispatched(call, dispatch);
        assertSame(call, context.callContext());
        assertSame(dispatch, context.dispatch());
        assertEquals(PortalCallKind.MODEL_GENERATE, context.callKind());
        assertEquals("invocation-private", context.dispatch().invocationId());
        assertEquals("attempt-private", context.dispatch().attemptId());
        assertEquals(REQUEST, context.dispatch().requestRef());
        assertEquals(CAPABILITY, context.dispatch().capabilityRef());
        assertEquals("fingerprint-private", context.dispatch().fingerprint());
        assertEquals(7, context.dispatch().fence());
        assertFalse(context.toString().contains("private"));
        assertFalse(dispatch.toString().contains("private"));
    }

    /** 相同租户不同 Run 和其他租户来源均不能借当前可信身份派发。 */
    @Test
    void rejectsMismatchedRunAndActionTenant() {
        var call = callContext(TENANT, RUN);
        assertThrows(IllegalArgumentException.class, () -> ProviderCallContext.dispatched(call,
                dispatch(new RunRef(TENANT, "other-run"), ACTION)));
        var other = new TenantId("other-tenant");
        assertThrows(IllegalArgumentException.class, () -> dispatch(RUN, new ActionRef(other, "action")));
        assertThrows(IllegalArgumentException.class, () -> ProviderCallContext.dispatched(call,
                dispatch(new RunRef(other, "other-run"), new ActionRef(other, "action"))));
        assertThrows(IllegalArgumentException.class, () -> ProviderCallContext.dispatched(null, dispatch(RUN, ACTION)));
        assertThrows(IllegalArgumentException.class, () -> ProviderCallContext.dispatched(call, null));
    }

    /** 关键引用、调用种类、身份、指纹和 fence 缺失时直接拒绝，不生成默认值。 */
    @Test
    void rejectsMissingDispatchFieldsAndNonpositiveFence() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderDispatch(null, ACTION, "invocation", "attempt",
                PortalCallKind.TOOL_INVOKE, REQUEST, CAPABILITY, "fingerprint", 1));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDispatch(RUN, null, "invocation", "attempt",
                PortalCallKind.TOOL_INVOKE, REQUEST, CAPABILITY, "fingerprint", 1));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDispatch(RUN, ACTION, " ", "attempt",
                PortalCallKind.TOOL_INVOKE, REQUEST, CAPABILITY, "fingerprint", 1));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDispatch(RUN, ACTION, "invocation", null,
                PortalCallKind.TOOL_INVOKE, REQUEST, CAPABILITY, "fingerprint", 1));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDispatch(RUN, ACTION, "invocation", "attempt",
                null, REQUEST, CAPABILITY, "fingerprint", 1));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDispatch(RUN, ACTION, "invocation", "attempt",
                PortalCallKind.TOOL_INVOKE, null, CAPABILITY, "fingerprint", 1));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDispatch(RUN, ACTION, "invocation", "attempt",
                PortalCallKind.TOOL_INVOKE, REQUEST, null, "fingerprint", 1));
        assertThrows(IllegalArgumentException.class, () -> new ProviderDispatch(RUN, ACTION, "invocation", "attempt",
                PortalCallKind.TOOL_INVOKE, REQUEST, CAPABILITY, "", 1));
        for (long fence : new long[]{0, -1}) assertThrows(IllegalArgumentException.class,
                () -> new ProviderDispatch(RUN, ACTION, "invocation", "attempt",
                        PortalCallKind.TOOL_INVOKE, REQUEST, CAPABILITY, "fingerprint", fence));
    }

    /** 上下文只公开原事实和唯一工厂，不公开构造或新增发送许可的入口。 */
    @Test
    void exposesNoAdditionalSendAuthority() {
        assertEquals(0,ProviderCallContext.class.getConstructors().length);
        var methods=java.util.Arrays.stream(ProviderCallContext.class.getDeclaredMethods())
                .filter(method->java.lang.reflect.Modifier.isPublic(method.getModifiers())).map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("dispatched","callContext","dispatch","callKind","equals","hashCode","toString"),methods);
    }

    /** 不同Attempt、调用种类、请求或能力不能与原派发混为一个值。 */
    @Test
    void preservesExactDispatchValueEquality() {
        var call = callContext(TENANT, RUN);
        var first = ProviderCallContext.dispatched(call, dispatch(RUN, ACTION));
        var second = ProviderCallContext.dispatched(call, dispatch(RUN, ACTION));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(31*call.hashCode()+first.dispatch().hashCode(),first.hashCode());
        assertNotEquals(first, ProviderCallContext.dispatched(call,
                new ProviderDispatch(RUN, ACTION, "invocation-private", "another-attempt", PortalCallKind.MODEL_GENERATE,
                        REQUEST, CAPABILITY, "fingerprint-private", 7)));
        for(var changed:java.util.List.of(
                new ProviderDispatch(RUN,ACTION,"invocation-private","attempt-private",PortalCallKind.TOOL_INVOKE,REQUEST,CAPABILITY,"fingerprint-private",7),
                new ProviderDispatch(RUN,ACTION,"invocation-private","attempt-private",PortalCallKind.MODEL_GENERATE,new ResultRef("other-request"),CAPABILITY,"fingerprint-private",7),
                new ProviderDispatch(RUN,ACTION,"invocation-private","attempt-private",PortalCallKind.MODEL_GENERATE,REQUEST,new ResultRef("other-capability"),"fingerprint-private",7),
                new ProviderDispatch(RUN,ACTION,"invocation-private","attempt-private",PortalCallKind.MODEL_GENERATE,REQUEST,CAPABILITY,"other-fingerprint",7),
                new ProviderDispatch(RUN,ACTION,"invocation-private","attempt-private",PortalCallKind.MODEL_GENERATE,REQUEST,CAPABILITY,"fingerprint-private",8)))
            assertNotEquals(first,ProviderCallContext.dispatched(call,changed));
    }

    /** 使用 API 自有事实构造直接派发，不引用 Session SPI 或任何旧许可。 */
    private static ProviderDispatch dispatch(RunRef run, ActionRef action) {
        return new ProviderDispatch(run, action, "invocation-private", "attempt-private", PortalCallKind.MODEL_GENERATE,
                REQUEST, CAPABILITY, "fingerprint-private", 7);
    }

    /** 显式可信归属和取消控制只构造合法前提，不伪造持久派发或权限验收。 */
    private static PortCallContext callContext(TenantId tenant, RunRef run) {
        var owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("agent"), new EndUserId(1));
        var input = new InputId("input");
        var identity = new RunIdentity(owner, owner.target().requireAgent(), input, run, new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("verified"), new com.skillforge.runtime.api.RunRequester.EndUser(owner.requireUser()), java.util.Optional.empty(), new com.skillforge.runtime.api.RunOriginRef(owner.sessionRef().tenantId(), input.value()));
        var verified = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()),
                owner, Set.of("runtime:invoke"), 1, new ResultRef("verified"));
        return new PortCallContext(identity, verified,
                new InvocationControl(Instant.parse("2026-10-22T00:00:00Z"), () -> false));
    }
}
