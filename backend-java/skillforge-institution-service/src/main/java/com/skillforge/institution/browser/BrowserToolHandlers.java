package com.skillforge.institution.browser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.connector.PlatformMediaToolHandler;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.core.capability.connector.PlatformToolHandler;
import com.skillforge.execution.browser.BrowserCommandContract;
import com.skillforge.execution.browser.BrowserParameters;
import com.skillforge.execution.browser.BrowserRequest;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 浏览器调用、停止和任务目录复用 PLATFORM 管道；交互式启动由 BrowserStartToolHandler 承担。 */
@Configuration
public class BrowserToolHandlers {
    private final BrowserClient client;
    private final BrowserMapper mapper;
    private final ObjectMapper json;
    private final BrowserScreenshotSink screenshots;
    private final BrowserTaskAccess taskAccess;

    public BrowserToolHandlers(BrowserClient client, BrowserMapper mapper, ObjectMapper json,
            BrowserScreenshotSink screenshots, BrowserTaskAccess taskAccess) {
        this.client = client; this.mapper = mapper; this.json = json; this.screenshots = screenshots; this.taskAccess = taskAccess;
    }

    @Bean public PlatformToolHandler browserCall() { return handler("browser_call"); }
    @Bean public PlatformToolHandler browserStop() { return handler("browser_stop"); }
    @Bean public PlatformToolHandler browserTasks() { return handler("browser_tasks"); }

    private PlatformToolHandler handler(String name) {
        return new PlatformMediaToolHandler() {
            @Override public String toolName() { return name; }

            @Override public Map<String, Object> execute(Map<String, Object> args,
                    PlatformToolExecutionContext ctx) throws Exception {
                Parsed command;
                try {
                    command = parse(name, args);
                } catch (ArgumentsRejected rejected) {
                    // 此作用域只解析模型参数，尚未访问浏览器；不能将之后的异常伪装成确定拒绝。
                    return BrowserToolResults.rejected(rejected.code, name);
                }
                var owner = ctx.run().owner();
                String tenant = owner.sessionRef().tenantId().value();
                String user = Long.toString(owner.requireUser().value());
                var states = client.taskStates(tenant, user);
                if (name.equals("browser_tasks"))
                    return BrowserToolResults.tasks(states.stream()
                            .filter(receipt -> taskAccess.readableForRun(ctx.run(), receipt)).toList(),
                            owner.sessionRef().sessionId());
                var source = states.stream().filter(receipt -> command.task().equals(receipt.task())).findFirst();
                if (source.isEmpty() || !taskAccess.readableForRun(ctx.run(), source.get()))
                    return BrowserToolResults.rejected("browser_task_source_unreadable", name);

                var request = new BrowserRequest(tenant, user, ctx.invocationId(), command.task(),
                        command.operation(), command.parameters(), owner.sessionRef().sessionId(),
                        ctx.run().runRef().runId(), ctx.deadlineAt());
                var receipt = client.execute(request);
                // 异体请求只得到临时拒绝；服务端保留原 invocation 事实，宿主投影也不能覆写它。
                boolean invocationConflict = "rejected".equals(receipt.status()) && receipt.result() != null
                        && "browser_invocation_conflict".equals(receipt.result().path("error").asText());
                if (!invocationConflict) {
                    String previous = TenantContext.getTenantId();
                    TenantContext.setTenantId(tenant);
                    try {
                        mapper.receipt(Long.parseLong(tenant), Long.parseLong(user), ctx.invocationId(),
                                receipt.task(), request.session(), request.run(), command.operation(),
                                receipt.status(), json.writeValueAsString(receipt));
                    } finally {
                        if (previous == null) TenantContext.clear(); else TenantContext.setTenantId(previous);
                    }
                }
                if ("unknown".equals(receipt.status())) throw new IOException("browser_result_unknown");
                // 停止原回执已完整保存；模型只消费固定有界投影，收尾预算不依赖任意 CLI 结果大小。
                if (command.operation().equals("stop")) return BrowserStopResult.project(receipt, json);
                Map<String, Object> result = json.convertValue(receipt, new TypeReference<LinkedHashMap<String, Object>>() {});
                var content = new ArrayList<Object>();
                content.addAll(BrowserToolResults.contents(receipt));
                if (command.operation().equals("screenshot") && "succeeded".equals(receipt.status()))
                    content.add(screenshots.register(owner.sessionRef(), ctx.run().runRef(), ctx.invocationId(),
                            client.screenshot(tenant, user, ctx.invocationId())));
                result.put("content", content);
                result.put("isError", Set.of("failed", "rejected", "cancelled", "cleanup_failed").contains(receipt.status()));
                return result;
            }
        };
    }

    /** 参数拒绝只携带本类定义的稳定错误码，不返回异常正文或原始参数。 */
    private Parsed parse(String name, Map<String, Object> args) throws IOException {
        if (args == null) throw new ArgumentsRejected("browser_arguments_invalid");
        if (name.equals("browser_tasks")) {
            if (!args.isEmpty()) throw new ArgumentsRejected("browser_tasks_parameters");
            return new Parsed("tasks", null, null);
        }
        String operation;
        String task = null;
        Map<?, ?> parameters;
        if (name.equals("browser_stop")) {
            if (!args.keySet().equals(Set.of("task"))) throw new ArgumentsRejected("browser_stop_parameters");
            task = task(args);
            operation = "stop";
            parameters = Map.of();
        } else {
            if (!args.keySet().equals(Set.of("task", "command"))) throw new ArgumentsRejected("browser_call_parameters");
            task = task(args);
            if (!(args.get("command") instanceof Map<?, ?> command)
                    || !command.keySet().equals(Set.of("operation", "parameters"))
                    || !(command.get("parameters") instanceof Map<?, ?> fields))
                throw new ArgumentsRejected("browser_command_parameters");
            if (!(command.get("operation") instanceof String value) || !BrowserCommandContract.operations().contains(value))
                throw new ArgumentsRejected("browser_operation_invalid");
            operation = value;
            parameters = fields;
        }
        var value = json.valueToTree(parameters);
        try {
            return new Parsed(operation, task, BrowserParameters.parse(operation, value));
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw new ArgumentsRejected("browser_parameters_invalid");
        }
    }

    private static String task(Map<String, Object> args) {
        if (!(args.get("task") instanceof String value)) throw new ArgumentsRejected("browser_task_invalid");
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new ArgumentsRejected("browser_task_invalid");
        }
        return value;
    }

    private record Parsed(String operation, String task, BrowserParameters.Parameters parameters) { }
    private static final class ArgumentsRejected extends RuntimeException {
        private final String code;
        private ArgumentsRejected(String code) { super(code); this.code = code; }
    }
}
