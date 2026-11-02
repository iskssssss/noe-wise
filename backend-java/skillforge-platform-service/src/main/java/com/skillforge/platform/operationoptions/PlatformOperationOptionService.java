package com.skillforge.platform.operationoptions;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.integration.PlatformManagementService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.platform.controller.PlatformOperationOptionMapper;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台操作表单的最小候选投影；复用真实动作/对象闭包，不附加宽泛目录读取权。 */
@Service
public class PlatformOperationOptionService {
    private static final Set<String> TENANT_OPERATIONS = Set.of(
            "platform.institutions.read", "platform.institutions.associate",
            "platform.agent_grants.read", "platform.agent_grants.create",
            "platform.agent_deployments.read", "platform.agent_deployments.create",
            "platform.institution_credentials.read", "platform.institution_credentials.issue",
            "platform.management_principals.create");
    private static final Set<String> DEFINITION_OPERATIONS = Set.of(
            "platform.agent_grants.create", "platform.management_principals.create");
    private static final Set<String> INTEGRATION_OPERATIONS = Set.of(
            "platform.agent_deployments.create", "platform.institution_credentials.read",
            "platform.institution_credentials.issue");
    private final PlatformManagementService management;
    private final PlatformOperationOptionMapper mapper;

    public PlatformOperationOptionService(PlatformManagementService management, PlatformOperationOptionMapper mapper) {
        this.management = management;
        this.mapper = mapper;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Page(List<PlatformOperationOption> items, long total, int page, int pageSize) {
        public Page { items = List.copyOf(items); }
    }

    /** 查询动作从封闭白名单选择，当前人员/机器的目标授权由唯一管理服务重读。 */
    @Transactional(readOnly = true)
    public Page tenants(String operation, String query, String id, int page, int pageSize) {
        validate(operation, TENANT_OPERATIONS, query, page, pageSize);
        Long target = decimalId(id);
        return inContext(null, () -> {
            Set<Long> allowed = management.allowedTenantIds(operation);
            return new Page(mapper.tenants(allowed, query, target, pageSize, offset(page, pageSize)),
                    mapper.tenantCount(allowed, query, target), page, pageSize);
        });
    }

    /** 直授只展示平台已发布且受控部署的源；管理身份签发使用同一 manage 目标范围。 */
    @Transactional(readOnly = true)
    public Page definitions(String operation, String query, String id, int page, int pageSize) {
        validate(operation, DEFINITION_OPERATIONS, query, page, pageSize);
        Long target = decimalId(id);
        return inContext(null, () -> {
            Set<Long> allowed = management.allowedDefinitionIds(operation);
            boolean grant = operation.equals("platform.agent_grants.create");
            return new Page(mapper.definitions(allowed, grant, query, target, pageSize, offset(page, pageSize)),
                    mapper.definitionCount(allowed, grant, query, target), page, pageSize);
        });
    }

    /** 接入包括原生/手工登记域，先核验目标机构操作权限再进入其只读 RLS 上下文。 */
    @Transactional(readOnly = true)
    public Page integrations(String operation, String tenantId, String query, String id, int page, int pageSize) {
        validate(operation, INTEGRATION_OPERATIONS, query, page, pageSize);
        Long tenant = decimalId(tenantId);
        if (tenant == null) throw invalid("tenant_id 必填");
        if (id != null && (id.isBlank() || id.length() > 128)) throw invalid("接入标识不合法");
        return inContext(null, () -> {
            management.requireTenant(operation, tenant);
            return inContext(Long.toString(tenant), () -> new Page(
                    mapper.integrations(tenant, query, id, pageSize, offset(page, pageSize)),
                    mapper.integrationCount(tenant, query, id), page, pageSize));
        });
    }

    private static void validate(String operation, Set<String> operations, String query, int page, int pageSize) {
        if (operation == null || !operations.contains(operation)) throw invalid("不支持的候选操作");
        if (page < 1 || pageSize < 1 || pageSize > 200) throw invalid("候选分页超出允许范围");
        if (query == null || query.codePointCount(0, query.length()) > 200) throw invalid("搜索文字超出允许范围");
    }

    private static Long decimalId(String value) {
        if (value == null) return null;
        if (!value.matches("[1-9][0-9]{0,18}")) throw invalid("目标标识必须为十进制字符串");
        try { return Long.parseLong(value); }
        catch (NumberFormatException failure) { throw invalid("目标标识超出范围"); }
    }

    private static long offset(int page, int pageSize) { return ((long) page - 1) * pageSize; }

    /** 任一成功/失败路径恢复调用前上下文；选择目标不会改变当前执行身份。 */
    private static <T> T inContext(String tenant, Supplier<T> operation) {
        String previous = TenantContext.getTenantId();
        try {
            if (tenant == null) TenantContext.clear(); else TenantContext.setTenantId(tenant);
            return operation.get();
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, false);
    }
}
