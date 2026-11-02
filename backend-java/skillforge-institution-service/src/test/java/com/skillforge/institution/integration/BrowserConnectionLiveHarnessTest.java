package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 显式启用的真实浏览器驻留夹具，不包含客户端Mock；外部只执行产品配对和用户确认。
 * 私有ready文件供本机验收使用，包含短期成员凭据，不能作为公开测试报告。
 */
@EnabledIfEnvironmentVariable(named="SKILLFORGE_BROWSER_LIVE",matches="true")
class BrowserConnectionLiveHarnessTest extends BrowserConnectionIntegrationSupport {
    @org.springframework.beans.factory.annotation.Autowired
    private com.skillforge.core.capability.CapabilityCatalogService catalog;
    @org.springframework.beans.factory.annotation.Autowired
    private com.skillforge.core.capability.SkillDraftService drafts;
    /** 使用根线程准备的独立服务；凭据只从进程环境注入，不写普通报告。 */
    @DynamicPropertySource
    static void browserService(DynamicPropertyRegistry registry) {
        registry.add("BROWSER_SERVICE_URL",()->required("SKILLFORGE_BROWSER_LIVE_SERVICE_URL"));
        registry.add("BROWSER_SERVICE_TOKEN",()->required("SKILLFORGE_BROWSER_LIVE_SERVICE_TOKEN"));
    }

