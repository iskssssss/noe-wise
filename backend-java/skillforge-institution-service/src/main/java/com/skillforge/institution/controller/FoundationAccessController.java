package com.skillforge.institution.controller;

import com.skillforge.core.foundation.access.AccessEndpoints;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 机构组织权限固定入口；租户身份只取已验证登录上下文。 */
@RestController
@RequestMapping("/api/v1/tenant/foundation/access")
public class FoundationAccessController extends AccessEndpoints {
    /** 注入机构组织权限应用服务。 */
    public FoundationAccessController(FoundationOrganizationService service,FoundationAccess access) { super(service,access,false); }
}
