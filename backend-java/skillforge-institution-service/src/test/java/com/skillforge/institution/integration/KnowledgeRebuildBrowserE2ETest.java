package com.skillforge.institution.integration;

import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.persistence.TenantMemberMapper;
import com.skillforge.core.model.ModelWrite;
import com.skillforge.core.model.PlatformModelCatalogService;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.knowledge.*;
import com.skillforge.core.knowledge.dto.request.KnowledgeCapabilityPolicyRequest;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.foundation.persistence.TenantMemberDO;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;

/** 实际机构页面操作与真实PG发布事实；本地HTTP模型用屏障保留可观察的RUNNING。 */
@TestPropertySource(properties={"skillforge.knowledge-rebuild-delay-ms=600000", "agent-runtime.workers.auto-startup=false"})
@org.springframework.context.annotation.Import(KnowledgeEmbeddingFixture.Configuration.class)
class KnowledgeRebuildBrowserE2ETest extends AbstractIntegrationTest {
    static final KnowledgeBrowserServer FRONTEND = KnowledgeBrowserServer.start();
    @DynamicPropertySource static void origins(DynamicPropertyRegistry r) {
        r.add("skillforge.web.allowed-origins", FRONTEND::baseUrl);
    }
    @Autowired KnowledgeService knowledge; @Autowired KnowledgeBaseRevisionService revisions;
    @Autowired KnowledgeRebuildService rebuilds; @Autowired PlatformModelCatalogService models;
    @Autowired TenantMapper tenants; @Autowired PlanMapper plans; @Autowired TenantMemberMapper members;
    @Autowired PasswordEncoder passwords; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired com.skillforge.core.foundation.access.FoundationOrganizationService organization;
    @LocalServerPort int port;

