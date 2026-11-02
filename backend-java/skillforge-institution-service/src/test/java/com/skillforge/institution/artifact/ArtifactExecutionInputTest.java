package com.skillforge.institution.artifact;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.connector.CodeExecutionArguments.ArtifactInput;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtifactExecutionInputTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final ArtifactMapper mapper=mock(ArtifactMapper.class);
    private final StudioArtifactProjection projection=mock(StudioArtifactProjection.class);
    private final String id="12345678-1234-1234-1234-123456789abc";

    @Test void streamsOnlyVerifiedSameSessionFileAndRemovesTransferArchive()throws Exception {
        var storage=storage();var row=row("1","session",Instant.now().plusSeconds(60));
        when(mapper.find("1",id)).thenReturn(row);
        Path file=root.resolve("ready/1/"+id+"/records.json");Files.createDirectories(file.getParent());Files.writeString(file,"[]");
        Path archived;
        try(var archive=storage.executionArchive(context(),List.of(new ArtifactInput(id,"records.json")),request(),List.of())) {
            archived=archive.path();
            assertEquals(List.of(new ArtifactProvenance(id,"records.json",2,
                    ArtifactStorage.hex(ArtifactStorage.hash().digest("[]".getBytes())))),archive.inputs());
            try(var zip=new ZipInputStream(Files.newInputStream(archived))) {
                assertEquals("request.json",zip.getNextEntry().getName());
                var header=json.readTree(zip.readAllBytes());assertEquals(2,header.path("artifacts").get(0).path("size").asLong());
                assertEquals("artifacts/"+id+"/records.json",zip.getNextEntry().getName());
                assertEquals("[]",new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));assertNull(zip.getNextEntry());
            }
        }
        assertFalse(Files.exists(archived));
        assertNull(TenantContext.getTenantId());
        Files.writeString(file,"tampered");
        assertThrows(java.io.IOException.class,()->storage.executionArchive(context(),List.of(new ArtifactInput(id,"records.json")),request(),List.of()));
        try(var staging=Files.list(root.resolve("staging"))){assertEquals(0,staging.count());}
    }

    @Test void rejectsCrossTenantCrossSessionExpiryAndManifestTamperingBeforeReading()throws Exception {
        var storage=storage();
        for(var invalid:List.of(row("2","session",Instant.now().plusSeconds(60)),row("1","other",Instant.now().plusSeconds(60)),
                row("1","session",Instant.now().minusSeconds(1)))) {
            when(mapper.find("1",id)).thenReturn(invalid);
            assertThrows(RuntimeException.class,()->storage.executionArchive(context(),List.of(new ArtifactInput(id,"records.json")),request(),List.of()));
        }
        var valid=row("1","session",Instant.now().plusSeconds(60));
        when(mapper.find("1",id)).thenReturn(new ArtifactRow(id,"1","session","run","invocation","file","records.json",2,
                "0".repeat(64),valid.manifest(),valid.inputsSha256(),valid.createdAt(),valid.expiresAt(),"ready"));
        assertThrows(IllegalStateException.class,()->storage.executionArchive(context(),List.of(new ArtifactInput(id,"records.json")),request(),List.of()));
    }

    @Test void jsonbFormattingPreservesDigestButChangedManifestContentsAreRejected()throws Exception {
        var storage=storage();var valid=row("1","session",Instant.now().plusSeconds(60));
        String contentDigest=ArtifactStorage.hex(ArtifactStorage.hash().digest("[]".getBytes()));
        // 模拟 PostgreSQL manifest::text：字段重排并插入空白，语义仍与发布时完全相同。
        String jsonb="[ { \"size\": 2, \"sha256\": \""+contentDigest+"\", \"path\": \"records.json\" } ]";
        assertNotEquals(valid.manifest(),jsonb);
        var row=new ArtifactRow(id,valid.tenantId(),valid.sessionId(),valid.runId(),valid.invocationId(),
                valid.type(),valid.name(),valid.size(),valid.sha256(),jsonb,valid.inputsSha256(),valid.createdAt(),valid.expiresAt(),valid.status());
        when(mapper.find("1",id)).thenReturn(row);
        Path file=root.resolve("ready/1/"+id+"/records.json");Files.createDirectories(file.getParent());Files.writeString(file,"[]");
        try(var archive=storage.executionArchive(context(),List.of(new ArtifactInput(id,"records.json")),request(),List.of())) {
            assertTrue(Files.size(archive.path())>0);
        }
        for(String changed:List.of(jsonb.replace("\"size\": 2","\"size\": 3"),
                jsonb.replace(contentDigest,"0".repeat(64)),jsonb.replace("records.json","other.json"))) {
            when(mapper.find("1",id)).thenReturn(new ArtifactRow(id,row.tenantId(),row.sessionId(),row.runId(),row.invocationId(),
                    row.type(),row.name(),row.size(),row.sha256(),changed,row.inputsSha256(),row.createdAt(),row.expiresAt(),row.status()));
            assertEquals("artifact_manifest_digest_mismatch",assertThrows(IllegalStateException.class,
                    ()->storage.executionArchive(context(),List.of(new ArtifactInput(id,"records.json")),request(),List.of())).getMessage());
        }
    }

    @Test void parentCanReadOnlyCurrentlyAuthorizedChildFiles()throws Exception {
        var storage=storage();var child=row("1","delegation:child",Instant.now().plusSeconds(60));
        when(mapper.find("1",id)).thenReturn(child);
        when(projection.route("1","delegation:child","old-run")).thenReturn(Optional.of(new StudioArtifactProjection.Route("session","AGENT","7","9","parent-run")));
        Path file=root.resolve("ready/1/"+id+"/records.json");Files.createDirectories(file.getParent());Files.writeString(file,"[]");
        try(var archive=storage.executionArchive(context(),List.of(new ArtifactInput(id,"records.json")),request(),List.of())) {
            assertEquals(1,archive.inputs().size());
        }
        when(projection.route("1","delegation:child","old-run")).thenReturn(Optional.empty());
        assertThrows(RuntimeException.class,()->storage.executionArchive(context(),List.of(new ArtifactInput(id,"records.json")),request(),List.of()));
    }
    @Test void actualArchiveKeepsCamelCaseUnderTheInstitutionNamingPolicy() throws Exception {
        json.setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
        var expected = request();
        try (var archive = storage().executionArchive(context(), List.of(), expected, List.of());
             var zip = new java.util.zip.ZipInputStream(Files.newInputStream(archive.path()))) {
            assertEquals(com.skillforge.execution.ExecutionLimits.REQUEST_ENTRY, zip.getNextEntry().getName());
            byte[] header = zip.readAllBytes();
            var sandboxJson = new ObjectMapper().findAndRegisterModules();
            var tree = sandboxJson.readTree(header).path("execution");
            assertTrue(tree.has("taskId"));
            assertTrue(tree.has("deadlineAt"));
            assertTrue(tree.has("programTools"));
            org.junit.jupiter.api.Assertions.assertFalse(tree.has("task_id"));
            assertEquals(expected, sandboxJson.readValue(header, com.skillforge.execution.ExecutionManifest.class).execution());
        }
    }

    private com.skillforge.execution.ExecuteRequest request() {
        return new com.skillforge.execution.ExecuteRequest("task",com.skillforge.execution.Language.PYTHON,"pass",List.of(),
                List.of(),null,false,Instant.now().plusSeconds(600),new com.skillforge.execution.ExecutionPolicy(1,300,15,24,60,
                4194304,2048,4,"1g",new java.math.BigDecimal("2"),256),List.of(),com.skillforge.execution.ExecutionProfile.STANDARD,null,null);
    }
    private ArtifactStorage storage() {
        when(mapper.anyFileReadable(anyString(),anyString(),anyString())).thenAnswer(call->{
            ArtifactRow stored=mapper.find(call.getArgument(0),call.getArgument(1));
            return stored!=null&&stored.tenantId().equals(call.getArgument(0))&&Instant.now().isBefore(stored.expiresAt());
        });
        when(mapper.fileReadable(anyString(),anyString(),anyString(),anyString())).thenAnswer(call->mapper.anyFileReadable(call.getArgument(0),call.getArgument(1),call.getArgument(3)));
        var transactions=mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new ArtifactStorage(mapper,json,transactions,root.toString(),com.skillforge.institution.TestOperationalSettings.runs(true),projection,mock(ArtifactPublicInspection.class));
    }
    private ArtifactRow row(String tenant,String session,Instant expiry)throws Exception {
        String manifest=json.writeValueAsString(List.of(new ArtifactMetadata.File("records.json",2,ArtifactStorage.hex(ArtifactStorage.hash().digest("[]".getBytes())))));
        return new ArtifactRow(id,tenant,session,"old-run","old-invocation","file","records.json",2,
                ArtifactStorage.hex(ArtifactStorage.hash().digest(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8))),manifest,
                "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",Instant.now(),expiry,"ready");
    }
    private PlatformToolExecutionContext context() {
        var tenant=new TenantId("1");var input=new InputId("input");
        var owner=SessionOwner.user(new SessionRef(tenant,"session"),new AgentId("7"),new EndUserId(9));
        var identity=new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant,"run"), new TraceEnvelope("trace","span",input,Optional.empty()), new ResultRef("result"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var verified = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of(), 1, new ResultRef("verification"));
        return new PlatformToolExecutionContext(
                new PortCallContext(identity, verified, new InvocationControl(Instant.now().plusSeconds(120), () -> false)),
                "invocation", "attempt");
    }
}
