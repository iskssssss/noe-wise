package com.skillforge.common.context;

import java.util.Set;

/** 已认证 Tenant Open Platform 服务身份的请求线程上下文。 */
public final class ServicePrincipalContext {

    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private ServicePrincipalContext() {
    }

    public static void set(Identity identity) {
        CURRENT.set(identity);
    }

    public static Identity get() {
        return CURRENT.get();
    }

    public static boolean hasScope(String scope) {
        Identity identity = CURRENT.get();
        return identity != null && scope != null && identity.scopes().contains(scope);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 冻结认证结果；调用方不得从请求正文推断 tenant、integration 或 scope。 */
    public record Identity(
            String principalId,
            String tenantId,
            String integrationId,
            long credentialVersion,
            Set<String> scopes) {
        public Identity {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }
}
