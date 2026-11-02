package com.skillforge.institution.group;
import static com.skillforge.institution.group.GroupMessageContracts.*;
import com.skillforge.common.context.*;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import com.skillforge.core.shared.web.*;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway.GroupRunDetail;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway.GroupRunResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 群公开来源、触发与原Run交互；不允许个人Session控制面绕过群归属。 */
@Validated @RestController
@RequestMapping(value="/api/v1/open-platform/groups",produces="application/json")
public class GroupMessageController {
 private final GroupMessageService messages;
 private final GroupBindingService groups;
 private final InstitutionIntegrationService integrations;
 private final AgentRuntimeExecutionGateway runtime;
 public GroupMessageController(GroupMessageService messages,GroupBindingService groups,InstitutionIntegrationService integrations,AgentRuntimeExecutionGateway runtime){this.messages=messages;this.groups=groups;this.integrations=integrations;this.runtime=runtime;}
 @PostMapping("/messages")
 public ApiResponse<MessageReceipt> record(@Valid @RequestBody MessageRecord command,@RequestHeader("Idempotency-Key") String key){return ApiResponses.wrap(messages.record(command,key));}
 @PostMapping("/triggers") @ResponseStatus(HttpStatus.ACCEPTED)
 public ApiResponse<TriggerReceipt> trigger(@Valid @RequestBody Trigger command,@RequestHeader("Idempotency-Key") String key){return ApiResponses.wrap(messages.trigger(command,key));}
 @GetMapping("/triggers/detail")
 public ApiResponse<TriggerReceipt> detail(@RequestParam("group_binding_id") @NotBlank @Size(min=1,max=64) String group,@RequestParam("trigger_id") @NotBlank @Size(min=1,max=64) String trigger){return ApiResponses.wrap(messages.detail(group,trigger));}
 @GetMapping("/runs/detail")
 public ApiResponse<GroupRunDetail> run(@RequestParam("group_binding_id") @NotBlank @Size(min=1,max=64) String group,@RequestParam("run_id") @NotBlank @Size(min=1,max=128) String run){var actor=actor("groups.runs.read");var binding=groups.requireActive(actor.tenantId(),actor.integrationId(),group);return ApiResponses.wrap(runtime.groupDetail(actor.tenantId(),actor.integrationId(),group,binding.sessionId(),run));}
 @PostMapping("/runs/respond")
 public ApiResponse<GroupRunResponse> respond(@Valid @RequestBody RunRespond command,@RequestHeader("Idempotency-Key") String key){
  var actor=actor("groups.runs.respond");if(!Objects.equals(key,command.inputId()))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Idempotency-Key必须等于稳定input_id",false);
  var binding=groups.requireActive(actor.tenantId(),actor.integrationId(),command.groupBindingId());groups.requireMember(actor.tenantId(),actor.integrationId(),command.groupBindingId(),command.responderEndUserId(),false);
  return ApiResponses.wrap(runtime.groupRespond(actor.tenantId(),actor.integrationId(),command.groupBindingId(),binding.sessionId(),command.runId(),command.inputId(),command.responderEndUserId(),command.hitlResponse()));
 }
 @PostMapping("/runs/commands")
 public ApiResponse<GroupRunResponse> command(@Valid @RequestBody RunCommand command,@RequestHeader("Idempotency-Key") String key){
  var actor=actor("groups.runs.control");if(!Objects.equals(key,command.inputId()))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Idempotency-Key必须等于稳定input_id",false);
  if(!"cancel_execution".equals(command.action())&&!"steer".equals(command.action()))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","群控制action非法",false);
  if("steer".equals(command.action())&&(command.text()==null||command.text().isBlank()))throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","steer必须提供非空text",false);
  if("cancel_execution".equals(command.action())&&command.text()!=null)throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","取消请求不能附带steer文本",false);
  var binding=groups.requireActive(actor.tenantId(),actor.integrationId(),command.groupBindingId());groups.requireMember(actor.tenantId(),actor.integrationId(),command.groupBindingId(),command.operatorEndUserId(),false);
  return ApiResponses.wrap(runtime.groupCommand(actor.tenantId(),actor.integrationId(),command.groupBindingId(),binding.sessionId(),command.runId(),command.inputId(),command.operatorEndUserId(),command.action(),command.text()));
 }
 private ServicePrincipalContext.Identity actor(String scope){var actor=ServicePrincipalContext.get();if(actor==null||!Objects.equals(actor.tenantId(),TenantContext.getTenantId()))throw new ApiException(HttpStatus.FORBIDDEN,"群入口须使用当前机构固定执行身份");integrations.requirePrincipal(actor,scope);return actor;}
}
