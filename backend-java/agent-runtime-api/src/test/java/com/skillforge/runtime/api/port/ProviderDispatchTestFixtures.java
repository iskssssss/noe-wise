package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.ResultRef;

/** Port纯值契约的明确派发夹具；不伪装Store当前IAM或实际发送许可。 */
final class ProviderDispatchTestFixtures {
    private ProviderDispatchTestFixtures() { }
    static ProviderCallContext providerContext(PortCallContext context,PortalCallKind kind) {
        return dispatched(context,kind,"execute-attempt-1");
    }
    static ProviderCallContext reconcileContext(PortCallContext context) {
        return dispatched(context,PortalCallKind.WORKFLOW_RECOVER_RESULT,"reconcile-attempt-1");
    }
    private static ProviderCallContext dispatched(PortCallContext context,PortalCallKind kind,String attempt) {
        return ProviderCallContext.dispatched(context,new ProviderDispatch(context.runIdentity().runRef(),
                new ActionRef(context.runIdentity().runRef().tenantId(),"action-1"),"invocation-1",attempt,kind,
                new ResultRef("invocation-snapshot-1"),new ResultRef("fixture-capability"),"request-fingerprint-1",1));
    }
}
