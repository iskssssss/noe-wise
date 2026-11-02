package com.skillforge.institution.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.application.EndUserService;
import com.skillforge.institution.config.ExternalAuthPaths;
import com.skillforge.institution.security.HmacSignatureVerifier;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class ChatEndUserTokensTest {
    private static final String REF = "cd0f4979-6b0b-434a-83cf-9c313f3c080c";
    private final ObjectMapper json = new ObjectMapper();
    private final SkillForgeProperties properties = new SkillForgeProperties();
    private final ChatClientProfileMapper profiles = mock(ChatClientProfileMapper.class);
    private final EndUserService users = mock(EndUserService.class);
    private final ChatEndUserTokens tokens = new ChatEndUserTokens(json, properties, profiles, users);
    @BeforeEach void setup() {
        TenantContext.setTenantId("7"); ChannelContext.setChannelId("8");
        when(profiles.channelExists(7,8)).thenReturn(true);
        when(profiles.find(7,8,REF)).thenReturn(9L);
        var user = new EndUserDO(); user.setId(9L); user.setStatus("active");
        when(users.require("7",9L)).thenReturn(user);
    }
    @AfterEach void clear() {
        TenantContext.clear(); ChannelContext.clear(); RequestContextHolder.resetRequestAttributes();
    }
    @Test void signedIdentityFreezesOwnerAndClientReference() {
        var request = new MockHttpServletRequest("GET", "/api/v1/chat/history");
        request.setAttribute(ExternalAuthPaths.ATTR_AUTH_SOURCE, ExternalAuthPaths.SOURCE_CHANNEL);
        request.addHeader(ChatEndUserTokens.HEADER, tokens.issue(7,8,9,REF));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        tokens.authenticate(request);
        assertDoesNotThrow(() -> ChatEndUserTokens.requireEndUser("9"));
        assertDoesNotThrow(() -> ChatEndUserTokens.requireClientRef(REF));
        assertThrows(ApiException.class, () -> ChatEndUserTokens.requireEndUser("10"));
        assertThrows(ApiException.class, () -> ChatEndUserTokens.requireEndUser(null));
        assertThrows(ApiException.class, () -> ChatEndUserTokens.requireClientRef("other"));
    }
    @Test void missingForgedExpiredRevokedAndForeignCredentialsAreRejected() throws Exception {
        String valid = tokens.issue(7,8,9,REF);
        assertThrows(ApiException.class, () -> tokens.verify(null));
        assertThrows(ApiException.class, () -> tokens.verify(valid + "x"));
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(
                new ChatEndUserTokens.Principal("skillforge.chat-user.v1","7","8","9",REF,1)));
        String key = HmacSignatureVerifier.sign(properties.getSecretKey(), "skillforge.chat-user.v1:signing-key");
        assertThrows(ApiException.class, () -> tokens.verify(body + "." + HmacSignatureVerifier.sign(key,"skillforge.chat-user.v1\n"+body)));
        when(profiles.find(7,8,REF)).thenReturn(10L);
        assertThrows(ApiException.class, () -> tokens.verify(valid));
        when(profiles.find(7,8,REF)).thenReturn(9L);
        users.require("7",9L).setStatus("disabled");
        assertThrows(ApiException.class, () -> tokens.verify(valid));
    }
    @Test void administratorDoesNotInheritChannelOwnerRestriction() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        assertNull(ChatEndUserTokens.current());
        assertDoesNotThrow(() -> ChatEndUserTokens.requireEndUser("another-user"));
    }
    @Test void storageOutageDoesNotInvalidateAValidCredential() {
        String token = tokens.issue(7,8,9,REF);
        var unavailable = new org.springframework.dao.DataAccessResourceFailureException("fixture unavailable");
        when(profiles.channelExists(7,8)).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> tokens.verify(token)));
    }
}
