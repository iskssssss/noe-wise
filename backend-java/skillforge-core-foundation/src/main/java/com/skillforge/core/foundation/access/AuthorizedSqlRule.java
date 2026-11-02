package com.skillforge.core.foundation.access;

import java.util.Map;
import java.util.Set;

/** 领域声明已授权窗口允许的 SQL；规则不授予身份或扩大事务范围，未命中默认拒绝。 */
public interface AuthorizedSqlRule {
    boolean allows(Access access);

    enum Window { INSTITUTION_DEPLOYMENT, DEFINITION_IMPACT, HEADQUARTERS_USAGE, PLATFORM_OBSERVATION, BILLING_SCAN }

    /** 由拦截器从真实 Mapper 和已校验窗口构造，调用方不能自行声明权限。 */
    record Access(Window window, String statementId, boolean query, boolean settings,
            Object parameters, String tenant, long definitionId, Set<String> targetTenants,
            String originalTenant, String originalPlatform, String action) {
        public Access { targetTenants = Set.copyOf(targetTenants); }
        public String namespace() {
            int dot = statementId.lastIndexOf('.');
            return dot < 0 ? statementId : statementId.substring(0, dot);
        }
        public Object parameter(String name) {
            return parameters instanceof Map<?, ?> values ? values.get(name) : null;
        }
        public boolean parameterEquals(String name, String expected) {
            Object value = parameter(name);
            return value != null && expected.equals(value.toString());
        }
        public boolean bindsCurrentTenant() {
            return parameterEquals("key", "app.tenant_id") && parameterEquals("value", tenant)
                    || parameterEquals("key", "app.platform_admin") && parameterEquals("value", "false");
        }
        public boolean restoresTargetTenant() {
            return parameterEquals("tenant", tenant)
                    && (parameterEquals("platformAdmin", "false") || parameterEquals("platformAdmin", ""));
        }
    }
}
