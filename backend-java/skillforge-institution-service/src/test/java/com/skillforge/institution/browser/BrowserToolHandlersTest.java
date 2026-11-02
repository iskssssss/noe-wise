package com.skillforge.institution.browser;

import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.execution.browser.BrowserReceipt;
import com.skillforge.execution.browser.BrowserRequest;
import com.skillforge.institution.media.SessionMediaService;
import com.skillforge.runtime.api.*;
import com.skillforge.runtime.api.media.MediaRef;
import com.skillforge.runtime.api.port.*;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 检查真实任务摘要、确定拒绝与未知结果边界，不调用浏览器或修改会话状态。 */
class BrowserToolHandlersTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TASK = "e76ddb32-3257-4a6a-8dba-240d3cae8b58";
    private static final SessionRef OWNER = new SessionRef(new TenantId("7"), "session");

    /** 任意巨大 CLI 扩展只留在完整持久回执，模型收到有界停止事实。 */
    @Test void stopProjectsBoundedFactsAfterPersistingFullReceipt() throws Exception {
        var fixture = new Fixture();
        var raw = JSON.createObjectNode().put("ok", true).put("cleanup_complete", true)
                .put("effect_state", "committed").put("cleanup_state", "complete")
                .put("arbitrary_cli_data", "private".repeat(20_000));
        when(fixture.client.execute(any())).thenReturn(receipt("stop", "succeeded", raw));
        var result = fixture.handlers.browserStop().execute(Map.of("task", TASK), context());
        assertEquals(true, result.get("success"));
        assertEquals(false, result.get("isError"));
        assertEquals("succeeded", result.get("status"));
        assertEquals(TASK, result.get("task"));
        assertEquals("invocation", result.get("invocation"));
        var projected = JSON.valueToTree(result).path("result");
        assertTrue(projected.path("cleanup_complete").booleanValue());
        assertEquals("complete", projected.path("cleanup_state").textValue());
        assertEquals("committed", projected.path("effect_state").textValue());
        assertFalse(projected.has("arbitrary_cli_data"));
        assertTrue(JSON.writeValueAsBytes(result).length <= 8_192);
        var persisted = ArgumentCaptor.forClass(String.class);
        verify(fixture.mapper).receipt(eq(7L), eq(1L), eq("invocation"), eq(TASK), eq("session"), eq("run"),
                eq("stop"), eq("succeeded"), persisted.capture());
        assertEquals(raw, JSON.readTree(persisted.getValue()).path("result"));
    }

    /** 确定失败与 UNKNOWN 不转成功，缺失的清理事实保持未知。 */
    @Test void stopFailureAndUnknownPreserveOriginalOutcome() throws Exception {
        var fixture = new Fixture();
        when(fixture.client.execute(any())).thenReturn(receipt("stop", "failed", JSON.createObjectNode()
                .put("error", "browser_stop_failed").put("reason", "cleanup_failed").put("cleanup_state", "failed")));
        var result = fixture.handlers.browserStop().execute(Map.of("task", TASK), context());
        assertEquals(false, result.get("success"));
        assertEquals(true, result.get("isError"));
        assertEquals("failed", result.get("status"));
        assertEquals("failed", JSON.valueToTree(result).path("result").path("cleanup_state").asText());
        assertTrue(JSON.valueToTree(result).path("result").path("cleanup_complete").isNull());
        when(fixture.client.execute(any())).thenReturn(receipt("stop", "unknown", JSON.createObjectNode().put("cleanup_complete", false)));
        assertEquals("browser_result_unknown", assertThrows(IOException.class,
                () -> fixture.handlers.browserStop().execute(Map.of("task", TASK), context())).getMessage());
        verify(fixture.client, times(2)).execute(any());
        verify(fixture.mapper).receipt(eq(7L), eq(1L), eq("invocation"), eq(TASK), eq("session"), eq("run"), eq("stop"), eq("unknown"), anyString());
    }

    /** 两个完整分支保留默认与显式选择语义，正式 Session 校验仍拒绝空串、null 和额外字段。 */
    @Test
    void publishedBrowserStartSchemaAcceptsOnlyDefaultOrExplicitNonEmptyDevice() throws Exception {
        var entry = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                .filter(tool -> tool.name().equals("browser_start")).findFirst().orElseThrow();
        assertEquals("builtin:9", entry.publishedVersionKey());
        var schema = new TypedPayload("json-schema", JSON.writeValueAsString(entry.parametersSchema()));
        var limits = new com.skillforge.runtime.json.CodecLimits(131_072, 4096, 32);
        for (String arguments : List.of("{}", "{\"browser\":\"browser-owned\"}")) {
            assertDoesNotThrow(() -> com.skillforge.runtime.session.SessionRuntimePayloads.validateAgainstSchema(
                    schema, new TypedPayload("application/json", arguments), limits));
        }
        for (String arguments : List.of("{\"browser\":\"\"}", "{\"browser\":null}", "{\"browser\":{}}",
                "{\"browser\":123}", "{\"extra\":true}", "{\"browser\":\"browser-owned\",\"extra\":true}")) {
            assertThrows(RuntimeException.class, () -> com.skillforge.runtime.session.SessionRuntimePayloads.validateAgainstSchema(
                    schema, new TypedPayload("application/json", arguments), limits));
        }
    }

    @Test
    void publishedBrowserCommandSchemaAndHandlerAgreeOnObjectShapes() throws Exception {
        var entry = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                .filter(tool -> tool.name().equals("browser_call")).findFirst().orElseThrow();
        var schema = new TypedPayload("json-schema", JSON.writeValueAsString(entry.parametersSchema()));
        var limits = new com.skillforge.runtime.json.CodecLimits(131_072, 4096, 32);
        var fixture = new Fixture();
        for (String operation : List.of("navigate", "observe", "screenshot")) {
            var arguments = call(operation, operation.equals("navigate") ? Map.of("url", "https://example.invalid/") : Map.of());
            assertDoesNotThrow(() -> com.skillforge.runtime.session.SessionRuntimePayloads.validateAgainstSchema(
                    schema, new TypedPayload("application/json", JSON.writeValueAsString(arguments)), limits));
            when(fixture.client.execute(any())).thenReturn(receipt(operation, "rejected", JSON.readTree("{\"error\":\"browser_device_revoked\"}")));
            // 到达服务回执说明通过 Handler 参数解析；拒绝回执避免触发截图媒体注册。
            assertEquals("rejected", fixture.handlers.browserCall().execute(arguments, context()).get("status"));
        }
        clearInvocations(fixture.client);
        for (Object command : List.of("{}", Map.of("operation", "observe"),
                Map.of("operation", "observe", "parameters", "{}"),
                Map.of("operation", "navigate", "parameters", Map.of(), "url", "https://example.invalid/"))) {
            var arguments = Map.<String, Object>of("task", TASK, "command", command);
            assertThrows(RuntimeException.class, () -> com.skillforge.runtime.session.SessionRuntimePayloads.validateAgainstSchema(
                    schema, new TypedPayload("application/json", JSON.writeValueAsString(arguments)), limits));
            reject(fixture.handlers.browserCall().execute(arguments, context()), "browser_command_parameters");
        }
        verifyNoInteractions(fixture.client);
    }

    @Test
    void rejectedStartExplainsOmittedDeviceArgumentsAndDoesNotInventATask() {
        for(String code:List.of("browser_start_parameters","browser_device_not_owned")) {
            var result=BrowserToolResults.rejected(code);
            assertEquals(true,result.get("isError"));assertEquals(false,result.get("dispatched"));
            assertEquals(code,result.get("error_code"));assertFalse(result.containsKey("task"));
            String guidance=JSON.valueToTree(result).path("content").get(0).path("text").asText();
            assertTrue(guidance.contains("省略 browser"));assertTrue(guidance.contains("{}"));
        }
    }

    @Test
    void exposesPageBodyBeforeContinuationReceiptWhileRetainingFullResultAndTrustedOwner() throws Exception {
        var fixture = new Fixture();
        JsonNode page = JSON.readTree("{\"page\":{\"tab_id\":123},\"text\":\"" + "page-body-".repeat(100) + "\"}");
        var receipt = receipt("snapshot", "succeeded", page);
        when(fixture.client.execute(any())).thenReturn(receipt);

        var result = fixture.handlers.browserCall().execute(call("snapshot", Map.of("tab_id", 123)), context());
        assertEquals(page.path("text").asText(), content(result).get(0).path("text").asText());
        String summary = content(result).get(1).path("text").asText();
        assertTrue(summary.startsWith("task=" + TASK));
        assertTrue(summary.substring(0, Math.min(384, summary.length())).contains(TASK));
        assertTrue(summary.contains("tab_id=123"));
        assertFalse(summary.contains("page-body"));
        assertFalse(summary.contains("rgjn"));
        assertEquals(page, JSON.valueToTree(result).path("result"));
        assertEquals(TASK, result.get("task"));
        assertEquals(false, result.get("isError"));

        var captured = ArgumentCaptor.forClass(BrowserRequest.class);
        verify(fixture.client).execute(captured.capture());
        assertEquals("7", captured.getValue().tenant());
        assertEquals("1", captured.getValue().endUser());
        assertEquals("session", captured.getValue().session());
        assertEquals("run", captured.getValue().run());
        assertEquals(TASK, captured.getValue().task());
        verify(fixture.mapper).receipt(eq(7L), eq(1L), eq("invocation"), eq(TASK), eq("session"),
                eq("run"), eq("snapshot"), eq("succeeded"), anyString());
    }

    @Test
    void exposesExactEvaluateTextAndStructuredValuesWithoutSelectingBusinessFields() throws Exception {
        var fixture = new Fixture();
        for (JsonNode value : List.of(JSON.getNodeFactory().textNode("第2名：累计下架2.6万余条AI魔改短视频\n待核验报道全文"),
                JSON.readTree("{\"count\":26000,\"details\":[\"已读取\",\"缺少第1名\"],\"nullable\":null}"))) {
            var page = JSON.createObjectNode().put("ok", true).put("tab_id", 123).set("value", value);
            when(fixture.client.execute(any())).thenReturn(receipt("evaluate", "succeeded", page));
            var result = fixture.handlers.browserCall().execute(call("evaluate", Map.of("expression", "document.title")), context());
            assertEquals(value.isTextual() ? value.textValue() : value.toString(), content(result).getFirst().path("text").asText());
            assertTrue(content(result).get(1).path("text").asText().contains("task=" + TASK));
            assertEquals(page, JSON.valueToTree(result).path("result"));
        }
        when(fixture.client.execute(any())).thenReturn(receipt("evaluate", "succeeded",
                JSON.readTree("{\"ok\":false,\"value\":\"未取得的页面正文\"}")));
        assertEquals(1, content(fixture.handlers.browserCall().execute(
                call("evaluate", Map.of("expression", "document.title")), context())).size());
    }

    @Test
    void rejectsControlledShapesAndTypedParameterErrorsBeforeExternalAccess() throws Exception {
        var fixture = new Fixture();
        reject(fixture.handlers.browserTasks().execute(Map.of("extra", "private"), context()), "browser_tasks_parameters");
        reject(fixture.handlers.browserStop().execute(Map.of("task", TASK, "extra", true), context()), "browser_stop_parameters");
        reject(fixture.handlers.browserCall().execute(Map.of(), context()), "browser_call_parameters");
        reject(fixture.handlers.browserCall().execute(Map.of("task", TASK, "command", "private"), context()), "browser_command_parameters");
        var wrongCommand = fixture.handlers.browserCall().execute(Map.of("task", TASK, "command",
                Map.of("operation", "observe", "parameters", "{}")), context());
        reject(wrongCommand, "browser_command_parameters");
        String guidance = content(wrongCommand).getFirst().path("text").asText();
        assertTrue(guidance.contains("parameters 也必须是对象"));
        assertTrue(guidance.contains("\"operation\":\"observe\",\"parameters\":{}"));
        reject(fixture.handlers.browserCall().execute(Map.of("task", TASK, "command",
                Map.of("operation", 1, "parameters", Map.of())), context()), "browser_operation_invalid");
        reject(fixture.handlers.browserCall().execute(call("snapshot", Map.of("tab_id", Map.of("secret", "private"))), context()), "browser_parameters_invalid");
        reject(fixture.handlers.browserCall().execute(call("snapshot", Map.of("_operation", "click")), context()), "browser_parameters_invalid");
        reject(fixture.handlers.browserCall().execute(call("snapshot", Map.of("path", "/private")), context()), "browser_parameters_invalid");
        verifyNoInteractions(fixture.client, fixture.mapper, fixture.screenshots);
    }

    @Test
    void rejectsFabricatedOrNonCanonicalTaskWithActionableRecovery() throws Exception {
        var fixture = new Fixture();
        for (Object task : List.of("task:rgjn", "rgjn", "skill:861710166540293", TASK.toUpperCase(), "1-1-1-1-1", 123)) {
            var result = fixture.handlers.browserCall().execute(Map.of("task", task,
                    "command", Map.of("operation", "tab-list", "parameters", Map.of())), context());
            reject(result, "browser_task_invalid");
            String text = content(result).getFirst().path("text").asText();
            assertTrue(text.contains("browser_tasks"));
            assertTrue(text.contains("binding.session_id 不是 task"));
            assertTrue(text.contains("先以 {} 调用 browser_start"));
            assertTrue(text.contains("连接等待中不要再次启动"));
            assertTrue(text.contains("UNKNOWN"));
            assertFalse(text.contains("task:rgjn"));
            assertFalse(text.contains("skill:861710166540293"));
            assertTrue(text.length() < 1200, "固定纠正正文有界且不回显原参数");
        }
        verifyNoInteractions(fixture.client, fixture.mapper, fixture.screenshots);
    }

    /** 停止任务的错误引用只查已有任务，不能复用调用入口的首次启动建议。 */
    @Test
    void invalidStopTaskExplainsExistingTaskRecoveryWithoutCreatingAnotherTask() throws Exception {
        var fixture = new Fixture();
        var result = fixture.handlers.browserStop().execute(Map.of("task", "skill:private-handle"), context());
        reject(result, "browser_task_invalid");
        String guidance = content(result).getFirst().path("text").asText();
        assertTrue(guidance.contains("browser_tasks"));
        assertTrue(guidance.contains("不为停止而调用 browser_start 新建任务"));
        assertTrue(guidance.contains("UNKNOWN"));
        assertFalse(guidance.contains("先以 {} 调用 browser_start"));
        assertFalse(guidance.contains("skill:private-handle"));
        assertTrue(guidance.length() < 1200);
        verifyNoInteractions(fixture.client, fixture.mapper, fixture.screenshots);
    }

    /** task 缺失先命中形状错误，也须保留 call 与 stop 不同的生命周期纠正语义。 */
    @Test
    void missingTaskGuidanceDistinguishesCallingFromStoppingBeforeExternalAccess() throws Exception {
        var fixture = new Fixture();
        var call = fixture.handlers.browserCall().execute(Map.of("command", Map.of("operation", "observe", "parameters", Map.of())), context());
        reject(call, "browser_call_parameters");
        assertTrue(content(call).getFirst().path("text").asText().contains("先以 {} 调用 browser_start"));
        var stop = fixture.handlers.browserStop().execute(Map.of(), context());
        reject(stop, "browser_stop_parameters");
        String stopGuidance = content(stop).getFirst().path("text").asText();
        assertTrue(stopGuidance.contains("不为停止而调用 browser_start 新建任务"));
        assertFalse(stopGuidance.contains("先以 {} 调用 browser_start"));
        verifyNoInteractions(fixture.client, fixture.mapper, fixture.screenshots);
    }

    /** 成功观察保留原始页面正文，并在实际模型 content 中解释域名与完整链接的能力边界。 */
    @Test
    void observationExplainsHowToReadCompleteLinksBeforeStopping() throws Exception {
        var fixture = new Fixture();
        String page = "@e47 link 搜索结果 [→ example.test]";
        when(fixture.client.execute(any())).thenReturn(receipt("observe", "succeeded", JSON.createObjectNode().put("text", page)));
        var result = fixture.handlers.browserCall().execute(call("observe", Map.of("tab_id", 123)), context());
        assertEquals(page, content(result).getFirst().path("text").asText());
        String explanation = content(result).get(1).path("text").asText();
        assertTrue(explanation.contains("[→ 域名] 不是完整 URL"));
        assertTrue(explanation.contains("browser_stop 前"));
        assertTrue(explanation.contains("当前真实 ref 调用 get-html"));
        assertTrue(explanation.contains("只读 evaluate"));
        assertTrue(explanation.contains("多个已观察目标的链接或字段"));
        assertTrue(explanation.contains("在准确 tab_id 上一次只读 evaluate 批量返回标题、完整 href 与所需字段"));
        assertTrue(explanation.contains("依据当前实际观察定位，不猜测选择器、不点击、不赋值、不触发导航"));
        assertTrue(explanation.contains("单个目标定位、源码或 HTML 核验"));
        assertTrue(explanation.contains("不得猜测链接或使用 url=... 占位"));
        assertTrue(explanation.contains("真实重定向 href 可原样交付"));
        assertTrue(explanation.contains("未核对不声称最终跳转目标"));
        assertFalse(explanation.contains("https://example.test"), "能力提示不补造页面链接");
    }

    @Test
    void presentsOnlyReturnedNavigationFactsWithoutCopyingQueryCredentialsOrPageBody() throws Exception {
        var fixture = new Fixture();
        JsonNode navigation = JSON.readTree("""
                {"navigation":{"status":"observed","source_tab_id":123,"active_tab_id":456,
                  "new_tabs":[{"tab_id":456,"title":"完整榜单","url":"https://user:password@example.com/list?token=secret#secret","active":true},
                    {"title":"no actual ID","url":"https://example.com/other"}]},"body":"private page body"}
                """);
        when(fixture.client.execute(any())).thenReturn(receipt("click", "succeeded", navigation));
        var result = fixture.handlers.browserCall().execute(call("click", Map.of("tab_id", 123, "ref", "e1")), context());
        String summary = content(result).getFirst().path("text").asText();
        assertTrue(summary.contains("navigation=observed source_tab_id=123 active_tab_id=456"));
        assertTrue(summary.contains("new_tabs=[tab_id=456 title=完整榜单 url=https://example.com/list active=true]"));
        for (String excluded : List.of("password", "user:", "secret", "token", "private page body", "no actual ID"))
            assertFalse(summary.contains(excluded), excluded);
        assertEquals(navigation, JSON.valueToTree(result).path("result"));
    }

    @Test
    void keepsActionSuccessWhenNavigationIsUnconfirmedAndSurfacesStableRefusalCode() throws Exception {
        var fixture = new Fixture();
        when(fixture.client.execute(any())).thenReturn(receipt("click", "succeeded",
                JSON.readTree("{\"navigation\":{\"status\":\"unconfirmed\",\"after_error\":\"browser_navigation_observation_failed\"}}")));
        var result = fixture.handlers.browserCall().execute(call("click", Map.of("ref", "e1")), context());
        assertEquals(false, result.get("isError"));
        assertTrue(content(result).getFirst().path("text").asText().contains("status=succeeded navigation=unconfirmed"));

        when(fixture.client.execute(any())).thenReturn(receipt("snapshot", "rejected",
                JSON.readTree("{\"error\":\"browser_task_not_active\",\"message\":\"known refusal\"}")));
        result = fixture.handlers.browserCall().execute(call("snapshot", Map.of()), context());
        assertEquals(true, result.get("isError"));
        assertTrue(content(result).getFirst().path("text").asText().contains("error_code=browser_task_not_active"));
        assertEquals("rejected", result.get("status"));
    }

    /** 模型正文保留原因和未知效果，不能仅看到一级错误码或误判为可重试失败。 */
    @Test
    void exposesSafeFailureFactsWithoutCopyingArbitraryMessages() throws Exception {
        var fixture = new Fixture();
        var unknown = receipt("click", "unknown", JSON.readTree("""
                {"error":"cdp_failed","reason":"input_outcome_unknown","effect_state":"unknown",
                 "cleanup_state":"failed","message":"private page text","data":{"token":"private"}}
                """));
        when(fixture.client.execute(any())).thenReturn(unknown);
        assertThrows(IOException.class, () -> fixture.handlers.browserCall().execute(call("click", Map.of("ref", "e1")), context()));
        verify(fixture.mapper).receipt(eq(7L), eq(1L), anyString(), eq(TASK), anyString(), anyString(),
                eq("click"), eq("unknown"), contains("input_outcome_unknown"));
        String summary = BrowserToolResults.summary(unknown);
        assertTrue(summary.contains("status=unknown"));
        assertTrue(summary.contains("error_code=cdp_failed reason=input_outcome_unknown effect_state=unknown cleanup_state=failed"));
        assertFalse(summary.contains("private"));

        when(fixture.client.execute(any())).thenReturn(receipt("click", "failed", JSON.readTree("""
                {"error":"permission_denied","reason":"element_not_visible","effect_state":"none"}
                """)));
        var result = fixture.handlers.browserCall().execute(call("click", Map.of("ref", "e1")), context());
        assertTrue(content(result).getFirst().path("text").asText().contains("reason=element_not_visible effect_state=none"));
        assertEquals("failed", result.get("status"));
    }

    @Test
    void preservesScreenshotImageAfterShortTaskText() throws Exception {
        var fixture = new Fixture();
        when(fixture.client.execute(any())).thenReturn(receipt("screenshot", "succeeded", JSON.createObjectNode()));
        byte[] png = {1, 2, 3};
        when(fixture.client.screenshot("7", "1", "invocation")).thenReturn(png);
        var media = new MediaRef(OWNER, "media", "a".repeat(64), "image/png", 3, 40, 20);
        var image = SessionMediaService.content(media);
        when(fixture.screenshots.register(eq(OWNER), any(), eq("invocation"), same(png))).thenReturn(image);
        var result = fixture.handlers.browserCall().execute(call("screenshot", Map.of()), context());
        var parts = content(result);
        assertEquals(2, parts.size());
        assertEquals("text", parts.get(0).path("type").asText());
        assertEquals("image", parts.get(1).path("type").asText());
        assertEquals("media", parts.get(1).path("media").path("id").asText());
    }

    @Test
    void tasksDirectoryPrioritizesCurrentSessionAndReturnsOnlyPublicContinuationMetadata() throws Exception {
        var fixture = new Fixture();
        var same = receipt("snapshot", "succeeded", JSON.readTree("{\"private\":\"large page\"}"));
        var other = new BrowserReceipt(null, "other-invocation", "start", "c77e9b67-aab5-4f0e-ae52-62a3df176b63",
                "other-session", "other-run", "2026-10-09T08:00:00Z", "active", null, "browser", null, JSON.createObjectNode());
        var receipts = List.of(other, same);
        when(fixture.client.taskStates("7", "1")).thenReturn(receipts);
        var result = fixture.handlers.browserTasks().execute(Map.of(), context());
        String summary = content(result).getFirst().path("text").asText();
        assertTrue(summary.startsWith("task=" + TASK));
        assertTrue(summary.contains("current_session=true"));
        assertTrue(summary.contains("current_session=false"));
        assertFalse(summary.contains("large page"));
        var items = JSON.valueToTree(result).path("items");
        assertEquals(2, items.size());
        var current = items.get(0);
        assertEquals(TASK, current.path("task").asText());
        assertEquals("invocation", current.path("invocation").asText());
        assertEquals("session", current.path("session").asText());
        assertEquals("run", current.path("run").asText());
        assertEquals("snapshot", current.path("operation").asText());
        assertEquals("succeeded", current.path("status").asText());
        assertEquals("browser", current.path("browser").asText());
        assertEquals("2026-10-09T04:20:00Z", current.path("created_at").asText());
        assertEquals("instance", current.path("binding").path("browser_instance_id").asText());
        assertEquals(12L, current.path("binding").path("agent_window_id").asLong());
        for (String removed : List.of("result", "fingerprint", "requestToken")) assertFalse(current.has(removed));
        assertFalse(current.path("binding").has("session_id"));
        assertFalse(items.toString().contains("large page"));
        verifyNoInteractions(fixture.mapper, fixture.screenshots);
    }

    @Test
    void tasksDirectoryUsesLiveBindingStateWithoutReplacingInvocationHistory() throws Exception {
        var fixture = new Fixture();
        var lost = receipt("start", "inactive", JSON.readTree("""
                {"error":"browser_task_binding_lost","cleanup_complete":false,"private":"page text"}
                """));
        var unknown = new BrowserReceipt(null, "other-invocation", "start", "c77e9b67-aab5-4f0e-ae52-62a3df176b63",
                "other-session", "other-run", "2026-10-09T08:00:00Z", "unknown", null, "browser", null,
                JSON.readTree("{\"error\":\"browser_task_binding_check_unknown\"}"));
        when(fixture.client.taskStates("7", "1")).thenReturn(List.of(unknown, lost));

        var result = fixture.handlers.browserTasks().execute(Map.of(), context());
        String summary = content(result).getFirst().path("text").asText();
        assertTrue(summary.contains("status=inactive error_code=browser_task_binding_lost"));
        assertTrue(summary.contains("status=unknown error_code=browser_task_binding_check_unknown"));
        var items = JSON.valueToTree(result).path("items");
        assertEquals("inactive", items.get(0).path("status").asText());
        assertFalse(items.get(0).path("cleanup_complete").asBoolean());
        assertFalse(result.toString().contains("page text"));
        verify(fixture.client).taskStates("7", "1");
        verify(fixture.client, never()).tasks(anyString(), anyString());
        verifyNoInteractions(fixture.mapper, fixture.screenshots);
    }

    @Test
    void conflictingInvocationCannotReplaceEarlierSucceededOrUnknownProjection() throws Exception {
        for (String originalStatus : List.of("succeeded", "unknown")) {
            var fixture = new Fixture();
            var projection = new java.util.concurrent.atomic.AtomicReference<String>();
            doAnswer(call -> { projection.set(call.getArgument(8)); return null; }).when(fixture.mapper)
                    .receipt(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(),
                            anyString(), anyString(), anyString());
            var original = receipt("click", originalStatus, JSON.readTree("{\"original\":\"immutable fact\"}"));
            when(fixture.client.execute(any())).thenReturn(original);
            if ("unknown".equals(originalStatus))
                assertThrows(IOException.class, () -> fixture.handlers.browserCall().execute(call("click", Map.of("ref", "e1")), context()));
            else fixture.handlers.browserCall().execute(call("click", Map.of("ref", "e1")), context());
            String originalPayload = projection.get();
            assertEquals(originalStatus, JSON.readTree(originalPayload).path("status").asText());

            when(fixture.client.execute(any())).thenReturn(receipt("click", "rejected",
                    JSON.readTree("{\"error\":\"browser_invocation_conflict\"}")));
            var rejected = fixture.handlers.browserCall().execute(call("click", Map.of("ref", "e2")), context());
            assertEquals(true, rejected.get("isError"));
            assertEquals("rejected", rejected.get("status"));
            assertTrue(content(rejected).getFirst().path("text").asText().contains("error_code=browser_invocation_conflict"));
            assertEquals(originalPayload, projection.get());
            verify(fixture.mapper, times(1)).receipt(anyLong(), anyLong(), anyString(), anyString(),
                    anyString(), anyString(), eq("click"), eq(originalStatus), anyString());
            verifyNoInteractions(fixture.screenshots);
        }
    }

    @Test
    void genuineUnknownResultIsPersistedAndPropagatedWithoutAFalseRejection() throws Exception {
        var fixture = new Fixture();
        when(fixture.client.execute(any())).thenReturn(receipt("click", "unknown", JSON.createObjectNode()));
        var failure = assertThrows(IOException.class,
                () -> fixture.handlers.browserCall().execute(call("click", Map.of("ref", "e1")), context()));
        assertEquals("browser_result_unknown", failure.getMessage());
        verify(fixture.mapper).receipt(eq(7L), eq(1L), anyString(), eq(TASK), anyString(), anyString(),
                eq("click"), eq("unknown"), anyString());
        verifyNoInteractions(fixture.screenshots);
    }

    @Test
    void externalFailuresAndProjectionFailuresRemainUnknownBoundaryExceptions() throws Exception {
        var fixture = new Fixture();
        for (Exception failure : List.of(new IOException("transport unavailable"),
                new IllegalArgumentException("unexpected external implementation"))) {
            doThrow(failure).when(fixture.client).execute(any());
            assertSame(failure, assertThrows(Exception.class,
                    () -> fixture.handlers.browserCall().execute(call("snapshot", Map.of()), context())));
        }
        doReturn(receipt("snapshot", "succeeded", JSON.createObjectNode())).when(fixture.client).execute(any());
        var storage = new IllegalArgumentException("projection failed");
        doThrow(storage).when(fixture.mapper).receipt(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString());
        String prior = TenantContext.getTenantId();
        TenantContext.setTenantId("prior-tenant");
        try {
            assertSame(storage, assertThrows(IllegalArgumentException.class,
                    () -> fixture.handlers.browserCall().execute(call("snapshot", Map.of()), context())));
            assertEquals("prior-tenant", TenantContext.getTenantId());
        } finally {
            if (prior == null) TenantContext.clear(); else TenantContext.setTenantId(prior);
        }
    }

    @Test
    void serializationFailureIsNotReclassifiedAsAParameterRejection() throws Exception {
        var fixture = new Fixture();
        var codec = mock(ObjectMapper.class);
        var serialization = new IllegalArgumentException("unexpected serialization failure");
        when(codec.valueToTree(any())).thenThrow(serialization);
        var handlers = new BrowserToolHandlers(fixture.client, fixture.mapper, codec, fixture.screenshots, fixture.taskAccess);
        assertSame(serialization, assertThrows(IllegalArgumentException.class,
                () -> handlers.browserCall().execute(call("snapshot", Map.of()), context())));
        verifyNoInteractions(fixture.client, fixture.mapper, fixture.screenshots);
    }

    private static void reject(Map<String, Object> result, String code) {
        assertEquals(true, result.get("isError"));
        assertEquals("rejected", result.get("status"));
        assertEquals(code, result.get("error_code"));
        assertEquals(false, result.get("dispatched"));
        assertEquals("text", content(result).getFirst().path("type").asText());
        assertFalse(result.toString().contains("private"));
    }

    private static List<JsonNode> content(Map<String, Object> result) {
        var values = JSON.valueToTree(result).path("content");
        return java.util.stream.StreamSupport.stream(values.spliterator(), false).toList();
    }

    private static Map<String, Object> call(String operation, Map<String, Object> parameters) {
        return Map.of("task", TASK, "command", Map.of("operation", operation, "parameters", parameters));
    }

    private static BrowserReceipt receipt(String operation, String status, JsonNode result) {
        return new BrowserReceipt(null, "invocation", operation, TASK, "session", "run",
                "2026-10-09T04:20:00Z", status, null, "browser",
                new BrowserReceipt.BrowserBinding("rgjn", "instance", 12L), result);
    }

    private static PlatformToolExecutionContext context() {
        var input = new InputId("input");
        var owner = SessionOwner.user(OWNER, new AgentId("agent"), new EndUserId(1));
        var run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(OWNER.tenantId(), "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("verification"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(OWNER.tenantId(), owner.requireUser()),
                owner, Set.of(), 1, new ResultRef("identity"));
        return new PlatformToolExecutionContext(new PortCallContext(run, identity,
                new InvocationControl(Instant.now().plusSeconds(30), () -> false)), "invocation", "attempt");
    }

    private static final class Fixture {
        private final BrowserClient client = mock(BrowserClient.class);
        private final BrowserMapper mapper = mock(BrowserMapper.class);
        private final BrowserScreenshotSink screenshots = mock(BrowserScreenshotSink.class);
        private final BrowserTaskAccess taskAccess = mock(BrowserTaskAccess.class);
        private final BrowserToolHandlers handlers = new BrowserToolHandlers(client, mapper, JSON, screenshots, taskAccess);
        private Fixture() throws Exception {
            when(client.taskStates("7", "1")).thenReturn(List.of(receipt("start", "active", JSON.createObjectNode())));
            when(taskAccess.readableForRun(any(), any())).thenReturn(true);
        }
    }
}
