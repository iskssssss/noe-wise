package com.skillforge.core.billing.export;

import org.springframework.web.bind.annotation.BindParam;

/** 历史筛选候选只由当前用量动作授权；id 精确回显不受搜索结果上限影响。 */
public record PlatformUsageOptionsQuery(String field, String view, String q, String id,
        @BindParam("tenant_id") Long tenantId) { }
