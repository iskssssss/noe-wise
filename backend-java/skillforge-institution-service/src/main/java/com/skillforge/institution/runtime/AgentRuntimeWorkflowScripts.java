package com.skillforge.institution.runtime;

import com.skillforge.core.workflow.execution.node.NodeExecutionContext;
import com.skillforge.core.workflow.execution.node.WorkflowExpressions;
import com.skillforge.core.workflow.execution.node.WorkflowNodeFailure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.execution.ExecutionPolicy;
import com.skillforge.core.workflow.runtime.SdkWorkflowPortal;
import com.skillforge.institution.execution.WorkflowScriptSandboxClient;
import com.skillforge.institution.execution.WorkflowScriptSandboxRequestFactory;
import com.skillforge.institution.execution.ExecutionSubmissionRejectedException;
import com.skillforge.runtime.adapter.jdbc.execution.JdbcWorkflowScriptCalls;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ExecutionLedgerPort.*;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 脚本只提交一次；进程重建或丢失响应后按冻结 taskId 查询同一沙箱任务。 */
@Component
public final class AgentRuntimeWorkflowScripts {
    private final JdbcWorkflowScriptCalls journal;
    private final WorkflowScriptSandboxClient sandbox;
    private final RunSettingsService settings;
    private final AgentRuntimePayloadValidation payloads;
    private final ObjectMapper json;
    private final Clock clock;

    public AgentRuntimeWorkflowScripts(JdbcWorkflowScriptCalls journal,WorkflowScriptSandboxClient sandbox,
            RunSettingsService settings,AgentRuntimePayloadValidation payloads,ObjectMapper json,Clock clock) {
        this.journal=journal;this.sandbox=sandbox;this.settings=settings;this.payloads=payloads;this.json=json;this.clock=clock;
    }
    /** 只冻结真实任务请求，不提交沙箱；新Node的实际Registered仍是发送权。 */
    public JdbcWorkflowScriptCalls.Entry prepareOnly(VerifiedIdentity identity,ExecutionLease lease,ExecutionRecord execution,
            InvocationControl control,SdkWorkflowPortal.NodeInputs inputs,NodeExecutionContext node) {
        return journal.find(identity,key(execution,node)).orElseGet(()->journal.prepare(identity,lease,execution.executionRevision(),prepare(execution,control,inputs,node)));
    }
    public Optional<JdbcWorkflowScriptCalls.Entry> find(VerifiedIdentity identity,WorkflowNodeCallIdentity key){return journal.find(identity,key);}
    /** 原任务policy/environment已经由真实业务准备冻结；来源重建不再capture当前配置或另开连接。 */
    public void requireFrozenSource(ExecutionRecord execution,SdkWorkflowPortal.NodeInputs inputs,JdbcWorkflowScriptCalls.Preparation preparation,NodeExecutionContext node) {
        var expected=preparedRequest(execution,inputs,preparation,node);
        if(!expected.taskId().equals(preparation.taskId())||!expected.canonicalJson().equals(preparation.request().canonicalJson()))throw rejected();
    }
    private WorkflowScriptSandboxClient.PreparedRequest preparedRequest(ExecutionRecord execution,SdkWorkflowPortal.NodeInputs inputs,JdbcWorkflowScriptCalls.Preparation preparation,NodeExecutionContext node) {
        if(!"script".equals(node.node().type())||!preparation.identity().equals(key(execution,node)))throw rejected();
        try {
            var original=json.readValue(preparation.request().canonicalJson(),com.skillforge.execution.ExecuteRequest.class);
            var input=WorkflowExpressions.interpolateValue(node.node().args().getOrDefault("input",Map.of()),node);
            if(!(input instanceof Map<?,?>))throw rejected();
            return WorkflowScriptSandboxRequestFactory.prepare(object(inputs.binding().definitionJson()),node.node(),json.convertValue(input,Map.class),
                    new WorkflowScriptSandboxRequestFactory.TaskIdentity(execution.executionRef().tenantId().value(),execution.executionRef().executionId(),node.node().id(),node.callIdentity().visit(),node.callIdentity().call()),
                    preparation.deadlineAt(),original.executionPolicy(),original.environmentVersion());
        } catch(java.io.IOException invalid){throw rejected();}
    }
    public WorkflowScriptSandboxClient.Status status(JdbcWorkflowScriptCalls.Entry entry) {
        try{return json.readValue(entry.result().orElseThrow().canonicalJson(),WorkflowScriptSandboxClient.Status.class);}
        catch(java.io.IOException invalid){throw rejected();}
    }

