package com.skillforge.institution.attachment;

import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.TenantContext;
import com.skillforge.runtime.api.TypedPayload;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.institution.runtime.AgentRuntimeProgramTools;
import com.skillforge.institution.runtime.ProgramResourceOriginFixture;
import org.springframework.beans.factory.ObjectProvider;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class SessionAttachmentServiceTest {
    private static final String ID = "3d9b2652-1243-4b93-a4c1-f432d5c88311";
    private final SessionAttachmentMapper mapper = mock(SessionAttachmentMapper.class);
    private final ArtifactAccess access = mock(ArtifactAccess.class);
    private final SessionAttachmentBodyStore bodies = mock(SessionAttachmentBodyStore.class);
    private final AgentRunBindings bindings = mock(AgentRunBindings.class);
    private final AgentRunBindings.Binding binding = mock(AgentRunBindings.Binding.class);
    private final ExecutionStore facts = mock(ExecutionStore.class);
    private final ObjectProvider<AgentRuntimeProgramTools> programs = ProgramResourceOriginFixture.ordinary(facts);
    private static final AgentExecutionCodec CODEC = new AgentExecutionCodec(new CodecLimits(1_000_000,100_000,64));
    private SessionAttachmentService service;

    @BeforeEach void setup() {
        service = new SessionAttachmentService(mapper, access, bindings, bodies, facts, CODEC,programs);
        when(access.tenant()).thenReturn("1");
        var run = context("1", "session", Instant.now().plusSeconds(60), false).run();
        when(bindings.find(run.runRef())).thenReturn(Optional.of(binding));
        when(binding.owner()).thenReturn(run.owner());
        when(binding.actor()).thenReturn(run.agentId());
        doReturn(Optional.of(storedRun(run))).when(facts).load(run.runRef());
        var input=attachmentInput(run);
        when(facts.originalInput(run.runRef())).thenReturn(Optional.of(input));
    }

    @AfterEach void clearTenant() {
        TenantContext.clear();
    }

    @Test void rawUploadAndNativeUploadShareOriginalContentValidationAndStableIdentity() throws Exception {
        var stored=new AtomicReference<SessionAttachmentRow>();
        when(mapper.insert(any())).thenAnswer(call->stored.compareAndSet(null,call.getArgument(0))?1:0);
        when(mapper.find("1","session",ID)).thenAnswer(call->stored.get());
        byte[] bytes="原始文件内容".getBytes(StandardCharsets.UTF_8);
        var original=service.upload("session",ID,"input.txt",bytes);
        var nativeReplay=service.upload("session",ID,new MockMultipartFile("file","input.txt","text/plain",bytes));
        assertEquals(original,nativeReplay);assertArrayEquals(bytes,stored.get().content());
        assertThrows(ApiException.class,()->service.upload("session",ID,"input.txt","changed".getBytes(StandardCharsets.UTF_8)));
        assertArrayEquals(bytes,stored.get().content());
    }

    @Test void rawUploadRejectsMalformedMaterialAndDeniedOwnerBeforeAnyRegistration() throws Exception {
        assertThrows(ApiException.class,()->service.upload("session",ID,"input.txt",new byte[]{(byte)0xff}));
        assertThrows(ApiException.class,()->service.upload("session",ID,"../input.txt",new byte[]{1}));
        doThrow(new SecurityException("session denied")).when(access).authorizeWritableCurrent("1","foreign");
        assertThrows(SecurityException.class,()->service.upload("foreign",ID,"input.txt",new byte[]{1}));
        verify(mapper,never()).insert(any());
    }

    @Test void uploadIsIdempotentAndRejectsReusingAnIdForDifferentContent() throws Exception {
        var stored = new AtomicReference<SessionAttachmentRow>();
        when(mapper.insert(any())).thenAnswer(call ->
                stored.compareAndSet(null, call.getArgument(0)) ? 1 : 0);
        when(mapper.find("1", "session", ID)).thenAnswer(call -> stored.get());
        var file = file("notes.txt", "first");

        var first = service.upload("session", ID, file);
        var replay = service.upload("session", ID, file("notes.txt", "first"));

        assertEquals(first, replay);
        assertEquals("attachments/" + ID + "/input.txt", first.inputPath());
        assertEquals(sha("first".getBytes(StandardCharsets.UTF_8)), first.sha256());
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), stored.get().content());
        assertThrows(ApiException.class,
                () -> service.upload("session", ID, file("notes.txt", "different")));
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8), stored.get().content());
        verify(access, times(3)).authorizeWritableCurrent("1", "session");
    }

    @Test void uploadRejectsUnsupportedExtensionMalformedUtf8AndOversizeBeforePersistence() {
        assertEquals("SESSION_ATTACHMENT_UPLOAD_REJECTED", assertThrows(ApiException.class,
                () -> service.upload("session", ID, file("payload.exe", "text"))).getCode());
        assertEquals("SESSION_ATTACHMENT_UPLOAD_REJECTED", assertThrows(ApiException.class, () -> service.upload("session", ID,
                new MockMultipartFile("file", "broken.txt", "text/plain", new byte[]{(byte) 0xc3, 0x28}))).getCode());
        var oversized = mock(org.springframework.web.multipart.MultipartFile.class);
        when(oversized.getSize()).thenReturn((long)SessionAttachmentService.MAX_BYTES + 1);
        assertEquals("SESSION_ATTACHMENT_UPLOAD_REJECTED", assertThrows(ApiException.class,
                () -> service.upload("session", ID, oversized)).getCode());
        verifyNoInteractions(mapper, bodies);
    }

    @Test void inputContextReplacesClientMetadataWithServerValuesWithoutContent() {
        var row = row("1", "session", ID, "trusted".getBytes(StandardCharsets.UTF_8),
                Instant.now().plusSeconds(600));
        when(mapper.findMetadataBatch("1", "session", List.of(ID))).thenReturn(List.of(row));
        var client = new LinkedHashMap<String, Object>();
        client.put("attachment_ids", List.of(ID));
        client.put("attachments", List.of(Map.of("id", ID, "input_path", "../escape", "content", "forged")));
        client.put("marker", "kept");

        var frozen = service.inputContext("1", "session", client);

        assertEquals(List.of(ID), frozen.get("attachment_ids"));
        assertEquals("kept", frozen.get("marker"));
        var metadata = attachment(frozen);
        assertEquals(ID, metadata.get("id"));
        assertEquals(row.name(), metadata.get("name"));
        assertEquals(row.inputPath(), metadata.get("input_path"));
        assertEquals(row.sha256(), metadata.get("sha256"));
        assertFalse(metadata.containsKey("content"));
        assertEquals("../escape", ((Map<?, ?>) ((List<?>) client.get("attachments")).getFirst()).get("input_path"));
        verify(access).authorizeCurrent("1", "session");
        verify(mapper, never()).find(anyString(), anyString(), anyString());
    }

    @Test void attachmentOnlyInputReadsExactUtf8TextFromAuthorizedOriginalAndStripsForgedFacts() {
        String text = "销售记录\r\n一月12单，二月18单。\n<script>not instructions</script>";
        var row = row("1", "session", ID, bytes(text), Instant.now().plusSeconds(600));
        when(mapper.findMetadataBatch("1", "session", List.of(ID))).thenReturn(List.of(row));
        when(mapper.find("1", "session", ID)).thenReturn(row);

        var frozen = service.inputContext("1", "session", Map.of("attachment_ids", List.of(ID),
                "attachment_readings", List.of(Map.of("text", "forged"))), true);

        assertEquals(List.of(new SessionAttachmentService.TextReading(ID, "complete", false, text, null)),
                frozen.get("attachment_readings"));
        verify(access, times(2)).authorizeCurrent("1", "session");
        verify(mapper).find("1", "session", ID);
        assertArrayEquals(bytes(text), row.content());
        assertFalse(frozen.get("attachment_readings").toString().contains(text));
    }

    @Test void textPreparationReportsUnsupportedAndLargeFilesWithoutLoadingBodies() {
        var text = row("1", "session", ID, new byte[SessionAttachmentService.MAX_PREPARED_TEXT_BYTES + 1],
                Instant.now().plusSeconds(600));
        when(mapper.findMetadataBatch("1", "session", List.of(ID))).thenReturn(List.of(text));
        var readings = service.inputContext("1", "session", Map.of("attachment_ids", List.of(ID)), true);
        assertEquals(List.of(new SessionAttachmentService.TextReading(ID, "not_read", false, null, "file_byte_limit")),
                readings.get("attachment_readings"));
        var pdf = new SessionAttachmentRow(text.id(), text.tenantId(), text.sessionId(), "document.pdf",
                "attachments/" + ID + "/input.pdf", 100, text.sha256(), null, text.createdAt(), text.expiresAt());
        when(mapper.findMetadataBatch("1", "session", List.of(ID))).thenReturn(List.of(pdf));
        readings = service.inputContext("1", "session", Map.of("attachment_ids", List.of(ID)), true);
        assertEquals(List.of(new SessionAttachmentService.TextReading(ID, "not_read", false, null, "requires_document_parser")),
                readings.get("attachment_readings"));
        verify(mapper, never()).find(anyString(), anyString(), anyString());
    }

    @Test void textPreparationStopsAtAggregateByteBudgetAndKeepsFormalAttachmentMetadata() {
        var ids = List.of(ID, "3d9b2652-1243-4b93-a4c1-f432d5c88312", "3d9b2652-1243-4b93-a4c1-f432d5c88313");
        var rows = ids.stream().map(id -> row("1", "session", id, bytes("x".repeat(24 * 1024)),
                Instant.now().plusSeconds(600))).toList();
        when(mapper.findMetadataBatch("1", "session", ids)).thenReturn(rows);
        for (int i = 0; i < 2; i++) when(mapper.find("1", "session", ids.get(i))).thenReturn(rows.get(i));

        var frozen = service.inputContext("1", "session", Map.of("attachment_ids", ids), true);

        var readings = (List<?>) frozen.get("attachment_readings");
        assertEquals("complete", ((SessionAttachmentService.TextReading) readings.get(0)).status());
        assertEquals("complete", ((SessionAttachmentService.TextReading) readings.get(1)).status());
        assertEquals(new SessionAttachmentService.TextReading(ids.get(2), "not_read", false, null, "message_byte_limit"),
                readings.get(2));
        assertEquals(ids, frozen.get("attachment_ids"));
        assertEquals(3, ((List<?>) frozen.get("attachments")).size());
        verify(mapper, never()).find("1", "session", ids.get(2));
    }

    @Test void preparedTextRejectsRevokedPermissionChangedDigestAndForeignContent() {
        var row = row("1", "session", ID, bytes("protected"), Instant.now().plusSeconds(600));
        when(mapper.findMetadataBatch("1", "session", List.of(ID))).thenReturn(List.of(row));
        doNothing().doThrow(new SecurityException("revoked")).when(access).authorizeCurrent("1", "session");
        assertThrows(SecurityException.class, () -> service.inputContext("1", "session", Map.of("attachment_ids", List.of(ID)), true));
        verify(mapper, never()).find(anyString(), anyString(), anyString());
        reset(access);
        var changed = new SessionAttachmentRow(row.id(), row.tenantId(), row.sessionId(), row.name(), row.inputPath(),
                row.size(), row.sha256(), bytes("different"), row.createdAt(), row.expiresAt());
        when(mapper.find("1", "session", ID)).thenReturn(changed);
        assertEquals("attachment_digest_mismatch", assertThrows(IllegalStateException.class,
                () -> service.inputContext("1", "session", Map.of("attachment_ids", List.of(ID)), true)).getMessage());
        when(mapper.find("1", "session", ID)).thenReturn(row("2", "session", ID, bytes("protected"), row.expiresAt()));
        assertThrows(ApiException.class, () -> service.inputContext("1", "session", Map.of("attachment_ids", List.of(ID)), true));
    }

    @Test void resolveUsesTrustedTenantAndSessionAndRestoresTenantContext() throws Exception {
        var row = row("1", "session", ID, "trusted".getBytes(StandardCharsets.UTF_8),
                Instant.now().plusSeconds(600));
        when(mapper.findMetadataBatch("1", "session", List.of(ID))).thenAnswer(call -> {
            assertEquals("1", TenantContext.getTenantId());
            return List.of(row);
        });
        when(mapper.find("1", "session", ID)).thenAnswer(call -> {
            assertEquals("1", TenantContext.getTenantId());
            return row;
        });
        TenantContext.setTenantId("outer");

        var resolved = service.resolve(context("1", "session", Instant.now().plusSeconds(60), false),
                List.of(ID), Map.of("inline.txt", "inline"));

        assertEquals(2, resolved.files().size());
        for (var file : resolved.files()) try (var input = file.open()) {
            assertEquals(file.file().path().endsWith("inline.txt") ? "inline" : "trusted",
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(List.of(ID), resolved.attachments().stream()
                .map(SessionAttachmentService.Metadata::id).toList());
        assertEquals("outer", TenantContext.getTenantId());
        verify(mapper).findMetadataBatch("1", "session", List.of(ID));
        verify(mapper).find("1", "session", ID);
    }

    @Test void backgroundWorkerUsesBoundRunPermissionWithoutHttpLoginAndRestoresItsTenant() throws Exception {
        var row = row("1", "session", ID, bytes("worker content"), Instant.now().plusSeconds(600));
        when(mapper.findMetadataBatch("1", "session", List.of(ID))).thenAnswer(call -> {
            assertEquals("1", TenantContext.getTenantId());
            return List.of(row);
        });
        when(mapper.find("1", "session", ID)).thenAnswer(call -> {
            assertEquals("1", TenantContext.getTenantId());
            return row;
        });
        doThrow(new SecurityException("http_login_missing")).when(access).authorizeCurrent(anyString(), anyString());
        TenantContext.setTenantId("caller");
        var trusted = context("1", "session", Instant.now().plusSeconds(60), false);
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                assertNull(TenantContext.getTenantId());
                TenantContext.setTenantId("worker");
                try {
                    var resolved = service.resolve(trusted, List.of(ID), Map.of());
                    assertEquals("worker", TenantContext.getTenantId());
                    try (var input = resolved.files().getFirst().open()) {
                        assertArrayEquals(row.content(), input.readAllBytes());
                    }
                    assertEquals("worker", TenantContext.getTenantId());
                    return null;
                } finally { TenantContext.clear(); }
            }).get(10, TimeUnit.SECONDS);
        }
        assertEquals("caller", TenantContext.getTenantId());
        verify(bindings, times(2)).find(trusted.run().runRef());
        verify(bindings, times(2)).authorizeRead(binding, "7");
        verifyNoInteractions(access);
    }

    @Test void missingOrForeignRunBindingRejectsBeforeAttachmentMetadataOrBodyRead() {
        var trusted = context("1", "session", Instant.now().plusSeconds(60), false);
        when(bindings.find(trusted.run().runRef())).thenReturn(Optional.empty());
        assertEquals("attachment_run_binding_missing", assertThrows(SecurityException.class,
                () -> service.resolve(trusted, List.of(ID), Map.of())).getMessage());
        when(bindings.find(trusted.run().runRef())).thenReturn(Optional.of(binding));
        var foreignOwners = List.of(
                SessionOwner.user(new SessionRef(new TenantId("2"), "session"), new AgentId("7"), new EndUserId(9)),
                SessionOwner.user(new SessionRef(new TenantId("1"), "other"), new AgentId("7"), new EndUserId(9)),
                SessionOwner.user(new SessionRef(new TenantId("1"), "session"), new AgentId("7"), new EndUserId(10)));
        TenantContext.setTenantId("outer");
        for (var owner : foreignOwners) {
            when(binding.owner()).thenReturn(owner);
            assertEquals("attachment_run_owner_mismatch", assertThrows(SecurityException.class,
                    () -> service.resolve(trusted, List.of(ID), Map.of())).getMessage());
            assertEquals("outer", TenantContext.getTenantId());
        }
        when(binding.owner()).thenReturn(trusted.run().owner());
        when(binding.actor()).thenReturn(new AgentId("8"));
        assertEquals("attachment_run_owner_mismatch", assertThrows(SecurityException.class,
                () -> service.resolve(trusted, List.of(ID), Map.of())).getMessage());
        assertEquals("outer", TenantContext.getTenantId());
        verify(bindings, never()).authorizeRead(any(), anyString());
        verifyNoInteractions(mapper, access);
    }

    @Test void permissionRevokedAfterResolutionRejectsBeforeLoadingAttachmentBody() {
        var row = row("1", "session", ID, bytes("protected"), Instant.now().plusSeconds(600));
        when(mapper.findMetadataBatch("1", "session", List.of(ID))).thenReturn(List.of(row));
        doNothing().doThrow(new SecurityException("requester_revoked")).when(bindings).authorizeRead(binding, "7");
        TenantContext.setTenantId("outer");
        var resolved = service.resolve(context("1", "session", Instant.now().plusSeconds(60), false),
                List.of(ID), Map.of());
        assertEquals("requester_revoked", assertThrows(SecurityException.class,
                () -> resolved.files().getFirst().open()).getMessage());
        assertEquals("outer", TenantContext.getTenantId());
        verify(bindings, times(2)).authorizeRead(binding, "7");
        verify(mapper, never()).find(anyString(), anyString(), anyString());
        verifyNoInteractions(access);
    }

    @Test void resolveRejectsRowsOutsideTrustedTenantSessionOrRetention() {
        when(mapper.findMetadataBatch("1", "session", List.of(ID))).thenReturn(
                List.of(row("2", "session", ID, bytes("tenant"), Instant.now().plusSeconds(600))),
                List.of(row("1", "other", ID, bytes("session"), Instant.now().plusSeconds(600))),
                List.of(row("1", "session", ID, bytes("expired"), Instant.now().minusSeconds(1))));
        TenantContext.setTenantId("outer");
        var context = context("1", "session", Instant.now().plusSeconds(60), false);

        assertThrows(ApiException.class, () -> service.resolve(context, List.of(ID), Map.of()));
        assertEquals("outer", TenantContext.getTenantId());
        assertThrows(ApiException.class, () -> service.resolve(context, List.of(ID), Map.of()));
        assertEquals("outer", TenantContext.getTenantId());
        assertThrows(ApiException.class, () -> service.resolve(context, List.of(ID), Map.of()));
        assertEquals("outer", TenantContext.getTenantId());
    }

    @Test void resolveRejectsDigestMismatchAndInputPathCollisionAndRestoresContext() {
        var invalidDigest = row("1", "session", ID, bytes("stored"), Instant.now().plusSeconds(600));
        invalidDigest = new SessionAttachmentRow(invalidDigest.id(), invalidDigest.tenantId(),
                invalidDigest.sessionId(), invalidDigest.name(), invalidDigest.inputPath(), invalidDigest.size(),
                "0".repeat(64), invalidDigest.content(), invalidDigest.createdAt(), invalidDigest.expiresAt());
        var valid = row("1", "session", ID, bytes("stored"), Instant.now().plusSeconds(600));
        when(mapper.findMetadataBatch("1", "session", List.of(ID)))
                .thenReturn(List.of(invalidDigest), List.of(valid));
        when(mapper.find("1", "session", ID)).thenReturn(invalidDigest);
        TenantContext.setTenantId("outer");
        var context = context("1", "session", Instant.now().plusSeconds(60), false);

        assertThrows(IllegalStateException.class,
                () -> service.resolve(context, List.of(ID), Map.of()).files().getFirst().open());
        assertEquals("outer", TenantContext.getTenantId());
        assertThrows(IllegalArgumentException.class,
                () -> service.resolve(context, List.of(ID), Map.of(valid.inputPath(), "inline")));
        assertEquals("outer", TenantContext.getTenantId());
    }

    @Test void acceptedAttachmentManifestRejectsTotalAbove200MiBWithoutLoadingBodies() {
        var ids = java.util.stream.IntStream.range(0, 5).mapToObj(index -> java.util.UUID.randomUUID().toString()).toList();
        var rows = ids.stream().map(id -> new SessionAttachmentRow(id, "1", "session", "large.txt",
                    "attachments/" + id + "/input.txt", SessionAttachmentService.MAX_BYTES, "0".repeat(64), null,
                    Instant.now(), Instant.now().plusSeconds(600))).toList();
        when(mapper.findMetadataBatch("1", "session", ids)).thenReturn(rows);
        assertThrows(IllegalArgumentException.class,
                () -> service.inputContext("1", "session", Map.of("attachment_ids", ids)));
        verify(mapper, never()).find(anyString(), anyString(), anyString());
    }

    @Test void resolveHonorsCancellationAndDeadlineAndRestoresContext() {
        TenantContext.setTenantId("outer");

        assertThrows(IllegalStateException.class, () -> service.resolve(
                context("1", "session", Instant.now().plusSeconds(60), true), List.of(ID), Map.of()));
        assertEquals("outer", TenantContext.getTenantId());
        assertThrows(IllegalStateException.class, () -> service.resolve(
                context("1", "session", Instant.now().minusSeconds(1), false), List.of(ID), Map.of()));
        assertEquals("outer", TenantContext.getTenantId());
        verifyNoInteractions(mapper);
    }

    @Test void binaryPdfUploadAndMountKeepOriginalBytesDigestAndTrustedAuthorization() throws Exception {
        byte[] bytes = SessionAttachmentContentValidatorTest.pdf(false, 1);
        var stored = new AtomicReference<SessionAttachmentRow>();
        when(mapper.insert(any())).thenAnswer(call -> stored.compareAndSet(null, call.getArgument(0)) ? 1 : 0);
        when(mapper.find("1", "session", ID)).thenAnswer(call -> stored.get());
        when(mapper.findMetadataBatch("1", "session", List.of(ID))).thenAnswer(call -> List.of(stored.get()));
        var metadata = service.upload("session", ID,
                new MockMultipartFile("file", "report.pdf", "application/pdf", bytes));
        assertEquals("attachments/" + ID + "/input.pdf", metadata.inputPath());
        assertEquals(sha(bytes), metadata.sha256());
        TenantContext.setTenantId("outer");
        var resolved = service.resolve(context("1", "session", Instant.now().plusSeconds(60), false), List.of(ID), Map.of());
        assertEquals("data/" + metadata.inputPath(), resolved.files().getFirst().file().path());
        try (var input = resolved.files().getFirst().open()) { assertArrayEquals(bytes, input.readAllBytes()); }
        assertEquals("outer", TenantContext.getTenantId());
        verify(access).authorizeWritableCurrent("1", "session");
        verify(bindings, times(2)).authorizeRead(binding, "7");
    }

    @Test void batchMetadataRetainsRequestedOrderAndRejectsMissingIdsWithoutLoadingBodies() {
        var ids = java.util.stream.IntStream.range(0, 16)
                .mapToObj(index -> java.util.UUID.randomUUID().toString()).toList();
        var rows = ids.stream().map(id -> row("1", "session", id, bytes(id),
                Instant.now().plusSeconds(600))).toList();
        when(mapper.findMetadataBatch("1", "session", ids)).thenReturn(rows.reversed());

        var frozen = service.inputContext("1", "session", Map.of("attachment_ids", ids));
        var selected = (List<?>) frozen.get("attachments");
        assertEquals(ids, selected.stream().map(value ->
                ((SessionAttachmentService.FrozenMetadata) value).id()).toList());
        verify(mapper).findMetadataBatch("1", "session", ids);
        verify(mapper, never()).find(anyString(), anyString(), anyString());
        verify(mapper, never()).findMetadata(anyString(), anyString(), anyString());

        when(mapper.findMetadataBatch("1", "session", ids)).thenReturn(rows.subList(1, rows.size()));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> service.inputContext("1", "session",
                        Map.of("attachment_ids", ids))).getStatus());
    }

    @Test void emptyAttachmentSelectionsDoNotQueryAndDuplicateOrInvalidIdsRemainRejected() {
        assertFalse(service.inputContext("1", "session", Map.of("attachment_ids", List.of()))
                .containsKey("attachment_ids"));
        assertTrue(service.resolve(context("1", "session", Instant.now().plusSeconds(60), false),
                List.of(), Map.of()).attachments().isEmpty());
        assertThrows(ApiException.class, () -> service.inputContext("1", "session",
                Map.of("attachment_ids", List.of(ID, ID))));
        assertThrows(ApiException.class, () -> service.inputContext("1", "session",
                Map.of("attachment_ids", List.of("invalid"))));
        verifyNoInteractions(mapper, bindings);
    }

    @Test void resolveBatchRetainsOrderAndOnlyLoadsEachBodyWhenOpened() throws Exception {
        var otherId = java.util.UUID.randomUUID().toString();
        var first = row("1", "session", ID, bytes("first"), Instant.now().plusSeconds(600));
        var second = row("1", "session", otherId, bytes("second"), Instant.now().plusSeconds(600));
        when(mapper.findMetadataBatch("1", "session", List.of(ID, otherId))).thenReturn(List.of(second, first));
        var original=attachmentInput(context("1","session",Instant.now().plusSeconds(60),false).run(),List.of(ID,otherId));
        when(facts.originalInput(new RunRef(new TenantId("1"),"run"))).thenReturn(Optional.of(original));
        when(mapper.find("1", "session", ID)).thenReturn(first);
        when(mapper.find("1", "session", otherId)).thenReturn(second);
        var resolved = service.resolve(context("1", "session", Instant.now().plusSeconds(60), false),
                List.of(ID, otherId), Map.of());
        assertEquals(List.of(ID, otherId), resolved.attachments().stream()
                .map(SessionAttachmentService.Metadata::id).toList());
        verify(mapper, never()).find(anyString(), anyString(), anyString());
        try (var input = resolved.files().getFirst().open()) {
            assertArrayEquals(first.content(), input.readAllBytes());
        }
        verify(mapper).find("1", "session", ID);
        verify(mapper, never()).find("1", "session", otherId);
    }

    @Test void firstAttachmentPathCollisionPrecedesLaterMissingAttachment() {
        var missingId = java.util.UUID.randomUUID().toString();
        var first = row("1", "session", ID, bytes("first"), Instant.now().plusSeconds(600));
        when(mapper.findMetadataBatch("1", "session", List.of(ID, missingId))).thenReturn(List.of(first));
        var original=attachmentInput(context("1","session",Instant.now().plusSeconds(60),false).run(),List.of(ID,missingId));
        when(facts.originalInput(new RunRef(new TenantId("1"),"run"))).thenReturn(Optional.of(original));

        var failure = assertThrows(IllegalArgumentException.class, () -> service.resolve(
                context("1", "session", Instant.now().plusSeconds(60), false), List.of(ID, missingId),
                Map.of(first.inputPath(), "inline")));

        assertEquals("attachment_input_path_collision", failure.getMessage());
        verify(mapper, never()).find(anyString(), anyString(), anyString());
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test void delegatedOriginalSelectionIsVisibleAndStagedFromTrustedParentOnly() throws Exception {
        var child = context("1", "delegation:child", Instant.now().plusSeconds(60), false);
        var parentOwner = context("1", "parent-session", Instant.now().plusSeconds(60), false).run().owner();
        var parent = new RunIdentity(parentOwner, new AgentId((parentOwner).target().id()), new InputId("parent-input"), new RunRef(new TenantId("1"), "parent-run"), child.run().trace(), new ResultRef("parent-origin"), new RunRequester.EndUser((parentOwner).requireUser()), java.util.Optional.empty(), new RunOriginRef((parentOwner).sessionRef().tenantId(), "input:" + (new InputId("parent-input")).value()));
        var parentBinding = mock(AgentRunBindings.Binding.class);
        when(bindings.find(parent.runRef())).thenReturn(Optional.of(parentBinding));
        when(parentBinding.owner()).thenReturn(parentOwner);
        when(parentBinding.actor()).thenReturn(parent.agentId());
        when(parentBinding.parentRunRef()).thenReturn(Optional.empty());
        when(binding.owner()).thenReturn(child.run().owner());
        doReturn(Optional.of(storedRun(child.run()))).when(facts).load(child.run().runRef());
        when(binding.actor()).thenReturn(child.run().agentId());
        when(binding.parentRunRef()).thenReturn(Optional.of(parent.runRef()));
        var inputFact1 = attachmentInput(parent);
        when(facts.originalInput(parent.runRef())).thenReturn(Optional.of(inputFact1));
        var inputFact2 = attachmentInput(child.run());
        when(facts.originalInput(child.run().runRef())).thenReturn(Optional.of(inputFact2));
        var parentFact = mock(ExecutionStore.Run.class);
        when(parentFact.identity()).thenReturn(parent);
        when(facts.load(parent.runRef())).thenReturn(Optional.of(parentFact));
        byte[] bytes = "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8);
        var row = row("1", "parent-session", ID, bytes, Instant.now().plusSeconds(60));
        when(mapper.findMetadataBatch("1", "parent-session", List.of(ID))).thenReturn(List.of(row));
        when(mapper.find("1", "parent-session", ID)).thenReturn(row);
        var metadata = service.delegationContext(parent);
        assertEquals(List.of(ID), metadata.get("attachment_ids"));
        assertEquals(row.inputPath(), attachment(metadata).get("input_path"));
        assertFalse(metadata.containsKey("attachment_readings"));
        var frozen = assertInstanceOf(SessionAttachmentService.FrozenMetadata.class,
                ((List<?>) metadata.get("attachments")).getFirst());
        var resolved = service.resolve(child, List.of(ID), Map.of());
        assertEquals("data/" + row.inputPath(), resolved.files().getFirst().file().path());
        assertEquals("/input/data/" + frozen.inputPath(), "/input/" + resolved.files().getFirst().file().path());
        try (var stream = resolved.files().getFirst().open()) { assertArrayEquals(bytes, stream.readAllBytes()); }
        assertThrows(SecurityException.class, () -> service.resolve(child, List.of("4d9b2652-1243-4b93-a4c1-f432d5c88311"), Map.of()));
        doThrow(new SecurityException("revoked")).when(bindings).authorizeRead(parentBinding, parent.agentId().value());
        assertThrows(SecurityException.class, () -> resolved.files().getFirst().open(), "deferred transfer rechecks parent authorization");
    }

    @Test void delegatedParentCannotCrossTenantOrEndUser() {
        for (var owner : List.of(
                SessionOwner.user(new SessionRef(new TenantId("2"), "parent"), new AgentId("7"), new EndUserId(9)),
                SessionOwner.user(new SessionRef(new TenantId("1"), "parent"), new AgentId("7"), new EndUserId(10)))) {
            var child = context("1", "delegation:child", Instant.now().plusSeconds(60), false).run();
            var parentRef = new RunRef(owner.sessionRef().tenantId(), "parent-run");
            var parentBinding = mock(AgentRunBindings.Binding.class);
            when(binding.owner()).thenReturn(child.owner());
            doReturn(Optional.of(storedRun(child))).when(facts).load(child.runRef());
            when(binding.parentRunRef()).thenReturn(Optional.of(parentRef));
            when(bindings.find(parentRef)).thenReturn(Optional.of(parentBinding));
            when(parentBinding.owner()).thenReturn(owner);
            var inputFact3 = attachmentInput(child);
            when(facts.originalInput(child.runRef())).thenReturn(Optional.of(inputFact3));
            var denied = assertThrows(SecurityException.class, () -> service.delegationContext(child));
            assertEquals("attachment_parent_owner_mismatch", denied.getMessage());
        }
        verifyNoInteractions(mapper);
    }

    @Test void delegatedParentCycleIsRejected() {
        var child = context("1", "delegation:child", Instant.now().plusSeconds(60), false).run();
        when(binding.owner()).thenReturn(child.owner());
        doReturn(Optional.of(storedRun(child))).when(facts).load(child.runRef());
        when(binding.parentRunRef()).thenReturn(Optional.of(child.runRef()));
        var inputFact4 = attachmentInput(child);
        when(facts.originalInput(child.runRef())).thenReturn(Optional.of(inputFact4));
        var record = mock(ExecutionStore.Run.class);
        when(record.identity()).thenReturn(child);
        when(facts.load(child.runRef())).thenReturn(Optional.of(record));
        var denied = assertThrows(SecurityException.class, () -> service.delegationContext(child));
        assertEquals("attachment_parent_chain_invalid", denied.getMessage());
        verifyNoInteractions(mapper);
    }

    @Test void delegatedSelectionCannotExpandOriginalParentSelection() {
        var child = context("1", "delegation:child", Instant.now().plusSeconds(60), false).run();
        var parentOwner = context("1", "parent-session", Instant.now().plusSeconds(60), false).run().owner();
        var parent = new RunIdentity(parentOwner, new AgentId((parentOwner).target().id()), new InputId("parent-input"), new RunRef(new TenantId("1"), "parent-run"), child.trace(), new ResultRef("parent-origin"), new RunRequester.EndUser((parentOwner).requireUser()), java.util.Optional.empty(), new RunOriginRef((parentOwner).sessionRef().tenantId(), "input:" + (new InputId("parent-input")).value()));
        var parentBinding = mock(AgentRunBindings.Binding.class);
        when(binding.owner()).thenReturn(child.owner());
        doReturn(Optional.of(storedRun(child))).when(facts).load(child.runRef());
        when(binding.parentRunRef()).thenReturn(Optional.of(parent.runRef()));
        when(bindings.find(parent.runRef())).thenReturn(Optional.of(parentBinding));
        when(parentBinding.owner()).thenReturn(parent.owner());
        when(parentBinding.actor()).thenReturn(parent.agentId());
        when(parentBinding.parentRunRef()).thenReturn(Optional.empty());
        var inputFact5 = attachmentInput(child);
        when(facts.originalInput(child.runRef())).thenReturn(Optional.of(inputFact5));
        var inputFact6 = attachmentInput(parent, List.of());
        when(facts.originalInput(parent.runRef())).thenReturn(Optional.of(inputFact6));
        var record = mock(ExecutionStore.Run.class);
        when(record.identity()).thenReturn(parent);
        when(facts.load(parent.runRef())).thenReturn(Optional.of(record));
        var denied = assertThrows(SecurityException.class, () -> service.delegationContext(child));
        assertEquals("attachment_selection_not_delegated", denied.getMessage());
        verifyNoInteractions(mapper);
    }

    /** 根Run与委派Run采用同一原准入选择器；已有附件和当前权限不能补造未选择附件。 */
    @Test void originalAdmissionSelectionAndMaterialAreRequiredBeforeAttachmentMetadataReads() {
        var context=context("1","session",Instant.now().plusSeconds(60),false);
        assertEquals("attachment_selection_not_delegated",assertThrows(SecurityException.class,
                ()->service.resolve(context,List.of("4d9b2652-1243-4b93-a4c1-f432d5c88311"),Map.of())).getMessage());
        when(facts.originalInput(context.run().runRef())).thenReturn(Optional.empty());
        assertEquals("attachment_input_missing",assertThrows(SecurityException.class,
                ()->service.resolve(context,List.of(ID),Map.of())).getMessage());
        var malformed=new ExecutionStore.Material(new ResultRef("opaque-original"),new TypedPayload("input.v1","{}"));
        when(facts.originalInput(context.run().runRef())).thenReturn(Optional.of(malformed));
        assertThrows(IllegalArgumentException.class,()->service.resolve(context,List.of(ID),Map.of()));
        verifyNoInteractions(mapper);
    }

    /** Program没有独立资源binding；只消费已验证父选择，传输仍保留child上下文并复核当前权限。 */
    @Test void programChildUsesExactParentSelectionAndRechecksParentPermissionWhenOpened() throws Exception {
        var parent=context("1","session",Instant.now().plusSeconds(60),false);
        var origin=parent.run();
        var child=new RunIdentity(origin.owner(), new AgentId((origin.owner()).target().id()), new InputId("program-input"), new RunRef(origin.runRef().tenantId(),"program-child"), origin.trace(), origin.causationIdentityRef(), new RunRequester.EndUser((origin.owner()).requireUser()), java.util.Optional.empty(), new RunOriginRef((origin.owner()).sessionRef().tenantId(), "input:" + (new InputId("program-input")).value()));
        var actual=new PlatformToolExecutionContext(new PortCallContext(child,parent.callContext().identity(),parent.callContext().control()),"child-invocation","child-attempt",true);
        doReturn(Optional.of(storedRun(child))).when(facts).load(child.runRef());
        var programTools=programs.getObject();
        doReturn(origin).when(programTools).resourceOrigin(child);
        var row=row("1","session",ID,bytes("parent-selected"),Instant.now().plusSeconds(600));
        when(mapper.findMetadataBatch("1","session",List.of(ID))).thenReturn(List.of(row));
        when(mapper.find("1","session",ID)).thenReturn(row);
        var resolved=service.resolve(actual,List.of(ID),Map.of());
        try(var input=resolved.files().getFirst().open()){assertArrayEquals(row.content(),input.readAllBytes());}
        assertEquals(child,actual.run());assertTrue(actual.programChild());
        verify(bindings,never()).find(child.runRef());
        verify(facts,never()).originalInput(child.runRef());
        doThrow(new SecurityException("parent_permission_revoked")).when(bindings).authorizeRead(binding,"7");
        assertEquals("parent_permission_revoked",assertThrows(SecurityException.class,()->resolved.files().getFirst().open()).getMessage());
        verify(mapper,times(1)).find("1","session",ID);
        when(bindings.find(origin.runRef())).thenReturn(Optional.empty());
        assertEquals("attachment_run_binding_missing",assertThrows(SecurityException.class,()->service.resolve(actual,List.of(ID),Map.of())).getMessage());
    }

    /** 原准入材料使用正式execution.input封套，引用故意不采用任何推测命名。 */
    private static ExecutionStore.Material attachmentInput(RunIdentity run) {
        return attachmentInput(run,List.of(ID));
    }

    private static ExecutionStore.Material attachmentInput(RunIdentity run,List<String> selection) {
        var frozen=new TypedPayload("input.v1","{\"extraParameters\":{\"attachment_ids\":"
                + new ObjectMapper().valueToTree(selection) + "}}");
        return new ExecutionStore.Material(new ResultRef("opaque-admission:"+run.runRef().runId()),CODEC.encodeInput(List.of(),frozen));
    }

    /** 仅作为Store已受理Run事实，所有所有者断言仍由被测服务执行。 */
    private static ExecutionStore.Run storedRun(RunIdentity run) {
        var stored=mock(ExecutionStore.Run.class);when(stored.identity()).thenReturn(run);return stored;
    }

    private static SessionAttachmentRow row(
            String tenant, String session, String id, byte[] content, Instant expiresAt) {
        return new SessionAttachmentRow(id, tenant, session, "notes.txt",
                "attachments/" + id + "/input.txt", content.length, sha(content), content,
                expiresAt.minus(7, ChronoUnit.DAYS), expiresAt);
    }

    private static String sha(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attachment(Map<String, Object> context) {
        return new ObjectMapper().convertValue(
                ((List<?>) context.get("attachments")).getFirst(), Map.class);
    }

    private static PlatformToolExecutionContext context(
            String tenantValue, String session, Instant deadline, boolean cancelled) {
        TenantId tenant = new TenantId(tenantValue);
        InputId input = new InputId("input");
        SessionOwner owner = SessionOwner.user(new SessionRef(tenant, session), new AgentId("7"),
                new EndUserId(9));
        RunIdentity run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("result"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of(), 1, new ResultRef("verification"));
        return new PlatformToolExecutionContext(
                new PortCallContext(run, identity, new InvocationControl(deadline, () -> cancelled)),
                "invocation", "attempt");
    }
}
