package com.skillforge.institution.integration;

import com.skillforge.core.foundation.persistence.TenantMemberMapper;

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
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实PG/Redis、新Runtime与生产BrowserClient HTTP组合；确定上游协议不代表真实扩展验收。 */
class BrowserConnectionContinuationIntegrationTest extends BrowserConnectionIntegrationSupport {
    private static RuntimeExecutionBrowserServer upstream;
    @Autowired private com.skillforge.core.foundation.catalog.entitlements.EntitlementMapper channelEntitlements;
    @Autowired private TenantMemberMapper channelMembers;
    @Autowired private com.skillforge.institution.runtime.BrowserConnectionRuntimeBridge channelBridge;
    @Autowired private com.skillforge.institution.media.SessionMediaService media;

    /** 只替换进程外Browser service地址，生产客户端、Handler和媒体服务保持真实。 */
    @DynamicPropertySource
    static void browserHttp(DynamicPropertyRegistry registry) {
        registry.add("BROWSER_SERVICE_URL", () -> upstream().endpoint());
        registry.add("BROWSER_SERVICE_TOKEN", () -> RuntimeExecutionBrowserServer.TOKEN);
    }

    /** 动态属性首次消费时启动本类拥有的loopback协议端。 */
    private static synchronized RuntimeExecutionBrowserServer upstream() {
        if (upstream == null) {
            try { upstream = new RuntimeExecutionBrowserServer(); }
            catch (java.io.IOException failure) { throw new IllegalStateException("browser_http_fixture_failed", failure); }
        }
        return upstream;
    }

    /** 先停止实际消费者，再关闭HTTP上游；不能把清理期间连接失败当业务结果。 */
    @org.junit.jupiter.api.AfterAll
    static void closeBrowser(@Autowired ApplicationContext application, @Autowired AgentRuntimeProperties properties) {
        properties.getWorkers().setAutoStartup(false);
        application.getBean(com.skillforge.institution.runtime.AgentRuntimeExecutionWorker.class).close();
        if (upstream != null) {
            upstream.close(); assertTrue(upstream.failures().isEmpty(), upstream.failures().toString());
        }
    }

