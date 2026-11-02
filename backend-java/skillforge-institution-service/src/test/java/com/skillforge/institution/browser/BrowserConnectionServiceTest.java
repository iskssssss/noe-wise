package com.skillforge.institution.browser;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.execution.browser.BrowserConnectionGrant;
import com.skillforge.execution.browser.BrowserDevices;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** 验证连接服务按个人授权、固定离线目标和准确配对关联恢复，不猜测另一在线设备。 */
class BrowserConnectionServiceTest {
    private final BrowserConnectionStore store=mock(BrowserConnectionStore.class);
    private final BrowserClient client=mock(BrowserClient.class);
    private final BrowserDeviceAuditService audit=mock(BrowserDeviceAuditService.class);
    private final AgentRunBindings bindings=mock(AgentRunBindings.class);
    private final BrowserConnectionService service=new BrowserConnectionService(store,client,audit,bindings,new ObjectMapper());

    @Test
    void explicitForeignBrowserIsForbiddenEvenWithAnOwnedOnlineDefault() throws Exception {
        when(client.devices("7","71")).thenReturn(devices("owned-browser",device("owned-device","owned-browser",true)));
        var denied=assertThrows(ApiException.class,()->service.inspect("7","71","foreign-browser"));
        assertEquals(HttpStatus.FORBIDDEN,denied.getStatus());assertEquals("browser_device_not_owned",denied.getDetail());
        verifyNoInteractions(store,audit,bindings);
    }

    @Test
    void offlineTargetSurvivesDefaultAndListChangesUntilThatExactDeviceReconnects() throws Exception {
        var offline=device("original-device","original-browser",false);
        when(client.devices("7","71")).thenReturn(devices("original-browser",offline));
        var selection=service.inspect("7","71",null);assertEquals("OFFLINE",selection.reason());
        var context=mock(PlatformToolExecutionContext.class);
        var tenant=new TenantId("7");var owner=SessionOwner.user(new SessionRef(tenant,"session-7"),new AgentId("9"),new EndUserId(71));
        when(context.run()).thenReturn(new RunIdentity(owner, new AgentId((owner).target().id()), new InputId("input-7"), new RunRef(tenant,"run-7"), new TraceEnvelope("trace-7","span-7",new InputId("input-7"),Optional.empty()), new ResultRef("identity-7"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (new InputId("input-7")).value())));
        when(context.invocationId()).thenReturn("invocation-7");
        when(store.create(any())).thenAnswer(call->call.getArgument(0));when(store.save(any())).thenReturn(true);
        var row=service.waitFor(context,selection);
        assertEquals("original-device",row.getDeviceId());assertEquals("original-browser",row.getBrowserId());
        var unrelated=device("new-default-device","new-default-browser",true);
        assertFalse(service.ready(row,devices("new-default-browser",unrelated,offline)));
        assertEquals("WAITING",row.getStatus());assertEquals("original-device",row.getDeviceId());
        verify(store,never()).save(any());verify(client,never()).connectionGrant(any(),any(),any());
        assertTrue(service.ready(row,devices("new-default-browser",unrelated,device("original-device","original-browser",true))));
        assertEquals("READY",row.getStatus());assertEquals("original-device",row.getDeviceId());assertEquals("original-browser",row.getBrowserId());
    }

    @Test
    void pairDispatchesExactRequestOnceEvenWhenFirstResultIsUnknown() throws Exception {
        var row=row();row.setDeviceId("old-offline");row.setBrowserId("old-browser");authorize(row);
        when(store.save(row)).thenReturn(true);
        when(audit.pair("7","71",row.getRequestId())).thenThrow(new IllegalStateException("transport-result-unknown"));
        assertThrows(IllegalStateException.class,()->service.pair("7","71",row.getRequestId(),null,true));
        assertTrue(row.isPairDispatched());assertTrue(row.isMakeDefault());assertNull(row.getDeviceId());assertNull(row.getBrowserId());
        var conflict=assertThrows(ApiException.class,()->service.pair("7","71",row.getRequestId(),null,true));
        assertEquals(HttpStatus.CONFLICT,conflict.getStatus());
        var order=inOrder(store,audit);order.verify(store).save(row);order.verify(audit).pair("7","71",row.getRequestId());
        verify(audit,times(1)).pair(any(),any(),any());verify(store,times(1)).save(row);
    }

