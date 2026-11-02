package com.skillforge.institution.service;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.agent.deployment.AgentTemplateGrantService;
import com.skillforge.core.foundation.institution.InstitutionDeploymentManagementService;
import com.skillforge.core.foundation.institution.InstitutionTargetOption;
import com.skillforge.core.foundation.institution.InstitutionTargetOptionMapper;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 机构授权与部署表单的目标选项，复用正式动作而不扩大目录或目标许可。 */
@Service
public class InstitutionTargetOptionService {
    private final AgentTemplateGrantService grants;
    private final InstitutionDeploymentManagementService deployments;
    private final InstitutionTargetOptionMapper mapper;

    public InstitutionTargetOptionService(AgentTemplateGrantService grants,
            InstitutionDeploymentManagementService deployments, InstitutionTargetOptionMapper mapper) {
        this.grants = grants; this.deployments = deployments; this.mapper = mapper;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page(List<InstitutionTargetOption> items, long total, int page, int pageSize) {
        public Page { items = List.copyOf(items); }
    }

    /** 当前动作授权、当前目标关系和分页计数读取同一事务快照；写入时仍重新核验。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page targets(String operation, String query, String id, int page, int size) {
        if (query == null || query.length() > 200 || query.chars().anyMatch(Character::isISOControl)
                || page < 1 || size < 1 || size > 200) throw invalid();
        Long selected = parseId(id);
        boolean direct = "tenant.agent_grants.create".equals(operation);
        long actor;
        Set<Long> targets;
        if (direct) {
            actor = grants.requireTenantGrantTargetOptions();
            targets = Set.of();
        } else {
            var scope = deployments.requireTargetOptions(operation);
            actor = scope.actorTenantId(); targets = scope.tenantIds();
        }
        return new Page(mapper.rows(actor, direct, targets, query, selected, size, ((long) page - 1) * size),
                mapper.count(actor, direct, targets, query, selected), page, size);
    }

    private Long parseId(String value) {
        if (value == null) return null;
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw invalid(); }
    }
    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "机构候选查询参数不合法", false);
    }
}