    public Optional<TypedPayload> execute(VerifiedIdentity identity,ExecutionLease lease,ExecutionRecord execution,
            InvocationControl control,SdkWorkflowPortal.NodeInputs inputs,NodeExecutionContext node) {
        var key=key(execution,node);
        var entry=journal.find(identity,key).orElseGet(()->journal.prepare(identity,lease,execution.executionRevision(),
                prepare(execution,control,inputs,node)));
        entry=journal.current(identity,lease,execution.executionRevision(),key);
        if(terminal(entry.state()))return result(entry,node);
        var prepared=entry.preparation();
        if(entry.state()==JdbcWorkflowScriptCalls.State.PREPARED&&!clock.instant().isBefore(prepared.deadlineAt()))
            throw new WorkflowNodeFailure("workflow_script_timed_out");
        var bounded=new InvocationControl(earlier(prepared.deadlineAt(),control.deadlineAt()),control.cancellationSignal());
        WorkflowScriptSandboxClient.Status observed=null;
        if(!bounded.cancellationSignal().isCancellationRequested()&&clock.instant().isBefore(bounded.deadlineAt())
                &&journal.dispatch(identity,lease,execution.executionRevision(),key)) {
            // DISPATCHED 已提交后网络异常只查询；确定未提交回执直接持久结算节点失败。
            try {
                var frozen=preparedRequest(execution,inputs,prepared,node);
                if(!frozen.taskId().equals(prepared.taskId())||!frozen.canonicalJson().equals(prepared.request().canonicalJson()))throw rejected();
                observed=sandbox.submit(frozen,bounded);
            }
            catch(ExecutionSubmissionRejectedException rejected) {
                var saved=journal.record(identity,lease,execution.executionRevision(),key,
                        JdbcWorkflowScriptCalls.State.FAILED,payload(rejected.rejection()));
                return result(saved,node);
            }
            catch(WorkflowScriptSandboxClient.OutcomeUnknownException unknown) { /* 下方查询原任务。 */ }
        }
        Instant pollUntil=earlier(bounded.deadlineAt(),clock.instant().plusSeconds(20));
        while(true) {
            if(observed!=null&&terminal(observed.state())) {
                var saved=journal.record(identity,lease,execution.executionRevision(),key,
                        JdbcWorkflowScriptCalls.State.valueOf(observed.state().name()),payload(observed));
                return result(saved,node);
            }
            if(bounded.cancellationSignal().isCancellationRequested()||!clock.instant().isBefore(bounded.deadlineAt())) {
                // 取消本次已知任务不授予新的执行权；取消响应未知时保留原 journal。
                try {
                    var cleanup=new InvocationControl(clock.instant().plusSeconds(5),()->false);
                    observed=sandbox.cancel(prepared.taskId(),cleanup);
                    if(terminal(observed.state()))continue;
                } catch(RuntimeException unavailable) { /* 保留原任务，等待查询收敛。 */ }
                return Optional.empty();
            }
            if(!clock.instant().isBefore(pollUntil))return Optional.empty();
            try {
                observed=sandbox.query(prepared.taskId(),bounded).orElse(null);
                if(observed==null||!terminal(observed.state()))Thread.sleep(100);
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();return Optional.empty();
            } catch(WorkflowScriptSandboxClient.OutcomeUnknownException unknown) {return Optional.empty();}
        }
    }

    public void requireSource(ExecutionRecord execution,SdkWorkflowPortal.NodeInputs inputs,
            JdbcWorkflowScriptCalls.Preparation preparation,NodeExecutionContext node) {
        requireFrozenSource(execution,inputs,preparation,node);
    }

