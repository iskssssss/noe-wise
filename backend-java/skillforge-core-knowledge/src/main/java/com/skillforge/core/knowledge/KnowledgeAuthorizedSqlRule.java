package com.skillforge.core.knowledge;

import com.skillforge.core.foundation.access.AuthorizedSqlRule;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 领域在已授权事务窗口内允许访问的固定 SQL，未命中不产生权限。 */
@Component
public final class KnowledgeAuthorizedSqlRule implements AuthorizedSqlRule {
    private static final Set<String> READERS = Set.of("com.skillforge.core.knowledge.mapper.KnowledgeBaseMapper",
            "com.skillforge.core.knowledge.mapper.KnowledgeBaseRevisionMapper");
    private static final String OBSERVATION_READ = "com.skillforge.core.knowledge.mapper.KnowledgeObservationAccessMapper.readable";
    @Override public boolean allows(Access access) {
        if (access.window() == Window.PLATFORM_OBSERVATION)
            return access.query() && !access.settings() && OBSERVATION_READ.equals(access.statementId())
                    && access.parameterEquals("tenant", access.tenant());
        return access.window() == Window.INSTITUTION_DEPLOYMENT && access.query() && !access.settings()
                && READERS.contains(access.namespace());
    }
}