    /** 渠道令牌与正式终端身份共同受理 browser_start，公开投影绑定新Store的原Action/代数。 */
    @Test
    void channelIdentityPublishesBrowserConnectionCardForOriginalSuspendedToolInput() throws Exception {
        try (var model = new BrowserModel(json)) {
            var fixture = fixture(model.endpoint());
            TenantContext.setTenantId(fixture.tenant());
            try {
                var member = channelMembers.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.skillforge.core.foundation.persistence.TenantMemberDO>()
                        .eq(com.skillforge.core.foundation.persistence.TenantMemberDO::getTenantId, Long.parseLong(fixture.tenant()))
                        .eq(com.skillforge.core.foundation.persistence.TenantMemberDO::getUsername, "browser-admin"));
                assertNotNull(member);
                withAuthenticatedMember(member.getId(), () -> {
                    assertEquals(1, channelEntitlements.insertTenantGrant(
                            new com.skillforge.core.foundation.catalog.entitlements.EntitlementContracts.SaveTenantGrant(
                                    fixture.tenant(), "channels", true, 0L), "browser-channel-fixture"));
                    return null;
                });
            } finally { TenantContext.clear(); }
            var channel = api(fixture.token(), "/api/v1/channels", Map.of("name", "浏览器等待渠道验收",
                    "channel_type", "openapi", "config", Map.of(), "ui_config", Map.of()), 200);
            String channelId = channel.path("id").asText(), channelToken = channel.path("channel_token").asText();
            assertFalse(channelId.isBlank()); assertFalse(channelToken.isBlank());
            var bindings = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/channels/agents"))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + fixture.token())
                    .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                            Map.of("channel_id", channelId, "payload", List.of(Map.of("agent_id", fixture.agent(),
                                    "priority", 0, "is_default", true, "traffic_percentage", 100)))))).build();
            assertEquals(200, HttpClient.newHttpClient().send(bindings, HttpResponse.BodyHandlers.discarding()).statusCode());
            String clientRef = UUID.randomUUID().toString();
            var profile = channelJson("POST", "/api/v1/chat/client-profile", Map.of("client_ref", clientRef,
                    "display_name", "真实渠道浏览器用户"), channelToken, null, 200);
            String identityToken = profile.path("identity_token").asText(), user = profile.path("end_user_id").asText();
            assertFalse(identityToken.isBlank()); assertFalse(user.isBlank());
            assertNotEquals(Long.toString(fixture.user()), user, "渠道身份必须由正式 profile 创建，不能借成员路径的夹具用户");
            var created = channelJson("POST", "/api/v1/chat/sessions", Map.of("agent_id", fixture.agent(),
                    "end_user_id", user), channelToken, identityToken, 200);
            String session = created.path("session_id").asText(); assertFalse(session.isBlank());
            String input = "browser-channel-" + UUID.randomUUID(), run = "run:" + input;
            channelJson("POST", "/api/v1/chat", Map.of("agent_id", fixture.agent(), "session_id", session,
                    "client_message_id", input, "content", List.of(Map.of("type", "text", "text", "启动我的浏览器")),
                    "context", Map.of("end_user_id", user)), channelToken, identityToken, 202);
            var scenario = new Scenario(session, run);
            var waiting = waitForRequest(fixture, scenario, model);
            assertEquals("SUSPENDED", runStatus(fixture, run)); assertEquals("WAITING", waiting.getStatus());
            assertEquals(Long.parseLong(user), waiting.getEndUserId());
            assertEquals(session, waiting.getSessionId()); assertEquals(run, waiting.getRunId());
            assertEquals("NO_SELECTION", waiting.getReason()); assertFalse(waiting.isPairDispatched());
            assertEquals(0, count(fixture, "SELECT count(*) FROM nw_browser_call_receipts WHERE tenant_id=?::bigint AND run_id=? AND operation='start'", run));
            var ref = runRef(fixture,run);
            var storedRun = executions.load(ref).orElseThrow();
            var persistedWait = executions.waiting(ref,storedRun.waiting().orElseThrow()).orElseThrow();
            assertTrue(persistedWait.acceptedResponse().isEmpty());
            assertEquals(waiting.getInvocationId(),persistedWait.reference().action().actionId());
            // Bridge通过新Store读取原INPUT_REQUIRED Attempt材料，原opaque state不公开。
            var runtimeWait=channelBridge.inspect(fixture.tenant(),run,waiting.getInvocationId(),waiting.getRequestId()).pending();
            assertNotNull(runtimeWait);
            var params=runtimeWait.requests().get(BrowserConnectionService.INPUT_KEY).params();
            assertEquals(BrowserConnectionService.REQUEST_SCHEMA,params.schemaId());
            assertEquals(waiting.getRequestId(),json.readTree(params.canonicalJson()).path("connection_request_id").asText());
            var snapshot = json.readValue(waiting.getWaitSnapshot(), BrowserConnectionWaitSnapshot.class);
            assertEquals(snapshot.actionId(),persistedWait.reference().action().actionId());
            assertEquals(snapshot.generation(),persistedWait.reference().generation());
            assertEquals(snapshot.runRevision(),runtimeWait.runRevision());
            assertEquals(persistedWait.reference(),runtimeWait.waiting());
            assertEquals(ref,runtimeWait.run());
            var projected = new AtomicReference<JsonNode>();
            await("渠道历史公开浏览器连接卡", () -> {
                try {
                    var history = channelJson("GET", "/api/v1/chat/history?session_id=" + session, null, channelToken, identityToken, 200);
                    for (var interaction : history.path("pending_interactions"))
                        if (run.equals(interaction.path("execution_id").asText())) { projected.set(interaction); return true; }
                    return false;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            JsonNode pending = projected.get();
            assertEquals("tool_input", pending.path("interaction_type").asText());
            assertEquals(snapshot.actionId(),pending.path("tool_wait").path("action_id").asText());
            assertEquals(Long.toString(snapshot.generation()),pending.path("tool_wait").path("generation").asText());
            assertTrue(pending.path("run_revision").isTextual(),"公开revision必须保持精确十进制字符串");
            assertEquals(Long.toString(snapshot.runRevision()),pending.path("run_revision").textValue());
            assertEquals("browser_connection", pending.path("card").path("type").asText());
            assertEquals(waiting.getRequestId(), pending.path("card").path("connection_request_id").asText());
            assertEquals("NO_SELECTION", pending.path("card").path("reason").asText());
            // cursor 与 SSE 共用公开事件投影；读取不提交 continuation，也不触发浏览器 start。
            var publicEvents = new AtomicReference<JsonNode>();
            await("渠道公开 Tool input 事件", () -> {
                try {
                    var events = channelJson("GET", "/api/v1/chat/events?session_id=" + session + "&limit=500", null, channelToken, identityToken, 200);
                    for (var event : events.path("events"))
                        if ("hitl.required".equals(event.path("event").asText())
                                && run.equals(event.path("data").path("execution_id").asText())) { publicEvents.set(event); return true; }
                    return false;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            });
            assertEquals(waiting.getRequestId(), publicEvents.get().path("data").path("card").path("connection_request_id").asText());
            assertEquals("browser_connection", publicEvents.get().path("data").path("card").path("type").asText());
            assertEquals("SUSPENDED", runStatus(fixture, run));
            assertTrue(executions.waiting(ref,persistedWait.reference()).orElseThrow().acceptedResponse().isEmpty());
            assertTrue(upstream().executions(fixture.tenant(),user).isEmpty());
            assertTrue(upstream().exchanges(fixture.tenant(),user).stream().anyMatch(value->"/devices".equals(value.path())));
            assertTrue(upstream().exchanges(fixture.tenant(),user).stream().noneMatch(value->"/pair".equals(value.path())));
        }
    }

    /** 渠道 HTTP 始终携带渠道令牌；登记后的每次调用还携带正式签发的终端身份凭证。 */
    private JsonNode channelJson(String method, String path, Object body, String channelToken, String identityToken, int status) throws Exception {
        if (!path.equals("/api/v1/chat/client-profile") && (identityToken == null || identityToken.isBlank()))
            throw new IllegalArgumentException("channel_identity_token_required");
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("X-Channel-Token", channelToken).header("Accept", "application/json");
        if (identityToken != null) request.header("X-Chat-User-Token", identityToken);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (body != null) request.header("Content-Type", "application/json");
        var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), method + " " + path + " " + response.body());
        var envelope = json.readTree(response.body()); assertEquals("OK", envelope.path("code").asText());
        return envelope.path("data");
    }

    @Test
    void workerResumesWithoutWebCallbackDeduplicatesAndDoesNotStartCancelledOrEndedRuns() throws Exception {
        try(var model=new BrowserModel(json)) {
            var fixture=fixture(model.endpoint());
            String user=Long.toString(fixture.user());
            upstream().devices(fixture.tenant(),user,List.of(),null);
            var first=begin(fixture,"resume");
            var waiting=waitForRequest(fixture,first,model);
            assertTrue(starts(fixture).isEmpty());assertEquals("WAITING",waiting.getStatus());
            assertEquals("SUSPENDED",runStatus(fixture,first.run()));
            assertNotNull(waiting.getWaitSnapshot(),"worker 必须持久绑定真实 Runtime 等待身份");
            var device=new BrowserDevices.Device("exact-device","exact-browser","测试浏览器","2099-01-01T00:00:00Z",true);
            upstream().devices(fixture.tenant(),user,List.of(device),null);
            api(fixture.token(),"/api/v1/chat/browser/select",Map.of("session_id",first.session(),"connection_request_id",waiting.getRequestId(),
                    "device_id","foreign-device","make_default",false),403);
            assertEquals("SUSPENDED",runStatus(fixture,first.run()));
            assertTrue(starts(fixture).isEmpty(),"他人设备选择器不能恢复原工具或启动浏览器");
            api(fixture.token(),"/api/v1/chat/browser/select",Map.of("session_id",first.session(),"connection_request_id",waiting.getRequestId(),
                    "device_id",device.deviceId(),"make_default",false),200);
            // 此后只观察数据库；没有 history/SSE/连接详情网页轮询或用户续答触发恢复。
            await("原 Run 自动完成",()->{
                var progress=store.find(Long.parseLong(fixture.tenant()),waiting.getRequestId());
                assertNotEquals("FAILED",progress.getStatus(),progress.getError());
                String status=runStatus(fixture,first.run());
                assertFalse(Set.of("FAILED","CANCELLED","TERMINATED").contains(status),"原Run提前终结："+status);
                return "COMPLETED".equals(status);
            });
            await("连接恢复确认",()->"RESUMED".equals(store.find(Long.parseLong(fixture.tenant()),waiting.getRequestId()).getStatus()));
            assertEquals(1,starts(fixture).size());assertEquals(first.run(),starts(fixture).getFirst().run());
            assertEquals("exact-browser",((BrowserParameters.StartParameters)starts(fixture).getFirst().parameters()).browser());
            assertEquals(waiting.getInvocationId(),starts(fixture).getFirst().invocation());
            assertEquals(first.session(),starts(fixture).getFirst().session());
            assertEquals(user,starts(fixture).getFirst().endUser());
            assertEquals(1,count(fixture,"SELECT count(*) FROM nw_browser_call_receipts WHERE tenant_id=?::bigint AND run_id=? AND operation='start' AND status='succeeded'",first.run()));
            var accepted = executions.response(runRef(fixture,first.run()),
                    new com.skillforge.runtime.api.InputId("browser-connection:"+waiting.getRequestId())).orElseThrow();
            assertTrue(accepted.acceptedResponse().isPresent());
            assertTrue(accepted.continuationAttempt().isPresent());
            assertEquals(1,count(fixture,"SELECT count(*) FROM agent_runtime_execution_waits WHERE tenant_id=? AND run_id=? AND input_id IS NOT NULL",first.run()));
            assertEquals(1,count(fixture,"SELECT count(*) FROM agent_runtime_execution_attempts WHERE tenant_id=? AND run_id=? AND continuation_generation IS NOT NULL",first.run()));
            worker.poll();worker.poll();
            assertEquals(1,starts(fixture).size(),"重复后台观察不得再次派发 start");

            upstream().devices(fixture.tenant(),user,List.of(),null);
            var cancelled=begin(fixture,"cancelled");var cancelWait=waitForRequest(fixture,cancelled,model);
            api(fixture.token(),"/api/v1/chat/browser/connection-requests/cancel",Map.of("session_id",cancelled.session(),"connection_request_id",cancelWait.getRequestId()),200);
            await("取消连接收敛",()->"CANCELLED".equals(store.find(Long.parseLong(fixture.tenant()),cancelWait.getRequestId()).getStatus()));
            await("取消工具恢复后原Run终结",()->Set.of("COMPLETED","FAILED","CANCELLED","TERMINATED").contains(runStatus(fixture,cancelled.run())));
            assertEquals(1,starts(fixture).size(),"取消连接等待不能触发 start");

            var ended=begin(fixture,"ended");var endedWait=waitForRequest(fixture,ended,model);
            // 产品会话关闭只更新元数据；终态防恢复必须通过正式 Run 取消协议建立事实。
            api(fixture.token(),"/api/v1/chat/runs/cancel",Map.of("session_id",ended.session(),"run_id",ended.run()),200);
            await("正式取消使原Run终结",()->"CANCELLED".equals(runStatus(fixture,ended.run())));
            upstream().devices(fixture.tenant(),user,List.of(device),"exact-browser");
            worker.poll();worker.poll();
            await("结束Run的连接关闭",()->Set.of("CANCELLED","FAILED").contains(store.find(Long.parseLong(fixture.tenant()),endedWait.getRequestId()).getStatus()));
            assertEquals(1,starts(fixture).size(),"原Run结束后设备在线也不得启动");
            assertTrue(upstream().failures().isEmpty(),upstream().failures().toString());
        }
    }

    /** 实际HTTP命令数包含重复请求，不能只数上游去重后的task。 */
    private List<BrowserRequest> starts(Fixture fixture) {
        return upstream().executions(fixture.tenant(),Long.toString(fixture.user())).stream()
                .filter(value->"start".equals(value.operation())).toList();
    }

    /** 连接恢复后实际截图HTTP字节经生产媒体注册及Model装载进入第三轮请求，不伪造MediaRef或图片结果。 */
    @Test
    void resumedBrowserScreenshotIsPersistedAndLoadedIntoActualModelHttpRequest() throws Exception {
        assertScreenshotModelMedia(false);
    }

    /** 用户上传和真实工具HTTP返回均超过模型dimension，实际准备后才计量/冻结并发送正确变体字节。 */
    @Test
    void oversizedUserAndBrowserImagesArePreparedBeforeActualModelHttpRequests() throws Exception {
        assertScreenshotModelMedia(true);
    }

    /** 小图与大图保留独立Run和上游来源，复用相同生产上传/工具/模型路径及精确材料核验。 */
    private void assertScreenshotModelMedia(boolean oversized) throws Exception {
        try(var model=new BrowserModel(json,true)) {
            var fixture=fixture(model.endpoint(),true);String user=Long.toString(fixture.user());
            byte[] screenshot=oversized?RuntimeExecutionBrowserServer.png(2400,1600):upstream().screenshot();
            if(oversized)upstream().screenshot(fixture.tenant(),user,screenshot);
            byte[] userImage=oversized?RuntimeExecutionBrowserServer.png(1800,1300):null;
            upstream().devices(fixture.tenant(),user,List.of(),null);
            var scenario=begin(fixture,oversized?"oversized-user-and-tool-images":"screenshot",userImage);var waiting=waitForRequest(fixture,scenario,model);
            var original=executions.load(runRef(fixture,scenario.run())).orElseThrow();
            var wait=original.waiting().orElseThrow();
            assertEquals(1,model.requests().size(),"等待不能推进第二次模型或把InputRequired当作工具正文");
            assertTrue(upstream().executions(fixture.tenant(),user).isEmpty());
            var device=new BrowserDevices.Device("image-device","image-browser","截图浏览器","2099-01-01T00:00:00Z",true);
            upstream().devices(fixture.tenant(),user,List.of(device),null);
            api(fixture.token(),"/api/v1/chat/browser/select",Map.of("session_id",scenario.session(),"connection_request_id",waiting.getRequestId(),
                    "device_id",device.deviceId(),"make_default",false),200);
            await("真实截图后原Run完成",()->{
                String status=runStatus(fixture,scenario.run());
                assertFalse(Set.of("FAILED","CANCELLED","TERMINATED").contains(status),"截图原Run终结："+status);
                return "COMPLETED".equals(status);
            });
            assertEquals(3,model.requests().size());
            var sent=upstream().executions(fixture.tenant(),user);
            assertEquals(List.of("start","screenshot"),sent.stream().map(BrowserRequest::operation).toList());
            assertEquals(wait.action().actionId(),sent.getFirst().invocation());
            assertEquals(sent.getFirst().run(),sent.getLast().run());
            assertEquals(sent.getFirst().session(),sent.getLast().session());
            assertNotNull(sent.getLast().task());assertEquals(scenario.session(),sent.getLast().session());
            assertEquals(1,upstream().exchanges(fixture.tenant(),user).stream().filter(value->"/screenshot".equals(value.path())).count());
            var images=model.requests().getLast().path("messages").findValues("image_url");
            assertEquals(oversized?2:1,images.size(),"原用户图片和工具图片均应由生产Provider装载真实data URI");
            String data=images.getLast().path("url").asText();int comma=data.indexOf(',');
            assertTrue(data.startsWith("data:image/")&&comma>0&&data.substring(0,comma).endsWith(";base64"));
            byte[] imageBytes=Base64.getDecoder().decode(data.substring(comma+1));
            var pixels=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
            assertNotNull(pixels);
            if(oversized){assertEquals(1024,pixels.getWidth());assertEquals(682,pixels.getHeight());}
            else {assertEquals(40,pixels.getWidth());assertEquals(20,pixels.getHeight());}
            var attachmentRows=queryRows(jdbc,fixture.tenant(),"SELECT id::text,sha256,size,mime_type,pixel_width,pixel_height,runtime_session_id,referenced_at,media_transform,source_media_id::text AS source_media_id "
                    +"FROM nw_chat_session_attachments WHERE tenant_id=?::bigint AND runtime_session_id=? AND body_format='bytea.v1'",fixture.tenant(),scenario.session());
            String originalDigest=sha256(screenshot);
            assertEquals(1,attachmentRows.stream().filter(value->"source-v1".equals(value.get("media_transform"))&&originalDigest.equals(value.get("sha256"))).count(),
                    "真实上游PNG原字节必须由生产媒体服务持久保存");
            var runtimeRun=executions.load(runRef(fixture,scenario.run())).orElseThrow();
            var owner=runtimeRun.identity().owner().sessionRef();
            var requests=queryRows(jdbc,fixture.tenant(),"SELECT m.schema_id,m.canonical_json FROM agent_runtime_execution_actions a "
                    +"JOIN agent_runtime_execution_materials m ON m.tenant_id=a.tenant_id AND m.reference=a.request_ref "
                    +"WHERE a.tenant_id=? AND a.run_id=? AND a.kind='MODEL' ORDER BY a.action_ordinal",fixture.tenant(),scenario.run());
            assertEquals(3,requests.size());var finalRequest=requests.getLast();
            var codec=new com.skillforge.runtime.session.AgentExecutionCodec(new com.skillforge.runtime.json.CodecLimits(properties.getMaxPayloadCodeUnits(),properties.getMaxPayloadNodes(),properties.getMaxPayloadDepth()));
            var prepared=ModelRequestTestMaterials.decode(codec, new com.skillforge.runtime.api.TypedPayload(finalRequest.get("schema_id").toString(),finalRequest.get("canonical_json").toString()), jdbc, fixture.tenant());
            var frozen=prepared.request();
            var toolImages=frozen.messages().stream().filter(value->value.role()==com.skillforge.runtime.api.port.ModelPort.ModelRole.TOOL)
                    .flatMap(value->value.contents().stream()).filter(com.skillforge.runtime.api.media.ModelContent.Image.class::isInstance)
                    .map(com.skillforge.runtime.api.media.ModelContent.Image.class::cast).toList();
            assertEquals(1,toolImages.size());assertEquals(owner,toolImages.getFirst().media().owner());
            var reference=toolImages.getFirst().media();
            assertEquals(pixels.getWidth(),reference.width());assertEquals(pixels.getHeight(),reference.height());
            assertTrue(reference.byteLength()<=frozen.target().capabilities().imagePolicy().maxImageBytes());
            var transformation=prepared.transformations().stream().filter(value->value.prepared().equals(reference)).findFirst().orElseThrow();
            assertEquals(com.skillforge.runtime.api.port.ModelPort.ModelRole.TOOL,frozen.messages().get(transformation.messageIndex()).role());
            // 同正文可共享sha256，但source/normalize/model是不同MediaRef；只检查真实冻结并发送的准确id。
            var storedImage=attachmentRows.stream().filter(value->reference.id().equals(value.get("id"))).findFirst().orElseThrow();
            assertEquals("image-v1:browser-http-image.v1:1024:1048576:jpeg85-or-alpha-png",storedImage.get("media_transform"));
            assertNotNull(storedImage.get("referenced_at"),"准确模型输入MediaRef在测试读取之前必须已受保护");
            assertEquals(owner.sessionId(),storedImage.get("runtime_session_id"));
            assertEquals(reference.sha256(),storedImage.get("sha256"));assertEquals(sha256(imageBytes),reference.sha256());
            assertEquals(reference.byteLength(),((Number)storedImage.get("size")).longValue());
            var normalized=attachmentRows.stream().filter(value->Objects.equals(value.get("id"),storedImage.get("source_media_id"))).findFirst().orElseThrow();
            assertEquals("normalize-v1",normalized.get("media_transform"));assertEquals(transformation.source().id(),normalized.get("id"));
            assertEquals(oversized?2400:40,transformation.source().width());assertEquals(oversized?1600:20,transformation.source().height());
            var sourceImage=attachmentRows.stream().filter(value->Objects.equals(value.get("id"),normalized.get("source_media_id"))).findFirst().orElseThrow();
            assertEquals("source-v1",sourceImage.get("media_transform"));assertEquals(originalDigest,sourceImage.get("sha256"));
            assertNotEquals(sourceImage.get("id"),storedImage.get("id"),"派生引用身份不同，即使原PNG与规范PNG的正文相同");
            assertArrayEquals(imageBytes,media.read(owner,reference),"模型data URI必须等于准确冻结MediaRef的生产实际读取字节");
            if(oversized) {
                assertEquals(2,prepared.transformations().size());
                var userTransformation=prepared.transformations().stream().filter(value->frozen.messages().get(value.messageIndex()).role()==com.skillforge.runtime.api.port.ModelPort.ModelRole.USER).findFirst().orElseThrow();
                assertEquals(1800,userTransformation.source().width());assertEquals(1300,userTransformation.source().height());
                assertTrue(userTransformation.prepared().width()<=1024&&userTransformation.prepared().height()<=1024);
                assertNotEquals(userTransformation.source().id(),userTransformation.prepared().id());
                for(int index=0;index<requests.size();index++) {
                    var row=requests.get(index);var actual=ModelRequestTestMaterials.decode(codec, new com.skillforge.runtime.api.TypedPayload(row.get("schema_id").toString(),row.get("canonical_json").toString()), jdbc, fixture.tenant());
                    var mapping=actual.transformations().stream().filter(value->value.source().equals(userTransformation.source())).findFirst().orElseThrow();
                    assertEquals(userTransformation.prepared(),mapping.prepared(),"每轮保留同一原用户图及同一策略变体，不对上轮变体再派生");
                    String uri=model.requests().get(index).path("messages").findValues("image_url").getFirst().path("url").asText();
                    byte[] emitted=Base64.getDecoder().decode(uri.substring(uri.indexOf(',')+1));
                    assertArrayEquals(media.read(owner,mapping.prepared()),emitted);
                    assertEquals(mapping.prepared().sha256(),sha256(emitted));
                }
                assertEquals(1,attachmentRows.stream().filter(value->"source-v1".equals(value.get("media_transform"))&&uncheckedSha256(userImage).equals(value.get("sha256"))).count());
                assertEquals(2,attachmentRows.stream().filter(value->value.get("media_transform").toString().startsWith("image-v1:")).count(),"两个来源各自只保存一个准确策略变体");
            }
            var accepted=executions.waiting(runRef(fixture,scenario.run()),wait).orElseThrow();
            assertTrue(accepted.acceptedResponse().isPresent());assertTrue(accepted.continuationAttempt().isPresent());
            assertEquals(accepted.continuationAttempt(),executions.action(wait.action()).orElseThrow().selectedAttempt());
            assertEquals(2,count(fixture,"SELECT count(*) FROM agent_runtime_execution_actions WHERE tenant_id=? AND run_id=? AND kind='TOOL' AND status='SUCCEEDED'",scenario.run()));
            assertEquals(1,count(fixture,"SELECT count(*) FROM agent_runtime_execution_events WHERE tenant_id=? AND run_id=? AND type='execution.completed'",scenario.run()));
            assertTrue(upstream().failures().isEmpty(),upstream().failures().toString());
        }
    }

    /** 比对实际二进制材料，不使用模型文本或上游预填摘要作为字节证据。 */
    private static String sha256(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** 流内引用预计算摘要，算法缺失保持明确失败。 */
    private static String uncheckedSha256(byte[] bytes) {
        try {return sha256(bytes);}catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }

}
