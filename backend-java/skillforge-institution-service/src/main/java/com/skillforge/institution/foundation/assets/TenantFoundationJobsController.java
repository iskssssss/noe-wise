package com.skillforge.institution.foundation.assets;

import com.skillforge.core.foundation.jobs.FoundationJobService;
import com.skillforge.core.foundation.jobs.web.FoundationJobsEndpoint;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 机构管理任务只访问当前可信机构作用域。 */
@RestController
@RequestMapping("/api/v1/tenant/foundation/jobs")
public class TenantFoundationJobsController extends FoundationJobsEndpoint {
    /** 接入同一管理任务服务，继承明确协议转换。 */ public TenantFoundationJobsController(FoundationJobService jobs){super(jobs);}
}
