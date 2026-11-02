package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.chat.access.OpenPlatformSessionAccessService;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.institution.chat.ChatClientProfileMapper;
import com.skillforge.institution.chat.ChatEndUserTokens;
import com.skillforge.institution.config.ExternalAuthPaths;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 真实 loopback HTTP、正式票据签发/核验与磁盘读取组合；只隔离已发布事实和渠道授权的数据库入口。
 * 每次故障仅修改用例自有文件，finally 恢复原字节，不能冒充真实数据库发布或浏览器验收。
 */
class ArtifactPreviewIntegrityHttpTest {
    /** 非 HTML 正文超过流式缓冲区，单字节同长度损坏必须发生在成功响应提交之前。 */
    private static final byte[] ORIGINAL=("body{color:#123456}/*r15-owned-resource*/\n".repeat(4096))
            .getBytes(StandardCharsets.UTF_8);
    /** 预览入口当前通用拒绝契约，不能套用下载的 500 JSON。 */
    private static final byte[] REFUSAL="预览不可用、已过期或资源未打包".getBytes(StandardCharsets.UTF_8);
    @TempDir Path root;

    /** 两条真实入口先读合法资源，再只损坏正文；原始证据和恢复完成后才断言拒绝契约。 */
    @ParameterizedTest @ValueSource(booleans={false,true})
    void bothPreviewRoutesRefuseSameLengthCorruptionBeforeCommittingSuccess(boolean published) throws Exception {
        String route=published?"deliverable":"artifact";
        var evidence=new Evidence(route);
        try(var fixture=new Fixture(root)) {
            URI uri=fixture.resourceUri(published);
            byte[] damaged=ORIGINAL.clone();damaged[damaged.length-1]^=1;
            evidence.fixture("original",ORIGINAL);
            Response normal=null,corrupt=null,recovered=null;
            try {
                normal=fixture.fetch(uri,evidence,"normal");
                assertNormal(normal);
                Files.write(fixture.source,damaged);
                assertEquals(ORIGINAL.length,Files.size(fixture.source));
                assertNotEquals(sha(ORIGINAL),sha(Files.readAllBytes(fixture.source)));
                evidence.fixture("damaged",Files.readAllBytes(fixture.source));
                corrupt=fixture.fetch(uri,evidence,"damaged");
            } finally {
                // 即使 HTTP 读取或断言失败，也只恢复本用例自有文件，并留下可核对的实际恢复摘要。
                Files.write(fixture.source,ORIGINAL);
                byte[] restored=Files.readAllBytes(fixture.source);
                evidence.fixture("restored",restored);
                assertEquals(sha(ORIGINAL),sha(restored),"必须恢复原始持久正文摘要");
                recovered=fixture.fetch(uri,evidence,"restored");
                evidence.finish(normal,corrupt,recovered);
            }
            assertNormal(recovered);
            assertRefused(corrupt);
            fixture.assertStagingEmpty();
        }
    }

    /** 没有首次正文写入的合法空资源也必须成功提交；两条入口共用同一延迟提交路径。 */
    @Test void emptyPreviewResourceStillCommitsSuccess() throws Exception {
        byte[] empty=new byte[0];
        try(var fixture=new Fixture(root,empty)) {
            for(boolean published:new boolean[]{false,true}) {
                var evidence=new Evidence(published?"deliverable-empty":"artifact-empty");
                evidence.fixture("original",Files.readAllBytes(fixture.source));
                var response=fixture.fetch(fixture.resourceUri(published),evidence,"normal");
                assertNormal(response,empty);
                evidence.write("receipt.json",Map.of("normal",Evidence.facts("normal",response),
                        "original_sha256",sha(empty),"empty_resource",true));
            }
            fixture.assertStagingEmpty();
        }
    }

    /** 合法预览保留精确正文、CSS 媒体类型和既有隔离安全头。 */
    private static void assertNormal(Response response) {
        assertNormal(response,ORIGINAL);
    }

    /** 普通或零长度资源都按调用方独立的合法原字节断言，不从响应反推预期。 */
    private static void assertNormal(Response response,byte[] expected) {
        assertNotNull(response);assertEquals("",response.readFailure());
        assertEquals(200,response.status());assertArrayEquals(expected,response.body());
        assertEquals(List.of("text/css; charset=utf-8"),response.headers().get("content-type"));
        assertEquals(List.of("no-store"),response.headers().get("cache-control"));
        assertEquals(List.of("nosniff"),response.headers().get("x-content-type-options"));
        assertEquals(List.of("no-referrer"),response.headers().get("referrer-policy"));
        assertEquals(List.of("null"),response.headers().get("access-control-allow-origin"));
        assertTrue(response.headers().get("content-security-policy").getFirst().contains("sandbox allow-scripts"));
    }

