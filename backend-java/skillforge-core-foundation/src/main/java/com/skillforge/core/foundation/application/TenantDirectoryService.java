package com.skillforge.core.foundation.application;

import com.skillforge.core.foundation.persistence.TenantMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/** 后台任务使用的机构身份目录；返回不可变 ID 列表，不跨领域暴露机构实体。 */
@Service
public final class TenantDirectoryService {
    private final TenantMapper tenants;

    public TenantDirectoryService(TenantMapper tenants) {
        this.tenants = java.util.Objects.requireNonNull(tenants, "tenants");
    }

    public List<String> tenantIds() {
        return tenants.selectTenantIds().stream().map(String::valueOf).toList();
    }

    public List<String> activeTenantIds() {
        return tenants.selectActiveTenantIds().stream().map(String::valueOf).toList();
    }
}