    @Test
    void unknownGrantDoesNotBorrowOnlineDeviceAndPairedGrantOnlyUsesExactDevice() throws Exception {
        var row=row();row.setPairDispatched(true);when(store.save(row)).thenReturn(true);
        var unrelated=device("unrelated-device","unrelated-browser",true);
        when(client.connectionGrant("7","71",row.getRequestId())).thenReturn(
                new BrowserConnectionGrant(row.getRequestId(),BrowserConnectionGrant.Status.UNKNOWN,null));
        assertFalse(service.ready(row,devices("unrelated-browser",unrelated)));
        assertNull(row.getDeviceId());assertEquals("WAITING",row.getStatus());verify(store,never()).save(any());
        when(client.connectionGrant("7","71",row.getRequestId())).thenReturn(
                new BrowserConnectionGrant(row.getRequestId(),BrowserConnectionGrant.Status.PAIRED,"paired-device"));
        assertFalse(service.ready(row,devices("unrelated-browser",unrelated)));
        assertFalse(service.ready(row,devices("unrelated-browser",unrelated,device("paired-device","paired-browser",false))));
        verify(store,never()).save(any());
        assertTrue(service.ready(row,devices("unrelated-browser",unrelated,device("paired-device","paired-browser",true))));
        assertEquals("paired-device",row.getDeviceId());assertEquals("paired-browser",row.getBrowserId());assertEquals("READY",row.getStatus());
        verify(store,times(1)).save(row);
    }

    @Test
    void anotherUserCannotReadConnectionByKnowingRequestId() {
        var row=row();when(store.find(7,row.getRequestId())).thenReturn(row);
        var denied=assertThrows(ApiException.class,()->service.require("7","72",row.getRequestId(),null));
        assertEquals(HttpStatus.FORBIDDEN,denied.getStatus());assertEquals("browser_connection_not_owned",denied.getDetail());
        verifyNoInteractions(bindings,client,audit);verify(store,never()).save(any());
    }

    /** 建立同一用户及原 Run 的当前读取授权。 */
    private void authorize(BrowserConnectionRequest row) {
        when(store.find(7,row.getRequestId())).thenReturn(row);
        var run=new RunRef(new TenantId("7"),row.getRunId());var binding=mock(AgentRunBindings.Binding.class);
        when(bindings.find(run)).thenReturn(Optional.of(binding));when(binding.actor()).thenReturn(new AgentId("9"));
        when(binding.owner()).thenReturn(SessionOwner.user(new SessionRef(run.tenantId(),row.getSessionId()),new AgentId("9"),new EndUserId(71)));
    }

    /** 连接等待只保存用户、原调用和选择引用。 */
    private static BrowserConnectionRequest row() {
        var row=new BrowserConnectionRequest();row.setRequestId(UUID.randomUUID().toString());row.setTenantId(7);row.setEndUserId(71);
        row.setSessionId("session-7");row.setRunId("run-7");row.setInvocationId("invocation-7");row.setReason("NO_SELECTION");row.setStatus("WAITING");return row;
    }

    /** 使用固定远未来授权期，在线与否由各场景明确指定。 */
    private static BrowserDevices.Device device(String id,String browser,boolean online) {
        return new BrowserDevices.Device(id,browser,"测试设备","2099-01-01T00:00:00Z",online);
    }

    /** 列表顺序和默认值均由场景输入，不能成为目标选择依据。 */
    private static BrowserDevices devices(String selected,BrowserDevices.Device... devices) {
        return new BrowserDevices(List.of(devices),"https://example.invalid/install",selected);
    }
}
