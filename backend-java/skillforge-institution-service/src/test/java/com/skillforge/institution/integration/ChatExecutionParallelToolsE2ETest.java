package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.institution.runtime.AgentRuntimeExecutionCatalog;
import com.skillforge.institution.runtime.AgentRuntimeExecutionConfiguration;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.RunStatus;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ExecutionBudgetPort;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** 真实OpenAI多tool_calls→生产native批次→两个实际平台模板slot；不声明虚构batch控制工具。 */
@Tag("runtime-latency")
@Tag("runtime-execution-parallel")
@Import({ChatRuntimeChannelE2ETest.LoopbackEvents.class, DeterministicModelCatalogConfiguration.class,
        AgentRuntimeExecutionConfiguration.class})
class ChatExecutionParallelToolsE2ETest extends ChatExecutionChainE2ETest {
    @Autowired private ExecutionStore executions;
    @Autowired private ExecutionBudgetPort budgets;
    @Autowired private AgentRuntimeExecutionCatalog catalogs;
    @Autowired private AgentRuntimeProperties properties;

    @Test @Timeout(180)
    void nativeParallelTemplateSlotsPersistExactResultsAndShareBudgetWithoutReplay() throws Exception {
        var evidence=Files.createTempDirectory(Files.createDirectories(Path.of("build","reports").toAbsolutePath()),"runtime-parallel-tools-");
        var report=new LinkedHashMap<String,Object>();report.put("outcome","FAILED");report.put("tenant",tenantId);
        String actualRun="run:"+runId;var reference=new RunRef(new TenantId(tenantId),actualRun);
        var model=new ParallelModel(objectMapper,"parallel-"+runId);
        try {
            try(model) {
                model.start();TenantContext.runWithTenant(tenantId,()->configureTenantModel(model.endpoint()));
                login();mountGenericTemplateTool();
                var input=chatPayload("请并行执行两个只读模板任务，并根据两个实际工具结果完成回答。");
                report.put("run",actualRun);report.put("session",sessionId);
                var capture=postSse(input);Files.writeString(evidence.resolve("complete.sse.txt"),capture.raw(),StandardCharsets.UTF_8);
                assertEquals(200,capture.statusCode(),capture.raw());assertTrue(capture.events().contains("execution.completed"),capture.raw());
                assertFalse(capture.events().contains("error"),capture.raw());assertTrue(capture.raw().contains(model.finalAnswer()),capture.raw());

                var run=executions.load(reference).orElseThrow();assertEquals(RunStatus.COMPLETED,run.status());assertEquals(ExecutionStore.Phase.FINISHED,run.phase());
                assertTrue(run.lease().isEmpty());assertTrue(run.currentBatch().isEmpty());assertTrue(run.currentAction().isEmpty());
                var catalog=catalogs.read(executions.material(reference.tenantId(),AgentRuntimeExecutionCatalog.reference(reference)).orElseThrow(),run.identity());
                var mounted=catalog.tools().stream().filter(tool->"template_render".equals(tool.binding().toolName())).toList();
                assertEquals(1,mounted.size());var target=mounted.getFirst();assertEquals(ToolEffect.READ_ONLY,target.target().effect());
                assertEquals(ToolConfirmationPolicy.NEVER,target.target().confirmation());assertTrue(target.parallelSafe());assertTrue(target.target().modelInvocable());

                var actions=executions.actions(reference,0,100);assertEquals(4,actions.size());
                var models=actions.stream().filter(action->action.kind()==ExecutionStore.Kind.MODEL).toList();assertEquals(2,models.size());
                var tools=actions.stream().filter(action->action.kind()==ExecutionStore.Kind.TOOL).toList();assertEquals(2,tools.size());
                var source=models.getFirst().selectedAttempt().orElseThrow();var slots=new HashSet<Integer>();var attempts=new HashSet<String>();var requests=new HashSet<String>();var results=new HashSet<String>();
                var codec=new AgentExecutionCodec(new CodecLimits(properties.getMaxPayloadCodeUnits(),properties.getMaxPayloadNodes(),properties.getMaxPayloadDepth()));
                var facts=new ArrayList<Map<String,Object>>();
                for(var action:tools) {
                    assertEquals(ExecutionStore.ActionStatus.SUCCEEDED,action.status());assertEquals(reference,action.run());
                    var slot=action.batchSlot().orElseThrow();assertEquals(reference,slot.batch().run());assertEquals(source,slot.batch().sourceAttempt());assertEquals(2,slot.batch().size());assertTrue(slots.add(slot.slot()));
                    var attempt=executions.attempt(reference,action.currentAttempt()).orElseThrow();assertEquals(action.reference(),attempt.action());assertEquals(reference,attempt.run());
                    assertEquals(action.selectedAttempt().orElseThrow(),attempt.id());assertTrue(attempt.adopted());assertEquals(ExecutionStore.ResultKind.SUCCEEDED,attempt.resultKind().orElseThrow());
                    assertTrue(attempt.uncertainty().isEmpty());assertTrue(attempt.continuation().isEmpty());assertTrue(attempt.feedback().isPresent());
                    assertEquals(action.request(),attempt.request());assertTrue(attempts.add(attempt.id()));assertTrue(requests.add(attempt.request().value()));assertTrue(results.add(attempt.result().orElseThrow().value()));
                    var request=codec.decodeTool(executions.material(reference.tenantId(),attempt.request()).orElseThrow().content());
                    assertEquals(target.target(),request.target());assertEquals(target.binding(),request.binding());assertEquals(model.wireName,request.wireName());assertTrue(request.continuation().isEmpty());
                    var arguments=objectMapper.readTree(request.arguments().canonicalJson());assertEquals(model.template(),arguments.path("template").asText());assertEquals(slot.slot(),arguments.path("data").path("quantity").asInt());
                    var actual=executions.material(reference.tenantId(),attempt.result().orElseThrow()).orElseThrow();
                    assertTrue(model.rendered(objectMapper.readTree(actual.content().canonicalJson()),model.expected(slot.slot())),"每个选定Attempt必须保存本slot真实success/rendered对象");
                    facts.add(Map.of("slot",slot.slot(),"action",action.reference().actionId(),"attempt",attempt.id(),"request",attempt.request().value(),"result",attempt.result().orElseThrow().value()));
                }
                assertEquals(Set.of(1,2),slots);
                assertEquals(4,countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND adopted AND result_kind='SUCCEEDED'",tenantId,actualRun));
                assertEquals(2,countRuntime("SELECT count(*) FROM agent_runtime_execution_batch_members WHERE tenant_id=? AND run_id=? AND source_attempt=? AND size=2 AND NOT reused",tenantId,actualRun,source));
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'",tenantId,actualRun));

                var budget=budgets.snapshot(reference);assertEquals(reference,budget.registration().run());assertEquals(reference,budget.registration().root());assertEquals(ExecutionBudgetPort.Role.PRIMARY,budget.registration().role());
                assertEquals(2,budget.consumed().tools());assertEquals(2,budget.runConsumed().tools());assertEquals(2,budget.consumed().businessModels());assertEquals(0,budget.reserved().tools());
                assertEquals(1,countRuntime("SELECT count(*) FROM agent_runtime_budget_runs WHERE tenant_id=? AND root_id=?",tenantId,actualRun));
                assertEquals(2,countRuntime("SELECT count(*) FROM agent_runtime_budget_attempts WHERE tenant_id=? AND run_id=? AND category='TOOL'",tenantId,actualRun));
                var replay=send("/api/v1/chat","POST",input,accessToken);assertEquals(202,replay.statusCode(),replay.body());assertTrue(successData(replay).path("replayed").asBoolean(),replay.body());
                assertEquals(actualRun,successData(replay).path("execution_id").asText());assertEquals(actions,executions.actions(reference,0,100));
                assertEquals(4,countRuntime("SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=?",tenantId,actualRun));
                var repeated=budgets.snapshot(reference);assertEquals(budget.consumed(),repeated.consumed());assertEquals(budget.reserved(),repeated.reserved());
                assertEquals(2,model.calls.get());assertEquals(2,model.verifiedResults.get());assertTrue(model.failures.isEmpty(),model.failures.toString());
                report.put("slots",facts);report.put("sharedRoot",budget.registration().root().runId());report.put("consumedTools",budget.consumed().tools());report.put("duplicateSubmissionReplayed",true);
            }
            assertEquals(2,model.calls.get());assertEquals(2,model.verifiedResults.get());assertTrue(model.failures.isEmpty(),model.failures.toString());report.put("outcome","PASS");
        } finally {
            report.put("model",model.snapshot());Files.writeString(evidence.resolve("parallel-tools-evidence.json"),objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),StandardCharsets.UTF_8);
            System.out.println("RUNTIME_EXECUTION_PARALLEL_TOOLS_REPORT "+evidence.resolve("parallel-tools-evidence.json"));
        }
    }

