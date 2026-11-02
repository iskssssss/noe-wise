package com.skillforge.institution.service;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.core.foundation.institution.InstitutionRelationService;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.integration.ProvisioningPlanOption;
import com.skillforge.core.shared.exception.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 总部开通表单只读选项，授权复用正式开通入口的当前主体/动作/总部状态。 */
@Service
public class SubInstitutionProvisionOptionService {
    private final InstitutionRelationService relations;
    private final InstitutionIntegrationMapper mapper;
    public SubInstitutionProvisionOptionService(InstitutionRelationService relations, InstitutionIntegrationMapper mapper) {
        this.relations = relations; this.mapper = mapper;
    }
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page(List<ProvisioningPlanOption> items, long total, int page, int pageSize) {
        public Page { items = List.copyOf(items); }
    }
    @Transactional(readOnly=true)
    public Page plans(String query, String code, int page, int size) {
        if (query == null || query.length() > 200 || page < 1 || size < 1 || size > 200
                || (code != null && (code.isBlank() || code.length() > 64)))
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","套餐查询参数不合法",false);
        relations.requireProvisionOptions();
        return new Page(mapper.activePlans(query,code,size,((long)page-1)*size),mapper.activePlanCount(query,code),page,size);
    }
}
