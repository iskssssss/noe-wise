package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;

/**
 * 已登记 Action/Attempt 的直接派发事实；由 Session 从真实派发提交回执构造。
 * 本值只传递一次调用的冻结身份，不签发新的发送权，也不包含旧 journal 或 consume proof。
 *
 * @param run 派发所属的完整运行引用
 * @param action 已登记逻辑动作，必须与 Run 同租户
 * @param invocationId 本次冻结请求的逻辑调用身份
 * @param attemptId 本次登记派发的唯一尝试身份
 * @param callKind 实际 Provider 调用种类，不从请求正文或旧 permit 推断
 * @param requestRef 已登记的不可变实际请求引用
 * @param capabilityRef 本次请求冻结的能力及版本材料引用
 * @param fingerprint 派发事务已采用的完整请求指纹
 * @param fence 本次登记使用的正租约 fencing token
 */
public record ProviderDispatch(RunRef run, ActionRef action, String invocationId, String attemptId,
        PortalCallKind callKind, ResultRef requestRef, ResultRef capabilityRef, String fingerprint, long fence) {

    /** 拒绝缺失关键字段、空白身份、跨租户动作或非正 fence，不补齐默认来源。 */
    public ProviderDispatch {
        run = PortContracts.requireNonNull(run, "run");
        action = PortContracts.requireNonNull(action, "action");
        invocationId = PortContracts.requireText(invocationId, "invocationId");
        attemptId = PortContracts.requireText(attemptId, "attemptId");
        callKind = PortContracts.requireNonNull(callKind, "callKind");
        requestRef = PortContracts.requireNonNull(requestRef, "requestRef");
        capabilityRef = PortContracts.requireNonNull(capabilityRef, "capabilityRef");
        fingerprint = PortContracts.requireText(fingerprint, "fingerprint");
        fence = PortContracts.requirePositive(fence, "fence");
        PortContracts.requireSameTenant(run.tenantId(), action.tenantId(), "action.tenantId");
    }

    /** 动作、请求、外部调用及能力身份不进入普通对象日志。 */
    @Override public String toString() { return "ProviderDispatch[kind=" + callKind + ",REDACTED]"; }
}