    @Test void institutionPagePreparesPublishesAndCancelsWithPgAuthority() throws Exception {
        ensureFreePlan(plans);
        String slug="knowledge-browser-"+SnowflakeIds.nextId(), tenant=createTenant(tenants,plans,slug);
        TenantContext.setTenantId(tenant);
        var gate=new CountDownLatch(1); var entered=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>(); var worker=new AtomicReference<Thread>();
        var control=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newVirtualThreadPerTaskExecutor();control.setExecutor(executor);
        Set<ProcessHandle> owned=new LinkedHashSet<>();
        try (var seedEmbedding=new KnowledgeEmbeddingFixture(models,tenant)) {
            var seed=models.create(new ModelWrite(null,"Browser seed embedding","EMBEDDING",true,"custom","custom-embedding","custom-v1","openai-embeddings","bearer-api-key",seedEmbedding.endpoint(),"browser-seed","fixture-key",null,null,null,null,null,30,1536,null,java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO,"CNY"),"knowledge-browser-fixture");
            models.publish(seed.id(),seed.revision(),"knowledge-browser-fixture");
            var selections=models.selections(tenant);
            models.select(tenant,new com.skillforge.core.model.ModelSelections(selections.llmModelConfigId(),seed.id(),selections.rerankModelConfigId()));
            var member=new TenantMemberDO();member.setId(SnowflakeIds.nextId());member.setTenantId(Long.parseLong(tenant));
            member.setUsername("knowledge-browser-admin");member.setRole("admin");member.setPermissions(List.of());
            member.setStatus("active");member.setPasswordHash(passwords.encode("KnowledgeBrowser123"));member.setCreatedAt(Instant.now());members.insert(member);
            organization.initializeTenant(tenant, member.getId().toString());
            var base=knowledge.createBase(tenant,"Browser Knowledge",null);
            List<String> originalSources = new ArrayList<>();
            List<String> documentContents = List.of(
                    "申请规则：资料查询需先选择已授权目录并取得申请编号。紧急口头请求仍需审批，未确认责任人保持待确认。",
                    "取消规则：处理前取消可退款；处理后转人工审核。供应商返回未知时保留原回执，禁止重新派发。",
                    "报销规则：申请编号、发票和付款凭证必须一致。金额保留两位小数，缺失发票转待补充，不能重复报销。");
            for (int index = 0; index < documentContents.size(); index++) {
                var upload = knowledge.uploadFile(tenant, base.id(), "Browser source " + index,
                        new MockMultipartFile("file", "source-" + index + ".txt", "text/plain",
                                documentContents.get(index).getBytes(StandardCharsets.UTF_8)));
                assertEquals(1, upload.chunks());
                originalSources.add(upload.sourceVersion());
            }
            assertEquals(documentContents.size(),seedEmbedding.calls());
            var draft=revisions.create(tenant,base.id());
            var ready=revisions.markReady(tenant,base.id(),draft.revision(),originalSources,new KnowledgeCapabilityPolicyRequest(true,false,Set.of("VECTOR"),"PINNED",true));
            revisions.publish(tenant,base.id(),ready.revision());
            String url="http://127.0.0.1:"+control.getAddress().getPort();
            control.createContext("/embeddings",exchange->{try(exchange){entered.countDown();
                try { if(!gate.await(90,TimeUnit.SECONDS))throw new IllegalStateException("embedding_gate_timeout"); }
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);}
                var input=json.readTree(exchange.getRequestBody()).path("input");var data=new ArrayList<Map<String,Object>>();
                for(int i=0;i<input.size();i++)data.add(Map.of("index",i,"embedding",Collections.nCopies(1536,.02)));
                byte[] bytes=json.writeValueAsBytes(Map.of("data",data,"usage",Map.of("prompt_tokens",input.size(),"total_tokens",input.size())));
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }});
            control.createContext("/run",exchange->{try(exchange){
                Thread thread=Thread.startVirtualThread(()->{TenantContext.setTenantId(tenant);try{rebuilds.run(tenant,rebuilds.latest(tenant,base.id()).jobId());}catch(Throwable e){failure.set(e);}finally{TenantContext.clear();}});worker.set(thread);
                try{assertTrue(entered.await(20,TimeUnit.SECONDS),"真实Embedding未进入屏障");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
                exchange.sendResponseHeaders(200,-1);
            }});
            control.createContext("/release",exchange->{try(exchange){gate.countDown();exchange.sendResponseHeaders(200,-1);}});control.start();
            var targetModel = models.create(new ModelWrite(null,"Browser target embedding","EMBEDDING",true,"custom","custom-embedding","custom-v1","openai-embeddings","bearer-api-key",url,"browser-target","fixture-key",null,null,null,null,null,120,1536,null,java.math.BigDecimal.valueOf(0L),java.math.BigDecimal.valueOf(0L),java.math.BigDecimal.valueOf(0L),"CNY"),"knowledge-browser-fixture");
            models.publish(targetModel.id(), targetModel.revision(), "knowledge-browser-fixture");
            Path root=repoRoot(),admin=root.resolve("frontend-admin"),cli=admin.resolve("node_modules/@playwright/test/cli.js");assertTrue(Files.isRegularFile(cli));
            Path evidence=FRONTEND.evidence();
            FRONTEND.bindBackend("http://127.0.0.1:"+port);
            var command=new ProcessBuilder(nodeExecutable(),cli.toString(),"test","--config=playwright.knowledge.config.ts");command.directory(admin.toFile());command.redirectErrorStream(true);command.redirectOutput(evidence.resolve("playwright-vite.log").toFile());
            var env=command.environment();env.remove("E2E_BASE_URL");env.put("E2E_BASE_URL",FRONTEND.baseUrl());env.put("E2E_BACKEND_URL","http://127.0.0.1:"+port);
            env.put("E2E_TENANT_SLUG",slug);env.put("E2E_USERNAME","knowledge-browser-admin");env.put("E2E_PASSWORD","KnowledgeBrowser123");env.put("E2E_KB_ID",base.id().toString());env.put("E2E_CONTROL_URL",url);env.put("E2E_ARTIFACT_DIR",evidence.toString());
            Process process=command.start();owned.add(process.toHandle());long end=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);
            while(process.isAlive()&&System.nanoTime()<end){capture(owned);process.waitFor(200,TimeUnit.MILLISECONDS);}capture(owned);
            assertFalse(process.isAlive(),"browser_timeout: "+evidence);assertEquals(0,process.exitValue(),"browser_failed: "+evidence+"\n"+Files.readString(evidence.resolve("playwright-vite.log")));
            assertNull(failure.get());var receipt=json.readTree(Files.readString(evidence.resolve("browser-receipt.json")));
            assertEquals("SUCCEEDED",receipt.path("publish").path("status").asText());
            assertEquals("PUBLISHED",receipt.path("publish").path("snapshot").path("status").asText());assertEquals("CANCELLED",receipt.path("cancel").path("status").asText());
            var facts=queryRows(jdbc,tenant,"SELECT b.active_revision_id,r.revision,r.status,d.current_source_version,b.rebuild_job_id FROM nw_knowledge_bases b JOIN nw_knowledge_base_revisions r ON r.id=b.active_revision_id JOIN nw_knowledge_documents d ON d.kb_id=b.id WHERE b.tenant_id=? AND b.id=?",Long.parseLong(tenant),base.id());
            assertEquals(3,facts.size());
            for (var fact : facts) {
                assertEquals("published", fact.get("status")); assertNull(fact.get("rebuild_job_id"));
                assertFalse(originalSources.contains(fact.get("current_source_version")));
                assertEquals(receipt.path("publish").path("snapshot").path("published_revision").asLong(), ((Number)fact.get("revision")).longValue());
            }
            Files.writeString(evidence.resolve("database-evidence.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(facts));
        } finally {
            gate.countDown();control.stop(0);
            try {
                if(worker.get()!=null){worker.get().join(10000);assertFalse(worker.get().isAlive(),"重建worker必须实际退出");}
                executor.close();
            } finally {try{terminate(owned);}finally{FRONTEND.close();TenantContext.clear();}}
        }
    }
    private static String nodeExecutable(){String value=System.getenv("NODE_BINARY");return value==null||value.isBlank()?"node":value;}
    private static Path repoRoot(){Path p=Path.of("").toAbsolutePath();while(p!=null){if(Files.isRegularFile(p.resolve("frontend-admin/package.json")))return p;p=p.getParent();}throw new IllegalStateException("repository_root_missing");}
    private static void capture(Set<ProcessHandle> handles){for(var h:List.copyOf(handles))h.descendants().forEach(handles::add);}
    private static void terminate(Set<ProcessHandle> handles){
        capture(handles);for(var h:List.copyOf(handles).reversed())if(h.isAlive())h.destroy();awaitExit(handles,5);
        for(var h:List.copyOf(handles).reversed())if(h.isAlive())h.destroyForcibly();awaitExit(handles,10);
        assertTrue(handles.stream().noneMatch(ProcessHandle::isAlive),"本次Vite/Playwright/浏览器子树必须实际退出");
    }
    private static void awaitExit(Set<ProcessHandle> handles,int seconds){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(handles.stream().anyMatch(ProcessHandle::isAlive)&&System.nanoTime()<end){capture(handles);java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));}}
}
