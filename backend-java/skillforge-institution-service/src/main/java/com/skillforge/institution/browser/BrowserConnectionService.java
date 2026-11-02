package com.skillforge.institution.browser;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.execution.browser.BrowserConnectionGrant;
import com.skillforge.execution.browser.BrowserDevices;
import com.skillforge.institution.runtime.AgentRunBindings;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 个人设备与准确工具等待的连接编排；配对和选择只由已认证用户明确发起。 */
@Service
public class BrowserConnectionService {
    public static final String REQUEST_SCHEMA = "skillforge.browser.connection-request.v1";
    public static final String RESPONSE_SCHEMA = "skillforge.browser.connection-response.v1";
    public static final String INPUT_KEY = "browser-connection";
    private static final Set<String> FROZEN = Set.of("READY", "RESUMING", "RESUMED");
    private final BrowserConnectionStore store;
    private final BrowserClient client;
    private final BrowserDeviceAuditService audit;
    private final AgentRunBindings bindings;
    private final ObjectMapper json;

    /** 组合宿主持久化、内部浏览器客户端和现有来源授权。 */
    public BrowserConnectionService(BrowserConnectionStore store, BrowserClient client,
            BrowserDeviceAuditService audit, AgentRunBindings bindings, ObjectMapper json) {
        this.store = store; this.client = client; this.audit = audit; this.bindings = bindings; this.json = json;
    }

    /** 首次派发前解析设备；未知服务错误传播，不能生成假连接等待。 */
    public Selection inspect(String tenant, String user, String explicit) throws Exception {
        BrowserDevices devices = client.devices(tenant, user);
        String browser = explicit != null ? explicit : devices.selectedBrowserId();
        if (browser == null || browser.isBlank()) return new Selection(devices, null, "NO_SELECTION");
        var device = devices.items().stream().filter(value -> browser.equals(value.browserId())).findFirst();
        if (device.isEmpty()) {
            if (explicit != null) throw new ApiException(HttpStatus.FORBIDDEN, "browser_device_not_owned");
            return new Selection(devices, null, "AUTHORIZATION_REQUIRED");
        }
        var chosen = device.orElseThrow();
        if (!Instant.parse(chosen.expiresAt()).isAfter(Instant.now()))
            return new Selection(devices, chosen, "AUTHORIZATION_REQUIRED");
        return new Selection(devices, chosen, chosen.connected() ? null : "OFFLINE");
    }

    /** 仅在实际启动尚未派发时保存稳定连接请求。 */
    public BrowserConnectionRequest waitFor(PlatformToolExecutionContext context, Selection selection) {
        var owner = context.run().owner();
        String key = owner.sessionRef().tenantId().value() + ":" + context.run().runRef().runId() + ":" + context.invocationId();
        var row = new BrowserConnectionRequest();
        row.setRequestId(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString());
        row.setTenantId(Long.parseLong(owner.sessionRef().tenantId().value()));
        row.setEndUserId(owner.requireUser().value()); row.setSessionId(owner.sessionRef().sessionId());
        row.setRunId(context.run().runRef().runId()); row.setInvocationId(context.invocationId());
        row.setReason(selection.reason()); row.setStatus("WAITING");
        row.setMakeDefault(selection.devices().selectedBrowserId() == null);
        if (selection.device() != null && "OFFLINE".equals(selection.reason())) {
            row.setDeviceId(selection.device().deviceId()); row.setBrowserId(selection.device().browserId());
        }
        return store.create(row);
    }

