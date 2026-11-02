package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.chat.access.OpenPlatformSessionAccessService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.institution.runtime.AgentRuntimeExecutionGateway;
import com.skillforge.institution.artifact.*;
import com.skillforge.institution.attachment.SessionAttachmentService;
import com.skillforge.institution.media.SessionMediaService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 公开状态、交付物、补充指令和材料入口全部在真实归属确认后才接触内部事实。 */
class OpenPlatformSessionQueryControllerTest {
    private final OpenPlatformSessionAccessService access=mock(OpenPlatformSessionAccessService.class);
    private final AgentRuntimeExecutionGateway runtime=mock(AgentRuntimeExecutionGateway.class);
    private final DeliverableReader deliverables=mock(DeliverableReader.class);
    private final DeliverableDownloadService downloads=mock(DeliverableDownloadService.class);
    private final SessionAttachmentService attachments=mock(SessionAttachmentService.class);
    private final SessionMediaService media=mock(SessionMediaService.class);
    private final com.skillforge.institution.runtime.AgentRuntimeRunControls controls=mock(com.skillforge.institution.runtime.AgentRuntimeRunControls.class);
    private final OpenPlatformSessionQueryController controller=new OpenPlatformSessionQueryController(access,runtime,
            deliverables,downloads,attachments,media,controls);
    private final OpenPlatformAccessService.Access identity=new OpenPlatformAccessService.Access(
            new ServicePrincipalContext.Identity("sp","7","crm",1,Set.of("sessions.read","sessions.write","artifacts.read")));
    private SessionView session() {
        return SessionView.builder().sessionId("s").tenantId(7L).integrationId("crm")
                .agentId(7L).endUserId(99L).scopeType("open-platform:crm").metadata(java.util.Map.of()).build();
    }

    @Test void statusUsesAuthenticatedIntegrationInsteadOfChatPrincipal() {
        var session=session();when(access.require("sessions.read")).thenReturn(identity);when(access.session(identity,"s")).thenReturn(session);
        var expected=new AgentRuntimeExecutionGateway.OpenPlatformRunStatus("s","r","suspended",List.of());
        when(runtime.openPlatformStatus(identity.principal(),session,"r")).thenReturn(expected);
        assertSame(expected,controller.status("s","r").data());
    }
    @Test void historyUsesAuthenticatedIntegrationForCollaborationAndPendingCards() {
        var session=session();when(access.require("sessions.read")).thenReturn(identity);when(access.session(identity,"s")).thenReturn(session);
        var expected=new com.skillforge.core.chat.dto.response.ChatHistoryResponse("s",List.of(),List.of(),List.of(),List.of(),
                com.skillforge.core.chat.dto.response.ChatHistoryResponse.Collaboration.empty());
        when(runtime.openPlatformHistory(identity.principal(),session)).thenReturn(expected);
        assertSame(expected,controller.history("s").data());
        verify(runtime).openPlatformHistory(identity.principal(),session);
    }
    @Test void crossIntegrationQueriesNeverReadRuntimeOrFiles() throws Exception {
        when(access.require(anyString())).thenReturn(identity);when(access.session(identity,"foreign")).thenThrow(new SecurityException("foreign"));
        assertThrows(SecurityException.class,()->controller.history("foreign"));
        assertThrows(SecurityException.class,()->controller.status("foreign","r"));
        assertThrows(SecurityException.class,()->controller.deliverables("foreign",true));
        assertThrows(SecurityException.class,()->controller.file("foreign","revision","file",new MockHttpServletResponse()));
        assertThrows(SecurityException.class,()->controller.attachment("foreign","upload","input.json",new byte[]{1}));
        assertThrows(SecurityException.class,()->controller.media("foreign","upload",new byte[]{1}));
        verifyNoInteractions(runtime,deliverables,downloads,attachments,media);
    }
    @Test void unpublishedWorkspaceFileCannotBeReadAsDeliverable() throws Exception {
        when(access.require("artifacts.read")).thenReturn(identity);when(access.session(identity,"s")).thenReturn(session());
        doThrow(new IllegalArgumentException("unpublished")).when(downloads).transfer(eq("s"),eq("private"),eq("working-file"),any(),any());
        assertThrows(RuntimeException.class,()->controller.file("s","private","working-file",new MockHttpServletResponse()));
        verify(deliverables,never()).file(anyString(),anyString(),anyString());
    }
    @Test void steeringBindsIdempotencyToIntegrationAndSession() {
        var session=session();when(access.require("sessions.write")).thenReturn(identity);when(access.session(identity,"s")).thenReturn(session);
        controller.steer(new OpenPlatformSessionQueryController.Steering("s","r","调整目标"),"key");
        verify(controls).steerOpenPlatform(identity.principal(),session,"r",OpenPlatformInputIds.create(identity.principal(),"s","steer","key"),"调整目标");
        verifyNoInteractions(runtime);
    }

    @Test void rawBinaryUploadUsesSignedQuerySelectorsAndPreservesEveryFileByte() throws Exception {
        when(access.require("sessions.write")).thenReturn(identity);when(access.session(identity,"s")).thenReturn(session());
        byte[] file={(byte)0xff,0,1,(byte)0x80,13,10};
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/v1/open-platform/sessions/attachments").queryParam("sessionId","s")
                        .queryParam("uploadId","stable-upload").queryParam("filename","原始.json")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(file))
                .andExpect(status().isOk());
        var ordered=inOrder(access,attachments);
        ordered.verify(access).require("sessions.write");ordered.verify(access).session(identity,"s");
        ordered.verify(attachments).upload(eq("s"),eq("stable-upload"),eq("原始.json"),aryEq(file));
        verifyNoInteractions(media,runtime,downloads);
    }

    @Test void rawImageUploadBindsOnlySessionAndStableUploadIdentity() throws Exception {
        when(access.require("sessions.write")).thenReturn(identity);when(access.session(identity,"s")).thenReturn(session());
        byte[] pixels={(byte)0x89,80,78,71};
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/v1/open-platform/sessions/media").queryParam("sessionId","s")
                        .queryParam("uploadId","stable-image").contentType(MediaType.APPLICATION_OCTET_STREAM).content(pixels))
                .andExpect(status().isOk());
        verify(media).upload(eq("s"),eq("stable-image"),aryEq(pixels));
        verifyNoInteractions(attachments);
    }

    @Test void retiredOpenPlatformMultipartAndMissingQuerySelectorsNeverReachStorage() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(multipart("/api/v1/open-platform/sessions/attachments")
                        .file(new MockMultipartFile("file","input.txt","text/plain",new byte[]{1})))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/v1/open-platform/sessions/media")
                        .file(new MockMultipartFile("file","input.png","image/png",new byte[]{1})))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/v1/open-platform/sessions/attachments").queryParam("sessionId","s")
                        .queryParam("uploadId","upload").contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[]{1}))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(access,attachments,media);
    }
}
