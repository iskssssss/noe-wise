package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.chat.access.OpenPlatformSessionAccessService;
import com.skillforge.core.foundation.runtimeconfig.RunSettingsService;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipInputStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 真实签名票据、MVC 错误映射与磁盘读取组合；数据库及授权服务用受控合法事实隔离。 */
class ArtifactDownloadIntegrityTest {
    /** 测试产物身份，所有文件都位于本用例的私有根目录。 */
    private static final String ID="11111111-1111-1111-1111-111111111111";
    /** 仅用于隔离测试票据的签名密钥。 */
    private static final String KEY="artifact-integrity-test-signing-key";
    /** 大于响应缓冲区的正文，尾部损坏也必须在响应提交前被发现。 */
    private static final byte[] ORIGINAL=("原始文件正文与完整性校验\n".repeat(4096)).getBytes(StandardCharsets.UTF_8);
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();
    private final ArtifactMapper mapper=mock(ArtifactMapper.class);
    private ArtifactStorage storage;
    private ArtifactRow row;
    private MockMvc mvc;
    private Path source;
    private String previousTenant;

    /** 建立未经故障修改的合法文件清单与真实票据消费者。 */
    @BeforeEach void setUp() throws Exception {
        previousTenant=TenantContext.getTenantId();
        var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(ignored->new SimpleTransactionStatus());
        var projection=mock(StudioArtifactProjection.class);
        storage=new ArtifactStorage(mapper,json,manager,root.toString(),mock(RunSettingsService.class),
                projection,mock(ArtifactPublicInspection.class));
        var files=List.of(metadata("a.txt",new byte[]{1,2,3}),metadata("report.md",ORIGINAL));
        String manifest=json.writeValueAsString(files);
        row=new ArtifactRow(ID,"7","session","run","invocation","file","报告",ORIGINAL.length+3,
                sha(manifest.getBytes(StandardCharsets.UTF_8)),manifest,sha("[]".getBytes(StandardCharsets.UTF_8)),
                Instant.now(),Instant.now().plusSeconds(3600),"ready");
        Path directory=root.resolve("ready/7").resolve(ID);Files.createDirectories(directory);
        Files.write(directory.resolve("a.txt"),new byte[]{1,2,3});
        source=directory.resolve("report.md");Files.write(source,ORIGINAL);
        when(mapper.find("7",ID)).thenReturn(row);
        when(mapper.anyFileReadable("7",ID,ArtifactPublicInspection.POLICY_VERSION)).thenReturn(true);
        when(mapper.publiclyInspected("7",ID,ArtifactPublicInspection.POLICY_VERSION)).thenReturn(true);
        when(mapper.fileReadable(eq("7"),eq(ID),anyString(),eq(ArtifactPublicInspection.POLICY_VERSION))).thenReturn(true);
        var access=new ArtifactAccess(storage,json,mock(AuditService.class),projection,
                mock(OpenPlatformSessionAccessService.class),mock(FoundationAccess.class),KEY,
                "http://preview.test","http://platform.test","http://platform.test",org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));
        var reader=mock(DeliverableReader.class);
        when(reader.file("7","revision","file")).thenReturn(new DeliverableReader.FileGrant(
                "revision","file","session",row,"report.md","报告.md","md","representation",null,"report.md"));
        var tickets=new DeliverableAccess(access,reader,json,KEY,"http://platform.test","http://preview.test",300,3600);
        mvc=MockMvcBuilders.standaloneSetup(new ArtifactController(storage,access,mock(ArtifactVersionService.class)),
                new DeliverableController(reader,tickets,storage,access))
                .setControllerAdvice(new GlobalExceptionHandler(json)).build();
    }

    /** 请求中的临时租户切换必须恢复，避免污染后续测试。 */
    @AfterEach void restoreTenant() {
        if(previousTenant==null)TenantContext.clear();else TenantContext.setTenantId(previousTenant);
    }

    /** 同长度篡改、截断与增长都必须拒绝，文件恢复后原票据仍返回精确原字节。 */
    @ParameterizedTest @EnumSource(Corruption.class)
    void bothTicketConsumersRefuseCorruptBytesAndRecoverWithoutManifestChanges(Corruption corruption) throws Exception {
        String artifact=ticket(false,false,Instant.now().plusSeconds(60));
        String deliverable=ticket(true,false,Instant.now().plusSeconds(60));
        assertDownload(fetch(false,artifact),ORIGINAL);
        assertDownload(fetch(true,deliverable),ORIGINAL);
        Files.write(source,corruption.apply(ORIGINAL));
        TenantContext.setTenantId("previous");
        assertRefused(fetch(false,artifact));
        assertEquals("previous",TenantContext.getTenantId());
        assertRefused(fetch(true,deliverable));
        assertEquals("previous",TenantContext.getTenantId());
        assertStagingEmpty();
        Files.write(source,ORIGINAL);
        assertDownload(fetch(false,artifact),ORIGINAL);
        assertDownload(fetch(true,deliverable),ORIGINAL);
        assertStagingEmpty();
    }

    /** ZIP 的后续文件损坏时，前面的合法条目和 ZIP 头也不能对外发送。 */
    @ParameterizedTest @EnumSource(Corruption.class)
    void zipRejectsEntireArchiveBeforeAnyResponseBytes(Corruption corruption) throws Exception {
        Files.write(source,corruption.apply(ORIGINAL));
        var output=new ByteArrayOutputStream();
        var failure=assertThrows(ApiException.class,()->storage.zip(row,output));
        assertEquals("ARTIFACT_INTEGRITY_MISMATCH",failure.getCode());
        assertEquals(0,output.size());
        assertRefused(fetch(false,ticket(false,true,Instant.now().plusSeconds(60))));
        assertStagingEmpty();
    }

    /** 合法 ZIP 保留清单文件名和正文，且成功后不遗留请求期副本。 */
    @Test void validZipPreservesEveryEntry() throws Exception {
        var response=fetch(false,ticket(false,true,Instant.now().plusSeconds(60)));
        assertEquals(200,response.getStatus());assertSecurityHeaders(response);
        try(var zip=new ZipInputStream(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertEquals("a.txt",zip.getNextEntry().getName());assertArrayEquals(new byte[]{1,2,3},zip.readAllBytes());
            assertEquals("report.md",zip.getNextEntry().getName());assertArrayEquals(ORIGINAL,zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
        assertStagingEmpty();
    }

    /** 文件消费开始时替换源文件，已核验响应仍只能来自原快照，不能二次打开源文件。 */
    @Test void sourceMutationAtFirstResponseWriteCannotChangeVerifiedBytes() throws Exception {
        var received=new ByteArrayOutputStream();
        storage.file(row,"report.md",new OutputStream() {
            private boolean changed;
            @Override public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
            @Override public void write(byte[] bytes,int offset,int length) throws IOException {
                if(!changed){changed=true;Files.write(source,Corruption.SAME_SIZE.apply(ORIGINAL));}
                received.write(bytes,offset,length);
            }
        });
        assertArrayEquals(ORIGINAL,received.toByteArray());assertStagingEmpty();
    }

    /** 消费方连接失败也必须释放句柄与副本，原始 IOException 不被吞掉。 */
    @ParameterizedTest @ValueSource(booleans={false,true})
    void disconnectedConsumerReclaimsSnapshot(boolean zip) throws Exception {
        var disconnected=new IOException("consumer disconnected");
        var output=new OutputStream() {
            @Override public void write(int value) throws IOException { throw disconnected; }
            @Override public void write(byte[] bytes,int offset,int length) throws IOException { throw disconnected; }
        };
        assertSame(disconnected,assertThrows(IOException.class,()->{
            if(zip)storage.zip(row,output);else storage.file(row,"report.md",output);
        }));
        assertStagingEmpty();
    }

    /** 有效签名不能绕过到期或当前可读性拒绝；拒绝时无需创建正文快照。 */
    @Test void expiredTicketsAndRevokedFileKeepExistingRefusal() throws Exception {
        assertEquals(410,fetch(false,ticket(false,false,Instant.now().minusSeconds(1))).getStatus());
        assertEquals(410,fetch(true,ticket(true,false,Instant.now().minusSeconds(1))).getStatus());
        when(mapper.fileReadable("7",ID,"report.md",ArtifactPublicInspection.POLICY_VERSION)).thenReturn(false);
        assertEquals(410,fetch(false,ticket(false,false,Instant.now().plusSeconds(60))).getStatus());
        assertEquals(410,fetch(true,ticket(true,false,Instant.now().plusSeconds(60))).getStatus());
        assertStagingEmpty();
    }

    /** 在真实签名协议中固定精确产物或成果文件，并控制票据到期时间。 */
    private String ticket(boolean deliverable,boolean zip,Instant expires) throws Exception {
        Object claim=deliverable
                ?new DeliverableAccess.Claim("deliverable.v1","7","9",null,"revision","file","representation","file",expires.getEpochSecond(),"nonce")
                :new ArtifactAccess.Claim("7","9",null,ID,zip?"zip":"file",zip?null:"report.md",expires.getEpochSecond(),"nonce");
        String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claim));
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.US_ASCII)));
    }

    /** 使用生产 MVC 路由和异常映射读取票据正文。 */
    private MockHttpServletResponse fetch(boolean deliverable,String ticket) throws Exception {
        return mvc.perform(get("/api/v1/chat/"+(deliverable?"deliverable":"artifact")+"-content/detail")
                .param("ticket",ticket)).andReturn().getResponse();
    }

    /** 拒绝必须是精确领域 JSON，不能是任意 500、带文件前缀的 JSON 或附件响应。 */
    private void assertRefused(MockHttpServletResponse response) throws Exception {
        assertEquals(500,response.getStatus());
        var mediaType=MediaType.parseMediaType(response.getContentType());
        assertEquals("application",mediaType.getType());assertEquals("json",mediaType.getSubtype());
        var body=json.readTree(response.getContentAsByteArray());
        assertEquals("ARTIFACT_INTEGRITY_MISMATCH",body.path("code").asText());
        assertFalse(body.path("retryable").asBoolean(true));assertTrue(body.path("data").isNull());
        assertNull(response.getHeader("Content-Disposition"));
        assertTrue(response.getContentAsByteArray().length<1024);
    }

    /** 正常下载的字节和既有安全头保持一致。 */
    private void assertDownload(MockHttpServletResponse response,byte[] expected) {
        assertEquals(200,response.getStatus());assertArrayEquals(expected,response.getContentAsByteArray());
        assertSecurityHeaders(response);
    }

    /** 下载不能丢失附件、安全嗅探和缓存策略。 */
    private void assertSecurityHeaders(MockHttpServletResponse response) {
        assertTrue(response.getHeader("Content-Disposition").startsWith("attachment;"));
        assertEquals("no-store",response.getHeader("Cache-Control"));
        assertEquals("no-referrer",response.getHeader("Referrer-Policy"));
        assertEquals("nosniff",response.getHeader("X-Content-Type-Options"));
    }

    /** 测试拥有的 staging 在成功和所有失败出口都应为空。 */
    private void assertStagingEmpty() throws IOException {
        Path staging=root.resolve("staging");if(!Files.exists(staging))return;
        try(var entries=Files.list(staging)){assertEquals(0,entries.count());}
    }

    /** 从合法原字节建立期望清单，负向测试只改文件而不改清单。 */
    private static ArtifactMetadata.File metadata(String path,byte[] bytes) {
        return new ArtifactMetadata.File(path,bytes.length,sha(bytes));
    }

    /** 计算夹具的发布摘要。 */
    private static String sha(byte[] bytes) { return ArtifactStorage.hex(ArtifactStorage.hash().digest(bytes)); }

    /** 每种故障只改变持久正文，不改变原清单、权限或票据。 */
    private enum Corruption {
        /** 尾字节翻转，保持长度以证明实际摘要检查。 */
        SAME_SIZE,
        /** 删除尾字节。 */
        TRUNCATED,
        /** 增加尾字节。 */
        APPENDED;

        /** 返回单条件损坏副本，原输入保持不变。 */
        byte[] apply(byte[] original) {
            byte[] changed=Arrays.copyOf(original,original.length+(this==TRUNCATED?-1:this==APPENDED?1:0));
            if(this==SAME_SIZE)changed[changed.length-1]^=1;
            return changed;
        }
    }
}
