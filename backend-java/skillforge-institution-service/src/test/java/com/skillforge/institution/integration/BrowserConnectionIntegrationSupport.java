package com.skillforge.institution.integration;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.EndUserMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.model.PlatformModelCatalogService;
import com.skillforge.execution.browser.*;
import com.skillforge.institution.browser.*;
import com.skillforge.institution.runtime.AgentRuntimeHostProvisioner;
import com.skillforge.institution.runtime.AgentRuntimeExecutionConfiguration;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实浏览器连接组合测试共用的资源建立、公开HTTP及确定模型夹具；不覆盖浏览器客户端。 */
@Import({DeterministicModelCatalogConfiguration.class, AgentRuntimeExecutionConfiguration.class})
abstract class BrowserConnectionIntegrationSupport extends AbstractIntegrationTest {
    private static final String PASSWORD="BrowserContinuation123";
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired EndUserMapper users;
    @Autowired PlatformModelCatalogService models;
    @Autowired AgentRuntimeHostProvisioner provisioner;
    @Autowired ApplicationContext application;
    @Autowired AgentRuntimeProperties properties;
    @Autowired BrowserConnectionStore store;
    @Autowired BrowserConnectionWorker worker;
    @Autowired ExecutionStore executions;
    private final HttpClient http=HttpClient.newHttpClient();

    /** 与其他 Runtime 集成用例采用相同真实 worker 路径。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("agent-runtime.workers.auto-startup",()->"true");
    }

    /** 经真实成员登录和公开能力生命周期建立 browser_start Agent，可供同包真实客户端验收复用。 */
    Fixture fixture(String endpoint) throws Exception {
        return fixture(endpoint,false);
    }

    /** 截图候选显式发布图片策略并挂载browser_call，不按模型名推断视觉能力。 */
    Fixture fixture(String endpoint,boolean screenshots) throws Exception {
        String slug="browser-continuation-"+SnowflakeIds.nextId();
        String tenant=createTenant(tenants,plans,slug);long user;
        TenantContext.setTenantId(tenant);
        try {
            createTestTenantAdministrator(tenant,"browser-admin",PASSWORD);
            var endUser=new EndUserDO();endUser.setId(SnowflakeIds.nextId());endUser.setTenantId(Long.parseLong(tenant));
            endUser.setDisplayName("浏览器连接验收用户");endUser.setStatus("active");endUser.setFirstSeenAt(Instant.now());
            endUser.setLastActiveAt(Instant.now());endUser.setCreatedAt(Instant.now());users.insert(endUser);user=endUser.getId();
            if(screenshots) {
                var imagePolicy=new com.skillforge.runtime.api.media.ModelImagePolicy(true,true,4,1_048_576,4_194_304,1024,512,85,170,"browser-http-image.v1");
                var created=models.create(new com.skillforge.core.model.ModelWrite(null,"Browser HTTP image fixture","LLM",true,
                        "custom","custom-openai-compatible","custom-v1","openai-chat-completions","bearer-api-key",endpoint,"runtime-browser-image-fixture","fixture-key",
                        4096,32768,1024,0.2,"DISABLED",30,null,null,null,null,null,"CNY",imagePolicy),"browser-http-image-fixture");
                models.publish(created.id(),created.revision(),"browser-http-image-fixture");
                models.select(tenant,new com.skillforge.core.model.ModelSelections(created.id(),null,null));
            } else PlatformModelFixtures.selectLlm(models,tenant,endpoint,"runtime-fixture","fixture-key","custom","custom-openai-compatible","custom-v1");
            provisioner.provision(tenant);
            assertInstanceOf(AgentRuntimeExecutionGateway.class, application.getBean(com.skillforge.core.chat.ChatRuntimeGateway.class),
                    "本用例必须经新执行Gateway，不能把旧worker运行状态当作新主链证据");
        }finally{TenantContext.clear();}
        String token=api(null,"/api/v1/auth/login",Map.of("tenant_slug",slug,"username","browser-admin","password",PASSWORD),200).path("access_token").asText();
        String agent=api(token,"/api/v1/agents",Map.of("agent_key",slug,"name","浏览器自动恢复验收","system_prompt","使用挂载浏览器工具启动浏览器，完成后回答。"),200).path("id").asText();
        mountBrowserTool(token,agent,"browser_start");
        if(screenshots)mountBrowserTool(token,agent,"browser_call");
        api(token,"/api/v1/agents/publish",Map.of("agent_id",agent),200);
        return new Fixture(tenant,token,agent,user);
    }

