package com.skillforge.platform.config;

import com.skillforge.core.shared.web.HostTenantContextInterceptor;
import org.springframework.stereotype.Component;

/** 注册共享的已认证租户上下文映射。 */
@Component
public class TenantContextInterceptor extends HostTenantContextInterceptor { }
