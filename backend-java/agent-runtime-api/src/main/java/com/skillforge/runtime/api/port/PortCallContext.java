package com.skillforge.runtime.api.port;

/** 固定一次 Runtime Port 调用的运行、授权与取消上下文，不授予外部调用权限。 */
public record PortCallContext(
        RunIdentity runIdentity,
        VerifiedIdentity identity,
        InvocationControl control) {
    /** 拒绝运行归属与当前验证身份不一致的调用。 */
    public PortCallContext {
        runIdentity = PortContracts.requireNonNull(runIdentity, "runIdentity");
        identity = PortContracts.requireNonNull(identity, "identity");
        control = PortContracts.requireNonNull(control, "control");
        if (!runIdentity.owner().equals(identity.owner())) {
            throw new IllegalArgumentException("runIdentity owner must match verified identity owner");
        }
    }
}
