package com.skillforge.institution.browser;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.skillforge.execution.browser.BrowserCommandContract;
import com.skillforge.execution.browser.BrowserParameters;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import com.skillforge.runtime.session.SessionSchemaValidation;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.io.IOException;
import java.util.UUID;

/** 冻结浏览器命令的当前读取资格；仅分类已核证的纯观察，不授予执行、并行或重试权限。 */
public final class BrowserToolReadPolicy {
    /** 固定协议再次校验命令类型，不能因原冻结 schema 较宽松而接受隐式类型转换。 */
    private static final TypedPayload COMMAND_SCHEMA = new TypedPayload("json-schema",
            new ObjectMapper().valueToTree(BrowserCommandContract.schema()).toString());
    /** 分类只读取完整规范参数，拒绝重复键和尾随正文。 */
    private static final ObjectReader ARGUMENTS = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readerFor(JsonNode.class);

    private BrowserToolReadPolicy() { }

    /**
     * 仅消费原 Prepared 的完整目标、绑定、schema 与规范参数，不查询当前目录或外部状态。
     * 参数非法、非官方平台绑定和会触碰页面的命令均不获得当前读取资格。
     */
    public static boolean currentRead(SessionToolDirectory.Entry frozen, TypedPayload arguments) {
        var target = frozen.target();
        var binding = frozen.binding();
        if (binding.providerType() != ToolBinding.ProviderType.PLATFORM
                || !"browser_call".equals(binding.providerRef()) || !"browser_call".equals(binding.toolName())
                || target.confirmation() != ToolConfirmationPolicy.NEVER || !target.modelInvocable()) return false;
        try {
            SessionSchemaValidation.validate(target.inputSchema(), arguments);
            JsonNode input = ARGUMENTS.readValue(arguments.canonicalJson());
            if (!input.isObject() || input.size() != 2 || !input.has("task") || !input.has("command")
                    || !input.path("task").isTextual()) return false;
            String task = input.path("task").textValue();
            if (!UUID.fromString(task).toString().equals(task)) return false;
            JsonNode command = input.path("command");
            SessionSchemaValidation.validate(COMMAND_SCHEMA,
                    new TypedPayload("application/json", command.toString()));
            return switch (BrowserParameters.parse(command.path("operation").textValue(), command.path("parameters"))) {
                case BrowserParameters.ObserveParameters observe -> !Boolean.TRUE.equals(observe.probe_hover());
                case BrowserParameters.SnapshotParameters ignored -> true;
                case BrowserParameters.GetHtmlParameters ignored -> true;
                case BrowserParameters.TabListParameters ignored -> true;
                // 固定上游根视口使用 captureBeyondViewport=false；ref 几何及全页滚动均不纳入。
                case BrowserParameters.ScreenshotParameters screenshot -> screenshot.refValue() == null
                        && !Boolean.TRUE.equals(screenshot.full_page()) && screenshot.scope() == null && screenshot.timeout() == null;
                default -> false;
            };
        } catch (IOException | IllegalArgumentException invalid) {
            return false;
        }
    }
}
