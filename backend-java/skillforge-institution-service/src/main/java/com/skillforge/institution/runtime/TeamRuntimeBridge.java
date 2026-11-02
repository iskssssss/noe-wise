package com.skillforge.institution.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.dag.DagWork;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.session.AgentExecutionProgram;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimePayloadValidation;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;

/** 团队控制只消费真实typed Run/Wait/Attempt；业务父子绑定仍由AgentRunBindings拥有。 */
@Service
public class TeamRuntimeBridge {
    private final ExecutionStore store;
    private final ObjectProvider<AgentRuntimeExecutionGateway> gateway;
    private final AgentRuntimeExecutionSteering steering;
    private final AgentRunBindings bindings;
    private final ObjectMapper json;
    private final AgentRuntimePayloadValidation payloads;
    private final AgentRuntimeExecutionProjection projection;

    public TeamRuntimeBridge(ExecutionStore store, ObjectProvider<AgentRuntimeExecutionGateway> gateway, AgentRuntimeExecutionSteering steering,
            AgentRunBindings bindings, ObjectMapper json, AgentRuntimePayloadValidation payloads,AgentRuntimeExecutionProjection projection) {
        this.store=store;this.gateway=gateway;this.steering=steering;this.bindings=bindings;this.json=json;this.payloads=payloads;this.projection=projection;
    }

    public Optional<ExecutionStore.Run> fact(DagWork work, RunRef run) {
        var binding=bindings.find(run);if(binding.isEmpty())return Optional.empty();
        var expected=binding(work,run,binding.orElseThrow());return owned(expected,run,store.load(run));
    }

    /** 主助手的真实Run可在DagWork建立之前按服务器owner读取。 */
    public Optional<ExecutionStore.Run> fact(SessionOwner owner, RunRef run) {
        var binding=bindings.find(run);if(binding.isEmpty())return Optional.empty();var expected=binding.orElseThrow();
        if(expected.role()!=AgentRunBindings.Role.PRIMARY||!expected.owner().equals(owner))throw invalid("studio_primary_run_binding_mismatch");
        return owned(expected,run,store.load(run));
    }

    /** 只报告准确Run已持久保存的真实模型用量；未知完整性由usage.complete单独保留。 */
    public long tokens(ExecutionStore.Run run) {return store.usage(run.identity().runRef()).reportedTokens();}
    public ExecutionStore.TokenUsage usage(ExecutionStore.Run run) {return store.usage(run.identity().runRef());}

    /** 取消不抹掉准确Attempt仍无结果或UNKNOWN的事实。 */
    public boolean outcomeUnknown(ExecutionStore.Run run) {
        if(run.phase()==ExecutionStore.Phase.UNCERTAIN)return true;
        long after=0;
        while(true) {
            var actions=store.actions(run.identity().runRef(),after,100);
            for(var action:actions) {
                if(action.ordinal()<=after||!action.run().equals(run.identity().runRef()))throw invalid("studio_action_owner_mismatch");
                after=action.ordinal();var attempt=store.attempt(action.run(),action.currentAttempt()).orElseThrow();
                if(!attempt.action().equals(action.reference())||!attempt.run().equals(action.run()))throw invalid("studio_attempt_owner_mismatch");
                if(attempt.result().isEmpty()||attempt.resultKind().orElseThrow()==ExecutionStore.ResultKind.UNKNOWN)return true;
            }
            if(actions.size()<100)return false;
        }
    }

    public boolean terminal(ExecutionStore.Run run) {return switch(run.status()){case COMPLETED,FAILED,TERMINATED,CANCELLED->true;default->false;};}
    public ResultRef evidence(ExecutionStore.Run run) {return new ResultRef("execution-run-state:"+run.identity().runRef().runId()+":"+run.revision());}

    public boolean acceptedResponse(DagWork work, RunRef run, String inputId) {
        binding(work,run);return store.response(run,new InputId(inputId)).flatMap(ExecutionStore.Waiting::acceptedResponse).isPresent();
    }

    public void cancel(DagWork work, RunRef run, String command) {
        var current=fact(work,run);if(current.isEmpty()||terminal(current.orElseThrow())||current.orElseThrow().cancelled())return;
        var binding=binding(work,run);bindings.authorize(binding);var record=current.orElseThrow();var input=new InputId(command);
        var commands=gateway.getObject();commands.cancel(record,commands.verify(record,input),input);
    }

