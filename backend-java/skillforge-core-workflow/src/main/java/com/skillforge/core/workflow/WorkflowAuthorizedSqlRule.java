package com.skillforge.core.workflow;

import com.skillforge.core.foundation.access.AuthorizedSqlRule;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 领域在已授权事务窗口内允许访问的固定 SQL，未命中不产生权限。 */
@Component
public final class WorkflowAuthorizedSqlRule implements AuthorizedSqlRule {
    private static final Set<String> READERS = Set.of("com.skillforge.core.workflow.mapper.WorkflowDefinitionVersionMapper",
            "com.skillforge.core.workflow.mapper.WorkflowInstanceMapper");
    @Override public boolean allows(Access access) {
        return access.window() == Window.INSTITUTION_DEPLOYMENT && access.query() && !access.settings()
                && READERS.contains(access.namespace());
    }
}
