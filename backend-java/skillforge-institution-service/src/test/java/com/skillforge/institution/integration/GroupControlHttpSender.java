package com.skillforge.institution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 真实独立Python sender与受控外部发送HTTP；业务门禁和delivery账本均归子进程SQLite。 */
final class GroupControlHttpSender implements AutoCloseable {
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Process process;
    private final String token=java.util.UUID.randomUUID().toString();
    private final String originToken=java.util.UUID.randomUUID().toString();
    private int port;

    GroupControlHttpSender(ObjectMapper json,Path root,Path evidence,Path state,String skillforge,String sink,
            String group,String endpoint,long generation,JsonNode senderCredential,JsonNode operatorCredential)throws Exception {
        this.json=json;
        Path config=evidence.resolve("sender-"+generation+".json"),log=evidence.resolve("sender-"+generation+".log");
        var sender=Map.of("api_key_env","GI04_SENDER_KEY","signing_secret_env","GI04_SENDER_SECRET");
        var operator=Map.of("actor_id","http-origin","token_env","GI04_ORIGIN_TOKEN","credential",
                Map.of("api_key_env","GI04_OPERATOR_KEY","signing_secret_env","GI04_OPERATOR_SECRET"));
        var manager=Map.of("actor_id","http-manager","token_env","GI04_OPERATOR_TOKEN","credential",sender);
        Files.writeString(config,json.writeValueAsString(Map.of("skillforge_origin",skillforge,"sink_origin",sink,
                "group_binding_id",group,"endpoint_id",endpoint,"generation",generation,"expected_generation",generation-1,
                "sender_credential",sender,"operators",List.of(operator,manager))),StandardCharsets.UTF_8);
        String python=System.getProperty("skillforge.test.python",System.getenv().getOrDefault("SKILLFORGE_TEST_PYTHON","python"));
        var builder=new ProcessBuilder(python,"-u",root.resolve("tools/integration-scenario/business_service.py").toString(),
                "--port","0","--state",state.toString(),"--sender-config",config.toString());
        builder.directory(root.resolve("tools/integration-scenario").toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env=builder.environment();env.put("PYTHONIOENCODING","utf-8");
        env.put("GI04_SENDER_KEY",senderCredential.path("api_key").asText());env.put("GI04_SENDER_SECRET",senderCredential.path("signing_secret").asText());
        env.put("GI04_OPERATOR_KEY",operatorCredential.path("api_key").asText());env.put("GI04_OPERATOR_SECRET",operatorCredential.path("signing_secret").asText());
        env.put("GI04_OPERATOR_TOKEN",token);env.put("GI04_ORIGIN_TOKEN",originToken);process=builder.start();
        try {
            await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                    .failFast("独立sender在ready前退出，日志 "+log,()->!process.isAlive()).untilAsserted(()->{
                assertThat(process.isAlive()).isTrue();boolean ready=false;
                for(String line:new String(Files.readAllBytes(log),StandardCharsets.UTF_8).split("\\R")) {
                    if(!line.startsWith("{"))continue;
                    JsonNode event;try{event=json.readTree(line);}catch(Exception partial){continue;}
                    if("group_sender_ready".equals(event.path("event").asText())){port=event.path("port").asInt();ready=true;}
                }
                assertThat(ready).isTrue();assertThat(port).isPositive();
            });
        } catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }

    JsonNode request(String method,String path,Object value,String key,int expected)throws Exception {
        return requestWithToken(token,method,path,value,key,expected);
    }
    JsonNode requestAsOrigin(String method,String path,Object value,String key,int expected)throws Exception {
        return requestWithToken(originToken,method,path,value,key,expected);
    }
    private JsonNode requestWithToken(String bearer,String method,String path,Object value,String key,int expected)throws Exception {
        String body=value==null?"":json.writeValueAsString(value);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20))
                .header("Authorization","Bearer "+bearer).header("Content-Type","application/json");
        if(key!=null)request.header("Idempotency-Key",key);
        var response=http.send(request.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        JsonNode result=json.readTree(response.body());
        assertThat(response.statusCode()).as("sender %s %s code=%s",method,path,result.path("code").asText()).isEqualTo(expected);
        return result.path("data");
    }
    JsonNode start()throws Exception{return request("POST","/api/v1/group-sender/endpoints/start",Map.of(),null,200);}
    JsonNode apply(String group)throws Exception{return request("POST","/api/v1/group-sender/controls/apply",Map.of("group_binding_id",group),null,200);}
    JsonNode deliver(Object delivery)throws Exception{return request("POST","/api/v1/group-deliveries",delivery,null,200);}
    JsonNode snapshot()throws Exception{return request("GET","/api/v1/group-sender/snapshot",null,null,200);}
    boolean alive(){return process.isAlive();}

