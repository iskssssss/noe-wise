package com.skillforge.platform.operationoptions;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.integration.InstitutionIntegrationMapper;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.foundation.integration.ManagementActions;
import com.skillforge.core.shared.exception.ApiException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 开通与管理身份签发选项，不将新增业务命名空间误当作必须已存在的资源。 */
@Service
public class PlatformProvisionOptionService {
    private static final String PROVISION = "platform.institutions.provision";
    private static final String ASSOCIATE = "platform.institutions.associate";
    private static final String CREATE_MANAGEMENT = "platform.management_principals.create";
    private final PlatformManagementService management;
    private final InstitutionIntegrationMapper mapper;

    public PlatformProvisionOptionService(PlatformManagementService management, InstitutionIntegrationMapper mapper) {
        this.management = management; this.mapper = mapper;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Namespaces(List<PlatformOperationOption> items, long total, int page, int pageSize, boolean allowCreate) {
        public Namespaces { items = List.copyOf(items); }
    }

    /** 开通权足以读取启用套餐；不依赖广泛机构目录权限。 */
    @Transactional(readOnly=true)
    public PlatformOperationOptionService.Page plans(String query, String code, int page, int size) {
        validate(query, page, size);
        if (code != null && (code.isBlank() || code.length() > 64)) throw invalid("套餐编码不合法");
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.clear(); management.requireAction(PROVISION);
            return new PlatformOperationOptionService.Page(mapper.activePlans(query, code, size, ((long) page - 1) * size)
                    .stream().map(option -> new PlatformOperationOption(option.id(), option.label(), option.description())).toList(),
                    mapper.activePlanCount(query, code), page, size);
        } finally { if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous); }
    }

    /** 受限签发者取所有已选命名空间相关动作的授予交集；all 人员可显式新建合法值。 */
    @Transactional(readOnly=true)
    public Namespaces namespaces(String operation, Set<String> scopes, String query, int page, int size) {
        validate(query, page, size);
        if (operation == null || !Set.of(PROVISION, ASSOCIATE, CREATE_MANAGEMENT).contains(operation)) throw invalid("不支持的命名空间操作");
        if (scopes == null || scopes.size() > 200 || !ManagementActions.SCOPES.containsAll(scopes)) throw invalid("管理动作不合法");
        if (!CREATE_MANAGEMENT.equals(operation) && !scopes.isEmpty()) throw invalid("开通或关联不接受签发动作集合");
        var base = management.authorizedNamespaceScope(operation);
        Set<String> allowed = new HashSet<>(base.namespaces());
        boolean allowCreate = base.allowCreate();
        if (CREATE_MANAGEMENT.equals(operation)) {
            for (String scope : scopes) management.requireAction(scope);
            var related = scopes.stream().filter(scope -> scope.equals(PROVISION) || scope.equals(ASSOCIATE)).sorted().toList();
            if (!related.isEmpty()) {
                allowed = null;
                for (String action : related) {
                    var current = management.authorizedNamespaceScope(action);
                    allowCreate &= current.allowCreate();
                    if (!current.allowCreate()) {
                        if (allowed == null) allowed = new HashSet<>(current.namespaces());
                        else allowed.retainAll(current.namespaces());
                    }
                }
                if (allowed == null) allowed = Set.of();
            } else if (!allowCreate) allowed = Set.of();
        }
        String text = query.toLowerCase(java.util.Locale.ROOT);
        var values = allowed.stream().filter(value -> value.toLowerCase(java.util.Locale.ROOT).contains(text)).sorted().toList();
        long offset = ((long) page - 1) * size;
        var items = values.stream().skip(offset).limit(size).map(value -> new PlatformOperationOption(value, value, "")).toList();
        return new Namespaces(items, values.size(), page, size, allowCreate);
    }

    private static void validate(String query, int page, int size) {
        if (query == null || query.codePointCount(0, query.length()) > 200 || page < 1 || size < 1 || size > 200) throw invalid("候选查询参数不合法");
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, false); }
}
