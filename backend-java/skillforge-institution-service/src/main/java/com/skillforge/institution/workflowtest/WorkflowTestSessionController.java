package com.skillforge.institution.workflowtest;

import com.skillforge.core.foundation.access.RequirePermission;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.skillforge.institution.workflowtest.WorkflowTestContracts.*;

/** 仅有资源创建、查询与关闭；没有 Builder run/resume/checkpoint 入口。 */
@RestController
@RequestMapping("/api/v1/builder/workflows/test-sessions")
public class WorkflowTestSessionController {
    private final WorkflowTestSessionService service;
    public WorkflowTestSessionController(WorkflowTestSessionService service) {this.service=service;}
    @RequirePermission("foundation.workflows.update")
    @GetMapping("/options")
    public ApiResponse<Options> options(@RequestParam("workflowId") String workflowId,
            @RequestParam(value="draftRevision",required=false) Long draftRevision,
            @RequestParam(value="definitionVersionId",required=false) String definitionVersionId) {
        return ApiResponses.wrap(service.options(workflowId,draftRevision,definitionVersionId));
    }
    @RequirePermission("foundation.workflows.update")
    @PostMapping
    public ApiResponse<Receipt> create(@RequestBody Create command) {return ApiResponses.wrap(service.create(command));}
    @RequirePermission("foundation.workflows.read")
    @GetMapping("/by-request")
    public ApiResponse<Receipt> byRequest(@RequestParam("requestId") String requestId) {return ApiResponses.wrap(service.byRequest(requestId));}
    @RequirePermission("foundation.workflows.read")
    @GetMapping("/reply-receipt")
    public ResponseEntity<ApiResponse<ReplyReceipt>> replyReceipt(
            @RequestParam("testSessionId") String testSessionId,
            @RequestParam("inputId") String inputId, @RequestParam("runId") String runId,
            @RequestParam("actionId") String actionId, @RequestParam("generation") long generation,
            @RequestParam("kind") String kind,
            @RequestParam(value="executionId",required=false) String executionId,
            @RequestParam(value="suspendRef",required=false) String suspendRef,
            @RequestParam(value="actionKey",required=false) String actionKey,
            @RequestParam(value="executionRevision",required=false) Long executionRevision) {
        var result=service.replyReceipt(testSessionId,inputId,runId,actionId,generation,kind,
                executionId,suspendRef,actionKey,executionRevision);
        return result==null?ResponseEntity.noContent().build():ResponseEntity.ok(ApiResponses.wrap(result));
    }
    @RequirePermission("foundation.workflows.read")
    @GetMapping
    public ApiResponse<Receipt> get(@RequestParam("testSessionId") String id) {return ApiResponses.wrap(service.get(id));}
    @RequirePermission("foundation.workflows.update")
    @PostMapping("/close")
    public ApiResponse<Receipt> close(@RequestBody Close command) {return ApiResponses.wrap(service.close(command.testSessionId()));}
}