    /** 精确现行预览错误体本身证明没有 CSS 正文、文件前缀或被提交为 200 的空体。 */
    private static void assertRefused(Response response) {
        assertNotNull(response);assertEquals("",response.readFailure());
        assertEquals(404,response.status(),"完整性拒绝不能保留先提交的 HTTP 200");
        assertEquals(List.of("text/plain; charset=utf-8"),response.headers().get("content-type"));
        assertArrayEquals(REFUSAL,response.body());
        assertFalse(response.headers().containsKey("content-disposition"));
    }

    /** 计算实际文件和 HTTP 响应摘要，不使用清单的声明值替代读取证据。 */
    private static String sha(byte[] bytes) { return ArtifactStorage.hex(ArtifactStorage.hash().digest(bytes)); }

    /**
     * 复用下载完整性测试的合法磁盘/清单事实、渠道测试的正式签票入口、预览集成测试的短命 HTTP 装配。
     * 监听器、HTTP 客户端及工作线程均由本 fixture 释放，端口由 loopback:0 原子分配。
     */
    private static final class Fixture implements AutoCloseable {
        /** 仅测试本地票据的合成密钥，不读取部署配置。 */
        private static final String KEY="r15-preview-offline-signing-key-32-characters";
        private final Path root;
        private final Path source;
        private final ArtifactAccess access;
        private final DeliverableAccess deliverables;
        private final ArtifactRow row;
        private final String origin;
        private final HttpServer server;
        private final ExecutorService workers;
        private final HttpClient client;
        private final ChatEndUserTokens userTokens;

        /** 默认合法 CSS 保持确认阶段的原输入不变。 */
        private Fixture(Path root) throws Exception {
            this(root,ORIGINAL);
        }

        /** 已发布/已检查事实只对精确租户、文件及渠道成立，正文可显式为合法空文件。 */
        private Fixture(Path root,byte[] content) throws Exception {
            this.root=root;
            var json=new ObjectMapper();
            var mapper=mock(ArtifactMapper.class);
            var manager=mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(ignored->new SimpleTransactionStatus());
            var projection=mock(StudioArtifactProjection.class);
            var storage=new ArtifactStorage(mapper,json,manager,root.toString(),mock(RunSettingsService.class),
                    projection,mock(ArtifactPublicInspection.class));
            String id=UUID.randomUUID().toString();
            byte[] html="<!doctype html><link rel=\"stylesheet\" href=\"style.css\"><p>owned preview</p>"
                    .getBytes(StandardCharsets.UTF_8);
            var files=List.of(new ArtifactMetadata.File("index.html",html.length,sha(html)),
                    new ArtifactMetadata.File("style.css",content.length,sha(content)));
            String manifest=json.writeValueAsString(files);
            row=new ArtifactRow(id,"7","session","run","invocation","website","R15 owned website",
                    html.length+content.length,sha(manifest.getBytes(StandardCharsets.UTF_8)),manifest,
                    sha("[]".getBytes(StandardCharsets.UTF_8)),Instant.now(),Instant.now().plusSeconds(3600),"ready");
            Path directory=root.resolve("ready/7").resolve(id);Files.createDirectories(directory);
            Files.write(directory.resolve("index.html"),html);source=directory.resolve("style.css");Files.write(source,content);
            when(mapper.find("7",id)).thenReturn(row);
            when(mapper.inputs("7",id)).thenReturn(List.of());
            when(mapper.anyFileReadable("7",id,ArtifactPublicInspection.POLICY_VERSION)).thenReturn(true);
            when(mapper.publiclyInspected("7",id,ArtifactPublicInspection.POLICY_VERSION)).thenReturn(true);
            for(var file:files)when(mapper.fileReadable("7",id,file.path(),ArtifactPublicInspection.POLICY_VERSION)).thenReturn(true);
            when(mapper.canChannelAccess("7","8","session")).thenReturn(true);
            when(mapper.sessionEndUser("7","session")).thenReturn("9");
            var properties=new SkillForgeProperties();properties.setSecretKey(KEY);
            var profiles=mock(ChatClientProfileMapper.class);var endUsers=mock(EndUserService.class);
            when(profiles.channelExists(7,8)).thenReturn(true);
            when(profiles.find(7,8,"preview-integrity-client")).thenReturn(9L);
            var endUser=new EndUserDO();endUser.setId(9L);endUser.setStatus("active");
            when(endUsers.require("7",9L)).thenReturn(endUser);
            userTokens=new ChatEndUserTokens(json,properties,profiles,endUsers);
            workers=Executors.newVirtualThreadPerTaskExecutor();
            client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpServer ownedServer=null;
            try {
                ownedServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server=ownedServer;
                origin="http://127.0.0.1:"+server.getAddress().getPort();
                String platform="http://localhost:3000";
                access=new ArtifactAccess(storage,json,mock(AuditService.class),projection,mock(OpenPlatformSessionAccessService.class),
                        mock(FoundationAccess.class),KEY,origin,platform,platform,org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));
                var deliveryMapper=mock(DeliverableMapper.class);
                for(var file:files) {
                    String fileId=file.path().equals("index.html")?"101":"102";
                    var publishedFile=Map.<String,Object>ofEntries(Map.entry("artifact_id",id),Map.entry("path",file.path()),
                            Map.entry("download_name",file.path()),Map.entry("relative_path",file.path()),
                            Map.entry("representation_id","201"),Map.entry("entry_path","index.html"),
                            Map.entry("session_id","session"),Map.entry("current_revision_id","301"),Map.entry("revision_no",1));
                    when(deliveryMapper.revisionFile(7L,"301",fileId)).thenReturn(List.of(publishedFile));
                    when(deliveryMapper.representationPath(7L,"201",file.path())).thenReturn(List.of(Map.of("file_id",fileId)));
                }
                when(deliveryMapper.representationFileCount(7L,"201")).thenReturn(2);
                var reader=new DeliverableReader(deliveryMapper,access,storage);
                deliverables=new DeliverableAccess(access,reader,json,KEY,platform,origin,300,3600);
                var endpoint=new ArtifactPreviewServer(access,storage,deliverables,0,origin,platform,"");
                server.createContext("/p/",endpoint::serve);server.createContext("/d/",endpoint::serveDeliverable);
                server.setExecutor(workers);server.start();
            } catch(Exception|Error setupFailure) {
                if(ownedServer!=null)ownedServer.stop(0);client.close();workers.close();throw setupFailure;
            }
        }

