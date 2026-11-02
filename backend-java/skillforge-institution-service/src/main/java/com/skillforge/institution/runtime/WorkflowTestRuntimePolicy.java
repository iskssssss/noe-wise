package com.skillforge.institution.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.workflowtest.*;
import com.skillforge.core.capability.CapabilityMountResolver;
import com.skillforge.core.capability.tool.ToolDeployment;
import com.skillforge.core.chat.ChatRuntimeGateway.Submission;
import com.skillforge.core.chat.contract.ChatContent;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.WorkflowPortal.FrozenWorkflowTarget;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.SessionExecutionWorkflows;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.util.*;
import org.apache.ibatis.session.SqlSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 测试用途与普通IAM求交；持久归属、唯一目标、精确槽和真实发送均重新核验。 */
@Component
public final class WorkflowTestRuntimePolicy {
    private final WorkflowTestIsolation isolation;
    private final WorkflowTestRegistry mapper;
    private final CapabilityMountResolver.Registry registry;
    private final ObjectMapper json;
    private final SessionExecutionWorkflows workflows;
    public WorkflowTestRuntimePolicy(WorkflowTestIsolation isolation,WorkflowTestRegistry mapper,
            CapabilityMountResolver.Registry registry,ObjectMapper json,SessionExecutionWorkflows workflows) {
        this.isolation=isolation;this.mapper=mapper;this.registry=registry;this.json=json;this.workflows=workflows;
    }
    public static void configure(org.apache.ibatis.session.Configuration configuration) {WorkflowTestRegistry.configure(configuration);}
    public Optional<WorkflowTestRegistry.Snapshot> session(SessionRef session) {
        return isolation.session(Long.parseLong(session.tenantId().value()),session.sessionId());
    }
    /** 只允许原预留初始input或其正式Pending回复，客户端不能借标签构造用途。 */
    public void input(Submission submission,String input,Optional<RunRef> response,boolean goalRound) {
        var session=submission.session();var test=isolation.session(session.getTenantId(),session.getSessionId());
        if(test.isEmpty()) {
            isolation.requireTarget(session.getTenantId(),session.getSessionId(),Long.valueOf(submission.agentId()));return;
        }var value=test.orElseThrow();isolation.requireMember(value);isolation.requireLive(value);
        if(goalRound||!Objects.equals(session.getAgentId(),value.getAgentId())||!Objects.equals(session.getEndUserId(),value.getEndUserId())
                ||session.getTeamId()!=null||session.getChannelId()!=null||session.getGroupBindingId()!=null||session.getIntegrationId()!=null)
            throw denied("workflow_test_identity_mismatch");
        var request=submission.request();
        if(request.references()!=null&&!request.references().isEmpty()
                ||request.content().stream().anyMatch(part->!(part instanceof ChatContent.Text))
                ||request.context()!=null&&request.context().keySet().stream().anyMatch(key->!Set.of("end_user_id","input_id","root_input_id","trace_id","span_id","event_id","delegation_requester_id").contains(key)))
            throw denied("workflow_test_input_scope_rejected");
        if(response.isPresent()) {
            if(!response.orElseThrow().equals(runRef(value)))throw denied("workflow_test_pending_owner_mismatch");
        } else if(!value.getInitialInputId().equals(input)||!value.getInitialInputId().equals(request.clientMessageId())||request.content().size()!=1
                ||!((ChatContent.Text)request.content().getFirst()).text().equals(value.getInitialMessage()))throw denied("workflow_test_initial_input_required");
    }
    public Optional<AgentExecutionProgram.AuthorizedWorkflowInvocation> initial(RunIdentity identity,List<FrozenWorkflowTarget> targets) {
        var test=session(identity.owner().sessionRef());if(test.isEmpty())return Optional.empty();var value=test.orElseThrow();
        isolation.requireLive(value);
        if(!identity.runRef().equals(runRef(value))||!identity.agentId().value().equals(value.getAgentId().toString())
                ||identity.owner().target().kind()!=SessionTarget.Kind.AGENT||identity.owner().requireUser().value()!=value.getEndUserId()
                ||!identity.causationInputId().value().equals(value.getInitialInputId())||!identity.trace().rootInputId().value().equals(value.getInitialInputId())
                ||targets.size()!=1||!target(value,targets.getFirst()))throw denied("workflow_test_target_mismatch");
        return Optional.of(new AgentExecutionProgram.AuthorizedWorkflowInvocation("workflow-test:"+value.getId(),targets.getFirst()));
    }
    public void ordinaryTargets(RunIdentity identity,List<FrozenWorkflowTarget> targets) {
        if(session(identity.owner().sessionRef()).isPresent())return;
        for(var target:targets)if(mapper.byInstance(Long.parseLong(identity.runRef().tenantId().value()),Long.parseLong(target.instanceId()))!=null)
            throw denied("workflow_test_target_mismatch");
    }
    public TypedPayload parameters(RunIdentity identity) {
        var test=session(identity.owner().sessionRef()).orElseThrow(()->denied("workflow_test_source_missing"));
        try{return new TypedPayload("application/json",json.writeValueAsString(test.getParameters()));}
        catch(java.io.IOException invalid){throw new IllegalStateException("workflow_test_input_invalid",invalid);}
    }
    /** 安装绑定不能借 currentBindings 升级到另一个版本或修订。 */
    public void installation(long tenant,long instance,WorkflowInstallationBindings configured,WorkflowInstallationBindings resolved) {
        var test=mapper.byInstance(tenant,instance);if(test==null)return;isolation.requireLive(test);
        if(!configured.equals(resolved)||!configured.workflows().isEmpty()||!configured.agents().isEmpty())throw denied("workflow_test_binding_drift");
        var manifest=mapper.bindings(tenant,test.getId());
        if(manifest.size()!=configured.capabilities().size())throw denied("workflow_test_binding_drift");
        for(var binding:configured.capabilities()) {
            if(manifest.stream().noneMatch(value->value.slot().equals(binding.slot())&&value.capabilityType().equals(binding.capability().type().name())
                    &&Long.toString(value.capabilityDefinitionId()).equals(binding.capability().capabilityId())&&Long.toString(value.capabilityVersionId()).equals(binding.capability().versionId())
                    &&Objects.equals(value.toolDeploymentKey(),binding.toolDeploymentId())&&Objects.equals(value.toolDeploymentRevision(),binding.toolDeploymentRevision())
                    &&Objects.equals(value.knowledgeRevision(),binding.knowledgeRevision())))throw denied("workflow_test_binding_drift");
        }
    }
    /** 与close更新同一归属行串行化，阻止关闭先完成后才受理新的root Run。 */
    public void admission(SqlSession sql,ExecutionStore.Admission admission) {
        var identity=admission.run();
        var test=mapper.lockSession(sql,Long.parseLong(identity.runRef().tenantId().value()),identity.owner().sessionRef().sessionId());
        if(test==null)return;
        if(!live(test)||!identity.runRef().equals(runRef(test))||!identity.agentId().value().equals(test.getAgentId().toString())
                ||identity.owner().requireUser().value()!=test.getEndUserId())throw denied("workflow_test_admission_rejected");
    }
    public boolean control(SqlSession sql,ExecutionStore.Run run,com.skillforge.runtime.adapter.jdbc.execution.JdbcExecutionStore.Control operation) {
        var test=mapper.session(sql,Long.parseLong(run.identity().runRef().tenantId().value()),run.identity().owner().sessionRef().sessionId());
        if(test==null)return true;
        return switch(operation) {
            case CANCEL,RESUME_REPLAY -> true;
            case RESUME -> live(test);
            case STEER -> false;
        };
    }
    /** 同一Store派发事务读取用途，首次和continuation均受约束；取消/查询仍沿原责任方。 */
    public boolean allowed(SqlSession sql,ExecutionStore.Run run,ExecutionStore.Prepared prepared,AgentExecutionCodec codec,SessionExecutionWorkflows workflows) {
        var test=mapper.session(sql,Long.parseLong(run.identity().runRef().tenantId().value()),run.identity().owner().sessionRef().sessionId());
        if(test==null)return mapper.agent(sql,Long.parseLong(run.identity().runRef().tenantId().value()),Long.parseLong(run.identity().agentId().value()))==null;
        if(prepared.kind()==ExecutionStore.Kind.WORKFLOW) {
            var control=workflows.decode(prepared.request().content());
            if(control instanceof SessionExecutionWorkflows.Cancel||control instanceof SessionExecutionWorkflows.Query)
                return run.identity().runRef().equals(runRef(test))&&target(test,control.target());
        }
        if(!live(test))return false;
        if(AgentRuntimeProgramTools.owns(run)||prepared.kind()==ExecutionStore.Kind.DELEGATION)return false;
        if(AgentRuntimeWorkflowNodeExecutions.owns(run))return Set.of(ExecutionStore.Kind.MODEL,ExecutionStore.Kind.TOOL,ExecutionStore.Kind.KNOWLEDGE).contains(prepared.kind())
                &&!WorkflowScriptNodeCodec.REQUEST_SCHEMA.equals(prepared.request().content().schemaId());
        if(!run.identity().runRef().equals(runRef(test))||prepared.kind()!=ExecutionStore.Kind.WORKFLOW)return false;
        var request=workflows.decode(prepared.request().content());if(!target(test,request.target()))return false;
        if(request instanceof SessionExecutionWorkflows.Dispatch dispatch)return dispatch.origin().equals(new SessionExecutionWorkflows.AuthorizedInputOrigin("workflow-test:"+test.getId()))
                &&dispatch.parameters().equals(parameters(run.identity()));
        if(request instanceof SessionExecutionWorkflows.Resume resume)return resume.origin().equals(new SessionExecutionWorkflows.AuthorizedInputOrigin("workflow-test:"+test.getId()));
        return request instanceof SessionExecutionWorkflows.Cancel||request instanceof SessionExecutionWorkflows.Query;
    }
    /** 实际Provider发送前再读当前用途和精确部署；READ_ONLY只是供应商声明，绝不宣称物理沙箱。 */
    public void tool(ExecutionStore.Run run,AgentExecutionCodec.ToolRequest request) {
        var test=session(run.identity().owner().sessionRef());if(test.isEmpty())return;var value=test.orElseThrow();isolation.requireLive(value);
        if(!AgentRuntimeWorkflowNodeExecutions.owns(run)||request.binding().providerType()!=ToolPortal.ToolBinding.ProviderType.MCP
                ||request.target().effect()!=ToolEffect.READ_ONLY||request.target().confirmation()!=ToolPortal.ToolConfirmationPolicy.NEVER)
            throw denied("workflow_test_tool_effect_rejected");
        var target=request.target();var matches=mapper.bindings(value.getTenantId(),value.getId()).stream().filter(binding->"TOOL".equals(binding.capabilityType())
                &&Long.toString(binding.capabilityDefinitionId()).equals(target.capability().capabilityId())&&Long.toString(binding.capabilityVersionId()).equals(target.capability().versionId())
                &&Objects.equals(binding.toolDeploymentKey(),target.deploymentId())&&Objects.equals(binding.toolDeploymentRevision(),target.deploymentRevision())).toList();
        if(matches.isEmpty())throw denied("workflow_test_tool_target_rejected");
        var deployment=registry.findToolDeployment(value.getTenantId(),target.deploymentId()).orElseThrow(()->denied("workflow_test_tool_target_rejected"));
        if(!deployment.active()||deployment.environment()!=ToolDeployment.Environment.TEST||deployment.providerType()!=ToolDeployment.ProviderType.MCP
                ||deployment.revision()!=target.deploymentRevision()||!deployment.toolVersion().capabilityId().equals(target.capability().capabilityId())
                ||!deployment.toolVersion().versionId().equals(target.capability().versionId())||!deployment.providerRef().equals(request.binding().providerRef()))
            throw denied("workflow_test_tool_target_rejected");
    }
    public void provider(ExecutionStore.Run run,ExecutionStore.Prepared prepared) {
        var test=session(run.identity().owner().sessionRef());if(test.isEmpty())return;
        if(prepared.kind()==ExecutionStore.Kind.WORKFLOW) {
            var request=workflows.decode(prepared.request().content());
            if((request instanceof SessionExecutionWorkflows.Cancel||request instanceof SessionExecutionWorkflows.Query)
                    &&target(test.orElseThrow(),request.target())&&run.identity().runRef().equals(runRef(test.orElseThrow())))return;
        }
        isolation.requireLive(test.orElseThrow());
        if(!AgentRuntimeWorkflowNodeExecutions.owns(run)&&prepared.kind()!=ExecutionStore.Kind.WORKFLOW)throw denied("workflow_test_provider_rejected");
        if(WorkflowScriptNodeCodec.REQUEST_SCHEMA.equals(prepared.request().content().schemaId()))throw denied("workflow_test_script_rejected");
    }
    public void node(SessionRef session,String type) {
        var test=session(session);if(test.isEmpty())return;isolation.requireLive(test.orElseThrow());
        if(Set.of("script","async_task","parallel","subgraph","agentic").contains(type))throw denied("workflow_test_node_unsupported");
    }
    public void knowledge(ExecutionStore.Run run,AgentExecutionCodec.KnowledgeCallRequest request) {
        var test=session(run.identity().owner().sessionRef());if(test.isEmpty())return;isolation.requireLive(test.orElseThrow());
        var target=request.target();
        if(!AgentRuntimeWorkflowNodeExecutions.owns(run)||request.request().mode()!=KnowledgeMode.GROUNDING||mapper.bindings(test.orElseThrow().getTenantId(),test.orElseThrow().getId()).stream()
                .noneMatch(binding->"KNOWLEDGE_SOURCE".equals(binding.capabilityType())&&Long.toString(binding.capabilityDefinitionId()).equals(target.capability().capabilityId())
                &&Long.toString(binding.capabilityVersionId()).equals(target.capability().versionId())&&Objects.equals(binding.knowledgeRevision(),target.descriptorRevision())))
            throw denied("workflow_test_knowledge_target_rejected");
    }
    private static boolean target(WorkflowTestRegistry.Snapshot test,FrozenWorkflowTarget target) {
        return target.instanceId().equals(test.getInstanceId().toString())&&target.instanceRevision()==test.getDefinitionVersionId()
                &&target.installationRevision()==test.getInstallationRevision()&&target.capability().capabilityId().equals(test.getCapabilityDefinitionId().toString())
                &&target.capability().versionId().equals(test.getCapabilityVersionId().toString());
    }
    private static boolean live(WorkflowTestRegistry.Snapshot test) {return "ready".equals(test.getStatus())&&test.getExpiresAt().isAfter(java.time.Instant.now());}
    private static RunRef runRef(WorkflowTestRegistry.Snapshot test){return new RunRef(new TenantId(test.getTenantId().toString()),"run:"+test.getInitialInputId());}
    private static ApiException denied(String code){return new ApiException(HttpStatus.FORBIDDEN,code,"当前测试用途、精确目标或资源生命周期不允许该操作",false);}
}
