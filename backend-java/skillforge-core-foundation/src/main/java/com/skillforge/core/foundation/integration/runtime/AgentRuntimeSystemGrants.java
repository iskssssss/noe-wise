package com.skillforge.core.foundation.integration.runtime;

import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.PrincipalRef;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统主体授权的运行期登记表。
 *
 * <p>启动时写入配置中的静态授权；宿主在为租户完成最小装配后可再登记同一 worker
 * 主体。同主体授权漂移失败关闭，空表禁用系统入口。</p>
 */
public final class AgentRuntimeSystemGrants {
    private final ConcurrentHashMap<PrincipalRef.SystemPrincipal, Set<String>> grants =
            new ConcurrentHashMap<>();

    AgentRuntimeSystemGrants(AgentRuntimePlatformProperties properties) {
        Objects.requireNonNull(properties, "properties");
        for (var grant : properties.getSystemGrants()) {
            register(new TenantId(grant.getTenantId()), grant.getPrincipalId(), grant.getActions());
        }
    }

    AgentRuntimeSystemGrants() {
    }

    /**
     * 为租户登记系统主体授权；已存在且内容相同则重放，内容漂移则拒绝。
     */
    public void register(TenantId tenantId, String principalId, Set<String> actions) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (principalId == null || principalId.isBlank()) {
            throw new IllegalArgumentException("system grant principal must not be blank");
        }
        if (actions == null || actions.isEmpty()
                || actions.stream().anyMatch(action -> action == null || action.isBlank())) {
            throw new IllegalArgumentException("system grant actions are invalid");
        }
        var principal = new PrincipalRef.SystemPrincipal(tenantId, principalId);
        Set<String> normalized = Set.copyOf(actions);
        Set<String> previous = grants.putIfAbsent(principal, normalized);
        if (previous != null && !previous.equals(normalized)) {
            throw new IllegalStateException("agent_runtime_system_grant_mismatch");
        }
    }

    /** 供 IAM 读取的实时视图；缺失主体返回 {@code null}。 */
    Map<PrincipalRef.SystemPrincipal, Set<String>> view() {
        return grants;
    }

    Set<TenantId> tenantIds() {
        var tenants = new LinkedHashSet<TenantId>();
        for (var principal : grants.keySet()) {
            tenants.add(principal.tenantId());
        }
        return Set.copyOf(tenants);
    }
}
