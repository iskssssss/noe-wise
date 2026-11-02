package com.skillforge.institution.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.foundation.application.AuditService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.chat.ChatClientProfileMapper;
import com.skillforge.institution.integration.AbstractIntegrationTest;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** 真实 PostgreSQL 审计提交、脱敏关联和派发前权限/持久化故障边界。 */
class BrowserDeviceAuditIntegrationTest extends AbstractIntegrationTest {
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired PlatformTransactionManager manager;
    final ObjectMapper json=new ObjectMapper();
    @AfterEach void clear(){TenantContext.clear();ChannelContext.clear();}
    String tenant(){String tenant=createTenant(tenants,plans,"browser-audit-"+UUID.randomUUID());TenantContext.setTenantId(tenant);return tenant;}
    List<Map<String,Object>> rows(String tenant){return jdbc.queryForList("SELECT actor_id,resource_id,payload::text AS payload FROM nw_audit_logs WHERE tenant_id=? AND resource_type='browser_authorization' ORDER BY id",Long.parseLong(tenant));}

    @Test void committedIntentAndChannelActorCorrelateWithoutSecretsAndUnknownDoesNotRetry()throws Exception{
        String tenant=tenant();ChannelContext.setChannelId("123");var client=mock(BrowserClient.class);var access=mock(ArtifactAccess.class);
        var service=new BrowserDeviceAuditService(audit,client,access,manager);String user="987654",device="private-device";
        when(client.pair(eq(tenant),eq(user),anyString())).thenAnswer(call->{
            var started=rows(tenant);assertEquals(1,started.size());assertTrue(started.getFirst().get("payload").toString().contains("started"));
            UUID.fromString(call.getArgument(2));assertEquals(call.getArgument(2),started.getFirst().get("resource_id"));
            return json.createObjectNode().put("pairing_url","https://private.example/#private-token");
        });
        var profiles=mock(ChatClientProfileMapper.class);String ref=UUID.randomUUID().toString();
        when(profiles.channelExists(Long.parseLong(tenant),123L)).thenReturn(true);
        when(profiles.find(Long.parseLong(tenant),123L,ref)).thenReturn(Long.parseLong(user));
        var controller=new BrowserDeviceController(profiles,client,mock(com.skillforge.institution.media.SessionMediaService.class),access,mock(SessionService.class),mock(com.skillforge.institution.runtime.AgentRunBindings.class),service,mock(BrowserConnectionService.class),mock(BrowserTaskAccess.class));
        controller.pair(new BrowserDeviceController.DeviceCommand(ref,null,null,null,null,null));var success=rows(tenant);assertEquals(2,success.size());assertEquals("channel:123",success.getFirst().get("actor_id"));
        assertEquals(success.getFirst().get("resource_id"),success.getLast().get("resource_id"));assertTrue(success.getLast().get("payload").toString().contains("succeeded"));
        when(client.revoke(eq(tenant),eq(user),eq(device),anyString())).thenThrow(new IOException("private-token"));
        assertThrows(IOException.class,()->service.revoke(tenant,user,device));var all=rows(tenant);assertEquals(4,all.size());
        assertTrue(all.getLast().get("payload").toString().contains("unknown"));String persisted=all.toString();
        for(String secret:List.of(user,device,"private-token","private.example"))assertFalse(persisted.contains(secret));
        verify(client,times(1)).revoke(eq(tenant),eq(user),eq(device),anyString());verifyNoInteractions(access);
    }
    @Test void outerRollbackCannotEraseAlreadyDispatchedAuthorizationIntent()throws Exception{
        String tenant=tenant();ChannelContext.setChannelId("123");var client=mock(BrowserClient.class);
        when(client.pair(eq(tenant),eq("789"),anyString())).thenReturn(json.createObjectNode().put("ok",true));
        var service=new BrowserDeviceAuditService(audit,client,mock(ArtifactAccess.class),manager);
        var outer=new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThrows(IllegalStateException.class,()->outer.executeWithoutResult(ignored->{
            try{service.pair(tenant,"789");}catch(Exception failure){throw new RuntimeException(failure);}
            throw new IllegalStateException("rollback caller");
        }));assertEquals(2,rows(tenant).size());verify(client,times(1)).pair(eq(tenant),eq("789"),anyString());
    }
    @Test void memberIdentityAndAuditWriteFailurePreventUnauditedDispatch()throws Exception{
        String tenant=tenant();var client=mock(BrowserClient.class);var access=mock(ArtifactAccess.class);when(access.member()).thenReturn("member-456");
        when(client.pair(eq(tenant),eq("789"),anyString())).thenReturn(json.createObjectNode().put("ok",true));
        new BrowserDeviceAuditService(audit,client,access,manager).pair(tenant,"789");assertEquals("member-456",rows(tenant).getFirst().get("actor_id"));
        var failing=mock(AuditService.class);doThrow(new org.springframework.dao.DataAccessResourceFailureException("audit unavailable")).when(failing).record(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyMap());
        clearInvocations(client);
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->new BrowserDeviceAuditService(failing,client,access,manager).pair(tenant,"789"));verifyNoInteractions(client);
    }
    @Test void unavailableChannelAndForbiddenSessionNeverReachAuditOrService()throws Exception{
        String tenant=tenant();var profiles=mock(ChatClientProfileMapper.class);var client=mock(BrowserClient.class);var access=mock(ArtifactAccess.class);var audited=mock(BrowserDeviceAuditService.class);
        var controller=new BrowserDeviceController(profiles,client,mock(com.skillforge.institution.media.SessionMediaService.class),access,mock(SessionService.class),mock(com.skillforge.institution.runtime.AgentRunBindings.class),audited,mock(BrowserConnectionService.class),mock(BrowserTaskAccess.class));
        ChannelContext.setChannelId("123");var channelRequest=new BrowserDeviceController.DeviceCommand(UUID.randomUUID().toString(),null,"device",null,null,null);
        assertThrows(com.skillforge.core.shared.exception.ApiException.class,()->controller.revoke(channelRequest));verifyNoInteractions(audited,client);
        ChannelContext.clear();doThrow(new SecurityException("denied")).when(access).authorizeCurrent(tenant,"private-session");
        assertThrows(SecurityException.class,()->controller.pair(new BrowserDeviceController.DeviceCommand(null,"private-session",null,null,null,null)));verifyNoInteractions(audited,client);
        assertTrue(rows(tenant).isEmpty());
    }
}