    /** 恢复只查询原 task；业务 deadline 过期不取消原任务，也不授予 submit 权。 */
    public Optional<JdbcWorkflowScriptCalls.Entry> queryOriginal(VerifiedIdentity identity,ExecutionLease lease,ExecutionRecord execution,
            InvocationControl control,SdkWorkflowPortal.NodeInputs inputs,NodeExecutionContext node) {
        if(!"script".equals(node.node().type()))return Optional.empty();
        var key=key(execution,node);
        if(journal.find(identity,key).isEmpty())return Optional.empty();
        // current 在同一真实 Run/Execution 锁下核当前 IAM、lease 和原冻结来源；查询不借 Registered。
        var entry=journal.current(identity,lease,execution.executionRevision(),key);
        requireFrozenSource(execution,inputs,entry.preparation(),node);
        if(entry.state()==JdbcWorkflowScriptCalls.State.PREPARED)return Optional.empty();
        if(!terminal(entry.state())) {
            var prepared=entry.preparation();
            try {
                var observation=sandbox.query(prepared.taskId(),control);
                if(observation.isEmpty()||!terminal(observation.orElseThrow().state()))return Optional.empty();
                var value=observation.orElseThrow();
                if(!prepared.taskId().equals(value.taskId()))throw rejected();
                entry=journal.record(identity,lease,execution.executionRevision(),key,
                        JdbcWorkflowScriptCalls.State.valueOf(value.state().name()),payload(value));
            } catch(WorkflowScriptSandboxClient.OutcomeUnknownException unavailable) {return Optional.empty();}
        }
        return Optional.of(entry);
    }

    /** 后台只观察原任务，先持久化终态，再交给 Session 的系统恢复入口。 */
    public Optional<TypedPayload> observe(VerifiedIdentity identity,ExecutionLease lease,ExecutionRecord execution,
            InvocationControl control,SdkWorkflowPortal.NodeInputs inputs,NodeExecutionContext node) {
        return queryOriginal(identity,lease,execution,control,inputs,node).map(this::signal);
    }

    public void requireSignal(VerifiedIdentity identity,ExecutionRecord execution,TypedPayload signal,
            SdkWorkflowPortal.NodeInputs inputs,NodeExecutionContext node) {
        if(!"script".equals(node.node().type()))throw rejected();
        var entry=journal.find(identity,key(execution,node)).orElseThrow(AgentRuntimeWorkflowScripts::rejected);
        requireSource(execution,inputs,entry.preparation(),node);
        if(!terminal(entry.state())||!signal(entry).equals(signal))throw rejected();
    }

    /** 只有当前持久取消与 lease 可以取消原任务；不确定结果继续保留活动 Execution。 */
    public boolean cancel(VerifiedIdentity identity,ExecutionLease lease,ExecutionRecord execution,InvocationControl control) {
        for(var candidate:journal.active(identity,execution)) {
            if(candidate.state()==JdbcWorkflowScriptCalls.State.PREPARED)continue;
            var key=candidate.preparation().identity();
            var current=journal.currentCancelling(identity,lease,execution.executionRevision(),key);
            if(current.state()==JdbcWorkflowScriptCalls.State.PREPARED||terminal(current.state()))continue;
            try {
                var observed=sandbox.cancel(current.preparation().taskId(),control);
                if(!terminal(observed.state()))return false;
                journal.recordCancelling(identity,lease,execution.executionRevision(),key,
                        JdbcWorkflowScriptCalls.State.valueOf(observed.state().name()),payload(observed));
            } catch(WorkflowScriptSandboxClient.OutcomeUnknownException unknown) {return false;}
        }
        return true;
    }

    private TypedPayload signal(JdbcWorkflowScriptCalls.Entry entry) {
        return new TypedPayload(SdkWorkflowPortal.EXTERNAL_SIGNAL_SCHEMA,
                payload(new TreeMap<>(Map.of("kind","script","taskId",entry.preparation().taskId()))).canonicalJson());
    }

    private JdbcWorkflowScriptCalls.Preparation prepare(ExecutionRecord execution,InvocationControl control,
            SdkWorkflowPortal.NodeInputs inputs,NodeExecutionContext node) {
        try {sandbox.requireConfiguration();}
        catch(IllegalStateException unavailable) {throw new WorkflowNodeFailure("workflow_script_sandbox_unavailable");}
        var request=request(execution,inputs,node,control.deadlineAt(),null);
        return new JdbcWorkflowScriptCalls.Preparation(key(execution,node),inputs.target().instanceRevision(),
                inputs.target().installationRevision(),execution.checkpoint(),request.taskId(),
                new TypedPayload("application/json",request.canonicalJson()),control.deadlineAt());
    }

