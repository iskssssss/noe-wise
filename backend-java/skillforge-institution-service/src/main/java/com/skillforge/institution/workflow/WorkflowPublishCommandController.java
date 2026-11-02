package com.skillforge.institution.workflow;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/builder/workflows/save-and-publish")
public class WorkflowPublishCommandController {
    public static final String PRE_DISPATCH_REJECTED = "WORKFLOW_PUBLISH_PRE_DISPATCH_REJECTED";
    private final WorkflowPublishCommandService service;
    public WorkflowPublishCommandController(WorkflowPublishCommandService service) { this.service = service; }

    @PostMapping
    public ApiResponse<WorkflowPublishCommandService.Receipt> publish(@RequestBody WorkflowPublishCommandService.Command command) {
        return ApiResponses.wrap(service.publish(Long.parseLong(TenantIds.requireTenantId()), command));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> invalidBody(HttpMessageNotReadableException failure) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure(
                PRE_DISPATCH_REJECTED, "工作流发布请求格式无效", false, java.util.Map.of()));
    }

    @GetMapping("/result")
    public ResponseEntity<ApiResponse<WorkflowPublishCommandService.Receipt>> result(
            @RequestParam("workflow_id") String workflowId, @RequestParam("request_id") UUID requestId) {
        var receipt = service.result(Long.parseLong(TenantIds.requireTenantId()), workflowId, requestId);
        return receipt == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(receipt));
    }
}
