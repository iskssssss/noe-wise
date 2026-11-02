package com.skillforge.institution.browser;

import com.skillforge.common.context.TenantContext;
import com.skillforge.execution.browser.BrowserDevices;
import com.skillforge.institution.runtime.BrowserConnectionRuntimeBridge;
import com.skillforge.institution.runtime.RuntimeWorkerRecoveryMapper;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.starter.AgentRuntimeProperties;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 有界观察准确设备并经 Session Inbox 恢复；无网页连接时同样工作。 */
@Component
public class BrowserConnectionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(BrowserConnectionWorker.class);
    private final BrowserConnectionStore store;
    private final BrowserConnectionService connections;
    private final BrowserConnectionRuntimeBridge runtime;
    private final BrowserClient client;
    private final RuntimeWorkerRecoveryMapper tenants;
    private final AgentRuntimeProperties properties;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    /** 只影响扫描公平性，恢复权威状态始终来自持久表；服务重启从头扫描。 */
    private final Map<String, String> scanAfter = new java.util.concurrent.ConcurrentHashMap<>();

    /** 使用当前活跃机构目录和既有 Runtime 开关；每次机构 SQL 显式绑定 RLS。 */
    public BrowserConnectionWorker(BrowserConnectionStore store, BrowserConnectionService connections,
            BrowserConnectionRuntimeBridge runtime, BrowserClient client, RuntimeWorkerRecoveryMapper tenants,
            AgentRuntimeProperties properties, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.store = store; this.connections = connections; this.runtime = runtime;
        this.client = client; this.tenants = tenants; this.properties = properties; this.json = json;
    }

    /** 只扫描尚未提交恢复的请求，按 owner 在本批复用一次设备观察。 */
    @Scheduled(fixedDelay = 2000, initialDelay = 3000)
    public void poll() {
        if (!properties.getWorkers().isAutoStartup()) return;
        for (String tenant : tenants.activeTenants()) TenantContext.callWithTenant(tenant, () -> {
            Map<Long, BrowserDevices> devices = new HashMap<>();
            var pending = store.pendingAfter(Long.parseLong(tenant), scanAfter.getOrDefault(tenant, ""));
            if (pending.isEmpty()) scanAfter.remove(tenant);
            else scanAfter.put(tenant, pending.getLast().getRequestId());
            for (var row : pending) {
                try { advance(row, devices); }
                catch (SecurityException denied) {
                    String code = denied instanceof com.skillforge.runtime.api.RuntimeAccessException rejected
                            ? rejected.reasonCode() : denied.getMessage();
                    if (code == null || !code.matches("[A-Za-z0-9_.:-]{1,128}")) code = "access_denied";
                    LOG.warn("浏览器连接恢复授权拒绝 requestId={} failureCode={} failureAt={}", row.getRequestId(),
                            code, denied.getStackTrace().length == 0 ? "unavailable" : denied.getStackTrace()[0]);
                    close(row, "FAILED", "browser_connection_access_denied");
                }
                catch (Exception unavailable) {
                    if (!"browser_connection_observation_unavailable".equals(row.getError())) {
                        row.setError("browser_connection_observation_unavailable"); store.save(row);
                        LOG.warn("浏览器连接恢复暂缓 requestId={} failureType={}", row.getRequestId(), unavailable.getClass().getSimpleName());
                    }
                }
            }
            return null;
        });
    }

    /** 既有输入优先去重；目标冻结后只提交同一稳定恢复输入。 */
    void advance(BrowserConnectionRequest row, Map<Long, BrowserDevices> observed) throws Exception {
        String tenant = Long.toString(row.getTenantId()), user = Long.toString(row.getEndUserId());
        var state = runtime.inspect(tenant, row.getRunId(), row.getInvocationId(), row.getRequestId());
        boolean cancelling = "CANCELLING".equals(row.getStatus());
        if (state.resumeAccepted()) { close(row, cancelling ? "CANCELLED" : "RESUMED", null); return; }
        if (state.terminal()) { close(row, "CANCELLED", "browser_connection_run_ended"); return; }
        if (state.pending() == null) return; // Provider 的等待结果尚未由 Core 持久化，不提前唤醒。
        var wait = BrowserConnectionWaitSnapshot.of(state.pending());
        if (row.getWaitSnapshot() == null) {
            row.setWaitSnapshot(json.writeValueAsString(wait));
            if (!store.save(row)) return;
            row = store.find(row.getTenantId(), row.getRequestId());
        } else if (!wait.equals(json.readValue(row.getWaitSnapshot(), BrowserConnectionWaitSnapshot.class))) {
            close(row, "FAILED", "browser_connection_wait_changed"); return;
        }
        if ("WAITING".equals(row.getStatus())) {
            var devices = observed.get(row.getEndUserId());
            if (devices == null) { devices = client.devices(tenant, user); observed.put(row.getEndUserId(), devices); }
            if (!connections.ready(row, devices)) return;
            row = store.find(row.getTenantId(), row.getRequestId());
        }
        if ("READY".equals(row.getStatus())) {
            row.setStatus("RESUMING"); row.setError(null);
            if (!store.save(row)) return;
            row = store.find(row.getTenantId(), row.getRequestId());
        }
        if (!cancelling && !"RESUMING".equals(row.getStatus())) return;
        if (!cancelling && row.isMakeDefault()) client.preference(tenant, user, row.getDeviceId(), null, true);
        runtime.resume(tenant, row.getRunId(), row.getRequestId(), state.pending(),
                new TypedPayload(BrowserConnectionService.RESPONSE_SCHEMA, connections.response(row, cancelling)), row.getCreatedAt());
        close(row, cancelling ? "CANCELLED" : "RESUMED", null);
    }

    /** CAS 竞争不回写旧选择；下一轮读取最新事实。 */
    private void close(BrowserConnectionRequest row, String status, String error) {
        row.setStatus(status); row.setError(error); store.save(row);
    }
}
