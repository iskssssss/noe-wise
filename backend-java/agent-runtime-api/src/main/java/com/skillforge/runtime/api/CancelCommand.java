package com.skillforge.runtime.api;

/** 将取消命令与目标运行及其 expected-revision CAS 前提绑定。 */
public record CancelCommand(
        InboundEnvelope envelope,
        RunRef runRef,
        long expectedRevision) {

    /** 拒绝非 command、非 ExistingRun、目标不相等、跨租户或无效 revision 的取消请求。 */
    public CancelCommand {
        envelope = ApiContracts.requireNonNull(envelope, "envelope");
        runRef = ApiContracts.requireNonNull(runRef, "runRef");
        expectedRevision = ApiContracts.requirePositive(expectedRevision, "expectedRevision");
        if (envelope.kind() != InboundKind.COMMAND) {
            throw new IllegalArgumentException("cancel envelope kind must be COMMAND");
        }
        if (!(envelope.runTarget() instanceof RunTarget.ExistingRun existingRun)) {
            throw new IllegalArgumentException("cancel envelope runTarget must be ExistingRun");
        }
        if (!existingRun.runRef().equals(runRef)) {
            throw new IllegalArgumentException("cancel envelope target must equal runRef");
        }
        ApiContracts.requireSameTenant(envelope.sessionRef(), runRef);
    }
}
