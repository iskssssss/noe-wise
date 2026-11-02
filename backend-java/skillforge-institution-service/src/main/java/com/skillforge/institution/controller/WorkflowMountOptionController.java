package com.skillforge.institution.controller;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.service.WorkflowMountOptionService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents/capability-mounts")
public class WorkflowMountOptionController {
    private final WorkflowMountOptionService options;
    public WorkflowMountOptionController(WorkflowMountOptionService options) {
        this.options = options;
    }

    @RequirePermission("foundation.workflows.read")
    @GetMapping("/workflow-options")
    public ApiResponse<List<WorkflowMountOptionService.Option>> list() {
        return ApiResponses.wrap(options.list());
    }
}