    /** 精确选择正式平台工具版本并通过公开API挂载，不插入测试专用Handler或冻结快照。 */
    private void mountBrowserTool(String token,String agent,String tool) throws Exception {
        var definitions=get(token,"/api/v1/capabilities/definitions?capability_type=TOOL&status=PUBLISHED");
        var definition=element(definitions,"capability_key","tool:"+tool);long definitionId=definition.path("id").asLong();
        var versions=get(token,"/api/v1/capabilities/definitions/versions?status=PUBLISHED&definitionId="+definitionId);
        String versionKey=new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(entry->entry.name().equals(tool)).findFirst().orElseThrow().publishedVersionKey();
        long version=element(versions,"version_key",versionKey).path("id").asLong();
        String deployment="browser-connection-"+SnowflakeIds.nextId();
        var deployed=api(token,"/api/v1/capabilities/deployments",Map.of("deployment_key",deployment,"tool_version_id",version,
                "provider_type","PLATFORM","provider_ref",tool,"environment","TEST","status","ACTIVE"),201);
        api(token,"/api/v1/agents/capability-mounts",Map.of("agent_id",agent,"payload",Map.of("capability_type","TOOL",
                "capability_definition_id",Long.toString(definitionId),"capability_version_id",Long.toString(version),"tool_config",Map.of("tool_deployment_id",deployment,
                "tool_deployment_revision",deployed.path("revision").asLong()),"priority",0)),201);
    }

    /** 只通过公开Chat提交一次初始输入，不订阅网页 SSE。 */
    Scenario begin(Fixture fixture,String marker) throws Exception {
        return begin(fixture, marker, null);
    }

    /** 用户图片先经公开上传接口，再以真实media_id受理；不预构造MediaRef或跳过会话授权。 */
    Scenario begin(Fixture fixture,String marker,byte[] userImage) throws Exception {
        String session=api(fixture.token(),"/api/v1/chat/sessions",Map.of("agent_id",fixture.agent(),"end_user_id",fixture.user()),200).path("session_id").asText();
        String input="browser-"+marker+"-"+UUID.randomUUID();
        var content=new ArrayList<Map<String,Object>>();content.add(Map.of("type","text","text","启动浏览器 "+marker));
        if(userImage!=null)content.add(Map.of("type","image","media_id",uploadImage(fixture.token(),session,userImage)));
        var result=api(fixture.token(),"/api/v1/chat",Map.of("session_id",session,"agent_id",fixture.agent(),"client_message_id",input,
                "content",content,"context",Map.of("end_user_id",fixture.user())),202);
        assertFalse(result.path("execution_id").asText().isBlank(),"公开受理应返回执行标识");
        String run="run:"+input;
        assertEquals(run,result.path("execution_id").asText(),"受理应返回同一新Run身份");
        var accepted=executions.load(runRef(fixture,run)).orElseThrow(()->new AssertionError("新执行Run缺失 input="+input));
        assertEquals(session,accepted.identity().owner().sessionRef().sessionId());
        assertEquals(0,queryCount(jdbc,fixture.tenant(),"SELECT count(*) FROM agent_runtime_runs WHERE tenant_id=? AND run_id=?",fixture.tenant(),run),
                "新输入不得另建旧Run");
        return new Scenario(session,run);
    }

    /** 实际multipart字节消费生产上传API，响应只读取服务端持久引用ID。 */
    private String uploadImage(String token,String session,byte[] bytes) throws Exception {
        String boundary="runtime-media-"+UUID.randomUUID();var body=new java.io.ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"session_id\"\r\n\r\n"+session+"\r\n"
                +"--"+boundary+"\r\nContent-Disposition: form-data; name=\"upload_id\"\r\n\r\n"+UUID.randomUUID()+"\r\n"
                +"--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"large-user-image.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/chat/sessions/media/upload")).timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode(),response.body());
        String id=json.readTree(response.body()).path("data").path("media_id").asText();
        assertFalse(id.isBlank(),response.body());return id;
    }