    public AgentRuntimeExecutionSteering.Snapshot supplement(DagWork work, RunRef run, String inputId, String text) {
        var binding=binding(work,run);bindings.authorize(binding);var record=fact(work,run).orElseThrow();var input=new InputId(inputId);
        steering.offer(record,gateway.getObject().verify(record,input),input,text);return steering.snapshot(store.load(run).orElseThrow());
    }

    /** 内部父依赖等待不向用户暴露成普通确认；用户只回复准确child的人类/工具等待。 */
    public Optional<Pending> pending(DagWork work, RunRef run) {
        var current=fact(work,run);if(current.isEmpty()||!publicWait(current.orElseThrow()))return Optional.empty();
        var binding=binding(work,run);bindings.authorizeRead(binding,binding.actor().value());var record=current.orElseThrow();
        return waitingCandidates(record).stream().filter(waiting->waiting.acceptedResponse().isEmpty()
                &&(waiting.kind()==ExecutionStore.WaitKind.HUMAN||waiting.kind()==ExecutionStore.WaitKind.TOOL))
                .findFirst().map(waiting->{var card=card(record,waiting.reference());return card==null?null:new Pending(record.identity().runRef(),waiting.reference(),record.revision(),waiting.kind(),card);});
    }

    /** 稳定输入仍由Store核对原Wait与原文，原样传递声明版本；回执重放不要求重新取得终态委派的执行权。 */
    public ExecutionStore.ResumeResult respond(DagWork work, RunRef run, String inputId, String operation, String pendingId,
            String version, String text, Instant receivedAt) {
        var binding=binding(work,run);var record=fact(work,run).orElseThrow();var input=new InputId(inputId);
        long revision=revision(version);var previous=store.response(run,input);
        final ExecutionStore.Waiting waiting;
        if(previous.isPresent()) {
            // 原输入已受理时只读取该回执；父任务结束不产生新的执行权，当前读授权与Store当前RESUME检查仍保留。
            bindings.authorizeRead(binding,binding.actor().value());
            waiting=previous.orElseThrow();waiting.acceptedResponse().orElseThrow();
        } else {
            bindings.authorize(binding);
            waiting=waitingCandidates(record).stream().filter(value->reference(value.reference()).equals(pendingId)).findFirst()
                    .orElseThrow(()->conflict("任务没有对应的待确认操作"));
        }
        if(!reference(waiting.reference()).equals(pendingId))throw conflict("待确认目标不一致");
        var commands=gateway.getObject();var actor=commands.verify(record,input);
        if(waiting.kind()==ExecutionStore.WaitKind.HUMAN) {
            if(!"reply".equals(operation))throw conflict("补充与操作批准不能混用");
            return commands.resumeHuman(record,actor,input,waiting.reference(),revision,text);
        }
        if(waiting.kind()==ExecutionStore.WaitKind.TOOL) {
            if(!"workflow".equals(operation)&&!"reply".equals(operation))throw conflict("工具输入不能代替操作批准");
            var response=toolReply(run,waiting.reference(),revision,text,json,payloads);
            return commands.resume(record,actor,input,response.waiting(),response.revision(),response.responses());
        }
        throw conflict("该等待由原业务依赖拥有，不能作为普通人类或工具回复");
    }

    /** 新公开工具输入必须携带准确Run/Wait/版本；不读取旧checkpoint或从当前状态补字段。 */
    static AgentRuntimeExecutionResponses.ToolReply toolReply(RunRef run,ExecutionStore.WaitRef waiting,long revision,String text,
            ObjectMapper json,AgentRuntimePayloadValidation payloads) {
        try {
            var fields=json.readValue(text,new TypeReference<Map<String,Object>>(){});var reply=AgentRuntimeExecutionResponses.tool(run.tenantId(),fields,payloads);
            if(!reply.run().equals(run)||!reply.waiting().equals(waiting)||reply.revision()!=revision)throw conflict("工具回复必须对应原等待和版本");
            return reply;
        } catch(java.io.IOException invalid) {throw conflict("工具回复结构无效");}
    }

    public record Pending(RunRef run,ExecutionStore.WaitRef waiting,long revision,ExecutionStore.WaitKind kind,TypedPayload card) {
        public String reference(){return TeamRuntimeBridge.reference(waiting);}
    }