    @Override public void close()throws Exception {
        process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();}
        assertThat(process.isAlive()).isFalse();
    }

    /** 保持真实HMAC目标与正文，仅在受控网络边界丢失控制提交；不伪造SkillForge业务ACK。 */
    static final class ControlProxy implements AutoCloseable {
        private final HttpServer server;private final URI destination;
        private final java.util.concurrent.ExecutorService executor=Executors.newCachedThreadPool();
        private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final AtomicBoolean dropControls=new AtomicBoolean();private final java.util.concurrent.atomic.AtomicInteger dropped=new java.util.concurrent.atomic.AtomicInteger();
        private final AtomicBoolean loseNextTerminalizationResponse=new AtomicBoolean();
        private final List<Map<String,String>> terminalizations=new CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicInteger terminalizationResponsesLost=new java.util.concurrent.atomic.AtomicInteger();
        ControlProxy(String destination)throws Exception {
            this.destination=URI.create(destination);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(executor);server.createContext("/",this::forward);server.start();
        }
        String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        void dropControls(boolean value){dropControls.set(value);}int dropped(){return dropped.get();}
        void loseNextTerminalizationResponse(){loseNextTerminalizationResponse.set(true);}
        int terminalizationResponsesLost(){return terminalizationResponsesLost.get();}
        List<Map<String,String>> terminalizations(){return List.copyOf(terminalizations);}
        private void forward(HttpExchange exchange)throws java.io.IOException {
            try(exchange){
                byte[] body=exchange.getRequestBody().readAllBytes();String target=exchange.getRequestURI().toString();
                if(dropControls.get()&&"POST".equals(exchange.getRequestMethod())&&target.equals("/api/v1/open-platform/groups/controls")){dropped.incrementAndGet();return;}
                boolean terminalize="POST".equals(exchange.getRequestMethod())&&target.equals("/api/v1/open-platform/groups/controls/terminalize");
                if(terminalize)terminalizations.add(Map.of("body",new String(body,StandardCharsets.UTF_8),"key",exchange.getRequestHeaders().getFirst("Idempotency-Key")));
                var request=HttpRequest.newBuilder(destination.resolve(target)).timeout(Duration.ofSeconds(20));
                for(String header:List.of("Content-Type","Accept","X-Api-Key","X-Timestamp","X-Signature","Idempotency-Key")) {
                    String value=exchange.getRequestHeaders().getFirst(header);if(value!=null)request.header(header,value);
                }
                try {
                    var response=http.send(request.method(exchange.getRequestMethod(),body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
                    // 中央已经返回真实成功，再丢网络响应；不能用提交前断链冒充终结结果未知。
                    if(terminalize&&response.statusCode()==200&&loseNextTerminalizationResponse.getAndSet(false)){terminalizationResponsesLost.incrementAndGet();return;}
                    exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(response.statusCode(),response.body().length);
                    exchange.getResponseBody().write(response.body());
                }catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            }
        }
        @Override public void close()throws Exception {server.stop(0);executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }

    /** 网络副作用接收者记录真实到达次数和稳定回执；断链只丢响应，保留已发生的发送。 */
    static final class Sink implements AutoCloseable {
        private final ObjectMapper json;private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor=Executors.newCachedThreadPool();
        private final List<JsonNode> requests=new CopyOnWriteArrayList<>();
        private final Map<String,JsonNode> receipts=new ConcurrentHashMap<>();
        private final AtomicBoolean loseNextResponse=new AtomicBoolean();
        Sink(ObjectMapper json)throws Exception {
            this.json=json;server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/send",this::send);server.createContext("/receipts",this::receipt);server.start();
        }
        String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        int sends(){return requests.size();}
        JsonNode sent(String delivery){return requests.stream().filter(value->delivery.equals(value.path("delivery_id").asText())).findFirst().orElseThrow();}
        void loseNextResponse(){loseNextResponse.set(true);}
        private void send(HttpExchange exchange)throws java.io.IOException {
            try(exchange){
                if(!"POST".equals(exchange.getRequestMethod())){reply(exchange,405,Map.of("code","METHOD_NOT_ALLOWED"));return;}
                JsonNode body=json.readTree(exchange.getRequestBody());requests.add(body);
                String id=body.path("delivery_id").asText();
                JsonNode result=json.valueToTree(Map.of("delivery_id",id,"status","delivered","result_ref","sink-receipt:"+id));
                receipts.putIfAbsent(id,result);
                if(loseNextResponse.getAndSet(false))return;
                reply(exchange,200,receipts.get(id));
            }
        }
        private void receipt(HttpExchange exchange)throws java.io.IOException {
            try(exchange){
                String query=exchange.getRequestURI().getRawQuery(),id="";
                if(query!=null)for(String field:query.split("&"))if(field.startsWith("delivery_id="))id=java.net.URLDecoder.decode(field.substring(12),StandardCharsets.UTF_8);
                JsonNode receipt=receipts.get(id);reply(exchange,receipt==null?404:200,receipt==null?Map.of("code","NOT_FOUND"):receipt);
            }
        }
        private void reply(HttpExchange exchange,int status,Object value)throws java.io.IOException {
            byte[] bytes=json.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
        }
        @Override public void close()throws Exception {server.stop(0);executor.shutdownNow();assertThat(executor.awaitTermination(10,TimeUnit.SECONDS)).isTrue();}
    }
}
