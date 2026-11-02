package com.skillforge.institution.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.EndUserDO;
import com.skillforge.core.foundation.application.EndUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChatClientProfileServiceTest {
    private static final String REF = "cd0f4979-6b0b-434a-83cf-9c313f3c080c";
    private final ChatClientProfileMapper mapper = mock(ChatClientProfileMapper.class);
    private final EndUserService users = mock(EndUserService.class);
    private final ChatEndUserTokens tokens = new ChatEndUserTokens(new com.fasterxml.jackson.databind.ObjectMapper(),
            new com.skillforge.core.shared.config.SkillForgeProperties(), mapper, users);
    private final ChatClientProfileService service = new ChatClientProfileService(mapper, users, tokens);

    @AfterEach void clear() { TenantContext.clear(); ChannelContext.clear(); }
    private void authenticate() {
        TenantContext.setTenantId("7"); ChannelContext.setChannelId("8");
        when(mapper.channelExists(7,8)).thenReturn(true);
    }
    private EndUserDO user() {
        var user = new EndUserDO(); user.setId(9L); user.setDisplayName("首次昵称"); user.setStatus("active");
        return user;
    }
    @Test void requiresBothAuthenticatedContexts() {
        assertThrows(ApiException.class, () -> service.register(REF,"昵称",null));
        TenantContext.setTenantId("7");
        assertThrows(ApiException.class, () -> service.register(REF,"昵称",null));
        verifyNoInteractions(mapper,users);
    }
    @Test void rejectsInvalidInputAndForeignChannelBeforeCreatingUser() {
        authenticate();
        assertThrows(ApiException.class, () -> service.register("1-1-1-1-1","昵称",null));
        assertThrows(ApiException.class, () -> service.register(REF," ",null));
        assertThrows(ApiException.class, () -> service.register(REF,"名".repeat(129),null));
        when(mapper.channelExists(7,8)).thenReturn(false);
        assertThrows(ApiException.class, () -> service.register(REF,"昵称",null));
        verifyNoInteractions(users);
    }
    @Test void locksBeforeFirstCreationAndMapping() {
        when(mapper.find(7,8,REF)).thenReturn(null);
        authenticate(); when(users.create("7","首次昵称")).thenReturn(user());
        var registered = service.register(REF," 首次昵称 ",null);
        assertEquals("9",registered.endUserId());
        assertNotNull(registered.identityToken());
        var ordered = inOrder(mapper,users);
        ordered.verify(mapper).channelExists(7,8);
        ordered.verify(mapper).lock("chat-client-profile:7:8:"+REF);
        ordered.verify(mapper).find(7,8,REF);
        ordered.verify(users).create("7","首次昵称");
        ordered.verify(mapper).insert(7,8,REF,9);
    }
    @Test void restoresExistingIdentityAndOriginalName() {
        authenticate(); when(mapper.find(7,8,REF)).thenReturn(9L); when(users.require("7",9L)).thenReturn(user());
        assertThrows(ApiException.class, () -> service.register(REF,"另一个昵称",null));
        var result = service.register(REF,"另一个昵称",tokens.issue(7,8,9,REF));
        assertEquals("首次昵称",result.displayName()); assertEquals("8",result.channelId());
        verify(users,never()).create(anyString(),anyString());
        verify(mapper,never()).insert(anyLong(),anyLong(),anyString(),anyLong());
    }
    @Test void rejectsDisabledIdentity() {
        authenticate(); when(mapper.find(7,8,REF)).thenReturn(9L); var user=user(); user.setStatus("disabled");
        when(users.require("7",9L)).thenReturn(user);
        assertThrows(ApiException.class, () -> service.register(REF,"昵称",tokens.issue(7,8,9,REF)));
    }
    @Test void refusesForgedOrOtherProfileCredentials() {
        authenticate(); when(mapper.find(7,8,REF)).thenReturn(9L); when(users.require("7",9L)).thenReturn(user());
        String token = tokens.issue(7,8,9,REF);
        assertThrows(ApiException.class, () -> service.register(REF,"昵称",token+"x"));
        assertThrows(ApiException.class, () -> service.register(java.util.UUID.randomUUID().toString(),"昵称",token));
        ChannelContext.setChannelId("18");
        assertThrows(ApiException.class, () -> tokens.verify(token));
        ChannelContext.setChannelId("8"); TenantContext.setTenantId("17");
        assertThrows(ApiException.class, () -> tokens.verify(token));
    }
}
