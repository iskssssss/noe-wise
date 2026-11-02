package com.skillforge.runtime.adapter.jdbc.identity;

import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.util.*;
import java.util.stream.Collectors;

/** Session IAM固定数组协议；资源完整解析只证明归属形状，不替代原持久事实。 */
public final class SessionPolicyResource {
    public static final String CLAIM_SCHEMA="skillforge.runtime.session.claim-resource.v2";
    public static final String INPUT_SCHEMA="skillforge.runtime.session.input-resource.v2";
    public static final String RUN_SCHEMA="skillforge.runtime.session.run-resource.v2";
    private SessionPolicyResource(){}
    public static TypedPayload claim(SessionOwner owner){return payload(CLAIM_SCHEMA,fields(owner));}
    public static TypedPayload input(InboundEnvelope envelope,RunRef run,String fingerprint){
        var values=fields(new SessionOwner(envelope.sessionRef(),envelope.subject(),envelope.sessionTarget()));
        if(!run.tenantId().equals(envelope.sessionRef().tenantId()))throw invalid();
        values.addAll(List.of(envelope.agentId().value(),envelope.inputId().value(),envelope.eventId().value(),envelope.causationIdentityRef().orElseThrow().value(),run.runId(),fingerprint));
        if(fingerprint.isBlank())throw invalid();return payload(INPUT_SCHEMA,values);
    }
    public static TypedPayload run(RunIdentity run,Optional<InputId> cancel){
        var values=fields(run.owner());values.addAll(List.of(run.agentId().value(),run.runRef().runId(),run.causationInputId().value(),run.causationIdentityRef().value(),
                run.trace().traceId(),run.trace().spanId(),run.trace().rootInputId().value(),run.trace().causedByEventId().map(EventId::value).orElse(""),cancel.map(InputId::value).orElse("")));
        values.addAll(RunPolicyFields.values(run));return payload(RUN_SCHEMA,values);
    }
    public static Optional<SessionOwner> owner(String action,TypedPayload resource){
        try{
            int size;
            if("session.claim".equals(action)&&CLAIM_SCHEMA.equals(resource.schemaId()))size=5;
            else if("session.execute".equals(action)&&INPUT_SCHEMA.equals(resource.schemaId()))size=11;
            else if(Set.of("session.execute","session.commit","session.cancel").contains(action)&&RUN_SCHEMA.equals(resource.schemaId()))size=18;
            else return Optional.empty();
            var f=AdmissionResource.flatStrings(resource.canonicalJson(),size);
            if(!json(f).equals(resource.canonicalJson()))return Optional.empty();
            var tenant=new TenantId(f.get(0));
            var owner=OwnerPolicyFields.owner(tenant,f.get(3),f.get(1),f.get(2),f.get(4));
            if(size==11){new AgentId(f.get(5));new InputId(f.get(6));new EventId(f.get(7));new ResultRef(f.get(8));new RunRef(tenant,f.get(9));if(f.get(10).isBlank())return Optional.empty();}
            if(size==18){var trace=new TraceEnvelope(f.get(9),f.get(10),new InputId(f.get(11)),f.get(12).isEmpty()?Optional.empty():Optional.of(new EventId(f.get(12))));
                RunPolicyFields.restore(owner,new AgentId(f.get(5)),new InputId(f.get(7)),new RunRef(tenant,f.get(6)),trace,new ResultRef(f.get(8)),f,14);
                if(!f.get(13).isEmpty())new InputId(f.get(13));if("session.cancel".equals(action)&&f.get(13).isEmpty())return Optional.empty();}
            return Optional.of(owner);
        }catch(RuntimeException failure){return Optional.empty();}
    }
    /** 提取已完整校验资源中的实际 Run/Agent，用于读取宿主冻结绑定并复验执行授权。
     * 领取与取消、提交收敛不附加执行权限，避免撤权阻止终态落账。 */
    public static Optional<ExecutionTarget> execution(String action,TypedPayload resource) {
        if(!"session.execute".equals(action)||owner(action,resource).isEmpty())return Optional.empty();
        int size=INPUT_SCHEMA.equals(resource.schemaId())?11:18;
        var fields=AdmissionResource.flatStrings(resource.canonicalJson(),size);
        return Optional.of(new ExecutionTarget(new RunRef(new TenantId(fields.get(0)),fields.get(size==11?9:6)),new AgentId(fields.get(5))));
    }
    /** 控制同样绑定准确原Run；取消不要求继续执行资格，当前操作者由独立策略核验。 */
    public static Optional<ExecutionTarget> controlTarget(String action,TypedPayload resource) {
        if(!Set.of("session.execute","session.cancel").contains(action)||!RUN_SCHEMA.equals(resource.schemaId())||owner(action,resource).isEmpty())return Optional.empty();
        var fields=AdmissionResource.flatStrings(resource.canonicalJson(),18);
        return Optional.of(new ExecutionTarget(new RunRef(new TenantId(fields.get(0)),fields.get(6)),new AgentId(fields.get(5))));
    }
    /** 资源已校验的执行地址，仅用于定位持久事实，不构成额外授权来源。 */
    public record ExecutionTarget(RunRef run,AgentId agent) {}
    private static ArrayList<String> fields(SessionOwner owner){return new ArrayList<>(List.of(owner.sessionRef().tenantId().value(),owner.target().kind().name(),owner.target().id(),owner.sessionRef().sessionId(),OwnerPolicyFields.subject(owner)));}
    private static TypedPayload payload(String schema,List<String> values){return new TypedPayload(schema,json(values));}
    private static String json(List<String> values){return values.stream().map(SessionPolicyResource::quote).collect(Collectors.joining(",","[","]"));}
    private static String quote(String value){var out=new StringBuilder("\"");for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){
        case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\t'->out.append("\\t");case '\n'->out.append("\\n");case '\f'->out.append("\\f");case '\r'->out.append("\\r");
        default->{if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}}return out.append('"').toString();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("session_policy_resource_invalid");}
}
