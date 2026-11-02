package com.skillforge.institution.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** 只模拟实际OpenAI HTTP边界；Workflow、模板效果、委派、等待及预算均由真实Runtime执行。 */
final class RuntimeExecutionCompositionProvider implements AutoCloseable {
    /** 首次真实响应与下一轮原生调用核验共用同一业务参数，不从结果正文推断原命令。 */
    private static final String DELEGATION_INSTRUCTION="请先向用户询问必要信息，收到实际回答后完成独立任务。";
    enum Scenario { WORKFLOW, DELEGATION }
    private final ObjectMapper json;
    private final Scenario scenario;
    private final String key;
    private final HttpServer server;
    private final AtomicInteger parentCalls=new AtomicInteger(),childCalls=new AtomicInteger(),verifiedResults=new AtomicInteger(),verifiedReplies=new AtomicInteger();
    /** 仅此夹具首轮实际发出的父命令事实；后续目录变化不能重写原函数和参数。 */
    private final AtomicReference<ParentCommand> originalParent=new AtomicReference<>();
    private final CopyOnWriteArrayList<JsonNode> requests=new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<String> failures=new CopyOnWriteArrayList<>();

    RuntimeExecutionCompositionProvider(ObjectMapper json,Scenario scenario,String key) throws IOException {
        this.json=json;this.scenario=scenario;this.key=key;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",this::handle);
    }
    void start(){server.start();}
    String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    String prompt(){return "请完成当前已授权的"+(scenario==Scenario.WORKFLOW?"工作流确认与模板任务":"委派任务，等待成员询问用户并完成后再汇总")+"，标记 "+key;}
    String childSystem(){return "composition-child:"+key+"。先询问用户，收到实际回答后再完成任务。";}
    String question(){return "composition-question-"+key;}
    String reply(){return "composition-reply-"+key;}
    String childAnswer(){return "composition-child-completed-"+key;}
    String finalAnswer(){return "composition-parent-completed-"+key;}
    String template(){return "composition-rendered-"+key+"={{quantity}}";}
    String rendered(){return "composition-rendered-"+key+"=27";}
    int parentCalls(){return parentCalls.get();}
    int childCalls(){return childCalls.get();}
    Map<String,Object> snapshot(){return Map.of("scenario",scenario.name(),"parentCalls",parentCalls(),"childCalls",childCalls(),
            "verifiedResults",verifiedResults.get(),"verifiedReplies",verifiedReplies.get(),"requests",List.copyOf(requests),"failures",List.copyOf(failures));}