    /** 只提供真实HTTP模型响应；工具名称来自本次实际下发schema，结果只能由下一轮原生tool配对证明。 */
    private static final class ParallelModel implements AutoCloseable {
        private final ObjectMapper json;
        private final String key;
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor=Executors.newFixedThreadPool(2,runnable->{var thread=new Thread(runnable,"native-parallel-model");thread.setDaemon(true);return thread;});
        private final AtomicInteger calls=new AtomicInteger();
        private final AtomicInteger verifiedResults=new AtomicInteger();
        private final List<String> failures=new CopyOnWriteArrayList<>();
        private volatile String wireName="";
        private volatile List<String> pairedCallIds=List.of();
        ParallelModel(ObjectMapper json,String key) throws IOException {
            this.json=json;this.key=key;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);server.createContext("/chat/completions",this::handle);
        }
        void start() { server.start(); }
        String endpoint() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        String template() { return key+"-slot-{{quantity}}"; }
        String expected(int slot) { return key+"-slot-"+slot; }
        String finalAnswer() { return key+"-both-results-confirmed"; }
        Map<String,Object> snapshot() { return Map.of("requests",calls.get(),"verifiedResults",verifiedResults.get(),"wireName",wireName,"pairedCallIds",pairedCallIds,"failures",List.copyOf(failures)); }
        private void handle(HttpExchange exchange) throws IOException {
            try {
                if(!"POST".equals(exchange.getRequestMethod()))throw new IOException("model_requires_post");
                var ordinal=calls.incrementAndGet();JsonNode request;try(var input=exchange.getRequestBody()){request=json.readTree(input);}
                if(ordinal==1) {
                    if(!request.path("parallel_tool_calls").asBoolean())throw new IOException("parallel_native_declaration_missing");
                    for(var tool:request.path("tools")) {
                        var function=tool.path("function");var properties=function.path("parameters").path("properties");
                        if(!properties.has("template")||!properties.has("data"))continue;
                        if(!wireName.isEmpty())throw new IOException("template_declaration_ambiguous");wireName=function.path("name").asText();
                    }
                    if(wireName.isBlank())throw new IOException("template_declaration_missing");
                    respond(exchange,request,List.of(call(1,wireName,Map.of("template",template(),"data",Map.of("quantity",1))),call(2,wireName,Map.of("template",template(),"data",Map.of("quantity",2)))));
                } else if(ordinal==2) {
                    requirePairedResults(request);
                    for(var tool:request.path("tools"))if("final_answer".equals(tool.path("function").path("name").asText()))throw new IOException("terminal_tool_must_not_be_declared");
                    respond(exchange,request,List.of());
                } else throw new IOException("unexpected_additional_model_call");
            } catch(IOException|RuntimeException rejected) {
                failures.add(rejected.getMessage()!=null&&rejected.getMessage().matches("[a-z_]+")?rejected.getMessage():"parallel_model_fixture_failed");
                if(exchange.getResponseCode()<0)send(exchange,"application/json",500,"{\"error\":{\"message\":\"parallel model fixture rejected request\"}}");
            } finally { exchange.close(); }
        }
        private Map<String,Object> call(int slot,String name,Map<String,Object> arguments) throws IOException {
            return Map.of("id",key+"-call-"+slot,"type","function","function",Map.of("name",name,"arguments",json.writeValueAsString(arguments)));
        }
        private void requirePairedResults(JsonNode request) throws IOException {
            Map<Integer,String> ids=new LinkedHashMap<>();int batches=0;
            for(var message:request.path("messages")) {
                if(!"assistant".equals(message.path("role").asText()))continue;
                var matching=new LinkedHashMap<Integer,String>();
                for(var call:message.path("tool_calls")) {
                    if(!wireName.equals(call.path("function").path("name").asText()))continue;
                    var arguments=json.readTree(call.path("function").path("arguments").asText());int slot=arguments.path("data").path("quantity").asInt();
                    if(slot<1||slot>2||!template().equals(arguments.path("template").asText())||matching.put(slot,call.path("id").asText())!=null)throw new IOException("native_batch_arguments_mismatch");
                }
                if(!matching.isEmpty()) { if(matching.size()!=2||++batches!=1)throw new IOException("native_batch_pair_missing_or_duplicated");ids.putAll(matching); }
            }
            if(batches!=1||new HashSet<>(ids.values()).size()!=2||ids.values().stream().anyMatch(String::isBlank))throw new IOException("native_batch_identity_mismatch");
            for(int slot=1;slot<=2;slot++) {
                int paired=0;
                for(var message:request.path("messages")) {
                    if(!"tool".equals(message.path("role").asText())||!ids.get(slot).equals(message.path("tool_call_id").asText()))continue;
                    if(!renderedContent(message.path("content").asText(),expected(slot)))throw new IOException("actual_parallel_tool_result_missing");paired++;
                }
                if(paired!=1)throw new IOException("actual_parallel_tool_pair_missing_or_duplicated");
            }
            pairedCallIds=List.of(ids.get(1),ids.get(2));verifiedResults.set(2);
        }
        private boolean renderedContent(String content,String expected) {
            for(int start=content.indexOf('{');start>=0;start=content.indexOf('{',start+1))try {if(rendered(json.readTree(content.substring(start)),expected))return true;}catch(IOException invalid){ }
            return false;
        }
        private boolean rendered(JsonNode value,String expected) {
            if(value==null)return false;
            if(value.isObject()&&value.path("success").isBoolean()&&value.path("success").asBoolean()&&expected.equals(value.path("rendered").asText()))return true;
            if(value.isTextual())try {return rendered(json.readTree(value.asText()),expected);}catch(IOException invalid){return false;}
            for(var child:value)if(rendered(child,expected))return true;return false;
        }
        private void respond(HttpExchange exchange,JsonNode request,List<Map<String,Object>> calls) throws IOException {
            var usage=Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18);String model=request.path("model").asText();
            var message=calls.isEmpty()?Map.of("role","assistant","content",finalAnswer()):Map.of("role","assistant","tool_calls",calls);
            String finish=calls.isEmpty()?"stop":"tool_calls";
            if(!request.path("stream").asBoolean()) {
                send(exchange,"application/json",200,json.writeValueAsString(Map.of("id",key,"object","chat.completion","model",model,"choices",List.of(Map.of("index",0,"message",message,"finish_reason",finish)),"usage",usage)));return;
            }
            var indexed=new ArrayList<Map<String,Object>>();for(int index=0;index<calls.size();index++){var call=new LinkedHashMap<>(calls.get(index));call.put("index",index);indexed.add(call);}
            var delta=calls.isEmpty()?Map.of("role","assistant","content",finalAnswer(),"reasoning_content","parallel-native-fixture"):Map.of("role","assistant","reasoning_content","parallel-native-fixture","tool_calls",indexed);
            String first=json.writeValueAsString(Map.of("id",key,"object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",delta))));
            String last=json.writeValueAsString(Map.of("id",key,"object","chat.completion.chunk","model",model,"choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",finish)),"usage",usage));
            send(exchange,"text/event-stream",200,"data: "+first+"\n\ndata: "+last+"\n\ndata: [DONE]\n\n");
        }
        private static void send(HttpExchange exchange,String contentType,int status,String body) throws IOException {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type",contentType+"; charset=UTF-8");exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
        }
        @Override public void close() {
            server.stop(0);executor.shutdownNow();
            try {if(!executor.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("parallel_model_not_quiescent");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("parallel_model_shutdown_interrupted",interrupted);}
        }
    }
}
