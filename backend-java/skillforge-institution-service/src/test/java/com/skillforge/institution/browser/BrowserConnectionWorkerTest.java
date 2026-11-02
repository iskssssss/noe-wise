package com.skillforge.institution.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.institution.runtime.BrowserConnectionRuntimeBridge;
import com.skillforge.institution.runtime.RuntimeWorkerRecoveryMapper;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

/** 验证连接恢复不会因重复观察或 Run 终结重新提交输入。 */
class BrowserConnectionWorkerTest {
    @Test void acceptedInputClosesAfterCrashWithoutRepeatingBrowserOrResume() throws Exception {
        var store = mock(BrowserConnectionStore.class); var service = mock(BrowserConnectionService.class);
        var runtime = mock(BrowserConnectionRuntimeBridge.class); var client = mock(BrowserClient.class);
        var worker = new BrowserConnectionWorker(store, service, runtime, client, mock(RuntimeWorkerRecoveryMapper.class),
                new AgentRuntimeProperties(), new ObjectMapper());
        var row = row(); row.setStatus("RESUMING");
        when(runtime.inspect("7", "run", "invocation", row.getRequestId()))
                .thenReturn(new BrowserConnectionRuntimeBridge.Snapshot(true, false, null));
        worker.advance(row, new HashMap<>());
        assertEquals("RESUMED", row.getStatus()); verify(store).save(row);
        verify(runtime, never()).resume(anyString(),anyString(),anyString(),any(),any(),any());
        verifyNoInteractions(client, service);
    }

    @Test void cancelledRunClosesWaitingAndDoesNotStartOrPair() throws Exception {
        var store = mock(BrowserConnectionStore.class); var service = mock(BrowserConnectionService.class);
        var runtime = mock(BrowserConnectionRuntimeBridge.class); var client = mock(BrowserClient.class);
        var worker = new BrowserConnectionWorker(store, service, runtime, client, mock(RuntimeWorkerRecoveryMapper.class),
                new AgentRuntimeProperties(), new ObjectMapper());
        var row = row();
        when(runtime.inspect("7", "run", "invocation", row.getRequestId()))
                .thenReturn(new BrowserConnectionRuntimeBridge.Snapshot(false, true, null));
        worker.advance(row, new HashMap<>());
        assertEquals("CANCELLED", row.getStatus()); verifyNoInteractions(client, service);
    }

    private static BrowserConnectionRequest row() {
        var row = new BrowserConnectionRequest(); row.setRequestId("b4458ce9-97ec-45b7-a1fc-a39f1df3ef91");
        row.setTenantId(7); row.setEndUserId(1); row.setRunId("run"); row.setInvocationId("invocation"); row.setStatus("WAITING"); return row;
    }
}
