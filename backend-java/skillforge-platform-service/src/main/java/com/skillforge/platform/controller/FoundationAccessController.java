package com.skillforge.platform.controller;

import com.skillforge.core.foundation.access.AccessEndpoints;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台组织权限固定入口，平台角色与机构角色隔离。 */
@RestController
@RequestMapping("/api/v1/admin/foundation/access")
public class FoundationAccessController extends AccessEndpoints {
    /** 注入平台组织权限应用服务。 */
    public FoundationAccessController(FoundationOrganizationService service,FoundationAccess access) { super(service,access,true); }
}
