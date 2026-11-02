package com.skillforge.institution.attachment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.json.CodecLimits;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 会话附件 HTTP 的 multipart 绑定、响应包络和授权短路契约。 */
class SessionAttachmentHttpContractTest {
    private static final String PATH = "/api/v1/chat/sessions/attachments";
    private static final String ID = "3d9b2652-1243-4b93-a4c1-f432d5c88311";
    private final SessionAttachmentMapper mapper = mock(SessionAttachmentMapper.class);
    private final ArtifactAccess access = mock(ArtifactAccess.class);
    private final SessionAttachmentBodyStore bodies = mock(SessionAttachmentBodyStore.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        when(access.tenant()).thenReturn("7");
        var service = new SessionAttachmentService(mapper, access,
                mock(AgentRunBindings.class), bodies, mock(ExecutionStore.class),new AgentExecutionCodec(new CodecLimits(1_000_000,100_000,64)),
                com.skillforge.institution.runtime.ProgramResourceOriginFixture.ordinary(mock(ExecutionStore.class)));
        var json = new ObjectMapper().findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mvc = MockMvcBuilders.standaloneSetup(new SessionAttachmentController(service))
                .setControllerAdvice(new GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    @Test void multipartUploadBindsSessionUploadIdAndFileAndReturnsSafeMetadata() throws Exception {
        byte[] content = "# notes".getBytes(StandardCharsets.UTF_8);
        var stored = new AtomicReference<SessionAttachmentRow>();
        when(mapper.insert(any(SessionAttachmentRow.class))).thenAnswer(call -> {
            stored.set(call.getArgument(0));
            return 1;
        });
        when(mapper.find("7", "session-7", ID)).thenAnswer(call -> stored.get());

        mvc.perform(multipart(PATH + "/upload")
                        .file(new MockMultipartFile("file", "notes.md", "text/markdown", content))
                        .param("session_id", "session-7")
                        .param("upload_id", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.id").value(ID))
                .andExpect(jsonPath("$.data.name").value("notes.md"))
                .andExpect(jsonPath("$.data.input_path")
                        .value("attachments/" + ID + "/input.md"))
                .andExpect(jsonPath("$.data.sha256").value(sha(content)))
                .andExpect(jsonPath("$.data.expires_at").exists())
                .andExpect(jsonPath("$.data.content").doesNotExist())
                .andExpect(jsonPath("$.data.inputPath").doesNotExist());

        var row = stored.get();
        assertEquals("7", row.tenantId());
        assertEquals("session-7", row.sessionId());
        assertEquals(ID, row.id());
        assertArrayEquals(content, row.content());
        verify(access).authorizeWritableCurrent("7", "session-7");
        verify(mapper).find("7", "session-7", ID);
    }

    @Test void getReturnsEnvelopeSnakeCaseMetadataAndNeverSerializesStoredContent() throws Exception {
        byte[] content = "secret body".getBytes(StandardCharsets.UTF_8);
        var row = row(content, Instant.now().plus(1, ChronoUnit.DAYS));
        when(mapper.list("7", "session-7")).thenReturn(List.of(row));

        mvc.perform(get(PATH).param("session_id", "session-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[0].id").value(ID))
                .andExpect(jsonPath("$.data[0].input_path").value(row.inputPath()))
                .andExpect(jsonPath("$.data[0].expires_at").exists())
                .andExpect(jsonPath("$.data[0].content").doesNotExist())
                .andExpect(jsonPath("$.data[0].inputPath").doesNotExist());

        verify(access).authorizeCurrent("7", "session-7");
        verify(mapper).list("7", "session-7");
    }

    @Test void binaryPdfUsesTheSameMultipartAuthorizationAndSafeMetadataContract() throws Exception {
        byte[] content = SessionAttachmentContentValidatorTest.pdf(false, 1);
        var stored = new AtomicReference<SessionAttachmentRow>();
        when(mapper.insert(any(SessionAttachmentRow.class))).thenAnswer(call -> { stored.set(call.getArgument(0)); return 1; });
        when(mapper.find("7", "session-7", ID)).thenAnswer(call -> stored.get());
        mvc.perform(multipart(PATH + "/upload")
                        .file(new MockMultipartFile("file", "report.pdf", "application/pdf", content))
                        .param("session_id", "session-7").param("upload_id", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.input_path").value("attachments/" + ID + "/input.pdf"))
                .andExpect(jsonPath("$.data.sha256").value(sha(content)))
                .andExpect(jsonPath("$.data.content").doesNotExist());
        assertArrayEquals(content, stored.get().content());
        verify(access).authorizeWritableCurrent("7", "session-7");
    }

    @Test void invalidContentReturnsPreWriteRejectionCode() throws Exception {
        mvc.perform(multipart(PATH + "/upload")
                        .file(new MockMultipartFile("file", "broken.txt", "text/plain", new byte[]{(byte) 0xff}))
                        .param("session_id", "session-7").param("upload_id", ID))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SESSION_ATTACHMENT_UPLOAD_REJECTED"));
        verifyNoInteractions(mapper, bodies);
    }

    @Test void authorizationFailureStopsUploadAndListBeforeMapperAccess() throws Exception {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "会话不可用"))
                .when(access).authorizeCurrent("7", "denied");
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "会话不可用"))
                .when(access).authorizeWritableCurrent("7", "denied");

        mvc.perform(get(PATH).param("session_id", "denied"))
                .andExpect(status().isNotFound());
        mvc.perform(multipart(PATH + "/upload")
                        .file(new MockMultipartFile("file", "notes.txt", "text/plain", bytes("notes")))
                        .param("session_id", "denied")
                        .param("upload_id", ID))
                .andExpect(status().isNotFound());

        verify(access).authorizeCurrent("7", "denied");
        verify(access).authorizeWritableCurrent("7", "denied");
        verifyNoInteractions(mapper);
    }

    @Test void storageBudgetUsesOnlyAuthorizedSessionAndPreservesBigintPrecisionWithoutTenantAggregate() throws Exception {
        when(bodies.inspectSession("7", "session-7")).thenReturn(new SessionAttachmentBodyStore.SessionStorageBudget(
                "9007199254740993", "0", false));
        mvc.perform(get(PATH + "/storage-budget").param("session_id", "session-7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session_bytes").value("9007199254740993"))
                .andExpect(jsonPath("$.data.session_limit_bytes").value("0"))
                .andExpect(jsonPath("$.data.session_configured").value(false))
                .andExpect(jsonPath("$.data.tenant_bytes").doesNotExist())
                .andExpect(jsonPath("$.data.tenant_limit_bytes").doesNotExist());
        verify(access).authorizeCurrent("7", "session-7");
        verify(bodies).inspectSession("7", "session-7");
        verifyNoInteractions(mapper);
    }

    @Test void deniedSessionBudgetShortCircuitsBeforeAnyCapacityQuery() throws Exception {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "会话不可用")).when(access).authorizeCurrent("7", "denied");
        mvc.perform(get(PATH + "/storage-budget").param("session_id", "denied"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(bodies, mapper);
    }

    private static SessionAttachmentRow row(byte[] content, Instant expiresAt) {
        return new SessionAttachmentRow(ID, "7", "session-7", "notes.txt",
                "attachments/" + ID + "/input.txt", content.length, sha(content), content,
                expiresAt.minus(7, ChronoUnit.DAYS), expiresAt);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
