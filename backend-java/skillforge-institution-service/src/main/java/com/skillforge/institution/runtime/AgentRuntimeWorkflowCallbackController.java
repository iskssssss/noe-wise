package com.skillforge.institution.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.runtime.adapter.jdbc.callback.CallbackPayloads;
import com.skillforge.core.foundation.integration.runtime.callback.JdbcCallbackSigningKeys;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionLedgerPort;
import com.skillforge.core.foundation.integration.runtime.identity.CallbackPolicyResource;
import com.skillforge.core.foundation.integration.runtime.identity.JdbcIdentityPolicyPort;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.*;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.spi.SessionWorkflowWakeups;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 为当前集成自身的 async_task 等待登记签名回调；目标只来自原 Run 的持久事实。 */
@RestController
@RequestMapping("/api/v1/open-platform/sessions")
public final class AgentRuntimeWorkflowCallbackController {
    private final SessionService sessions;
    private final ExecutionStore store;
    private final JdbcExecutionLedgerPort executions;
    private final JdbcIdentityPolicyPort identities;
    private final SdkWorkflowPortal workflows;
    private final CallbackRegistrationPort callbacks;

    public AgentRuntimeWorkflowCallbackController(SessionService sessions, ExecutionStore store,
            JdbcExecutionLedgerPort executions, JdbcIdentityPolicyPort identities,
            SdkWorkflowPortal workflows, CallbackRegistrationPort callbacks) {
        this.sessions=sessions; this.store=store; this.executions=executions;
        this.identities=identities; this.workflows=workflows; this.callbacks=callbacks;
    }

