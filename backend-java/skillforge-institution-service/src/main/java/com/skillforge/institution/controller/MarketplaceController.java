package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.core.workflow.WorkflowTemplateService;
import com.skillforge.core.workflow.dto.query.MarketplaceSearchQuery;
import com.skillforge.core.workflow.dto.response.MarketplaceSearchResponse;
import com.skillforge.core.workflow.dto.response.MarketplaceWorkflowDetailResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 工作流市场传输入口；模板发布规则由工作流域执行。 */
@RestController
@RequestMapping("/api/v1/marketplace")
public class MarketplaceController {
    private final WorkflowTemplateService templates;

    public MarketplaceController(WorkflowTemplateService templates) { this.templates = templates; }

    @GetMapping("/search")
    public ApiResponse<MarketplaceSearchResponse> search(MarketplaceSearchQuery query) {
        return ApiResponses.wrap(templates.search(query));
    }

    @GetMapping("/workflows/detail")
    public ApiResponse<MarketplaceWorkflowDetailResponse> getWorkflowDetail(@RequestParam("workflowId") String workflowId) {
        return ApiResponses.wrap(templates.detail(workflowId));
    }
}
