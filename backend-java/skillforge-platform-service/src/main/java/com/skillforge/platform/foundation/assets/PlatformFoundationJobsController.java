package com.skillforge.platform.foundation.assets;

import com.skillforge.core.foundation.jobs.FoundationJobService;
import com.skillforge.core.foundation.jobs.web.FoundationJobsEndpoint;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台管理任务只访问平台私有作用域。 */
@RestController
@RequestMapping("/api/v1/admin/foundation/jobs")
public class PlatformFoundationJobsController extends FoundationJobsEndpoint {
    /** 接入同一管理任务服务，平台队列不代执行租户任务。 */ public PlatformFoundationJobsController(FoundationJobService jobs){super(jobs);}
}