    /** 读取时同时检查个人、来源会话与当前授权，不能仅凭 UUID 读取其他 Run。 */
    public BrowserConnectionRequest require(String tenant, String user, String id, String selectedSession) {
        UUID.fromString(id);
        var row = store.find(Long.parseLong(tenant), id);
        if (row == null) throw new ApiException(HttpStatus.NOT_FOUND, "browser_connection_not_found");
        if (row.getEndUserId() != Long.parseLong(user)) throw new ApiException(HttpStatus.FORBIDDEN, "browser_connection_not_owned");
        var binding = bindings.find(new RunRef(new TenantId(tenant), row.getRunId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "browser_connection_source_unavailable"));
        if (!binding.owner().sessionRef().sessionId().equals(row.getSessionId())
                || binding.owner().requireUser().value() != row.getEndUserId()
                || selectedSession != null && !selectedSession.equals(binding.scope().origin().sessionRef().sessionId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "browser_connection_source_mismatch");
        bindings.authorizeRead(binding, binding.actor().value());
        return row;
    }

    /** 公开只读连接状态；用户界面轮询不签发新凭据。 */
    public BrowserConnectionView view(String tenant, String user, String id, String session) throws Exception {
        var row = require(tenant, user, id, session);
        return new BrowserConnectionView(row.getRequestId(), row.getStatus(), row.getReason(), row.getDeviceId(),
                row.getBrowserId(), row.isMakeDefault(), client.devices(tenant, user), row.getError());
    }

    /** 用户明确请求一次配对；先落派发事实，未知结果不重新签发。 */
    public JsonNode pair(String tenant, String user, String id, String session, boolean makeDefault) throws Exception {
        var row = require(tenant, user, id, session);
        if (!"WAITING".equals(row.getStatus()) || row.isPairDispatched()) throw conflict();
        row.setPairDispatched(true); row.setMakeDefault(makeDefault);
        // 用户选择新配对后，旧离线目标不能先抢跑原请求。
        row.setDeviceId(null); row.setBrowserId(null);
        if (!store.save(row)) throw conflict();
        return audit.pair(tenant, user, id);
    }

    /** 用户精确选择本人设备；与默认设置分开，不按列表位置选择。 */
    public BrowserDevices select(String tenant, String user, String id, String session,
            String deviceId, boolean makeDefault) throws Exception {
        var row = require(tenant, user, id, session);
        if (!"WAITING".equals(row.getStatus())) {
            if (FROZEN.contains(row.getStatus()) && deviceId.equals(row.getDeviceId()) && makeDefault == row.isMakeDefault())
                return client.devices(tenant, user);
            throw conflict();
        }
        var devices = client.devices(tenant, user);
        var chosen = devices.items().stream().filter(device -> deviceId.equals(device.deviceId())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "browser_device_not_owned"));
        if (!Instant.parse(chosen.expiresAt()).isAfter(Instant.now()))
            throw new ApiException(HttpStatus.CONFLICT, "browser_not_authorized");
        row.setDeviceId(chosen.deviceId()); row.setBrowserId(chosen.browserId()); row.setMakeDefault(makeDefault);
        if (!store.save(row)) throw conflict();
        return devices;
    }

    /** 取消连接等待，不撤销已建立的独立设备，也不伪造浏览器任务取消回执。 */
    public void cancel(String tenant, String user, String id, String session) {
        var row = require(tenant, user, id, session);
        if (Set.of("CANCELLING", "CANCELLED").contains(row.getStatus())) return;
        if (!"WAITING".equals(row.getStatus())) throw conflict();
        row.setStatus("CANCELLING"); if (!store.save(row)) throw conflict();
    }

    /** 后台只按明确设备或凭据摘要的准确配对结果收敛，原始凭据从不进入连接表。 */
    public boolean ready(BrowserConnectionRequest row, BrowserDevices devices) throws Exception {
        String tenant = Long.toString(row.getTenantId()), user = Long.toString(row.getEndUserId());
        String deviceId = row.getDeviceId();
        if (deviceId == null && row.isPairDispatched()) {
            BrowserConnectionGrant grant = client.connectionGrant(tenant, user, row.getRequestId());
            if (grant.status() == BrowserConnectionGrant.Status.UNKNOWN) return false;
            if (grant.status() == BrowserConnectionGrant.Status.PAIRED) deviceId = grant.deviceId();
        }
        if (deviceId == null) return false;
        String expected = deviceId;
        var selected = devices.items().stream().filter(value -> expected.equals(value.deviceId())).findFirst();
        if (selected.isEmpty()) return false;
        var device = selected.orElseThrow();
        if (!device.connected() || !Instant.parse(device.expiresAt()).isAfter(Instant.now())) return false;
        row.setDeviceId(device.deviceId()); row.setBrowserId(device.browserId()); row.setStatus("READY");
        return store.save(row);
    }

    /** 生成已冻结响应，只包含选择器与用户取消事实。 */
    public String response(BrowserConnectionRequest row, boolean cancelled) throws Exception {
        return json.writeValueAsString(new Response(row.getRequestId(), row.getDeviceId(), row.getBrowserId(), cancelled));
    }

    private static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "browser_connection_changed"); }

    /** 预检结果；reason 为空表示可直接启动，device 不是凭据。 */
    public record Selection(BrowserDevices devices, BrowserDevices.Device device, String reason) { }
    /** Tool 等待材料只含宿主请求引用和安全提示。 */
    public record Request(@JsonProperty("connection_request_id") String connectionRequestId, String reason, String message) { }
    /** 服务端冻结的 Tool 恢复正文，不接受该正文作为设备授权证明。 */
    public record Response(@JsonProperty("connection_request_id") String connectionRequestId,
            @JsonProperty("device_id") String deviceId, @JsonProperty("browser_id") String browserId, boolean cancelled) { }
}