    /** 等待数据库中的真实 Tool wait 及 worker 绑定，不以源代码或HTTP成功替代挂起事实。 */
    BrowserConnectionRequest waitForRequest(Fixture fixture,Scenario scenario) throws Exception {
        return waitForRequest(fixture,scenario,null);
    }

    /** 模型夹具故障与原Run持久事实一并保留；不重发已经UNKNOWN的模型或业务输入。 */
    BrowserConnectionRequest waitForRequest(Fixture fixture,Scenario scenario,BrowserModel model) throws Exception {
        var row=new AtomicReference<BrowserConnectionRequest>();
        try { await("真实浏览器Tool等待",()->{
            String status=runStatus(fixture,scenario.run());
            if("MISSING".equals(status)||Set.of("COMPLETED","FAILED","CANCELLED","TERMINATED").contains(status))
                fail("等待前原Run不可继续："+diagnostic(fixture,scenario,model));
            var pending=store.pending(Long.parseLong(fixture.tenant())).stream().filter(value->scenario.run().equals(value.getRunId())&&value.getWaitSnapshot()!=null).findFirst();
            pending.ifPresent(row::set);return pending.isPresent()&&"SUSPENDED".equals(runStatus(fixture,scenario.run()));
        }); } catch(AssertionError failure) {throw new AssertionError(failure.getMessage()+" 原事实="+diagnostic(fixture,scenario,model),failure);}
        return row.get();
    }

    /** 等待失败只读取原Run和Action的持久失败码，不重放输入或推测结果。 */
    private Map<String,Object> diagnostic(Fixture fixture,Scenario scenario,BrowserModel model) {
        return Map.of("runs",queryRows(jdbc,fixture.tenant(),"SELECT run_id,status,phase,revision FROM agent_runtime_execution_runs WHERE tenant_id=? AND run_id=?",fixture.tenant(),scenario.run()),
                "actions",queryRows(jdbc,fixture.tenant(),"SELECT action_id,status,current_attempt,selected_attempt FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=?",fixture.tenant(),scenario.run()),
                "model",model==null?Map.of("evidence","not_attached"):model.diagnostic());
    }

    /** 本浏览器用例先停止worker和连接扫描；Spring上下文留给DirtiesContext按正式顺序关闭。 */
    @org.junit.jupiter.api.AfterAll
    static void stopOwnedContextBeforeContainers(@Autowired ApplicationContext context) {
        context.getBean(AgentRuntimeProperties.class).getWorkers().setAutoStartup(false);
        context.getBeansOfType(org.springframework.context.SmartLifecycle.class).values().stream()
                .filter(value->value.getClass().getName().contains("AgentRuntimeWorkers")).forEach(org.springframework.context.SmartLifecycle::stop);
        context.getBean(com.skillforge.institution.runtime.AgentRuntimeExecutionWorker.class).close();
    }