        /** 经现有渠道授权入口签发真正生产票据，仅将入口页面路径替换为同表示中声明的 CSS。 */
        private URI resourceUri(boolean published) {
            String previousTenant=TenantContext.getTenantId(),previousChannel=ChannelContext.getChannelId();
            var previousRequest=RequestContextHolder.getRequestAttributes();
            try {
                TenantContext.setTenantId("7");ChannelContext.setChannelId("8");
                var request=new MockHttpServletRequest("POST","/api/v1/chat/artifacts/preview-ticket");
                request.setAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE,ExternalAuthPaths.SOURCE_CHANNEL);
                request.addHeader(ChatEndUserTokens.HEADER,userTokens.issue(7,8,9,"preview-integrity-client"));
                RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                userTokens.authenticate(request);
                String entry=published?deliverables.ticket("301","101",true).get("url"):access.ticket(row.id(),"preview",null);
                assertTrue(entry.startsWith(origin+(published?"/d/":"/p/")));
                assertTrue(entry.endsWith("/index.html"));
                return URI.create(entry.substring(0,entry.lastIndexOf('/')+1)+"style.css");
            } finally {
                if(previousTenant==null)TenantContext.clear();else TenantContext.setTenantId(previousTenant);
                if(previousChannel==null)ChannelContext.clear();else ChannelContext.setChannelId(previousChannel);
                if(previousRequest==null)RequestContextHolder.resetRequestAttributes();else RequestContextHolder.setRequestAttributes(previousRequest);
            }
        }

        /** 先保存已收到的状态/响应头，再有界读取正文；正文断连不能抹掉已提交的成功头。 */
        private Response fetch(URI uri,Evidence evidence,String stage) throws Exception {
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
            var received=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            var headers=received.headers().map();
            var body=new ByteArrayOutputStream();String readFailure;
            var reader=Executors.newVirtualThreadPerTaskExecutor();
            try(InputStream input=received.body()) {
                evidence.head(stage,received.statusCode(),headers);
                var read=reader.submit(()->{
                    try {
                        byte[] buffer=new byte[8192];int count;
                        while((count=input.read(buffer))!=-1) {
                            if(body.size()+count>ORIGINAL.length+1024)throw new IOException("preview_response_exceeds_fixture_bound");
                            body.write(buffer,0,count);
                        }
                        return "";
                    } catch(IOException failure) { return failure.getClass().getName()+": "+failure.getMessage(); }
                });
                try { readFailure=read.get(10,TimeUnit.SECONDS); }
                catch(java.util.concurrent.TimeoutException timeout) {
                    input.close();read.cancel(true);readFailure="preview_response_body_timeout";
                }
            } finally {
                reader.shutdown();
                assertTrue(reader.awaitTermination(5,TimeUnit.SECONDS),"响应读取线程必须静止");
            }
            var result=new Response(received.statusCode(),headers,body.toByteArray(),readFailure);
            evidence.response(stage,result);return result;
        }

        /** 成功、完整性拒绝及恢复后均不能遗留请求私有快照。 */
        private void assertStagingEmpty() throws IOException {
            Path staging=root.resolve("staging");if(!Files.exists(staging))return;
            try(var entries=Files.list(staging)){assertEquals(0,entries.count());}
        }

        /** 先停止入口与客户端，再等待所有本 fixture 工作静止。 */
        @Override public void close() { server.stop(0);client.close();workers.close(); }
    }

    /**
     * 已收到的传输事实独立于契约断言。
     * @param status 实际 HTTP 状态，正文读取失败仍保留
     * @param headers 实际响应头
     * @param body 实际已读取的全部或部分原字节
     * @param readFailure 空串表示正文完整；否则保留有界读取失败而不推断状态
     */
    private record Response(int status,Map<String,List<String>> headers,byte[] body,String readFailure) { }

    /** 可选私有证据目录每次独占创建，不覆写历史；不记录票据 URL 或签名密钥。 */
    private static final class Evidence {
        private final ObjectMapper json=new ObjectMapper();
        private final Path directory;

        /** 执行负责人可覆盖证据根；默认使用模块 build/reports，每次用例创建唯一子目录。 */
        private Evidence(String route) throws IOException {
            String configured=System.getProperty("skillforge.preview.evidence");
            if(configured==null||configured.isBlank())configured=System.getenv("SKILLFORGE_PREVIEW_EVIDENCE_DIR");
            Path base=configured==null||configured.isBlank()?Path.of("build/reports/r15-preview-integrity"):Path.of(configured);
            Files.createDirectories(base);directory=Files.createDirectory(base.resolve(route+"-"+UUID.randomUUID()));
        }

        /** 保留原/损坏/恢复 fixture 实际字节及 SHA，清单不随故障变化。 */
        private void fixture(String stage,byte[] bytes) throws IOException {
            Files.write(directory.resolve("fixture-"+stage+".bin"),bytes);
            write("fixture-"+stage+".json",Map.of("stage",stage,"size",bytes.length,"sha256",sha(bytes)));
        }

        /** 正文读取前立即保存服务端已发送的头，避免随后断连造成证据丢失。 */
        private void head(String stage,int status,Map<String,List<String>> headers) throws IOException {
            write(stage+"-head.json",Map.of("stage",stage,"status",status,"headers",headers));
        }

        /** 正文保存为原字节，摘要与读取失败单独描述；普通输出只包含安全的状态事实。 */
        private void response(String stage,Response response) throws IOException {
            Files.write(directory.resolve(stage+"-body.bin"),response.body());
            var facts=facts(stage,response);write(stage+"-response.json",facts);
            System.out.println("R15_HTTP_EVIDENCE "+json.writeValueAsString(facts));
        }

        /** 恢复完成后保存请求顺序，未执行阶段保持明确未观测，不能补写成功。 */
        private void finish(Response normal,Response damaged,Response restored) throws IOException {
            write("receipt.json",Map.of("normal",facts("normal",normal),"damaged",facts("damaged",damaged),
                    "restored",facts("restored",restored),"original_sha256",sha(ORIGINAL),
                    "refusal_contract","404 text/plain; charset=utf-8; fixed preview refusal body"));
        }

        /** 只投影实际观测，缺响应显式表示未观测，不使用契约期望补齐。 */
        private static Map<String,Object> facts(String stage,Response response) {
            return response==null?Map.of("stage",stage,"observed",false)
                    :Map.of("stage",stage,"observed",true,"status",response.status(),"headers",response.headers(),
                            "body_size",response.body().length,"body_sha256",sha(response.body()),"read_failure",response.readFailure());
        }

        /** 写入当前用例独占目录的完整 JSON，不触碰任何公共协调记录。 */
        private void write(String name,Object value) throws IOException {
            Files.write(directory.resolve(name),json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
        }
    }
}