    @PostMapping(value = "/workflow-callbacks", produces = "application/json")
    public ApiResponse<Response> register(@Valid @RequestBody Request request) {
        var caller=ServicePrincipalContext.get();
        if(caller==null||!caller.scopes().contains("sessions.write"))throw denied();
        var session=sessions.require(caller.tenantId(),request.sessionId());
        if(!("open-platform:"+caller.integrationId()).equals(session.getScopeType()))throw denied();
        if(sessions.isOpenPlatformClosed(session))throw conflict();
        var tenant=new TenantId(caller.tenantId());
        var target=session.getAgentId()!=null?SessionTarget.agent(new AgentId(session.getAgentId().toString()))
                :SessionTarget.team(session.getTeamId());
        var owner=SessionOwner.user(new SessionRef(tenant,session.getSessionId()),target,new EndUserId(session.getEndUserId()));
        var run=store.load(new RunRef(tenant,request.runId())).orElseThrow(AgentRuntimeWorkflowCallbackController::denied);
        if(!run.identity().owner().equals(owner))throw denied();
        if(run.phase()!=ExecutionStore.Phase.WAITING||run.cancelled()||run.waiting().isEmpty())throw conflict();
        var runWait=store.waiting(run.identity().runRef(),run.waiting().orElseThrow()).orElseThrow(AgentRuntimeWorkflowCallbackController::conflict);
        if(runWait.kind()!=ExecutionStore.WaitKind.WORKFLOW||!(runWait.internal().orElse(null) instanceof ExecutionStore.WorkflowWait exact))throw conflict();
        var principal=new PrincipalRef.ServicePrincipal(tenant,caller.principalId(),caller.integrationId(),caller.credentialVersion());
        var original=identities.originalVerification(owner,run.identity().causationIdentityRef()).orElseThrow(AgentRuntimeWorkflowCallbackController::denied);
        if(!original.principal().equals(principal))throw denied();
        var auth=identities.registerTrusted(principal,owner,"open-platform","request",Duration.ofMinutes(5));
        var verification=identities.verify(new IdentityPolicyPort.IdentityRequest(auth,owner,"open-platform","request",
                new RequestId("workflow-callback:"+UUID.randomUUID()),Optional.empty(),run.identity().trace().traceId(),run.identity().trace().spanId()));
        if(!(verification instanceof IdentityPolicyPort.IdentityResult.Verified verified))throw denied();
        var actor=verified.identity();
        var execution=executions.find(new ExecutionLedgerPort.ExecutionLookup(actor,exact.execution())).orElseThrow(AgentRuntimeWorkflowCallbackController::conflict);
        try {AgentRuntimeWorkflowRecoveryWorker.requireWaiting(run,runWait,execution);} catch(IllegalArgumentException rejected) {throw conflict();}
        final Optional<java.time.Instant> deadline;
        try {deadline=workflows.requireAsyncTaskWait(actor,execution);} catch(IllegalArgumentException rejected) {throw conflict();}
        var wait=execution.waiting().orElseThrow();
        String id=id(execution.executionRef(),execution.executionRevision());
        var draft=new CallbackRegistrationDraft(new CallbackRef(tenant,id),owner,run.identity().agentId(),
                new CallbackTarget.ExecutionTarget(execution.executionRef(),run.identity().runRef(),wait.actionKey(),execution.executionRevision()),
                "workflow-async-task",CallbackPayloads.EXECUTION_SCHEMA,CallbackPayloads.SIGNATURE_POLICY,JdbcCallbackSigningKeys.reference(principal));
        var command=new RegisterCallback(draft,actor,Optional.of(run.identity()),run.identity().causationInputId(),run.identity().trace(),
                run.identity().causationIdentityRef(),new IdempotencyKey(id),id);
        // 重复注册仍核验当前权限；原注册不会因本次 verificationRef 改变而被改写。
        if(!(identities.authorize(new IdentityPolicyPort.AuthorizationRequest(actor,"callback.register",CallbackPolicyResource.registration(command),
                run.identity().trace().traceId(),run.identity().trace().spanId())) instanceof IdentityPolicyPort.AuthorizationResult.Allowed))throw denied();
        var existing=callbacks.find(draft.callbackRef());
        if(existing.isEmpty()) {
            var result=callbacks.register(command);
            if(result instanceof CallbackRegisterResult.Rejected)throw denied();
            existing=callbacks.find(draft.callbackRef());
        }
        var registration=existing.orElseThrow(AgentRuntimeWorkflowCallbackController::conflict);
        if(!registration.draft().equals(draft)||!registration.causationIdentityRef().equals(run.identity().causationIdentityRef())
                ||registration.status()!=CallbackRegistrationStatus.ACTIVE)throw conflict();
        var response=new Response(id,"/api/v1/runtime/callbacks/deliveries",tenant.value(),
                execution.executionRef().executionId(),run.identity().runRef().runId(),wait.actionKey(),
                wait.suspendRef(),Long.toString(execution.executionRevision()),Long.toString(run.revision()),
                runWait.reference().action().actionId(),Long.toString(runWait.reference().generation()),deadline.orElse(null),
                CallbackPayloads.EXECUTION_SCHEMA,SessionWorkflowWakeups.ASYNC_RESPONSE_SCHEMA,
                CallbackPayloads.SIGNATURE_POLICY);
        return ApiResponses.wrap(response);
    }

    private static String id(ExecutionRef ref,long revision) {
        try {return "workflow-async:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (ref.tenantId().value()+"\n"+ref.executionId()+"\n"+revision).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static ApiException denied() {return new ApiException(HttpStatus.FORBIDDEN,"workflow_callback_source_rejected");}
    private static ApiException conflict() {return new ApiException(HttpStatus.CONFLICT,"workflow_async_wait_unavailable");}
    public record Request(@JsonProperty("session_id") @NotBlank String sessionId,@JsonProperty("run_id") @NotBlank String runId) { }

    /** 签名回调登记的公开字段；显式响应类型使运行时 OpenAPI 保留完整契约。 */
    @Schema(name = "WorkflowCallback")
    public record Response(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String callbackId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String deliveryPath,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String tenantId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String executionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String runId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String actionKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String suspendRef,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[1-9][0-9]*") String executionRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[1-9][0-9]*") String runRevision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String actionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[1-9][0-9]*") String generation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}) Instant deadlineAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String payloadSchema,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String responseSchema,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String signaturePolicy) { }
}