    private void handle(HttpExchange exchange) throws IOException {
        try {
            JsonNode request;
            try(var input=exchange.getRequestBody()){request=json.readTree(input);}
            requests.add(request.deepCopy());
            boolean child=scenario==Scenario.DELEGATION&&hasSystem(request,childSystem());
            int ordinal=(child?childCalls:parentCalls).incrementAndGet();
            String wire,id;Map<String,Object> arguments;
            if(child) {
                if(ordinal==1){wire="ask_user";id="composition-child-question";arguments=Map.of("question",question());}
                else if(ordinal==2){requireHumanReply(request);wire=null;id="composition-child-final";arguments=Map.of();}
                else throw new IOException("unexpected_child_model_call");
            } else if(ordinal==1) {
                if(scenario==Scenario.WORKFLOW){wire="run_workflow";id="composition-workflow-dispatch";arguments=Map.of("handle",handle(request,"run_workflow","handle"));}
                else {wire="delegate";id="composition-delegation-start";arguments=Map.of("target_handle",handle(request,"delegate","target_handle"),
                        "instruction",DELEGATION_INSTRUCTION,"background",false);}
            } else if(ordinal==2) {
                var original=originalParent.get();if(original==null)throw new IOException("composition_original_parent_call_missing");
                String parameter=scenario==Scenario.WORKFLOW?"handle":"target_handle";
                if(!handle(request,original.wireName(),parameter).equals(original.arguments().get(parameter)))
                    throw new IOException("composition_frozen_target_changed");
                var actual=pairedToolResult(request,original.wireName(),original.arguments());
                if(scenario==Scenario.WORKFLOW?!containsRendered(actual):!containsCompletedDelegate(actual))throw new IOException("actual_composition_result_missing");
                verifiedResults.incrementAndGet();wire=null;id="composition-parent-final";arguments=Map.of();
            } else throw new IOException("unexpected_parent_model_call");
            if(wire!=null)requireTool(request,wire);
            if(!child&&ordinal==1&&!originalParent.compareAndSet(null,new ParentCommand(wire,arguments)))
                throw new IOException("composition_original_parent_call_duplicate");
            respond(exchange,request,wire,id,arguments,child?childAnswer():finalAnswer());
        } catch(RuntimeException|IOException rejected) {
            failures.add(rejected.getClass().getSimpleName()+":"+rejected.getMessage());
            byte[] body=json.writeValueAsBytes(Map.of("error",Map.of("message","composition_fixture_rejected","type","fixture_error")));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(500,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        } finally {exchange.close();}
    }
    /** 首次响应只含标量参数，防御复制后固定为本场景后续核验的唯一原命令。 */
    private record ParentCommand(String wireName,Map<String,Object> arguments){
        private ParentCommand {arguments=Map.copyOf(arguments);}
    }
    private static boolean hasSystem(JsonNode request,String expected){for(var message:request.path("messages"))if("system".equals(message.path("role").asText())&&message.path("content").asText().contains(expected))return true;return false;}
    /** 只从匹配native函数的指定参数消费唯一完整enum；消息中的文本及其他函数均不能提供路由。 */
    private static String handle(JsonNode request,String toolName,String parameter) throws IOException {
        JsonNode selected = null;
        for (var tool : request.path("tools")) {
            if (!"function".equals(tool.path("type").asText()) || !toolName.equals(tool.path("function").path("name").asText())) continue;
            if (selected != null) throw new IOException("frozen_composition_target_ambiguous");
            selected = tool.path("function").path("parameters").path("properties").path(parameter).path("enum");
        }
        if (selected == null || !selected.isArray() || selected.size() != 1 || !selected.get(0).isTextual() || selected.get(0).textValue().isBlank())
            throw new IOException("frozen_composition_target_missing_or_ambiguous");
        return selected.get(0).textValue();
    }
    private static void requireTool(JsonNode request,String name) throws IOException {
        for(var tool:request.path("tools"))if(name.equals(tool.path("function").path("name").asText()))return;
        throw new IOException("native_composition_tool_not_exposed:"+name);
    }
    /** 原生历史使用Runtime规范调用ID；由唯一准确函数和原参数定位，再严格核对同ID结果。 */
    private JsonNode pairedToolResult(JsonNode request,String wireName,Map<String,Object> expectedArguments) throws IOException {
        int matchingCalls=0,callPosition=-1,position=0;JsonNode selected=null;
        for(var message:request.path("messages")) {
            if("assistant".equals(message.path("role").asText()))for(var call:message.path("tool_calls"))
                if(wireName.equals(call.path("function").path("name").asText())){matchingCalls++;selected=call;callPosition=position;}
            position++;
        }
        if(matchingCalls!=1||selected==null||!selected.path("id").isTextual()||selected.path("id").textValue().isBlank()
                ||!"function".equals(selected.path("type").asText()))throw new IOException("composition_tool_result_pair_missing_or_duplicate");
        var function=selected.path("function");
        if(!function.path("arguments").isTextual()
                ||!json.valueToTree(expectedArguments).equals(json.readTree(function.path("arguments").textValue())))
            throw new IOException("composition_tool_call_arguments_mismatch");
        String callId=selected.path("id").textValue();int calls=0,results=0,resultPosition=-1;JsonNode actual=null;position=0;
        for(var message:request.path("messages")) {
            if("assistant".equals(message.path("role").asText()))for(var call:message.path("tool_calls"))if(callId.equals(call.path("id").asText()))calls++;
            if("tool".equals(message.path("role").asText())&&message.path("tool_call_id").isTextual()
                    &&callId.equals(message.path("tool_call_id").textValue())){results++;actual=message.path("content");resultPosition=position;}
            position++;
        }
        if(calls!=1||results!=1||resultPosition<=callPosition||actual==null||!actual.isTextual())
            throw new IOException("composition_tool_result_pair_missing_or_duplicate");
        return json.readTree(actual.textValue());
    }
    private boolean containsRendered(JsonNode node) throws IOException {
        if(node.isObject()&&node.path("success").asBoolean()&&rendered().equals(node.path("rendered").asText()))return true;
        return nested(node,this::containsRendered);
    }
    private boolean containsCompletedDelegate(JsonNode node) throws IOException {
        if(node.isObject()&&"completed".equals(node.path("state").asText())&&childAnswer().equals(node.path("summary").asText()))return true;
        return nested(node,this::containsCompletedDelegate);
    }
    private boolean nested(JsonNode node,Match match) throws IOException {
        if(node.isObject()) {
            var fields=node.fields();while(fields.hasNext()){var entry=fields.next();
                if(("canonicalJson".equals(entry.getKey())||"canonical_json".equals(entry.getKey()))&&entry.getValue().isTextual()) {
                    if(match.test(json.readTree(entry.getValue().textValue())))return true;
                } else if(match.test(entry.getValue()))return true;
            }
        } else if(node.isArray())for(var child:node)if(match.test(child))return true;
        return false;
    }
    @FunctionalInterface private interface Match {boolean test(JsonNode value) throws IOException;}
    private void requireHumanReply(JsonNode request) throws IOException {
        var messages=RuntimeExecutionTimeFixture.businessMessages(request,2);int size=messages.size();
        if(size<4||!RuntimeExecutionTimeFixture.isBudget(messages.get(size-1)))throw new IOException("actual_child_human_reply_missing");
        var question=messages.get(size-4);var receipt=messages.get(size-3);var response=messages.get(size-2);
        if(!"assistant".equals(question.path("role").asText())||!"tool".equals(receipt.path("role").asText())
                ||!"user".equals(response.path("role").asText())||question.path("tool_calls").size()!=1
                ||question.has("tool_call_id")||receipt.path("tool_calls").size()!=0||response.path("tool_calls").size()!=0
                ||!reply().equals(response.path("content").asText())||response.has("tool_call_id"))throw new IOException("actual_child_human_reply_missing");
        var call=question.path("tool_calls").get(0);var function=call.path("function");String originalId="composition-child-question";
        if(!originalId.equals(call.path("id").asText())||!"function".equals(call.path("type").asText())
                ||!"ask_user".equals(function.path("name").asText())||!function.path("arguments").isTextual()
                ||!originalId.equals(receipt.path("tool_call_id").asText())||!receipt.path("content").isTextual())throw new IOException("actual_child_human_reply_missing");
        final JsonNode arguments;
        try {arguments=json.readTree(function.path("arguments").asText());}
        catch(IOException invalid){throw new IOException("actual_child_human_reply_missing");}
        if(arguments==null||!arguments.isObject()||arguments.size()!=1||!question().equals(arguments.path("question").asText())
                ||!RuntimeExecutionTimeFixture.HUMAN_RECEIPT.equals(receipt.path("content").textValue()))throw new IOException("actual_child_human_reply_missing");
        verifiedReplies.incrementAndGet();
    }
    private void respond(HttpExchange exchange,JsonNode request,String wire,String id,Map<String,Object> arguments,String text) throws IOException {
        var call=new LinkedHashMap<String,Object>();call.put("id",id);call.put("type","function");
        if(wire!=null)call.put("function",Map.of("name",wire,"arguments",json.writeValueAsString(arguments)));
        var message=wire==null?Map.of("role","assistant","content",text):Map.of("role","assistant","tool_calls",List.of(call));
        String finish=wire==null?"stop":"tool_calls";
        var usage=Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18);String model=request.path("model").asText("composition-fixture");
        String body;boolean stream=request.path("stream").asBoolean();
        if(stream){if(wire!=null)call.put("index",0);var first=json.writeValueAsString(Map.of("id",id,"object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",message))));
            var last=json.writeValueAsString(Map.of("id",id,"object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",finish)),"usage",usage));
            body="data: "+first+"\n\ndata: "+last+"\n\ndata: [DONE]\n\n";
        } else body=json.writeValueAsString(Map.of("id",id,"object","chat.completion","model",model,"choices",List.of(Map.of("index",0,"message",message,"finish_reason",finish)),"usage",usage));
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",stream?"text/event-stream":"application/json");
        exchange.sendResponseHeaders(200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
    }
    @Override public void close(){server.stop(0);}
}
