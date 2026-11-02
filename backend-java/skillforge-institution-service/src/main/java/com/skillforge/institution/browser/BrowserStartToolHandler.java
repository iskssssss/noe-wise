package com.skillforge.institution.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.connector.PlatformInteractiveToolHandler;
import com.skillforge.core.capability.connector.PlatformMediaToolHandler;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolInteractionResult;
import com.skillforge.execution.browser.BrowserParameters;
import com.skillforge.execution.browser.BrowserRequest;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputMethod;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputRequest;
import com.skillforge.runtime.api.port.ToolPortal.ToolInputResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 在首次启动前持久等待个人设备；恢复只消费原 Tool wait 和服务端确认的准确目标。 */
@Component
public class BrowserStartToolHandler implements PlatformInteractiveToolHandler, PlatformMediaToolHandler {
    private final BrowserConnectionService connections;
    private final BrowserConnectionStore store;
    private final BrowserClient client;
    private final BrowserMapper receipts;
    private final ObjectMapper json;

    /** 启动与其他浏览器命令共用同一服务和回执来源。 */
    public BrowserStartToolHandler(BrowserConnectionService connections, BrowserConnectionStore store,
            BrowserClient client, BrowserMapper receipts, ObjectMapper json) {
        this.connections = connections; this.store = store; this.client = client; this.receipts = receipts; this.json = json;
    }

    @Override public String toolName() { return "browser_start"; }

    /** 无设备时只登记输入等待；不向 Browser service 提交 start。 */
    @Override public PlatformToolInteractionResult invoke(Map<String, Object> arguments,
            PlatformToolExecutionContext context) throws Exception {
        if (!valid(arguments)) return rejected("browser_start_parameters");
        String explicit = (String) arguments.get("browser");
        if (explicit != null && (explicit.isBlank() || explicit.startsWith("-"))) return rejected("browser_start_parameters");
        String tenant = context.run().owner().sessionRef().tenantId().value();
        String user = Long.toString(context.run().owner().requireUser().value());
        BrowserConnectionService.Selection choice;
        try { choice = connections.inspect(tenant, user, explicit); }
        catch (com.skillforge.core.shared.exception.ApiException denied) {
            if (denied.getStatus().value() == 403) return rejected("browser_device_not_owned");
            throw denied;
        }
        if (choice.reason() == null) return start(choice.device().browserId(), context);
        // Workflow 节点拥有独立 Execution 等待协议，不能创建仅能由 Agent Tool wait 恢复的连接。
        if (context.workflowNode()) return rejected("browser_connection_workflow_not_supported");
        var request = connections.waitFor(context, choice);
        var params = new BrowserConnectionService.Request(request.getRequestId(), request.getReason(), "此任务需要连接你的浏览器");
        String value = json.writeValueAsString(params);
        return new PlatformToolInteractionResult.InputRequired(
                Map.of(BrowserConnectionService.INPUT_KEY, new ToolInputRequest(ToolInputMethod.ELICITATION_CREATE,
                        new TypedPayload(BrowserConnectionService.REQUEST_SCHEMA, value))), Optional.of(request.getRequestId()));
    }

    /** 服务端确认及 Runtime 冻结的响应必须同时命中同一请求和目标，不读取新的默认配置。 */
    @Override public PlatformToolInteractionResult resume(Map<String, Object> arguments,
            Map<String, ToolInputResponse> inputResponses, Optional<String> requestState,
            PlatformToolExecutionContext context) throws Exception {
        if (!valid(arguments) || requestState.isEmpty() || inputResponses.size() != 1)
            return rejected("browser_connection_response_invalid");
        var response = inputResponses.get(BrowserConnectionService.INPUT_KEY);
        if (response == null || response.method() != ToolInputMethod.ELICITATION_CREATE
                || !response.result().schemaId().equals(BrowserConnectionService.RESPONSE_SCHEMA))
            return rejected("browser_connection_response_invalid");
        var body = json.readValue(response.result().canonicalJson(), BrowserConnectionService.Response.class);
        String tenant = context.run().owner().sessionRef().tenantId().value();
        var row = store.find(Long.parseLong(tenant), requestState.orElseThrow());
        if (row == null || !row.getRequestId().equals(body.connectionRequestId())
                || row.getEndUserId() != context.run().owner().requireUser().value()
                || !row.getSessionId().equals(context.run().owner().sessionRef().sessionId())
                || !row.getRunId().equals(context.run().runRef().runId()) || !row.getInvocationId().equals(context.invocationId()))
            return rejected("browser_connection_response_invalid");
        if (body.cancelled() && Set.of("CANCELLING", "CANCELLED").contains(row.getStatus()))
            return rejected("browser_connection_cancelled");
        if (body.cancelled() || !Set.of("READY", "RESUMING", "RESUMED").contains(row.getStatus())
                || !java.util.Objects.equals(row.getDeviceId(), body.deviceId())
                || !java.util.Objects.equals(row.getBrowserId(), body.browserId()) || row.getBrowserId() == null)
            return rejected("browser_connection_response_invalid");
        return start(row.getBrowserId(), context);
    }

    /** 完成实际启动并保存公开回执；未知结果继续由既有账本收敛。 */
    private PlatformToolInteractionResult start(String browser, PlatformToolExecutionContext context) throws Exception {
        var owner = context.run().owner();
        String tenant = owner.sessionRef().tenantId().value();
        var request = new BrowserRequest(tenant, Long.toString(owner.requireUser().value()), context.invocationId(), null,
                "start", new BrowserParameters.StartParameters(browser), owner.sessionRef().sessionId(),
                context.run().runRef().runId(), context.deadlineAt());
        var receipt = client.execute(request);
        boolean conflict = "rejected".equals(receipt.status()) && receipt.result() != null
                && "browser_invocation_conflict".equals(receipt.result().path("error").asText());
        if (!conflict) TenantContext.callWithTenant(tenant, () -> {
            try { receipts.receipt(Long.parseLong(tenant), owner.requireUser().value(), context.invocationId(), receipt.task(),
                    request.session(), request.run(), "start", receipt.status(), json.writeValueAsString(receipt)); }
            catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return null;
        });
        if ("unknown".equals(receipt.status())) throw new IOException("browser_result_unknown");
        Map<String, Object> output = json.convertValue(receipt, new TypeReference<LinkedHashMap<String, Object>>() { });
        // 公开回执的可选字段不进入 PLATFORM 顶层非空值映射；完整回执已按原格式持久化。
        output.values().removeIf(java.util.Objects::isNull);
        output.put("content", BrowserToolResults.contents(receipt));
        boolean rejected = Set.of("failed", "rejected", "cancelled", "cleanup_failed").contains(receipt.status());
        output.put("isError", rejected);
        return rejected
                ? new PlatformToolInteractionResult.Rejected(output) : new PlatformToolInteractionResult.Completed(output);
    }

    /** 参数解析在任何设备或网络访问之前完成。 */
    private static boolean valid(Map<String, Object> args) {
        return args != null && Set.of("browser").containsAll(args.keySet())
                && (!args.containsKey("browser") || args.get("browser") instanceof String);
    }

    private static PlatformToolInteractionResult rejected(String code) {
        return new PlatformToolInteractionResult.Rejected(BrowserToolResults.rejected(code));
    }
}
