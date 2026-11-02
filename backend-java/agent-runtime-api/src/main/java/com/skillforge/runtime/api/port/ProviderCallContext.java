package com.skillforge.runtime.api.port;

/** Provider调用的可信上下文与唯一原派发事实；本值不签发新的发送或重试权。 */
public final class ProviderCallContext {
    private final PortCallContext callContext;
    private final ProviderDispatch dispatch;

    private ProviderCallContext(PortCallContext callContext,ProviderDispatch dispatch) {
        this.callContext=PortContracts.requireNonNull(callContext,"callContext");
        this.dispatch=PortContracts.requireNonNull(dispatch,"dispatch");
        if(!dispatch.run().equals(callContext.runIdentity().runRef()))
            throw new IllegalArgumentException("dispatch run must match call context runRef");
        PortContracts.requireSameTenant(callContext.runIdentity().owner().sessionRef().tenantId(),
                dispatch.action().tenantId(),"dispatch.action.tenantId");
    }

    /** 只接收真实登记回执转换出的原身份，Provider消费前仍须由Host复验当前发送权。 */
    public static ProviderCallContext dispatched(PortCallContext callContext,ProviderDispatch dispatch) {
        return new ProviderCallContext(callContext,dispatch);
    }
    public PortCallContext callContext(){return callContext;}
    public ProviderDispatch dispatch(){return dispatch;}
    public PortalCallKind callKind(){return dispatch.callKind();}
    @Override public boolean equals(Object other){return this==other||other instanceof ProviderCallContext context
            &&callContext.equals(context.callContext)&&dispatch.equals(context.dispatch);}
    @Override public int hashCode(){return 31*callContext.hashCode()+dispatch.hashCode();}
    @Override public String toString(){return "ProviderCallContext[source=DISPATCH,REDACTED]";}
}
