package com.skillforge.institution.artifact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.institution.config.ExternalAuthPaths;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 开放身份签票和逐次读取都必须重新校验渠道与会话权限。 */
class ArtifactChannelAccessTest {
    private final ArtifactStorage storage=mock(ArtifactStorage.class);
    private final StudioArtifactProjection studioArtifacts=mock(StudioArtifactProjection.class);
    private final com.skillforge.core.foundation.access.FoundationAccess foundation=mock(com.skillforge.core.foundation.access.FoundationAccess.class);
    private final ArtifactAccess access=new ArtifactAccess(storage,new ObjectMapper(),mock(AuditService.class),studioArtifacts,mock(com.skillforge.core.chat.access.OpenPlatformSessionAccessService.class),foundation,
            "artifact-channel-test-signing-key-32-chars","http://preview.localhost:8091","http://localhost:3000","http://localhost:8000",org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));
    private final String id="ce452fb7-c086-4fa4-bc88-cf676c8abc48";
    private final ArtifactRow row=new ArtifactRow(id,"7","session","run","invocation","website","site",10,"hash","[]",
            "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",
            Instant.now(),Instant.now().plusSeconds(3600),"ready");
    @AfterEach void clear(){TenantContext.clear();ChannelContext.clear();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
    private void authenticated(){
        TenantContext.setTenantId("7");ChannelContext.setChannelId("8");
        when(storage.require("7",id)).thenReturn(row);
        when(storage.canChannelAccess("7","8","session")).thenReturn(true);
        when(storage.sessionEndUser("7","session")).thenReturn("9");
        when(studioArtifacts.visibleSessions("7","session")).thenReturn(List.of("session"));
        when(studioArtifacts.route("7","session","run")).thenReturn(java.util.Optional.empty());
        when(storage.metadata(row)).thenReturn(new ArtifactMetadata(id,"session","run","invocation","website","site",10,
                "hash",row.expiresAt(),"ready",List.of(new ArtifactMetadata.File("index.html",10,"hash")),List.of()));
    }
    private String token(String url){return url.contains("?ticket=")?url.substring(url.indexOf("?ticket=")+8)
            :url.substring(url.indexOf("/p/")+3,url.lastIndexOf("/index.html"));}
    @Test void opensOnlyMetadataAndTicketIssuanceForBothExternalMethods(){
        for(String path:List.of("/api/v1/chat/sessions/artifacts","/api/v1/chat/artifacts/download-ticket","/api/v1/chat/artifacts/preview-ticket")){
            assertTrue(ExternalAuthPaths.OPEN_PATHS.contains(path));assertFalse(ExternalAuthPaths.isSignedOpenApiPath(path));
        }
    }
    @Test void currentSessionReadRequiresBothChannelBindingAndAuthenticatedEndUser() {
        authenticated();
        var request=new org.springframework.mock.web.MockHttpServletRequest("GET","/api/v1/chat/work/tasks/detail");
        request.setAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE,ExternalAuthPaths.SOURCE_CHANNEL);
        request.setAttribute(com.skillforge.institution.chat.ChatEndUserTokens.class.getName(),
                new com.skillforge.institution.chat.ChatEndUserTokens.Principal("skillforge.chat-user.v1","7","8","9","client",Instant.now().plusSeconds(60).getEpochSecond()));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        assertDoesNotThrow(()->access.authorizeCurrent("7","session"));
        when(storage.sessionEndUser("7","session")).thenReturn("other-user");
        assertEquals(org.springframework.http.HttpStatus.FORBIDDEN,assertThrows(com.skillforge.core.shared.exception.ApiException.class,
                ()->access.authorizeCurrent("7","session")).getStatus());
        when(storage.canChannelAccess("7","8","session")).thenReturn(false);
        assertThrows(RuntimeException.class,()->access.authorizeCurrent("7","session"));
        verifyNoInteractions(foundation);
    }

    @Test void listsOnlyAfterChannelAuthorization(){
        authenticated();var controller=new ArtifactController(storage,access,mock(ArtifactVersionService.class));
        controller.list("session");verify(storage).canChannelAccess("7","8","session");
        verify(storage).listSessions("7",List.of("session"));
        when(storage.canChannelAccess("7","8","foreign-session")).thenReturn(false);
        assertThrows(RuntimeException.class,()->controller.list("foreign-session"));
        verify(storage,never()).listSessions(eq("7"),argThat(sessions->sessions.contains("foreign-session")));
    }
    @Test void studioChildArtifactsAreProjectedAndEveryTicketReadUsesTheProductSession(){
        TenantContext.setTenantId("7");ChannelContext.setChannelId("8");
        var child=new ArtifactRow(id,"7","delegation:d1","child-run","invocation","website","site",10,
                "hash","[]","4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",
                Instant.now(),Instant.now().plusSeconds(3600),"ready");
        var metadata=new ArtifactMetadata(id,"delegation:d1","child-run","invocation","website","site",10,
                "hash",child.expiresAt(),"ready",List.of(new ArtifactMetadata.File("index.html",10,"hash")),List.of());
        when(storage.canChannelAccess("7","8","product-session")).thenReturn(true);
        when(storage.sessionEndUser("7","product-session")).thenReturn("9");
        when(studioArtifacts.visibleSessions("7","product-session"))
                .thenReturn(List.of("product-session","delegation:d1"));
        when(storage.listSessions("7",List.of("product-session","delegation:d1"))).thenReturn(List.of(metadata));
        when(storage.require("7",id)).thenReturn(child);
        when(storage.metadata(child)).thenReturn(metadata);
        when(studioArtifacts.route("7","delegation:d1","child-run"))
                .thenReturn(java.util.Optional.of(new StudioArtifactProjection.Route(
                        "product-session","TEAM","team","source-member","parent-run")));

        var listed=access.listCurrent("7","product-session");
        assertEquals("product-session",listed.getFirst().sessionId());
        String ticket=token(access.ticket(id,"preview",null));
        ChannelContext.clear();TenantContext.setTenantId("99");

        assertEquals(id,access.verify(ticket,"preview").row().id());
        assertEquals("99",TenantContext.getTenantId());
        verify(studioArtifacts,times(2)).route("7","delegation:d1","child-run");
        verify(storage,times(3)).canChannelAccess("7","8","product-session");
        verify(storage,never()).canChannelAccess("7","8","delegation:d1");
    }
    @Test void downloadAndPreviewRecheckBindingWithoutLoginAndRestoreTenant(){
        authenticated();
        String download=token(access.ticket(id,"file","index.html")),preview=token(access.ticket(id,"preview",null));
        ChannelContext.clear();TenantContext.setTenantId("99");
        assertEquals("8",access.verify(download,null).claim().channel());
        assertEquals("7",access.verify(preview,"preview").claim().tenant());
        assertEquals("99",TenantContext.getTenantId());
        when(storage.canChannelAccess("7","8","session")).thenReturn(false);
        assertThrows(RuntimeException.class,()->access.verify(download,null));
        assertThrows(RuntimeException.class,()->access.verify(preview,"preview"));
        assertEquals("99",TenantContext.getTenantId());
        verifyNoInteractions(foundation);
    }
    @Test void rejectsUnboundIssuanceAndUnlistedFile(){
        authenticated();assertThrows(RuntimeException.class,()->access.ticket(id,"file","secret.txt"));
        when(storage.canChannelAccess("7","8","session")).thenReturn(false);
        assertThrows(RuntimeException.class,()->access.ticket(id,"preview",null));
        assertThrows(RuntimeException.class,()->access.ticket(id,"zip",null));
    }
    @Test void signedTenantCannotBeReplaced(){
        authenticated();String url=access.ticket(id,"zip",null);assertTrue(url.startsWith("http://localhost:8000/api/v1/chat/artifact-content/detail?ticket="));String ticket=token(url);
        String[] parts=ticket.split("\\.");String payload=new String(java.util.Base64.getUrlDecoder().decode(parts[0]),java.nio.charset.StandardCharsets.UTF_8);
        String tampered=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.replace("\"7\"","\"17\"").getBytes(java.nio.charset.StandardCharsets.UTF_8))+"."+parts[1];
        assertThrows(RuntimeException.class,()->access.verify(tampered,null));
    }
    @Test void channelTicketsRecheckTheirBoundEndUser(){
        authenticated();String ticket=token(access.ticket(id,"file","index.html"));
        assertEquals("9",access.verify(ticket,null).claim().endUser());
        when(storage.sessionEndUser("7","session")).thenReturn("10");
        assertThrows(RuntimeException.class,()->access.verify(ticket,null));
    }
}
