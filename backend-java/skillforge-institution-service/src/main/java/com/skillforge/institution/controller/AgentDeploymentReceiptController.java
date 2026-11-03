package com.skillforge.institution.controller;

import static com.skillforge.core.agent.deployment.AgentDeploymentContracts.*;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.service.AgentDeploymentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 机构管理端只读查询原部署创建请求。 */
@RestController
@RequestMapping("/api/v1/tenant/agent-deployments")
public class AgentDeploymentReceiptController {
    private final AgentDeploymentService deployments;
    public AgentDeploymentReceiptController(AgentDeploymentService deployments) { this.deployments=deployments; }
    @PostMapping("/receipt/query")
    public ApiResponse<Receipt<View>> receipt(@Valid @RequestBody Deploy command,@RequestHeader("Idempotency-Key") String key) {
        return ApiResponses.wrap(deployments.deployReceipt(command,key));
    }
}
