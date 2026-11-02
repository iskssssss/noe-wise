package com.skillforge.institution.browser;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolInteractionResult;
import com.skillforge.execution.browser.BrowserDevices;
import com.skillforge.execution.browser.BrowserReceipt;
import com.skillforge.execution.browser.BrowserRequest;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.port.*;
import com.skillforge.runtime.api.port.ToolPortal.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 验证等待发生在实际派发之前，以及恢复只能使用原请求冻结的目标。 */
class BrowserStartToolHandlerTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final BrowserConnectionService connections = mock(BrowserConnectionService.class);
    private final BrowserConnectionStore store = mock(BrowserConnectionStore.class);
    private final BrowserClient client = mock(BrowserClient.class);
    private final BrowserMapper receipts = mock(BrowserMapper.class);
    private final BrowserStartToolHandler handler = new BrowserStartToolHandler(connections, store, client, receipts, json);

    @Test void missingSelectionWaitsWithoutStartingAndInvalidArgumentsNeverReadDevices() throws Exception {
        var devices = new BrowserDevices(List.of(), "https://install.example", null);
        var selection = new BrowserConnectionService.Selection(devices, null, "NO_SELECTION");
        when(connections.inspect("7", "1", null)).thenReturn(selection);
        var row = request("WAITING"); when(connections.waitFor(any(), eq(selection))).thenReturn(row);
        var waiting = assertInstanceOf(PlatformToolInteractionResult.InputRequired.class, handler.invoke(Map.of(), context()));
        assertEquals(Optional.of(row.getRequestId()), waiting.requestState());
        assertEquals(row.getRequestId(), json.readTree(waiting.inputRequests().get("browser-connection")
                .params().canonicalJson()).path("connection_request_id").asText());
        verifyNoInteractions(client, receipts);
        clearInvocations(connections);
        for (var args : List.of(Map.<String,Object>of("browser", 123), Map.<String,Object>of("path", "/private")))
            assertInstanceOf(PlatformToolInteractionResult.Rejected.class, handler.invoke(args, context()));
        verifyNoInteractions(connections);
    }

    @Test void onlineDefaultStartsOnceWithExactRunAndDevice() throws Exception {
        var device = new BrowserDevices.Device("device", "browser", "My browser", Instant.now().plusSeconds(600).toString(), true);
        var other = new BrowserDevices.Device("other-device", "other-browser", "Other browser", device.expiresAt(), true);
        when(client.devices("7", "1")).thenReturn(new BrowserDevices(List.of(other, device), "https://install.example", "browser"));
        when(client.execute(any())).thenReturn(new BrowserReceipt(null,"invocation","start","task","session","run",
                "now","active",null,"browser",null,json.createObjectNode()));
        assertInstanceOf(PlatformToolInteractionResult.Completed.class, realConnectionHandler().invoke(Map.of(), context()));
        var sent = ArgumentCaptor.forClass(BrowserRequest.class); verify(client, times(1)).execute(sent.capture());
        assertEquals("browser", ((com.skillforge.execution.browser.BrowserParameters.StartParameters)sent.getValue().parameters()).browser());
        assertEquals("invocation", sent.getValue().invocation()); assertEquals("run", sent.getValue().run());
        verify(client, times(1)).devices("7", "1");
        verifyNoInteractions(store, connections);
    }

    /** 未选默认设备时，即使有在线设备也只建立准确等待，不按列表位置代选。 */
    @Test void noDefaultSelectionUsesRealServiceToWaitWithoutStartingAnyOnlineDevice() throws Exception {
        var online = new BrowserDevices.Device("online-device", "online-browser", "Online", "2099-01-01T00:00:00Z", true);
        when(client.devices("7", "1")).thenReturn(new BrowserDevices(List.of(online), "https://install.example", null));
        when(store.create(any())).thenAnswer(call -> call.getArgument(0));
        var waiting = assertInstanceOf(PlatformToolInteractionResult.InputRequired.class,
                realConnectionHandler().invoke(Map.of(), context()));
        var created = ArgumentCaptor.forClass(BrowserConnectionRequest.class); verify(store).create(created.capture());
        var row = created.getValue();
        assertEquals("WAITING", row.getStatus()); assertEquals("NO_SELECTION", row.getReason());
        assertEquals(7L, row.getTenantId()); assertEquals(1L, row.getEndUserId());
        assertEquals("session", row.getSessionId()); assertEquals("run", row.getRunId()); assertEquals("invocation", row.getInvocationId());
        assertNull(row.getDeviceId()); assertNull(row.getBrowserId()); assertTrue(row.isMakeDefault());
        assertEquals(Optional.of(row.getRequestId()), waiting.requestState());
        var request = waiting.inputRequests().get("browser-connection");
        assertEquals(ToolInputMethod.ELICITATION_CREATE, request.method());
        assertEquals(BrowserConnectionService.REQUEST_SCHEMA, request.params().schemaId());
        assertEquals(row.getRequestId(), json.readTree(request.params().canonicalJson()).path("connection_request_id").asText());
        verify(client, never()).execute(any()); verifyNoInteractions(receipts, connections);
    }

    /** 默认设备离线时冻结该目标，另一在线设备不能使首次调用误发到新目标。 */
    @Test void offlineDefaultUsesRealServiceToFreezeOriginalTargetDespiteAnotherOnlineDevice() throws Exception {
        var offline = new BrowserDevices.Device("offline-device", "offline-browser", "Offline default", "2099-01-01T00:00:00Z", false);
        var other = new BrowserDevices.Device("other-device", "other-browser", "Online other", offline.expiresAt(), true);
        when(client.devices("7", "1")).thenReturn(new BrowserDevices(List.of(other, offline), "https://install.example", "offline-browser"));
        when(store.create(any())).thenAnswer(call -> call.getArgument(0));
        var waiting = assertInstanceOf(PlatformToolInteractionResult.InputRequired.class,
                realConnectionHandler().invoke(Map.of(), context()));
        var created = ArgumentCaptor.forClass(BrowserConnectionRequest.class); verify(store).create(created.capture());
        var row = created.getValue();
        assertEquals("OFFLINE", row.getReason()); assertEquals("WAITING", row.getStatus());
        assertEquals("offline-device", row.getDeviceId()); assertEquals("offline-browser", row.getBrowserId());
        assertFalse(row.isMakeDefault()); assertEquals(Optional.of(row.getRequestId()), waiting.requestState());
        assertEquals("OFFLINE", json.readTree(waiting.inputRequests().get("browser-connection").params().canonicalJson()).path("reason").asText());
        verify(client, never()).execute(any()); verifyNoInteractions(receipts, connections);
    }

    /** 错误显式值不能被解释为省略browser，即使本人默认设备在线也不得fallback启动。 */
    @Test void invalidExplicitBrowserUsesRealServiceToRejectInsteadOfFallingBackToOnlineDefault() throws Exception {
        var online = new BrowserDevices.Device("default-device", "default-browser", "Default", "2099-01-01T00:00:00Z", true);
        when(client.devices("7", "1")).thenReturn(new BrowserDevices(List.of(online), "https://install.example", "default-browser"));
        var actual = realConnectionHandler();
        for (String browser : List.of("default", "missing-browser")) {
            var result = assertInstanceOf(PlatformToolInteractionResult.Rejected.class,
                    actual.invoke(Map.of("browser", browser), context()));
            assertEquals("browser_device_not_owned", result.output().get("error_code"));
        }
        verify(client, times(2)).devices("7", "1"); verify(client, never()).execute(any());
        clearInvocations(client);
        for (var arguments : List.of(Map.<String,Object>of("browser", ""), Map.<String,Object>of("browser", "--default"),
                java.util.Collections.<String,Object>singletonMap("browser", null))) {
            var result = assertInstanceOf(PlatformToolInteractionResult.Rejected.class, actual.invoke(arguments, context()));
            assertEquals("browser_start_parameters", result.output().get("error_code"));
        }
        verifyNoInteractions(client, store, receipts, connections);
    }

    @Test void workflowWithoutUsableDeviceRejectsBeforeAnyWaitOrTaskIsCreated() throws Exception {
        var device = new BrowserDevices.Device("device", "browser", "My browser", Instant.now().plusSeconds(600).toString(), false);
        var devices = new BrowserDevices(List.of(device), "https://install.example", "browser");
        for (String reason : List.of("NO_SELECTION", "OFFLINE", "AUTHORIZATION_REQUIRED")) {
            when(connections.inspect("7", "1", null)).thenReturn(new BrowserConnectionService.Selection(devices, device, reason));
            var rejected = assertInstanceOf(PlatformToolInteractionResult.Rejected.class, handler.invoke(Map.of(), workflowContext()));
            assertEquals("browser_connection_workflow_not_supported", rejected.output().get("error_code"));
        }
        verify(connections, never()).waitFor(any(), any());
        verifyNoInteractions(store, client, receipts);
    }

    @Test void workflowOnlineDeviceStillStartsDirectlyWithoutConnectionWait() throws Exception {
        var device = new BrowserDevices.Device("device", "browser", "My browser", Instant.now().plusSeconds(600).toString(), true);
        when(connections.inspect("7", "1", null)).thenReturn(new BrowserConnectionService.Selection(
                new BrowserDevices(List.of(device), "https://install.example", "browser"), device, null));
        when(client.execute(any())).thenReturn(new BrowserReceipt(null,"invocation","start","task","session","run",
                "now","active",null,"browser",null,json.createObjectNode()));
        assertInstanceOf(PlatformToolInteractionResult.Completed.class, handler.invoke(Map.of(), workflowContext()));
        var sent = ArgumentCaptor.forClass(BrowserRequest.class);verify(client).execute(sent.capture());
        assertEquals("browser", ((com.skillforge.execution.browser.BrowserParameters.StartParameters)sent.getValue().parameters()).browser());
        verify(connections, never()).waitFor(any(), any());verifyNoInteractions(store);
    }

    @Test void resumeRejectsForgedTargetAndKeepsCancellationBeforeDispatch() throws Exception {
        var row = request("RESUMED"); row.setDeviceId("device"); row.setBrowserId("browser");
        when(store.find(7, row.getRequestId())).thenReturn(row);
        var forged = responses(row, "another-browser", false);
        assertInstanceOf(PlatformToolInteractionResult.Rejected.class,
                handler.resume(Map.of(), forged, Optional.of(row.getRequestId()), context()));
        row.setStatus("CANCELLED");
        var cancelled = assertInstanceOf(PlatformToolInteractionResult.Rejected.class,
                handler.resume(Map.of(), responses(row, "browser", true), Optional.of(row.getRequestId()), context()));
        assertEquals("browser_connection_cancelled", cancelled.output().get("error_code"));
        verifyNoInteractions(client, receipts);
    }

    /** 组合真实选择/等待服务与Handler，仅替换外部客户端及持久化端口，复用当前可信Runtime上下文。 */
    private BrowserStartToolHandler realConnectionHandler() {
        var service = new BrowserConnectionService(store, client, mock(BrowserDeviceAuditService.class),
                mock(com.skillforge.institution.runtime.AgentRunBindings.class), json);
        return new BrowserStartToolHandler(service, store, client, receipts, json);
    }

    private Map<String, ToolInputResponse> responses(BrowserConnectionRequest row, String browser, boolean cancelled) throws Exception {
        return Map.of("browser-connection", new ToolInputResponse(ToolInputMethod.ELICITATION_CREATE,
                new TypedPayload(BrowserConnectionService.RESPONSE_SCHEMA, json.writeValueAsString(
                        new BrowserConnectionService.Response(row.getRequestId(), row.getDeviceId(), browser, cancelled)))));
    }

    private static BrowserConnectionRequest request(String state) {
        var row = new BrowserConnectionRequest(); row.setRequestId("b4458ce9-97ec-45b7-a1fc-a39f1df3ef91");
        row.setTenantId(7); row.setEndUserId(1); row.setSessionId("session"); row.setRunId("run");
        row.setInvocationId("invocation"); row.setReason("NO_SELECTION"); row.setStatus(state); return row;
    }

    private static PlatformToolExecutionContext context() {
        var owner = SessionOwner.user(new SessionRef(new TenantId("7"), "session"), new AgentId("agent"), new EndUserId(1));
        var run = new RunIdentity(owner, new AgentId((owner).target().id()), new InputId("input"), new RunRef(new TenantId("7"), "run"), new TraceEnvelope("trace", "span", new InputId("input"), Optional.empty()), new ResultRef("identity"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (new InputId("input")).value()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(owner.sessionRef().tenantId(), owner.requireUser()),
                owner, java.util.Set.of(), 1, new ResultRef("identity"));
        return new PlatformToolExecutionContext(new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(60), () -> false)), "invocation", "attempt");
    }

    private static PlatformToolExecutionContext workflowContext() {
        var base = context();
        return new PlatformToolExecutionContext(base.callContext(), base.invocationId(), base.attemptId(), false, true);
    }
}
