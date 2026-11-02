package com.skillforge.institution.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.chat.access.OpenPlatformSessionAccessService;
import com.skillforge.core.chat.dto.openplatform.OpenPlatformInboundReceiptResponse;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 正式发布Workflow的明确调用入口，沿用会话归属、受理与恢复契约。 */
@RestController
@RequestMapping(value="/api/v1/open-platform/sessions/workflow-invocations",produces="application/json")
public final class OpenPlatformWorkflowInvocationController {
    private final OpenPlatformSessionAccessService access;
    private final SessionService sessions;
    private final AgentRuntimeExecutionGateway runtime;
    private final ObjectMapper json;
    private final AgentRuntimePayloadValidation payloads;

    public OpenPlatformWorkflowInvocationController(OpenPlatformSessionAccessService access,SessionService sessions,
            AgentRuntimeExecutionGateway runtime,ObjectMapper json,AgentRuntimePayloadValidation payloads) {
        this.access=access;this.sessions=sessions;this.runtime=runtime;
        this.json=json;this.payloads=payloads;
    }

    @PostMapping
    @Operation(summary="调用会话当前获授权的正式Workflow版本",description="同一会话及Idempotency-Key相同内容返回原受理回执，异内容409。execution_id复用现有状态、事件、等待响应及取消入口。")
    public ApiResponse<OpenPlatformInboundReceiptResponse> invoke(@Valid @RequestBody Command command,
            @Parameter(required=true,schema=@Schema(minLength=1,maxLength=256))
            @RequestHeader("Idempotency-Key") String key) {
        var current=access.require("sessions.write");
        if(command==null||command.sessionId()==null||command.sessionId().isBlank()||key==null||key.isBlank()||key.length()>256
                ||command.parameters()==null||!command.parameters().isObject())
            throw new ApiException(HttpStatus.BAD_REQUEST,"workflow_invocation_invalid","需要session_id、Idempotency-Key和对象类型业务参数",false);
        final String canonical;
        try {
            // 只排序对象键，保留原数值节点，避免经Object或double转换损失业务精度。
            canonical=json.writeValueAsString(ordered(command.parameters()));
        } catch(com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"workflow_invocation_invalid",invalid);
        }
        var parameters=new TypedPayload("application/json",canonical);
        if(!payloads.accepts(parameters))throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"workflow_invocation_invalid","业务参数超过Runtime输入配额",false);
        var invocation=new AgentRuntimeExecutionGateway.WorkflowInvocation(command.mountId(),command.capabilityId(),command.versionId(),parameters);
        var session=access.session(current,command.sessionId());
        if(sessions.isOpenPlatformClosed(session))throw new ApiException(HttpStatus.CONFLICT,"会话已关闭");
        return ApiResponses.wrap(runtime.acceptWorkflow(current.principal(),session,
                OpenPlatformInputIds.create(current.principal(),session.getSessionId(),"workflow-invocation",key),invocation));
    }

    /** 动态参数按对象键排序；数组具有业务顺序，标量保持外部解析后的准确值。 */
    private JsonNode ordered(JsonNode value) {
        if(value.isObject()) {
            var result=json.createObjectNode();
            var names=new java.util.ArrayList<String>();value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for(String name:names)result.set(name,ordered(value.get(name)));
            return result;
        }
        if(value.isArray()) {
            var result=json.createArrayNode();value.forEach(item->result.add(ordered(item)));return result;
        }
        return value;
    }

    public record Command(
            @JsonProperty("session_id") @NotBlank String sessionId,
            @JsonProperty("mount_id") @NotBlank @Schema(type="string",description="当前会话Agent的精确Workflow挂载ID") String mountId,
            @JsonProperty("capability_id") @NotBlank @Pattern(regexp="[1-9][0-9]*") @Schema(type="string",description="当前会话Agent已挂载的正式Workflow能力ID") String capabilityId,
            @JsonProperty("version_id") @NotBlank @Pattern(regexp="[1-9][0-9]*") @Schema(type="string",description="精确正式发布版本ID") String versionId,
            @JsonProperty("parameters") @NotNull @Schema(type="object",description="Workflow输入schema定义的业务参数，不承担身份或授权语义") JsonNode parameters) {}
}
