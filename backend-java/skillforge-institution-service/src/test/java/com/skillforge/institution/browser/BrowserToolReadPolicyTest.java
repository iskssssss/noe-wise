package com.skillforge.institution.browser;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.connector.PlatformToolCatalog;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.runtime.api.port.ToolPortal.FrozenToolTarget;
import com.skillforge.runtime.api.port.ToolPortal.ToolBinding;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolHandle;
import com.skillforge.runtime.session.spi.SessionToolDirectory;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 当前读取只允许真实冻结平台绑定上的固定纯观察，非法参数不能靠隐式转换获得资格。 */
class BrowserToolReadPolicyTest {
    private static final TypedPayload INPUT_SCHEMA = new TypedPayload("json-schema", new ObjectMapper().valueToTree(
            new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(value -> value.name().equals("browser_call"))
                    .findFirst().orElseThrow().parametersSchema()).toString());

    /** 新鲜观察包含原生静态页面读取，显式关闭 hover 也保留纯观察资格。 */
    @Test void acceptsOnlyValidatedStaticObservationModes() {
        var frozen = browser();
        for (var arguments : List.of(command("observe", "{}"),
                command("observe", "{\"probe_hover\":false,\"tab_id\":7}"),
                command("snapshot", "{\"tab_id\":7}"), command("get-html", "{\"ref\":\"e1\",\"tab_id\":7}"),
                command("tab-list", "{\"scope\":\"agent\"}"), command("screenshot", "{}"),
                command("screenshot", "{\"full_page\":false,\"tab_id\":7}"))) {
            assertTrue(BrowserToolReadPolicy.currentRead(frozen, arguments));
        }
    }

    /** hover、ref 几何或滚动截图与执行型命令不因相同浏览器入口获得新鲜读取身份。 */
    @Test void excludesPageInteractionsAndNonViewportScreenshots() {
        for (var arguments : List.of(command("observe", "{\"probe_hover\":true}"),
                command("screenshot", "{\"ref\":\"e1\"}"), command("screenshot", "{\"full_page\":true}"),
                command("screenshot", "{\"scope\":\"follow\"}"), command("screenshot", "{\"timeout\":\"30s\"}"),
                command("evaluate", "{\"expression\":\"document.title\"}"),
                command("navigate", "{\"url\":\"https://example.test/\"}"),
                command("click", "{\"ref\":\"e1\"}"), command("tab-select", "{\"tab_id\":7}"),
                command("record-start", "{}"), command("record-stop", "{}"),
                command("debug", "{\"action\":\"status\"}"), command("upload", "{}"), command("get-text", "{}"))) {
            assertFalse(BrowserToolReadPolicy.currentRead(browser(), arguments));
        }
    }

    /** 发现名称与模型操作名不能替代 PLATFORM、准确 providerRef、模型可调用及确认策略。 */
    @Test void requiresExactFrozenProviderAndInvocationPolicy() {
        var arguments = command("observe", "{}");
        for (var frozen : List.of(
                entry(new ToolBinding(ToolBinding.ProviderType.MCP, "browser_call", "browser_call"), true, ToolConfirmationPolicy.NEVER, INPUT_SCHEMA),
                entry(new ToolBinding(ToolBinding.ProviderType.PLATFORM, "other", "browser_call"), true, ToolConfirmationPolicy.NEVER, INPUT_SCHEMA),
                entry(new ToolBinding(ToolBinding.ProviderType.PLATFORM, "browser_call", "other"), true, ToolConfirmationPolicy.NEVER, INPUT_SCHEMA),
                entry(binding(), false, ToolConfirmationPolicy.NEVER, INPUT_SCHEMA),
                entry(binding(), true, ToolConfirmationPolicy.ALWAYS, INPUT_SCHEMA))) {
            assertFalse(BrowserToolReadPolicy.currentRead(frozen, arguments));
        }
    }

