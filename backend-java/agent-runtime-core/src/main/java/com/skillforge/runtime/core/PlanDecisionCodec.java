package com.skillforge.runtime.core;

import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.port.*;
import java.util.*;
import static com.skillforge.runtime.core.CanonicalJson.*;

/** 原生计划封闭 wire 协议；所有字段在模型边界严格核对。 */
final class PlanDecisionCodec {
    static PlanCommand decode(Value v) {
        var op=PlanCommand.Operation.valueOf(v.field("operation").stringValue().toUpperCase(Locale.ROOT));
        switch(op) {
            case SUBMIT -> v.requireFields("type","operation","work_id","expected_revision","plan");
            case GET -> v.requireFields("type","operation","work_id");
            case START_STEP -> v.requireFields("type","operation","work_id","expected_revision","task_id");
            case FINISH_STEP -> {
                if(v.optionalField("result_refs").isPresent()) v.requireFields("type","operation","work_id","expected_revision","task_id","outcome","summary","result_refs","outputs");
                else v.requireFields("type","operation","work_id","expected_revision","task_id","outcome","summary","outputs");
            }
            case WAIT -> v.requireFields("type","operation","work_id","task_ids");
        }
        // 先取得修订前提；Plan仅校验fragment形状，完整图约束由命令初次提交或宿主合并后校验。
        long expectedRevision = v.optionalField("expected_revision").map(Value::integralNumberValue).orElse(0L);
        return new PlanCommand(op,v.field("work_id").stringValue(),expectedRevision,
            v.optionalField("plan").map(PlanDecisionCodec::plan),str(v,"task_id"),str(v,"outcome"),str(v,"summary"),
            array(v,"result_refs").stream().map(x->new ResultRef(x.stringValue())).toList(),array(v,"task_ids").stream().map(Value::stringValue).toList(),
            v.optionalField("outputs").map(x -> x.objectFields().stream().collect(java.util.stream.Collectors.toMap(Field::name,f -> f.value().stringValue()))).orElse(Map.of()));
    }
    static Value encode(PlanCommand c) {
        var f=new ArrayList<Field>(); f.add(s("type","plan")); f.add(s("operation",c.operation().name().toLowerCase(Locale.ROOT)));f.add(s("work_id",c.workId()));
        switch(c.operation()) {
            case SUBMIT -> { f.add(n("expected_revision",c.expectedRevision())); f.add(new Field("plan",plan(c.plan().orElseThrow()))); }
            case GET -> {}
            case START_STEP -> {f.add(n("expected_revision",c.expectedRevision()));f.add(s("task_id",c.taskId()));}
            case FINISH_STEP -> { f.add(new Field("outputs",object(c.outputs().entrySet().stream().map(e->s(e.getKey(),e.getValue())).toList()))); f.add(n("expected_revision",c.expectedRevision()));f.add(s("task_id",c.taskId()));f.add(s("outcome",c.outcome()));f.add(s("summary",c.summary()));f.add(new Field("result_refs",strings(c.resultRefs().stream().map(ResultRef::value).toList()))); }
            case WAIT -> f.add(new Field("task_ids",strings(c.waitTaskIds())));
        }
        return object(f);
    }
    private static PlanCommand.Plan plan(Value v) {
        v.requireFields("title","change_reason","tasks","approval_required");
        return new PlanCommand.Plan(str(v,"title"),str(v,"change_reason"),array(v,"tasks").stream().map(t->{
            t.requireFields("id","title","executor","task","inputs","outputs","exclusive_resources","cancelled","replaces_task_id");
            var e=t.field("executor"); e.requireFields("type","target_handle"); var task=t.field("task");task.requireFields("objective","context","constraints","expected_output");
            if (!str(t,"id").isBlank() && str(t,"id").equals(str(t,"replaces_task_id"))) throw new SelfReplacementRejected();
            return new PlanCommand.Task(str(t,"id"),str(t,"title"),new PlanCommand.Executor(PlanCommand.ExecutorType.valueOf(str(e,"type").toUpperCase(Locale.ROOT)),str(e,"target_handle")),
                new DelegationCommand.Task(str(task,"objective"),str(task,"context"),array(task,"constraints").stream().map(Value::stringValue).toList(),str(task,"expected_output")),
                array(t,"inputs").stream().map(i->{i.requireFields("name","source_task","output"); return new PlanCommand.Input(str(i,"name"),str(i,"source_task"),str(i,"output"));}).toList(),set(t,"outputs"),set(t,"exclusive_resources"),t.field("cancelled").booleanValue(),str(t,"replaces_task_id"));
        }).toList(),v.field("approval_required").booleanValue());
    }
    /** 仅标记已解析的自替换语义，不携带任务身份或参数正文。 */
    static final class SelfReplacementRejected extends IllegalArgumentException {
        SelfReplacementRejected() { super("plan_task_self_replacement"); }
    }
    private static Value plan(PlanCommand.Plan p) {return object(List.of(s("title",p.title()),s("change_reason",p.changeReason()),new Field("approval_required",bool(p.approvalRequired())),new Field("tasks",CanonicalJson.array(p.tasks().stream().map(t->object(List.of(
        s("id",t.id()),s("title",t.title()),new Field("cancelled",bool(t.cancelled())),s("replaces_task_id",t.replacesTaskId()),new Field("executor",object(List.of(s("type",t.executor().type().name().toLowerCase(Locale.ROOT)),s("target_handle",t.executor().targetHandle())))),
        new Field("task",object(List.of(s("objective",t.task().objective()),s("context",t.task().context()),new Field("constraints",strings(t.task().constraints())),s("expected_output",t.task().expectedOutput())))),
        new Field("inputs",CanonicalJson.array(t.inputs().stream().map(i->object(List.of(s("name",i.name()),s("source_task",i.sourceTask()),s("output",i.output())))).toList())),new Field("outputs",strings(t.outputs().stream().sorted().toList())),new Field("exclusive_resources",strings(t.exclusiveResources().stream().sorted().toList()))))).toList()))));}
    private static String str(Value v,String key){return v.optionalField(key).map(Value::stringValue).orElse("");}
    private static List<Value> array(Value v,String key){return v.optionalField(key).map(Value::arrayValue).orElse(List.of());}
    private static Set<String> set(Value v,String key){var a=array(v,key).stream().map(Value::stringValue).toList();var s=new HashSet<>(a);if(s.size()!=a.size())throw new IllegalArgumentException("duplicate_plan_value");return s;}
    private static Value strings(List<String> a){return CanonicalJson.array(a.stream().map(CanonicalJson::string).toList());}
    private static Field s(String k,String v){return new Field(k,string(v));}
    private static Field n(String k,long v){return new Field(k,number(Long.toString(v)));}
}
