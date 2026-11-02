package com.skillforge.runtime.starter;

import com.skillforge.runtime.api.TenantId;

/**
 * 宿主把 worker 轮询绑定到自己的租户执行上下文。
 *
 * <p>Starter 不读取宿主 ThreadLocal；灵知 必须提供实现，把
 * {@code TenantContext} 设到当前租户后再执行轮询，轮询结束必须清理。</p>
 */
@FunctionalInterface
public interface AgentRuntimeTenantBinder {

    /**
     * 在指定租户上下文中执行一次 worker 轮询。
     *
     * @param tenant 当前轮询租户
     * @param work 已解析租户后的领取与驱动
     */
    void bind(TenantId tenant, Runnable work);
}
