package com.skillforge.core.model;

import com.skillforge.core.foundation.access.AuthorizedSqlRule;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 领域在已授权事务窗口内允许访问的固定 SQL，未命中不产生权限。 */
@Component
public final class ModelAuthorizedSqlRule implements AuthorizedSqlRule {
    private static final Set<String> READERS = Set.of("com.skillforge.core.model.PlatformModelMapper");
    @Override public boolean allows(Access access) {
        if (access.window() == Window.PLATFORM_OBSERVATION) {
            return access.query() && !access.settings() && "foundation.sessions.read".equals(access.action())
                    && access.targetTenants().equals(Set.of(access.tenant()))
                    && Set.of("com.skillforge.core.model.invocation.ModelCallRecordMapper.find",
                              "com.skillforge.core.model.invocation.ModelCallRecordMapper.statuses").contains(access.statementId())
                    && access.parameterEquals("tenant", access.tenant());
        }
        return access.window() == Window.INSTITUTION_DEPLOYMENT && access.query() && !access.settings()
                && READERS.contains(access.namespace());
    }
}
