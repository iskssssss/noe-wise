package com.skillforge.institution.controller;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.service.WorkflowCreationService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/builder/workflows/create-with-receipt")
public class WorkflowCreationController {
    private final WorkflowCreationService service;
    public WorkflowCreationController(WorkflowCreationService service) { this.service=service; }
    @PostMapping
    public ApiResponse<WorkflowCreationService.Result> create(@RequestBody WorkflowCreationService.Command command) {
        try { return ApiResponses.wrap(service.create(command)); }
        catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value()==408 || "WORKFLOW_CREATE_IDENTITY_CONFLICT".equals(failure.getCode())) throw failure;
            throw new ApiException(failure.getStatus(),"WORKFLOW_CREATE_REJECTED",String.valueOf(failure.getDetail()),false);
        }
    }
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<WorkflowCreationService.Result>> result(@RequestParam("request_id") UUID requestId) {
        var result=service.result(requestId);
        return result==null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }
}