    @Test
    void exactPairingResumesOriginalWaitingRunThroughBackgroundWorker() throws Exception {
        Path directory=Path.of(required("SKILLFORGE_BROWSER_LIVE_EVIDENCE")).toAbsolutePath();
        Files.createDirectories(directory);
        var report=new LinkedHashMap<String,Object>();report.put("status","FAILED");
        try(var model=new BrowserModel(json)) {
            var fixture=publishAndMountBrowserSkill(fixture(model.endpoint()),report);var scenario=begin(fixture,"live-extension");
            var pending=waitForRequest(fixture,scenario);
            String requestId=pending.getRequestId();
            report.put("tenant",fixture.tenant());report.put("session",scenario.session());report.put("run",scenario.run());
            report.put("requestId",requestId);report.put("invocationId",pending.getInvocationId());
            long initial=count(fixture,"SELECT count(*) FROM nw_browser_call_receipts WHERE tenant_id=?::bigint AND run_id=? AND operation='start'",scenario.run());
            assertEquals(0,initial,"配对之前不得派发start");
            // 不调用pair或continuation；用户/外部验收仅通过正式产品API配对，本夹具只查持久原事实。
            var ready=new LinkedHashMap<String,Object>();
            ready.put("endpoint","http://127.0.0.1:"+port);ready.put("tenantId",fixture.tenant());ready.put("endUserId",fixture.user());
            ready.put("accessToken",fixture.token());ready.put("sessionId",scenario.session());ready.put("runId",scenario.run());
            ready.put("connectionRequestId",requestId);ready.put("invocationId",pending.getInvocationId());
            ready.put("pairPath","/api/v1/chat/browser/pair");
            ready.put("pairBody",Map.of("session_id",scenario.session(),"connection_request_id",requestId,"make_default",false));
            ready.put("readyAt",Instant.now().toString());ready.put("deadlineAt",Instant.now().plusSeconds(300).toString());
            Files.writeString(directory.resolve("ready.private.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(ready));
            long deadline=System.nanoTime()+Duration.ofMinutes(5).toNanos();boolean completed=false;
            while(System.nanoTime()<deadline) {
                String status=runStatus(fixture,scenario.run());
                if("COMPLETED".equals(status)){completed=true;break;}
                if(java.util.Set.of("FAILED","TERMINATED","CANCELLED").contains(status))fail("原Run未完成："+status);
                Thread.sleep(250);
            }
            assertTrue(completed,"业务等待窗口5分钟内原Run未完成；不重试配对、不提交网页continuation");
            report.put("runStatus",runStatus(fixture,scenario.run()));
            // Run 终态先提交，Session 完成事件由独立 publication worker 物化；只等待原事件，不重发业务。
            await("原Run完成事件物化",()->count(fixture,
                    "SELECT count(*) FROM agent_runtime_session_events WHERE tenant_id=? AND run_id=? AND event_type='run.completed'",
                    scenario.run())>0);
            var completedEvents=queryRows(jdbc,fixture.tenant(),
                    "SELECT event_id,occurred_at_epoch_second,occurred_at_nano FROM agent_runtime_session_events WHERE tenant_id=? AND run_id=? AND event_type='run.completed'",
                    fixture.tenant(),scenario.run());
            assertEquals(1,completedEvents.size(),"原Run应只有一个持久完成事件");
            var completedEvent=completedEvents.getFirst();
            report.put("runCompletedAt",Instant.ofEpochSecond(
                    ((Number)completedEvent.get("occurred_at_epoch_second")).longValue(),
                    ((Number)completedEvent.get("occurred_at_nano")).longValue()).toString());
            report.put("runCompletedEventId",completedEvent.get("event_id"));
            var restored=store.find(Long.parseLong(fixture.tenant()),requestId);
            assertNotNull(restored.getDeviceId());assertNotNull(restored.getBrowserId());
            report.put("deviceId",restored.getDeviceId());report.put("browserId",restored.getBrowserId());
            await("真实浏览器连接恢复确认",()->"RESUMED".equals(store.find(Long.parseLong(fixture.tenant()),requestId).getStatus()));
            var receipts=queryRows(jdbc,fixture.tenant(),"SELECT invocation_id,status,public_receipt FROM nw_browser_call_receipts WHERE tenant_id=?::bigint AND run_id=? AND operation='start'",fixture.tenant(),scenario.run());
            report.put("startReceipts",receipts.size());
            if(receipts.size()==1) report.put("browserReceipt",json.readTree(receipts.getFirst().get("public_receipt").toString()));
            assertEquals(1,receipts.size());assertEquals(pending.getInvocationId(),receipts.getFirst().get("invocation_id"));
            assertEquals("active",receipts.getFirst().get("status"),"成功启动并claim的浏览器任务应为active");
            var receipt=json.readTree(receipts.getFirst().get("public_receipt").toString());
            assertEquals(restored.getBrowserId(),receipt.path("browser").asText());
            assertEquals(1,count(fixture,"SELECT count(*) FROM nw_runtime_session_tool_input_responses WHERE tenant_id=? AND run_id=?",scenario.run()));
            worker.poll();worker.poll();
            assertEquals(1,count(fixture,"SELECT count(*) FROM nw_browser_call_receipts WHERE tenant_id=?::bigint AND run_id=? AND operation='start'",scenario.run()));
            report.put("status","PASS");report.put("deviceId",restored.getDeviceId());report.put("browserId",restored.getBrowserId());
            report.put("startReceipts",1);report.put("toolResponseJournals",1);report.put("runStatus",runStatus(fixture,scenario.run()));
            report.put("browserReceipt",receipt);
        }finally {
            report.put("finishedAt",Instant.now().toString());
            Files.writeString(directory.resolve("result.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }

    /** 在本次隔离测试机构导入新不可变版本，并用公开API挂载准确版本；不更新已有Skill。 */
    private Fixture publishAndMountBrowserSkill(Fixture fixture,Map<String,Object> report)throws Exception {
        byte[] archive=Files.readAllBytes(Path.of(required("SKILLFORGE_BROWSER_LIVE_SKILL_ZIP")));
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(archive));
        assertEquals("fd895fddc82d3dd1e7a5639aa239b0cfee58e4a7deeff3c40897362431af8293",sha,"导入必须使用根线程绑定的确切BrowserSkill包");
        var files=new LinkedHashMap<String,byte[]>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null)if(!entry.isDirectory())files.put(entry.getName(),zip.readAllBytes());
        }
        assertEquals(8,files.size());
        var scope=com.skillforge.core.capability.CapabilityCatalogScope.tenant(Long.parseLong(fixture.tenant()));
        long version;long definition;String versionKey;
        com.skillforge.common.context.TenantContext.setTenantId(fixture.tenant());
        try {
            var created=catalog.createDefinition(scope,com.skillforge.core.capability.CapabilityType.SKILL,
                    "browser-live-"+java.util.UUID.randomUUID(),"BrowserSkill 按需连接验收版本","来自本批绑定的真实包");
            definition=created.id();var draft=drafts.create(scope,definition,null);
            var imported=drafts.importZip(scope,draft.id(),draft.revision(),archive);
            imported=drafts.save(scope,imported.id(),imported.revision(),
                    new com.skillforge.core.capability.SkillDraftContent("browser-connection-v1",imported.files(),
                            imported.modelInvocable(),imported.userInvocable(),imported.activationMode(),
                            imported.conflictGroup(),imported.requiredCapabilities()));
            var validation=drafts.validate(scope,imported.id(),imported.revision());assertTrue(validation.valid(),validation.toString());
            var publication=drafts.publish(scope,imported.id(),imported.revision(),created.revision());version=publication.versionId();
            var published=catalog.getVersion(scope,version);assertEquals("PUBLISHED",published.status());versionKey=published.versionKey();
            var savedFiles=drafts.version(scope,version).files();assertEquals(files.keySet(),savedFiles.keySet());
            for(var entry:files.entrySet())assertArrayEquals(entry.getValue(),java.util.Base64.getDecoder().decode(savedFiles.get(entry.getKey())),entry.getKey());
        }finally{com.skillforge.common.context.TenantContext.clear();}
        String agent=api(fixture.token(),"/api/v1/agents/revise",Map.of("agent_id",fixture.agent()),200).path("id").asText();
        var mounted=api(fixture.token(),"/api/v1/agents/capability-mounts",Map.of("agent_id",agent,"payload",Map.of("capability_type","SKILL",
                "capability_definition_id",Long.toString(definition),"capability_version_id",Long.toString(version),
                "skill_config",Map.of("activation_mode","ON_DEMAND","token_budget",4000),"priority",0)),201);
        var mount=element(mounted.path("mounts"),"capability_definition_id",Long.toString(definition));
        assertEquals(Long.toString(version),mount.path("capability_version_id").asText());
        var read=get(fixture.token(),"/api/v1/agents/capability-mounts?agentId="+agent);
        assertEquals(mounted,read);api(fixture.token(),"/api/v1/agents/publish",Map.of("agent_id",agent),200);
        report.put("skillDefinitionId",definition);report.put("skillVersionId",version);report.put("skillVersionKey",versionKey);
        report.put("skillArchiveSha256",sha);report.put("skillFileCount",files.size());report.put("skillMountId",mount.path("mount_id").asText());
        return new Fixture(fixture.tenant(),fixture.token(),agent,fixture.user());
    }

    /** 缺少显式服务或证据配置立即拒绝，不回退到开发环境服务。 */
    private static String required(String key) {
        String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException("缺少真实浏览器验收环境参数："+key);return value;
    }
}