    /** 精确原Run的持久状态。 */
    String runStatus(Fixture fixture,String run) {
        return executions.load(runRef(fixture,run)).map(value->value.status().name()).orElse("MISSING");
    }
    /** 完整租户和原Run引用用于所属Store的typed读取，不从旧恢复记录重建。 */
    RunRef runRef(Fixture fixture,String run){return new RunRef(new TenantId(fixture.tenant()),run);}
    /** 在真实RLS连接中统计当前Run的证据行。 */
    long count(Fixture fixture,String sql,String run){return ((Number)queryRows(jdbc,fixture.tenant(),sql,fixture.tenant(),run).getFirst().values().iterator().next()).longValue();}
    /** 有界等待持久条件，超时保留失败，不重试业务输入。 */
    static void await(String description,BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(80).toNanos();
        while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(200);}
        fail("等待超时："+description);
    }
    /** 精确选择目录项，缺席立即失败。 */
    static JsonNode element(JsonNode rows,String field,String value){for(var row:rows)if(value.equals(row.path(field).asText()))return row;throw new AssertionError("缺少目录项 "+value);}
    /** 公开GET消费与服务端相同的目录字段。 */
    JsonNode get(String token,String path)throws Exception{return exchange(token,path,null,200);}
    /** 公开写入仅用于建立资源、初始输入和明确用户选择。 */
    JsonNode api(String token,String path,Object body,int expected)throws Exception{return exchange(token,path,body,expected);}
    /** HTTP错误直接保留服务端响应，成功只解开统一data信封。 */
    private JsonNode exchange(String token,String path,Object body,int expected)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());assertEquals(expected,response.statusCode(),response.body());
        return json.readTree(response.body()).path("data");
    }
    /** @param tenant 当前机构 @param token 仅测试进程内的成员令牌 @param agent 已发布Agent @param user 终端用户 */
    record Fixture(String tenant,String token,String agent,long user){}
    /** @param session 原会话 @param run 原Runtime Run */
    record Scenario(String session,String run){}

    /** 延用现有确定HTTP模型夹具协议，只固定browser_start与最终正文两个真实模型回合。 */
    static final class BrowserModel implements AutoCloseable {
        private final HttpServer server;
        /** 仅本样本的真实Provider HTTP请求，供图片字节及配对证据核对；不记录HTTP认证头。 */
        private final java.util.List<JsonNode> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 每个独立夹具保留原HTTP request和异常；没有HTTP认证头或外部凭据。 */
        private final java.nio.file.Path evidence=java.nio.file.Path.of("build","reports","browser-connection-model",java.util.UUID.randomUUID().toString()).toAbsolutePath();
        private final java.util.List<ModelExchange> observations=new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<String> evidenceFailures=new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicInteger observed=new java.util.concurrent.atomic.AtomicInteger();
        /** 启动受控loopback服务；真实Provider仍负责HTTP/SSE解码及用量提交。 */
        BrowserModel(ObjectMapper json)throws Exception {
            this(json,false);
        }

        /** 可选择第三轮截图模型消费；所有选择均从本次真实模型请求的工具目录和前轮结果取得。 */
        BrowserModel(ObjectMapper json,boolean screenshots)throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/chat/completions",exchange->{
                int ordinal=observed.incrementAndGet();int status=0;Long bytesWritten=null;String selectedTool=null;Exception problem=null;
                try {
                    var request=json.readTree(exchange.getRequestBody());requests.add(request);
                    java.nio.file.Files.createDirectories(evidence);
                    java.nio.file.Files.writeString(evidence.resolve("request-"+ordinal+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(request));
                    int results=0;String task=null;
                    for(var message:request.path("messages"))if("tool".equals(message.path("role").asText())) {
                        results++;
                        // 生产Tool文本先给出structuredContent，再接有界摘要；只读取首个真实JSON对象的task。
                        if(task==null&&message.path("content").isTextual())try(var parser=json.createParser(message.path("content").textValue())) {
                            JsonNode structured=json.readTree(parser);if(structured!=null&&structured.path("task").isTextual())task=structured.path("task").textValue();
                        }
                    }
                    boolean resumed=results>0;
                    String tool=null;String arguments="{}";
                    if(!resumed){
                        tool=selectTool(json,request,"browser_start");
                        arguments="{}";
                    } else if(screenshots&&results==1) {
                        if(task==null||!UUID.fromString(task).toString().equals(task))throw new IllegalStateException("browser fixture missing actual task UUID");
                        tool=selectTool(json,request,"browser_call");
                        arguments=json.writeValueAsString(Map.of("task",task,"command",Map.of("operation","screenshot","parameters",Map.of())));
                    } else if(screenshots) {
                        if(request.path("messages").findValues("image_url").isEmpty())throw new IllegalStateException("browser screenshot was not loaded into actual model HTTP request");
                    }
                    selectedTool=tool==null?"assistant_text":tool;
                    String callId=!resumed?"start-browser":screenshots&&results==1?"screenshot-browser":"final-browser";
                    var call=tool==null?Map.<String,Object>of():Map.<String,Object>of("index",0,"id",callId,"type","function","function",Map.of("name",tool,"arguments",arguments));
                    var message=tool==null?Map.of("role","assistant","content","浏览器等待处理完成"):Map.of("role","assistant","tool_calls",List.of(call));
                    String finish=tool==null?"stop":"tool_calls";
                    var usage=Map.of("prompt_tokens",11,"completion_tokens",7,"total_tokens",18);
                    String body;
                    if(request.path("stream").asBoolean()){
                        String first=json.writeValueAsString(Map.of("id","browser-fixture","object","chat.completion.chunk","model",request.path("model").asText(),
                                "choices",List.of(Map.of("index",0,"delta",message))));
                        String last=json.writeValueAsString(Map.of("id","browser-fixture","object","chat.completion.chunk","model",request.path("model").asText(),
                                "choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",finish)),"usage",usage));
                        body="data: "+first+"\n\ndata: "+last+"\n\ndata: [DONE]\n\n";
                        exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                    }else{
                        body=json.writeValueAsString(Map.of("id","browser-fixture","object","chat.completion","model",request.path("model").asText(),
                                "choices",List.of(Map.of("index",0,"message",message,"finish_reason",finish)),"usage",usage));
                        exchange.getResponseHeaders().set("Content-Type","application/json");
                    }
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);status=200;
                    exchange.getResponseBody().write(bytes);bytesWritten=(long)bytes.length;
                }catch(Exception failure){
                    problem=failure;
                    if(status==0)try {
                        byte[] bytes="{\"error\":{\"message\":\"browser_model_fixture_rejected\"}}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(500,bytes.length);status=500;exchange.getResponseBody().write(bytes);bytesWritten=(long)bytes.length;
                    }catch(Exception responseFailure){failure.addSuppressed(responseFailure);}
                }finally{
                    var result=new ModelExchange(ordinal,selectedTool,status,bytesWritten,problem==null?null:problem.getClass().getName(),problem==null?null:problem.getMessage());
                    observations.add(result);
                    try {
                        java.nio.file.Files.createDirectories(evidence);
                        java.nio.file.Files.writeString(evidence.resolve("exchange-"+ordinal+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                        if(problem!=null){var trace=new java.io.StringWriter();problem.printStackTrace(new java.io.PrintWriter(trace));
                            java.nio.file.Files.writeString(evidence.resolve("exception-"+ordinal+".txt"),trace.toString());}
                    }catch(Exception recordingFailure){evidenceFailures.add(recordingFailure.getClass().getName()+":"+recordingFailure.getMessage());}
                    exchange.close();
                }
            });server.start();
        }
        /** 当前正式逻辑身份和精确schema用于选择唯一实际name，不猜opaque函数名前缀或oneOf根结构。 */
        private static String selectTool(ObjectMapper json,JsonNode request,String logicalName) {
            var expected=new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                    .filter(value->logicalName.equals(value.name())).findFirst().orElseThrow();
            JsonNode schema=json.valueToTree(expected.parametersSchema());String selected=null;
            for(var item:request.path("tools")) {
                var function=item.path("function");String description=function.path("description").asText();
                if(!description.contains("logical_name=\""+logicalName+"\"\n")
                        ||!description.contains("qualified_name=\"platform:"+logicalName+"/"+logicalName+"\"\n")
                        ||!schema.equals(function.path("parameters")))continue;
                if(selected!=null)throw new IllegalStateException("browser fixture ambiguous exact tool: "+logicalName);
                selected=function.path("name").asText();
            }
            if(selected==null||selected.isBlank())throw new IllegalStateException("browser fixture missing exact tool: "+logicalName);
            return selected;
        }
        /** 当前进程loopback端点，供模型目录冻结。 */
        String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        /** 原HTTP请求仅在本测试进程内读取，不能用预设模型响应冒充收到图片。 */
        List<JsonNode> requests(){return List.copyOf(requests);}
        /** 失败诊断只列实际HTTP状态和独立证据位置，不复制原请求到JUnit超时文本。 */
        Map<String,Object> diagnostic(){return Map.of("evidence",evidence.toString(),"exchanges",List.copyOf(observations),"recordingFailures",List.copyOf(evidenceFailures));}
        /** 释放本测试拥有的确定模型端口。 */
        @Override public void close(){server.stop(1);assertTrue(evidenceFailures.isEmpty(),diagnostic().toString());}
        /** @param ordinal 原HTTP顺序 @param selectedTool 实际选择的name，缺失表示选择前失败 @param httpStatus 已成功发送的HTTP头状态，0表示未确认
         * @param responseBytesWritten 完整write确认字节，NULL表示未知 @param failureType 原异常类型 @param failureMessage 原异常摘要，完整stack另存
         */
        private record ModelExchange(int ordinal,String selectedTool,int httpStatus,Long responseBytesWritten,String failureType,String failureMessage){}
    }
}
