package com.skillforge.institution.media;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.attachment.SessionAttachmentBodyStore;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import com.skillforge.institution.runtime.AgentRunBindings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 会话容量观察不返回机构聚合；上传拒绝读取权限替代写权限。 */
class SessionMediaHttpContractTest {
    private final SessionMediaMapper mapper = mock(SessionMediaMapper.class);
    private final ArtifactAccess access = mock(ArtifactAccess.class);
    private final SessionAttachmentBodyStore bodies = mock(SessionAttachmentBodyStore.class);
    private MockMvc mvc;
    @BeforeEach void setup() {
        when(access.tenant()).thenReturn("7");
        var service = new SessionMediaService(mapper, access, mock(AgentRunBindings.class), bodies,mock(com.skillforge.runtime.session.spi.ExecutionStore.class),mock(org.springframework.beans.factory.ObjectProvider.class));
        mvc = MockMvcBuilders.standaloneSetup(new SessionMediaController(service))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();
    }
    @Test void budgetReportsExactAuthorizedSessionBytesAndExplicitUnconfiguredLimit() throws Exception {
        when(bodies.inspectSession("7", "session")).thenReturn(new SessionAttachmentBodyStore.SessionStorageBudget("9007199254740993", "0", false));
        mvc.perform(get("/api/v1/chat/sessions/media/storage-budget").param("session_id", "session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session_bytes").value("9007199254740993"))
                .andExpect(jsonPath("$.data.session_limit_bytes").value("0"))
                .andExpect(jsonPath("$.data.session_configured").value(false))
                .andExpect(jsonPath("$.data.tenant_bytes").doesNotExist());
        verify(access).authorizeCurrent("7", "session");
        verify(bodies).inspectSession("7", "session");
        verifyNoInteractions(mapper);
    }
    @Test void deniedSessionBudgetNeverQueriesStorageAndReadonlyPrincipalCannotUpload() throws Exception {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "会话不可用")).when(access).authorizeCurrent("7", "denied");
        mvc.perform(get("/api/v1/chat/sessions/media/storage-budget").param("session_id", "denied"))
                .andExpect(status().isNotFound());
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "会话不可写")).when(access).authorizeWritableCurrent("7", "readonly");
        mvc.perform(multipart("/api/v1/chat/sessions/media/upload")
                .file(new MockMultipartFile("file", "image.png", "image/png", new byte[]{1, 2, 3}))
                .param("session_id", "readonly").param("upload_id", "3d9b2652-1243-4b93-a4c1-f432d5c88311"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(bodies, mapper);
    }
    @Test void invalidImageReturnsPreWriteRejectionCode() throws Exception {
        mvc.perform(multipart("/api/v1/chat/sessions/media/upload")
                .file(new MockMultipartFile("file", "image.png", "image/png", new byte[]{1, 2, 3}))
                .param("session_id", "session").param("upload_id", "3d9b2652-1243-4b93-a4c1-f432d5c88311"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SESSION_MEDIA_UPLOAD_REJECTED"));
        verifyNoInteractions(bodies, mapper);
    }
    @Test void uploadReceiptIsSessionAuthorizedAndDoesNotMutateOrTreatMissingAsFailure() throws Exception {
        String upload = "3d9b2652-1243-4b93-a4c1-f432d5c88311";
        mvc.perform(get("/api/v1/chat/sessions/media/upload-receipt").param("session_id", "session").param("upload_id", upload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UNKNOWN"));
        verify(access).authorizeCurrent("7", "session");
        verify(mapper, never()).pin(anyString(), anyString(), anyString());
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "会话不可用")).when(access).authorizeCurrent("7", "denied");
        clearInvocations(mapper);
        mvc.perform(get("/api/v1/chat/sessions/media/upload-receipt").param("session_id", "denied").param("upload_id", upload))
                .andExpect(status().isNotFound());
        verifyNoInteractions(mapper);
    }
    @Test void uploadReceiptReturnsOnlyNormalizedImageForOriginalUpload() throws Exception {
        String upload = "3d9b2652-1243-4b93-a4c1-f432d5c88311";
        var owner = new com.skillforge.runtime.api.SessionRef(new com.skillforge.runtime.api.TenantId("7"), "session");
        String sourceId = java.util.UUID.nameUUIDFromBytes((owner + ":source:" + upload).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        String digest = "a".repeat(64);
        String normalizedId = java.util.UUID.nameUUIDFromBytes((owner + ":" + sourceId + ":" + digest + ":normalize-v1").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        var source = new SessionMediaRow(sourceId, "7", "session", "session", null, "图片", 10, digest, "image/png", 2, 2, null, null, "source-v1", java.time.Instant.now());
        var normalized = new SessionMediaRow(normalizedId, "7", "session", "session", null, "图片", 8, "b".repeat(64), "image/png", 2, 2, null, sourceId, "normalize-v1", java.time.Instant.now());
        when(mapper.find("7", "session", sourceId)).thenReturn(source);
        when(mapper.find("7", "session", normalizedId)).thenReturn(normalized);
        mvc.perform(get("/api/v1/chat/sessions/media/upload-receipt").param("session_id", "session").param("upload_id", upload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FOUND"))
                .andExpect(jsonPath("$.data.media.media_id").value(normalizedId));
        verify(mapper, never()).pin(anyString(), anyString(), anyString());
    }
}