    /** 原冻结 schema 与固定命令协议同时校验，宽松 schema 也不能接纳额外字段或错误类型。 */
    @Test void rejectsMalformedArgumentsAndViolationsOfOriginalSchema() {
        var permissive = entry(binding(), true, ToolConfirmationPolicy.NEVER, new TypedPayload("json-schema", "{}"));
        for (var arguments : List.of(command("observe", "{\"probe_hover\":\"false\"}"),
                command("observe", "{\"probe_hover\":null}"), command("observe", "{\"tab_id\":\"7\"}"),
                command("screenshot", "{\"full_page\":\"false\"}"), command("screenshot", "{\"full_page\":null}"),
                command("observe", "{\"extra\":false}"), new TypedPayload("application/json", "{}"),
                new TypedPayload("application/json", "{\"command\":{\"operation\":\"observe\",\"parameters\":\"{}\"},\"task\":\"b6cb7447-34e2-4da3-b7dc-ecc4a12917c1\"}"),
                new TypedPayload("application/json", "{\"command\":{\"operation\":\"observe\",\"parameters\":{}},\"task\":\"default\"}"),
                new TypedPayload("application/json", "{\"command\":{\"extra\":false,\"operation\":\"observe\",\"parameters\":{}},\"task\":\"b6cb7447-34e2-4da3-b7dc-ecc4a12917c1\"}"),
                new TypedPayload("application/json", "{\"command\":{\"operation\":\"observe\",\"parameters\":{}},\"extra\":false,\"task\":\"b6cb7447-34e2-4da3-b7dc-ecc4a12917c1\"}"))) {
            assertFalse(BrowserToolReadPolicy.currentRead(browser(), arguments));
            assertFalse(BrowserToolReadPolicy.currentRead(permissive, arguments));
        }
        var schema = new TypedPayload("json-schema", "{\"type\":\"object\",\"required\":[\"missing\"]}");
        assertFalse(BrowserToolReadPolicy.currentRead(entry(binding(), true, ToolConfirmationPolicy.NEVER, schema), command("observe", "{}")));
        assertFalse(BrowserToolReadPolicy.currentRead(entry(binding(), true, ToolConfirmationPolicy.NEVER,
                new TypedPayload("json-schema", "false")), command("observe", "{}")));
    }

    /** 冻结目录使用真实公开 schema，效果仍保留 browser_call 的非幂等写声明。 */
    private static SessionToolDirectory.Entry browser() {
        return entry(binding(), true, ToolConfirmationPolicy.NEVER, INPUT_SCHEMA);
    }

    /** 实际平台路由固定 providerRef 和工具名相同，展示名称不参与资格判定。 */
    private static ToolBinding binding() {
        return new ToolBinding(ToolBinding.ProviderType.PLATFORM, "browser_call", "browser_call");
    }

    /** 用明确冻结值构造目录条目，不引入数据库或当前发布目录依赖。 */
    private static SessionToolDirectory.Entry entry(ToolBinding binding, boolean invocable,
                                                    ToolConfirmationPolicy confirmation, TypedPayload schema) {
        var target = new FrozenToolTarget(new ToolHandle("tool:browser"), new CapabilityRef(CapabilityType.TOOL, "browser", "v1"),
                "deployment", 1, schema, new TypedPayload("json-schema", "{}"), ToolEffect.NON_IDEMPOTENT_WRITE, false,
                Duration.ofSeconds(120), invocable, confirmation, Set.of(), Set.of(), true);
        return new SessionToolDirectory.Entry(target, binding, new SessionToolDirectory.Discovery(binding.serverIdentity(),
                binding.serverIdentity() + "/" + binding.toolName(), binding.toolName(), Optional.empty(), "浏览器工具"), false);
    }

    /** 保留完整任务身份和对象型参数，具体命令仍由原 schema 与固定协议核验。 */
    private static TypedPayload command(String operation, String parameters) {
        return new TypedPayload("application/json", "{\"command\":{\"operation\":\"" + operation + "\",\"parameters\":"
                + parameters + "},\"task\":\"b6cb7447-34e2-4da3-b7dc-ecc4a12917c1\"}");
    }
}