    private WorkflowScriptSandboxClient.PreparedRequest request(ExecutionRecord execution,SdkWorkflowPortal.NodeInputs inputs,
            NodeExecutionContext node,Instant deadline,String frozenEnvironment) {
        var key=key(execution,node);
        var operation=settings.captureOperation(key.executionRef().tenantId().value(),key.owner().runRef().runId(),
                "workflow-script:"+key.executionRef().executionId()+":"+key.nodeId()+":"+key.visit()+":"+key.call(),null,0);
        var config=settings.read(operation,"sandbox");
        if(!config.bool("enabled"))throw new WorkflowNodeFailure("workflow_script_disabled");
        var policy=new ExecutionPolicy(config.revision(),config.integer("execution_timeout_seconds"),
                com.skillforge.execution.ExecutionLimits.DOCKER_CONTROL_TIMEOUT_SECONDS,config.integer("workspace_retention_hours"),
                com.skillforge.execution.ExecutionLimits.WORKSPACE_CLEANUP_INTERVAL_MINUTES,config.integer("output_capture_bytes"),
                config.integer("max_artifact_files"),config.integer("max_concurrent_executions"),config.text("memory_limit"),
                config.decimal("cpu_limit"),config.integer("pids_limit"));
        Object input=com.skillforge.core.workflow.execution.node.WorkflowExpressions.interpolateValue(node.node().args().getOrDefault("input",Map.of()),node);
        if(!(input instanceof Map<?,?>))throw new WorkflowNodeFailure("workflow_script_input_invalid");
        String script=Objects.toString(node.node().args().get("script"),"");
        // 首次准备冻结环境；后续来源核验只采用原请求中的版本。
        String environment=frozenEnvironment==null?sandbox.environmentVersion(script.endsWith(".py")?"python":"node"):frozenEnvironment;
        return WorkflowScriptSandboxRequestFactory.prepare(object(inputs.binding().definitionJson()),node.node(),
                json.convertValue(input,Map.class),new WorkflowScriptSandboxRequestFactory.TaskIdentity(
                        key.executionRef().tenantId().value(),key.executionRef().executionId(),key.nodeId(),key.visit(),key.call()),
                deadline,policy,environment);
    }

    private Optional<TypedPayload> result(JdbcWorkflowScriptCalls.Entry entry,NodeExecutionContext node) {
        if(entry.state()!=JdbcWorkflowScriptCalls.State.COMPLETED)
            throw new WorkflowNodeFailure("workflow_script_"+entry.state().name().toLowerCase(Locale.ROOT));
        try {
            var observed=json.readValue(entry.result().orElseThrow().canonicalJson(),WorkflowScriptSandboxClient.Status.class);
            var output=observed.jsonResult().orElseThrow();
            var schema=new TypedPayload("json-schema",payload(node.node().args().getOrDefault("output_schema",Map.of("type","object"))).canonicalJson());
            if(!payloads.accepts(schema,output))throw new WorkflowNodeFailure("workflow_script_output_schema_rejected");
            return Optional.of(output);
        } catch(WorkflowNodeFailure failure) {throw failure;}
        catch(Exception invalid) {throw new WorkflowNodeFailure("workflow_script_output_invalid");}
    }

    private static WorkflowNodeCallIdentity key(ExecutionRecord execution,NodeExecutionContext node) {
        return new WorkflowNodeCallIdentity(execution.owner(),execution.executionRef(),node.node().id(),node.callIdentity().visit(),node.callIdentity().call());
    }
    private static Instant earlier(Instant first,Instant second) {return first.isBefore(second)?first:second;}
    private static boolean terminal(JdbcWorkflowScriptCalls.State state) {return state!=JdbcWorkflowScriptCalls.State.PREPARED&&state!=JdbcWorkflowScriptCalls.State.DISPATCHED;}
    private static boolean terminal(WorkflowScriptSandboxClient.State state) {return state!=WorkflowScriptSandboxClient.State.QUEUED&&state!=WorkflowScriptSandboxClient.State.RUNNING&&state!=WorkflowScriptSandboxClient.State.UNKNOWN;}
    private TypedPayload payload(Object value) {try{return new TypedPayload("application/json",json.writeValueAsString(value));}catch(Exception invalid){throw rejected();}}
    private Map<String,Object> object(String value) {try{return json.readValue(value,Map.class);}catch(Exception invalid){throw rejected();}}
    private static IllegalArgumentException rejected() {return new IllegalArgumentException("workflow_script_source_rejected");}
}