    /** 并行组等待直接读当前slot原Attempt对应的权威Wait，不从公开事件或表单重建generation。 */
    private List<ExecutionStore.Waiting> waitingCandidates(ExecutionStore.Run run) {
        if(run.waiting().isPresent())return List.of(store.waiting(run.identity().runRef(),run.waiting().orElseThrow()).orElseThrow());
        if(run.currentBatch().isEmpty())return List.of();var result=store.batchWaits(run.currentBatch().orElseThrow());
        if(result.stream().anyMatch(waiting->!waiting.run().equals(run.identity().runRef())||waiting.kind()!=ExecutionStore.WaitKind.TOOL))throw invalid("studio_batch_wait_mismatch");
        return result;
    }

    private static boolean publicWait(ExecutionStore.Run run) {
        return !run.cancelled()&&(run.phase()==ExecutionStore.Phase.WAITING
                ||run.currentBatch().isPresent()&&(run.phase()==ExecutionStore.Phase.AWAITING_RESULT||run.phase()==ExecutionStore.Phase.READY));
    }

    private TypedPayload card(ExecutionStore.Run run,ExecutionStore.WaitRef waiting) {
        long after=0;ExecutionStore.SavedEvent found=null;
        while(true) {
            var page=store.events(run.identity().owner().sessionRef(),after,100);
            for(var event:page) {
                if(event.sequence()<=after)throw invalid("studio_event_sequence_invalid");after=event.sequence();
                if(!event.run().equals(run.identity().runRef())||!AgentExecutionProgram.WAITING_EVENT.equals(event.event().type()))continue;
                if(!AgentExecutionProgram.WAITING_SCHEMA.equals(event.event().data().schemaId()))throw invalid("studio_wait_card_schema_invalid");
                try {
                    var body=json.readTree(event.event().data().canonicalJson());
                    if(waiting.action().actionId().equals(body.path("actionId").asText())&&Long.toString(waiting.generation()).equals(body.path("generation").asText()))found=event;
                }catch(java.io.IOException invalid){throw invalid("studio_wait_card_invalid");}
            }
            if(page.size()<100)break;
        }
        if(found==null)throw invalid("studio_wait_card_missing");
        var visible=projection.project(run.identity().owner(),List.of(found)).pendingInteractions();
        if(visible.isEmpty())return null;
        try{return new TypedPayload("skillforge.runtime.execution.public-wait-card.v1",json.writeValueAsString(visible.getFirst().get("card")));}
        catch(java.io.IOException invalid){throw invalid("studio_wait_card_invalid");}
    }

    private Optional<ExecutionStore.Run> owned(AgentRunBindings.Binding binding,RunRef expected,Optional<ExecutionStore.Run> run) {
        run.ifPresent(value->{if(!value.identity().runRef().equals(expected)||!value.identity().owner().equals(binding.owner())||!value.identity().agentId().equals(binding.actor()))throw invalid("studio_run_owner_mismatch");});return run;
    }
    private AgentRunBindings.Binding binding(DagWork work,RunRef run) {
        return binding(work,run,bindings.find(run).orElseThrow(()->invalid("studio_run_binding_missing")));
    }
    private AgentRunBindings.Binding binding(DagWork work,RunRef run,AgentRunBindings.Binding binding) {
        var expected=work.key().owner();
        if(!binding.scope().origin().equals(expected)||!binding.owner().subject().equals(expected.subject())
                ||!binding.owner().sessionRef().tenantId().equals(expected.sessionRef().tenantId())
                ||binding.role()==AgentRunBindings.Role.PRIMARY&&!binding.owner().equals(expected)
                ||binding.role()==AgentRunBindings.Role.DELEGATE&&!binding.parentRunRef().equals(Optional.of(work.request().controlRun())))throw invalid("studio_run_binding_mismatch");
        return binding;
    }
    private static String reference(ExecutionStore.WaitRef wait){return wait.action().actionId()+":"+wait.generation();}
    private static long revision(String text){try{long value=Long.parseLong(text);if(value<=0||!Long.toString(value).equals(text))throw conflict("等待版本无效");return value;}catch(NumberFormatException invalid){throw conflict("等待版本无效");}}
    private static IllegalStateException invalid(String reason){return new IllegalStateException(reason);}
    private static ApiException conflict(String reason){return new ApiException(HttpStatus.CONFLICT,reason);}
}
