package com.skillforge.core.capability.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.capability.CapabilityCatalogScope;
import com.skillforge.core.capability.CapabilityCatalogErrors;
import com.skillforge.core.capability.CapabilityDescriptor;
import com.skillforge.core.capability.CapabilityDescriptorCodec;
import com.skillforge.core.capability.CapabilityProducerPublicationBridge;
import com.skillforge.core.capability.CapabilityProducerPublicationRequest;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.capability.ToolVersion;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.settings.OperationalSettingsDefaults;
import com.skillforge.core.chat.contract.ContextReferenceLimits;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import com.skillforge.runtime.api.ToolEffect;
import com.skillforge.runtime.api.port.ToolPortal.ToolConfirmationPolicy;
import com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

class PlatformToolCatalogTest {
    private final PlatformToolCatalog catalog = new PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor());
    @Test
    void searchDiscoveryPublishesBoundedExcerptsAndExplicitAdditionalMatchEvidence() {
        assertEquals("builtin:5", entry("search_tools").publishedVersionKey());
        assertOutputFields("search_tools", Set.of("success", "tools", "has_more", "hint", "error"));
        var output = new ObjectMapper().valueToTree(entry("search_tools").outputSchema());
        assertEquals("boolean", output.at("/properties/has_more/type").asText());
        assertEquals(400, output.at("/properties/tools/items/properties/description/maxLength").asInt());
        assertEquals(List.of("query"), entry("search_tools").parametersSchema().get("required"));
        assertEquals("READ_ONLY", entry("search_tools").descriptor().get("effect"));
    }
    /** 操作指引以新目录版本发布，命令继续使用显式对象联合契约。 */
    @Test
    void browserCallPublishesExplicitCommandTypeUnderNewImmutableVersion() {
        var call = entry("browser_call");
        assertEquals("builtin:13", call.publishedVersionKey());
        var command = new ObjectMapper().valueToTree(inputProperties("browser_call").get("command"));
        assertEquals("object", command.path("type").asText());
        assertEquals(34, command.path("oneOf").size());
        assertEquals("string", command.at("/properties/operation/type").asText());
        for (var branch : command.path("oneOf")) {
            assertFalse(branch.path("properties").path("operation").has("type"));
            assertTrue(branch.path("properties").path("operation").has("const"));
        }
    }

    /** 定位失败与求值成功不能替代页面观察，也不能绕过可见性或借用授权。 */
    @Test
    void browserCallGuidanceRequiresFreshPageEvidenceAndRespectsInteractionBoundaries() {
        String description = entry("browser_call").description();
        for (String boundary : List.of("当前真实观察与新鲜 ref", "页面变化后重新观察", "不猜测选择器或复用旧 ref",
                "不得用 evaluate 绕过元素可见性、借用确认或授权边界", "借用用户标签页须等待用户确认",
                "evaluate 成功只证明求值成功", "业务完成须核对真实页面证据")) assertTrue(description.contains(boundary), boundary);
    }

    /** 操作回执、候选标签页与异步受理各有证据边界，完整来源沿现有历史工具读取。 */
    @Test
    void browserCallGuidanceSeparatesActionSuccessFromObservationAndCompletion() {
        String description = entry("browser_call").description();
        for (String boundary : List.of("partial/unconfirmed", "先核对页面，不重复动作", "只是候选，不新增控制授权",
                "submitted 仅表示受理", "须核对持久回执及产物", "UNKNOWN 沿原回执收敛", "不自动重放副作用"))
            assertTrue(description.contains(boundary), boundary);
    }

    /** 域名提示不能当成交付链接，准确 href 必须在停止页面任务前从实际目标读取。 */
    @Test
    void browserCallGuidanceRequiresRealLinksBeforeStopping() {
        String description = entry("browser_call").description();
        assertTrue(description.contains("browser_stop 前核对真实标题与完整 href"));
        assertTrue(description.contains("域名提示、猜测地址、省略号不能当作链接"));
        assertTrue(description.contains("截断时读取完整原来源或缩小读取范围，不重新执行页面动作"));
        assertFalse(description.contains("避免逐条 get-html"));
        var command = new ObjectMapper().valueToTree(inputProperties("browser_call").get("command"));
        assertTrue(command.path("oneOf").size() > 0);
        for (var branch : command.path("oneOf")) {
            assertEquals("object", command.at("/properties/parameters/type").asText());
            assertEquals(List.of("operation", "parameters"), new ObjectMapper().convertValue(command.path("required"), List.class));
        }
    }

    @Test
    void browserStartAllowsEmptyArgumentsAndExplainsTheRealDeviceSelector() {
        var start=entry("browser_start");
        assertEquals("builtin:9",start.publishedVersionKey());
        var schema = new ObjectMapper().valueToTree(start.parametersSchema());
        assertEquals("object", schema.path("type").asText());
        assertFalse(schema.has("additionalProperties"), "根不能把显式 browser 分支误拒绝");
        assertEquals(2, schema.path("oneOf").size());
        var defaults = schema.path("oneOf").get(0);
        assertEquals("object", defaults.path("type").asText());
        assertEquals(0, defaults.path("properties").size());
        assertEquals(0, defaults.path("required").size());
        assertFalse(defaults.path("additionalProperties").asBoolean(true));
        var explicit = schema.path("oneOf").get(1);
        assertEquals("object", explicit.path("type").asText());
        assertEquals("browser", explicit.path("required").get(0).asText());
        assertFalse(explicit.path("additionalProperties").asBoolean(true));
        var browser=explicit.path("properties").path("browser");
        assertEquals(1,browser.path("minLength").asInt());
        assertTrue(browser.path("description").asText().contains("省略此字段"));
        assertTrue(start.description().contains("传 {}"));
    }

    /** 平台目录承担启动、续接、查询和停止的职责边界，不依赖 Skill 内 transport 才能使用。 */
    @Test
    void browserLifecycleGuidancePublishesExactTaskSourcesAndSafeContinuation() {
        var start = entry("browser_start");
        assertTrue(start.description().contains("等待连接时尚无可用 task"));
        assertTrue(start.description().contains("由服务端继续同一调用"));
        assertTrue(start.description().contains("成功启动回执中的完整 task UUID"));
        var call = entry("browser_call");
        var taskSchema = (Map<?, ?>) inputProperties("browser_call").get("task");
        assertTrue(taskSchema.get("description").toString().contains("尚无已启动任务时先调用 browser_start {}"));
        assertTrue(taskSchema.get("description").toString().contains("缺少引用时先查询 browser_tasks"));
        assertTrue(call.description().contains("read_image"));
        assertTrue(call.description().contains("media_id"));
        assertTrue(call.description().contains("UNKNOWN"));
        var stop = entry("browser_stop");
        assertEquals("builtin:7", stop.publishedVersionKey());
        assertTrue(stop.description().contains("不为停止任务而新建 browser_start"));
        assertTrue(stop.description().contains("预算收尾保留一次必要停止"));
        assertTrue(stop.description().contains("成果最后冻结和最终答复之前"));
        assertTrue(stop.description().contains("完整上游回执独立持久"));
        var tasks = entry("browser_tasks");
        assertEquals("builtin:6", tasks.publishedVersionKey());
        assertEquals(List.of(), tasks.parametersSchema().get("required"));
        assertEquals(Map.of(), tasks.parametersSchema().get("properties"));
        assertTrue(tasks.description().contains("以 {} 只读查询"));
        assertTrue(tasks.description().contains("不按最新记录或列表位置选择"));
        assertTrue(tasks.description().contains("UNKNOWN"));
        for (var tool : List.of(start, call, stop, tasks)) {
            assertTrue(tool.description().contains(tool.name()));
            assertTrue(tool.description().contains("CLI"));
            assertTrue(tool.description().contains("Shell"));
        }
        for (String name : List.of("browser_call", "browser_stop")) {
            var task = (Map<?, ?>) inputProperties(name).get("task");
            assertEquals("string", task.get("type"));
            assertTrue(task.get("description").toString().contains("完整 task UUID"));
            assertTrue(task.get("description").toString().contains("browser_tasks"));
            assertFalse(task.containsKey("pattern"), "本批保留 Handler 对错误 task 的准确拒绝反馈");
        }
    }

    @Test
    void standaloneExecutionDefinitionsCarryDirectoryLifecycleInVisibleInputs() {
        for (String name : List.of("code_execute", "run_code", "workspace_files", "workspace_exec")) {
            String field = switch (name) {
                case "workspace_files" -> "op";
                case "workspace_exec" -> "command";
                default -> "use_workspace";
            };
            String description = ((Map<?, ?>) inputProperties(name).get(field)).get("description").toString();
            for (String rule : List.of("/workspace/project 跨调用保留", "/workspace/work 和 /workspace/output 每次独立",
                    "不继承前次文件", "同次程序", "output_files 收集")) assertTrue(description.contains(rule), name + ": " + rule);
        }
        String code = entry("code_execute").description();
        assertTrue(code.contains("stdout_withheld/stdout_unavailable/stdout_truncated"));
        assertTrue(code.contains("未呈现部分保持未知"));
        assertTrue(entry("finalize_task_outputs").description().contains("真实文件引用、读取权限、格式与版本等事实"));
    }

    @Test
    void runCodeDeliveryGuidanceDoesNotRequireAnAbsentCodeExecuteTool() {
        String workspace = ((Map<?, ?>) inputProperties("run_code").get("use_workspace")).get("description").toString();
        assertTrue(workspace.contains("当前已提供且支持 use_workspace 和 output_files 的代码工具"));
        assertTrue(workspace.contains("显式 use_workspace=true"));
        assertTrue(workspace.contains("不调用未提供的工具"));
        assertFalse(workspace.contains("code_execute("));
        assertTrue(inputProperties("run_code").containsKey("output_files"));
    }

    @Test
    void abandonmentGuidanceRejectsPartialPublicationAndGoalCompletionTogether() {
        String abandonment = ((Map<?, ?>) inputProperties("finalize_task_outputs").get("abandon_reason"))
                .get("description").toString();
        assertTrue(abandonment.contains("必须传 deliverables:[]"));
        assertTrue(abandonment.contains("省略 goal_completion"));
        assertTrue(abandonment.contains("不能同时发布部分成果或完成 Goal"));
        assertEquals(List.of("deliverables"), entry("finalize_task_outputs").parametersSchema().get("required"));
    }

    @Test
    void candidateGuidanceSeparatesCollectionReuseAndFinalPublication() {
        for (String name : List.of("workspace_files", "workspace_exec")) {
            String description = entry(name).description();
            assertTrue(description.contains("本工具不收集候选"));
            assertTrue(description.contains("tools.call 子调用禁止访问工作区"));
        }
        for (String name : List.of("code_execute", "run_code")) {
            var inputs = inputProperties(name);
            String reuse = ((Map<?, ?>) inputs.get("artifact_inputs")).get("description").toString();
            assertTrue(reuse.contains("ready 候选"));
            assertTrue(reuse.contains("无需先正式发布"));
            String collection = ((Map<?, ?>) inputs.get("output_files")).get("description").toString();
            assertTrue(collection.contains("必要中间文件"));
            assertTrue(collection.contains("宿主自动准入收尾预算"));
            assertTrue(collection.contains("finalize_task_outputs 只选择已完成的最终成果"));
            assertTrue(collection.contains("公开检查"));
        }
        assertTrue(entry("render_artifact").description().contains("ready 候选"));
        assertTrue(entry("render_artifact").description().contains("无需先正式发布"));
        assertFalse(entry("render_artifact").description().contains("先登记最终成果计划"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void workspaceToolsPublishClosedInputsWithConservativeGovernance() {
        for (String name : List.of("workspace_files", "workspace_exec", "workspace_checkpoint")) {
            assertGovernance(name, "NON_IDEMPOTENT_WRITE", false, "NEVER",
                    com.skillforge.execution.ExecutionLimits.MAX_TIMEOUT_SECONDS * 1000L,
                    true, true, false, Set.of());
            assertEquals(switch(name) { case "workspace_checkpoint" -> "builtin:3"; case "workspace_exec" -> "builtin:6"; default -> "builtin:5"; }, entry(name).publishedVersionKey());
            assertEquals(false, entry(name).parametersSchema().get("additionalProperties"));
            assertFalse(inputProperties(name).keySet().stream()
                    .anyMatch(key -> Set.of("workspace_id", "tenant_id", "session_id", "url").contains(key)));
            assertTrue(entry(name).description().contains("tools.call"));
        }
        assertEquals(List.of("op"), entry("workspace_files").parametersSchema().get("required"));
        assertEquals(List.of("list", "read", "write", "patch", "mkdir", "move", "delete", "import"),
                ((Map<String, Object>) inputProperties("workspace_files").get("op")).get("enum"));
        assertEquals(Set.of("op", "path", "target_path", "content", "old_text", "new_text", "expected_revision",
                "expected_sha256", "offset", "limit", "artifact_inputs", "attachment_ids"),
                inputProperties("workspace_files").keySet());
        assertEquals(List.of("command"), entry("workspace_exec").parametersSchema().get("required"));
        assertEquals(Set.of("command", "cwd", "timeout_seconds", "skill_handle"), inputProperties("workspace_exec").keySet());
        assertEquals(List.of("operation"), entry("workspace_checkpoint").parametersSchema().get("required"));
        assertEquals(List.of("status", "list", "create", "restore"),
                ((Map<String, Object>) inputProperties("workspace_checkpoint").get("operation")).get("enum"));
        for (String name : List.of("code_execute", "run_code")) {
            var choice = (Map<String, Object>) inputProperties(name).get("use_workspace");
            assertEquals("boolean", choice.get("type"));
            assertEquals(false, choice.get("default"));
            assertFalse(((List<String>) entry(name).parametersSchema().get("required")).contains("use_workspace"));
        }
    }

    @Test void memorySearchPublishesOneOptionalGroupSubjectWithoutOwnerOverrides() {
        var tool=entry(MemorySearchToolContract.NAME);
        assertEquals(Set.of(MemorySearchToolContract.QUERY,MemorySearchToolContract.LIMIT,MemorySearchToolContract.SUBJECT_END_USER_ID),
                inputProperties(MemorySearchToolContract.NAME).keySet());
        assertEquals(List.of(MemorySearchToolContract.QUERY),tool.parametersSchema().get("required"));
        var subject=(Map<?,?>)inputProperties(MemorySearchToolContract.NAME).get(MemorySearchToolContract.SUBJECT_END_USER_ID);
        assertEquals("string",subject.get("type"));
        assertEquals("^[1-9][0-9]{0,18}$",subject.get("pattern"));
        assertTrue(tool.description().contains("当前发言者"));
        assertTrue(tool.description().contains("私聊不传"));
        assertEquals("builtin:4",tool.publishedVersionKey());
    }

    @Test void contextReferenceSchemaDistinguishesContextAndSourceEventIds() {
        var tool = entry("read_context_reference");
        assertEquals("builtin:16", tool.publishedVersionKey());
        assertTrue(tool.description().contains("扫描20条原事件"));
        assertTrue(tool.description().contains("将 next_sequence 作为 after_sequence 继续"));
        var properties = (Map<?, ?>) tool.parametersSchema().get("properties");
        var reference = (Map<?, ?>) properties.get("reference_id");
        String pattern = (String) reference.get("pattern");
        assertTrue(("context:" + "a".repeat(64)).matches(pattern));
        assertFalse(("tool-result:" + "a".repeat(64)).matches(pattern));
        assertTrue(((String) reference.get("description")).contains("mode=history"));
        assertEquals(ContextReferenceLimits.MAX_PAGE_CHARACTERS, ((Map<?,?>) properties.get("limit")).get("maximum"));
        assertEquals(200, ((Map<?, ?>) properties.get("find")).get("maxLength"));
        assertTrue(tool.description().contains("match_found=false"));
        assertTrue(tool.description().contains("实际单页最多" + ContextReferenceLimits.MAX_MODEL_PAGE_CHARACTERS + "字符"));
        assertTrue(tool.description().contains("按真实next_offset继续读取"));
        assertTrue(tool.description().contains("从offset=0开始"));
        assertTrue(tool.description().contains("连续覆盖全部区间"));
        assertTrue(tool.description().contains("跳到末页的end=true不证明中间正文已读"));
        assertTrue(tool.description().contains("不表示正文已呈现或已读完"));
        assertTrue(tool.description().contains("没有累计读取总量上限"));
        assertTrue(tool.description().contains("不重做原动作"));
        assertTrue(tool.description().contains("拒绝时不换引用绕过"));
    }

    @Test void contextReferenceOutputDeclaresStructuredArgumentFailureWithoutInventingAPage() {
        var output = entry("read_context_reference").outputSchema();
        assertEquals(List.of("success"), output.get("required"));
        var properties = (Map<?, ?>) output.get("properties");
        assertEquals("string", ((Map<?, ?>) properties.get("error")).get("type"));
        var fields = (Map<?, ?>) properties.get("field_errors");
        assertEquals("object", fields.get("type"));
        assertEquals(Map.of("type", "string"), fields.get("additionalProperties"));
        assertTrue(properties.containsKey("end"));
        assertTrue(properties.containsKey("records"));
        var records = (Map<?, ?>) properties.get("records");
        var item = (Map<?, ?>) records.get("items");
        var recordProperties = (Map<?, ?>) item.get("properties");
        assertEquals("boolean", ((Map<?, ?>) recordProperties.get("content_truncated")).get("type"));
        assertEquals("boolean", ((Map<?, ?>) recordProperties.get("query_match_in_content")).get("type"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void outputContractsDistinguishOmittedBaseAndDeclareRecoverableFeedback() {
        for (String tool : List.of("finalize_task_outputs")) {
            var deliverables = (Map<String,Object>) inputProperties(tool).get("deliverables");
            var item = (Map<String,Object>) deliverables.get("items");
            var fields = (Map<String,Object>) item.get("properties");
            var base = (Map<String,Object>) fields.get("base_revision_id");
            assertEquals(1, base.get("minLength"));
            assertFalse("".matches(base.get("pattern").toString()));
            assertFalse("  ".matches(base.get("pattern").toString()));
            assertFalse(((List<String>) item.get("required")).contains("base_revision_id"));
            var kind = (Map<String,Object>) fields.get("kind");
            assertEquals(List.of("document", "spreadsheet", "presentation", "website", "image", "code", "configuration", "media", "data"),
                    kind.get("enum"));
            assertEquals(PlatformToolCatalog.DELIVERABLE_KINDS, kind.get("enum"));
            assertFalse(((List<String>) kind.get("enum")).contains("report"));
            assertTrue(((String) kind.get("description")).contains("document"));
            assertEquals("builtin:11", entry(tool).publishedVersionKey());
        }
        var finalization = (Map<String,Object>) inputProperties("finalize_task_outputs").get("deliverables");
        var finalizedItem = (Map<String,Object>) finalization.get("items");
        assertEquals(Set.of("key", "title", "kind", "base_revision_id", "representations"), ((Map<?,?>) finalizedItem.get("properties")).keySet());
        assertEquals(List.of("key", "title", "kind", "representations"), finalizedItem.get("required"));
        assertEquals(false, finalizedItem.get("additionalProperties"));
        var representationList = (Map<String,Object>) ((Map<?,?>) finalizedItem.get("properties")).get("representations");
        var representation = (Map<String,Object>) representationList.get("items");
        var representationFields = (Map<String,Object>) representation.get("properties");
        assertEquals(List.of("role", "format", "primary_preview", "files"), representation.get("required"));
        assertEquals(1, ((Map<?,?>) representationFields.get("files")).get("minItems"));
        assertEquals(List.of("source", "export", "bundle"), ((Map<?,?>) representationFields.get("role")).get("enum"));
        assertTrue(entry("finalize_task_outputs").description().contains("完整交付清单"));
        assertTrue(catalog.entries().stream().noneMatch(value -> value.name().equals("plan_task_outputs")));
        assertTrue(outputProperties("finalize_task_outputs").containsKey("corrections"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void freezesGoalCompletionWithOnlyRevisionAndExposesExactPublicationFacts() {
        var completion = (Map<String,Object>) inputProperties("finalize_task_outputs").get("goal_completion");
        assertEquals(false, completion.get("additionalProperties"));
        assertEquals(List.of("expected_revision"), completion.get("required"));
        var fields = (Map<String,Object>) completion.get("properties");
        assertEquals(Set.of("expected_revision"), fields.keySet());
        var revision = (Map<String,Object>) fields.get("expected_revision");
        assertEquals("integer", revision.get("type"));
        assertEquals(1, revision.get("minimum"));
        assertEquals("builtin:11", entry("finalize_task_outputs").publishedVersionKey());
        assertTrue(outputProperties("finalize_task_outputs").containsKey("completion_pending"));
        for (String tool : List.of("get_goal", "update_goal")) {
            assertOutputFields(tool, Set.of("success", "error", "goal", "continuation_enabled", "completion_pending", "publication"));
            var publication = (Map<String,Object>) outputProperties(tool).get("publication");
            assertEquals(List.of("object", "null"), publication.get("type"));
            assertEquals(false, publication.get("additionalProperties"));
            var facts = (Map<String,Object>) publication.get("properties");
            assertEquals(Set.of("run_id", "result_ref", "status", "reason", "revision_refs"), facts.keySet());
            assertEquals(List.of("pending", "published", "failed", "cancelled"), ((Map<?,?>) facts.get("status")).get("enum"));
            assertEquals("update_goal".equals(tool) ? "builtin:6" : "builtin:4", entry(tool).publishedVersionKey());
            var goal = (Map<String,Object>) outputProperties(tool).get("goal");
            var goalFields = (Map<String,Object>) goal.get("properties");
            var rounds = (Map<String,Object>) goalFields.get("max_rounds");
            assertEquals(com.skillforge.runtime.session.SessionGoal.MIN_ROUNDS, rounds.get("minimum"));
            assertEquals(com.skillforge.runtime.session.SessionGoal.MAX_ROUNDS, rounds.get("maximum"));
        }
    }

    private static final Set<String> DESCRIPTOR_FIELDS = Set.of(
            "model_invocable", "user_invocable", "tool_name", "input_schema", "output_schema", "effect",
            "idempotent", "confirmation_policy", "timeout_ms", "sensitive_fields",
            "provider_requirements", "parallel_safe", com.skillforge.runtime.api.port.ToolPortal.ToolCallIdentityPolicy.DESCRIPTOR_FIELD);

    private final CapabilityDescriptorCodec codec = new CapabilityDescriptorCodec(
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));

    @Test
    void exposesAllPublishedStrictToolContracts() {
        assertEquals(List.of(
                "browser_start", "browser_call", "browser_stop", "browser_tasks", "read_image",
                "finalize_task_outputs", "get_current_time", "calculate_expression", "convert_datetime",
                "json_query", "json_schema_validate", "template_render", "http_request", "code_execute",
                "workspace_files", "workspace_exec", "workspace_checkpoint",
                "render_artifact", "list_artifacts",
                "generate_image", "generate_video", "transcribe_audio", "synthesize_speech", "query_model_media_job",
                "search_memory", "search_tasks", "read_task", "update_task",
                "exit_plan_mode", "run_code", "get_goal", "update_goal", "search_tools", "read_context_reference"),
                catalog.entries().stream().map(PlatformToolCatalog.Entry::name).toList());
        assertEquals(34, catalog.names().size());
        assertFalse(catalog.names().contains("submit_team_plan"));

        for (PlatformToolCatalog.Entry entry : catalog.entries()) {
            assertFalse(entry.title().isBlank(), entry.name());
            assertFalse(entry.title().equals("调用工具"), entry.name());
            Map<String, Object> descriptor = entry.descriptor();
            assertEquals(DESCRIPTOR_FIELDS, descriptor.keySet(), entry.name());
            assertTrue(descriptor.keySet().stream().allMatch(key -> key.matches("[a-z][a-z0-9_]*")),
                    entry.name());
            assertEquals(Set.of("platform_handler:" + entry.name()),
                    descriptor.get("provider_requirements"), entry.name());
            assertEquals(64, entry.contentHash().length(), entry.name());
            assertTrue(entry.contentHash().matches("[0-9a-f]{64}"), entry.name());

            ToolVersion decoded = assertInstanceOf(ToolVersion.class, codec.decode(
                    new com.skillforge.core.capability.CapabilityRef(
                            CapabilityType.TOOL, "definition:" + entry.name(), entry.publishedVersionKey()),
                    null, entry.name(), entry.description(), "PLATFORM", "PUBLISHED",
                    entry.contentHash(), descriptor));
            assertEquals(entry.modelInvocable(), decoded.metadata().invocationPolicy().modelInvocable());
            assertEquals(entry.userInvocable(), decoded.metadata().invocationPolicy().userInvocable());
            if (Set.of("get_current_time", "calculate_expression", "convert_datetime", "json_query",
                    "json_schema_validate", "template_render", "http_request", "code_execute", "render_artifact", "list_artifacts")
                    .contains(entry.name())) {
                assertTrue(decoded.metadata().invocationPolicy().userInvocable(), entry.name());
            }
            assertEquals(entry.name(), decoded.definition().name());
            assertEquals(entry.parametersSchema(), decoded.definition().inputSchema());
            assertEquals(entry.outputSchema(), decoded.definition().outputSchema().orElseThrow());
            assertEquals(entry.parallelSafe(), decoded.policy().parallelSafe());
        }
    }

    @Test
    void toolInstructionsDescribeActualInputAndDeliveryStages() {
        assertFalse(catalog.names().contains("plan_task_outputs"));
        assertTrue(entry("finalize_task_outputs").description().contains("完整交付清单"));
        assertTrue(((Map<?, ?>) inputProperties("code_execute").get("output_files")).get("description").toString().contains("候选 ready 不代表正式发布"));
        assertTrue(inputProperties("finalize_task_outputs").containsKey("goal_completion"));
        assertTrue(entry("finalize_task_outputs").description().contains("冻结后只最终回复"));
        assertTrue(entry("get_goal").description().contains("publication"));
        assertTrue(entry("update_goal").description().contains("不提前 COMPLETE"));
    }

    @Test
    void presentationToolExampleUsesFrozenScriptWithoutInlineFieldsOrCopiedDependencies() throws Exception {
        var mapper = new ObjectMapper();
        Map<String, Object> example;
        try (var stream = getClass().getResourceAsStream("/official-skills/presentation/examples/presentation-tool-call.json")) {
            assertNotNull(stream, "完整调用示例必须随实际 Skill 包发布");
            example = mapper.readValue(stream, new TypeReference<>() {});
        }
        assertEquals(Set.of("mode", "language", "skill_handle", "script_path", "input_files", "output_files"), example.keySet());
        var arguments = example;
        var parsed = CodeExecutionArguments.parse(arguments);
        assertEquals("skill", parsed.mode());
        assertEquals("node", parsed.language());
        assertFalse(arguments.containsKey("code"), "Skill 模式不允许占位代码");
        assertFalse(arguments.containsKey("dependencies"), "冻结 manifest 依赖由宿主自动提供");
        assertEquals(List.of("presentation.pptx"), parsed.outputFiles());
        assertFalse(arguments.containsKey("public_result_file"));
        try (var stream = getClass().getResourceAsStream("/official-skills/presentation/manifest.json")) {
            assertNotNull(stream);
            var manifest = mapper.readTree(stream);
            assertEquals(manifest.path("entry").path("script_path").asText(), parsed.scriptPath());
        }
        var input = mapper.readTree(parsed.inputFiles().get("presentation.json"));
        assertEquals(2, input.path("schema_version").asInt());
        assertFalse(input.path("title").asText().isBlank());
        assertEquals("tech-innovation", input.path("theme").path("preset").asText());
        assertEquals(8, input.path("slides").size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executionToolsExposeAdmissionDiagnosticsWithoutRequiringThemForRuntimeFailures() {
        for (String name : List.of("code_execute", "run_code", "render_artifact")) {
            var diagnostic = (Map<String, Object>) outputProperties(name).get("diagnostic");
            assertEquals(false, diagnostic.get("additionalProperties"), name);
            assertEquals(List.of("status"), diagnostic.get("required"), name);
            var properties = (Map<String, Object>) diagnostic.get("properties");
            assertEquals(Set.of("status", "code", "field", "message", "hint", "stream", "text", "truncated"),
                    properties.keySet(), name);
            assertEquals(List.of("available", "unavailable", "withheld"), ((Map<?, ?>) properties.get("status")).get("enum"), name);
        }
    }

    @Test
    void carriesExactGovernanceAndExplicitOutputSchemas() {
        for (String name : List.of(
                "get_current_time", "calculate_expression", "convert_datetime", "json_query",
                "json_schema_validate", "template_render")) {
            assertGovernance(name, "READ_ONLY", true, "NEVER", 5_000, true, true, true, Set.of());
        }
        assertEquals("builtin:6", entry("http_request").publishedVersionKey());
        assertGovernance("http_request", "NON_IDEMPOTENT_WRITE", false, "NEVER", OperationalSettingsDefaults.HTTP_MAX_TIMEOUT_SECONDS * 1000L, true, true, false,
                Set.of("headers"));
        assertGovernance("code_execute", "NON_IDEMPOTENT_WRITE", false, "NEVER", com.skillforge.execution.ExecutionLimits.MAX_TIMEOUT_SECONDS*1000L, true, true, false,
                Set.of());
        assertGovernance("render_artifact", "NON_IDEMPOTENT_WRITE", false, "NEVER", com.skillforge.execution.ExecutionLimits.MAX_TIMEOUT_SECONDS*1000L, true, true, false,
                Set.of());
        assertGovernance("run_code", "NON_IDEMPOTENT_WRITE", false, "NEVER", com.skillforge.execution.ExecutionLimits.MAX_TIMEOUT_SECONDS*1000L, true, false, false,
                Set.of());
        assertGovernance("list_artifacts", "READ_ONLY", true, "NEVER", 10_000, true, true, true,
                Set.of());
        assertGovernance("read_context_reference", "READ_ONLY", true, "NEVER", 10_000, true, false, true,
                Set.of());

        for (String name : List.of("search_memory", "search_tasks", "read_task")) {
            assertGovernance(name, "READ_ONLY", true, "NEVER", 10_000, true, false, true, Set.of());
            assertEquals(switch (name) { case "search_memory" -> "builtin:4"; case "search_tasks" -> "builtin:3"; default -> "builtin:2"; }, entry(name).publishedVersionKey());
        }
        assertGovernance("update_task", "IDEMPOTENT_WRITE", true, "NEVER", 10_000, true, false, false, Set.of());
        assertGovernance("exit_plan_mode", "IDEMPOTENT_WRITE", true, "NEVER", 10_000,
                true, false, false, Set.of());

        assertOutputFields("get_current_time",
                Set.of("success", "timezone", "iso_8601", "epoch_millis", "error"));
        assertOutputFields("calculate_expression", Set.of("success", "value", "error"));
        assertOutputFields("convert_datetime", Set.of("success", "timezone", "value", "error"));
        assertOutputFields("json_query", Set.of("success", "found", "value", "error"));
        assertOutputFields("json_schema_validate", Set.of("success", "valid", "errors", "error"));
        assertOutputFields("template_render", Set.of("success", "rendered", "error"));
        assertOutputFields("http_request",
                Set.of("success", "status", "content_type", "body", "truncated", "error"));
        assertOutputFields("code_execute", Set.of(
                "success", "state", "phase", "exit_code", "logs", "diagnostic",
                "stdout", "stdout_withheld", "stdout_unavailable",
                "stdout_truncated", "stderr_truncated", "error", "delivery_status", "delivery_error",
                "attachment_inputs", "input_provenance", "artifacts", "public_presentation"));
        assertOutputFields("render_artifact", Set.of(
                "success", "state", "phase", "exit_code", "logs", "diagnostic",
                "stdout_truncated", "stderr_truncated", "error", "delivery_status", "delivery_error",
                "artifacts", "public_presentation"));
        assertOutputFields("run_code", Set.of(
                "success", "state", "phase", "exit_code", "logs", "diagnostic",
                "stdout_truncated", "stderr_truncated", "error", "delivery_status", "delivery_error",
                "attachment_inputs", "input_provenance", "artifacts", "public_presentation"));
        assertAttachmentInputsOutputSchema("code_execute");
        assertAttachmentInputsOutputSchema("run_code");
        assertOutputFields("exit_plan_mode", Set.of("success", "approved", "feedback", "error"));

        assertEquals("builtin:4", entry("exit_plan_mode").publishedVersionKey());
        assertEquals(List.of("plan"), entry("exit_plan_mode").parametersSchema().get("required"));

        PlatformToolCatalog.Entry codeExecute = entry("code_execute");
        assertEquals("builtin:28", codeExecute.publishedVersionKey());
        for (String name : List.of("code_execute", "run_code")) {
            assertFalse(inputProperties(name).containsKey("public_result_file"));
            String description = ((Map<?, ?>) inputProperties(name).get("code")).get("description").toString();
            assertTrue(description.contains("/workspace/output/result.json"));
            assertTrue(description.contains("执行成功后自动采纳"));
            assertTrue(description.contains("文件缺失合法"));
            assertTrue(description.contains("format仅支持plain或markdown"));
            assertTrue(description.contains("JSON业务对象使用format=plain"));
            assertTrue(description.contains("禁止json或text/plain"));
            assertTrue(description.contains("{\"results\":[{\"kind\":\"text\",\"format\":\"plain\",\"text\":"));
            assertTrue(description.contains("本次实际读取或计算的值"));
            assertTrue(description.contains("无需结果路径参数"));
            assertTrue(description.contains("output_files禁止result.json及result.json/"));
            assertTrue(description.contains("不接受任意JSON根对象或results字符串"));
            assertTrue(description.contains("最多8MiB"));
            assertTrue(description.contains("kind=table、columns=[{key,label}]、rows标量列表"));
        }
        String inlineCode = ((Map<?, ?>) inputProperties("code_execute").get("code")).get("description").toString();
        assertFalse(inlineCode.contains("tools.call"), "普通代码执行不提供工具编排 API");
        assertFalse(inlineCode.contains("run_code"), "公共结果协议不能引入另一代码工具");
        assertEquals(32768, ((Map<?, ?>) outputProperties("code_execute").get("stdout")).get("maxLength"));
        assertEquals(Map.of("type", "string", "maxLength", 32768), outputProperties("code_execute").get("stdout"));
        for (String tool : List.of("code_execute", "run_code", "render_artifact")) {
            var logs = (Map<?, ?>) outputProperties(tool).get("logs");
            var fields = (Map<?, ?>) ((Map<?, ?>) logs.get("items")).get("properties");
            assertEquals(Map.of("type", "integer"), fields.get("characters"));
        }
        assertTrue(codeExecute.description().contains("stdout_withheld/stdout_unavailable/stdout_truncated"));
        assertTrue(codeExecute.description().contains("未呈现部分保持未知"));
        assertTrue(codeExecute.description().contains("不证明打印的条件成立"));
        assertTrue(codeExecute.description().contains("不自动成为公开结果或正式成果"));
        assertEquals(List.of("inline", "skill"), ((Map<?, ?>) inputProperties("code_execute").get("mode")).get("enum"));
        assertTrue(((Map<?, ?>) inputProperties("code_execute").get("code")).get("description").toString().contains("skill 模式必须省略整个字段"));
        var attachmentSelection = (Map<?, ?>) inputProperties("code_execute").get("attachment_ids");
        assertTrue(attachmentSelection.get("description").toString().contains("每次调用都必须填写本字段"));
        assertTrue(attachmentSelection.get("description").toString().contains("上一调用选择不继承"));
        assertTrue(attachmentSelection.get("description").toString().contains("/input/data/<input_path>"));
        assertEquals(List.of("mode", "language"), codeExecute.parametersSchema().get("required"));
        assertEquals(false, codeExecute.parametersSchema().get("additionalProperties"));
        assertAttachmentIdsInputSchema("code_execute");

        PlatformToolCatalog.Entry renderArtifact = entry("render_artifact");
        assertEquals("builtin:11", renderArtifact.publishedVersionKey());
        assertEquals(List.of("artifact_id", "path", "format"), renderArtifact.parametersSchema().get("required"));
        assertEquals(Set.of("artifact_id", "path", "format", "output_name"), inputProperties("render_artifact").keySet());


        PlatformToolCatalog.Entry runCode = entry("run_code");
        assertEquals("builtin:22", runCode.publishedVersionKey());
        String program = ((Map<?, ?>) inputProperties("run_code").get("code")).get("description").toString();
        assertTrue(program.contains("最多" + com.skillforge.execution.ExecutionLimits.PROGRAM_CALLS + "次"));
        assertFalse(runCode.description().contains("console.log("));
        assertTrue(program.contains("tools.call(name, arguments)"));
        assertTrue(program.contains("不复制状态、内部标识或私有诊断"));
        assertFalse(runCode.description().contains("tool:真实句柄"));
        assertTrue(program.contains("不传 language 或 mode"));
        assertEquals(List.of("code"), runCode.parametersSchema().get("required"));
        assertEquals(false, runCode.parametersSchema().get("additionalProperties"));
        Map<String, Object> runCodeProperties = inputProperties("run_code");
        assertEquals(Set.of("code", "dependencies", "attachment_ids", "artifact_inputs", "input_files", "output_files", "use_workspace"),
                runCodeProperties.keySet());
        assertAttachmentIdsInputSchema("run_code");
        assertFalse(runCodeProperties.containsKey("language"));
        assertFalse(runCodeProperties.containsKey("mode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> codeSchema = (Map<String, Object>) runCodeProperties.get("code");
        assertTrue(codeSchema.get("description").toString().contains("工具固定 Node.js 和 inline"));

        PlatformToolCatalog.Entry listArtifacts = entry("list_artifacts");
        assertEquals("builtin:7", listArtifacts.publishedVersionKey());
        assertTrue(listArtifacts.description().contains("需要查找、复用或修改"));
        assertTrue(listArtifacts.description().contains("无需先查清单"));
        assertTrue(listArtifacts.description().contains("has_more 只表示可继续读取"));
        assertFalse(listArtifacts.description().contains("code_execute"), "成果查询不能要求特定代码工具已挂载");
        assertTrue(inputProperties("run_code").containsKey("artifact_inputs"));
        assertTrue(inputProperties("code_execute").containsKey("artifact_inputs"));

        Map<String, Object> jsonQueryProperties = outputProperties("json_query");
        // json_query returns an arbitrary JSON node, so its value schema is the one real
        // polymorphic {} schema in this catalog rather than an omitted output contract.
        assertEquals(Map.of(), jsonQueryProperties.get("value"));

        assertThrows(IllegalArgumentException.class, () -> PlatformToolCatalog.Entry.builder()
                .name("unsafe-read")
                .title("非法并行写入")
                .publishedVersionKey("test:1")
                .description("unsafe")
                .parametersSchema(Map.of())
                .outputSchema(Map.of())
                .effect(ToolEffect.IDEMPOTENT_WRITE)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(5_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(true)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build());
    }

    @Test
    void canonicalHashIgnoresMapAndSetConstructionOrderButCoversContent() {
        Map<String, Object> firstSchema = new LinkedHashMap<>();
        firstSchema.put("z", new LinkedHashSet<>(List.of("second", "first")));
        firstSchema.put("a", Map.of("nested_z", 2, "nested_a", 1));
        Map<String, Object> secondSchema = new LinkedHashMap<>();
        secondSchema.put("a", Map.of("nested_a", 1, "nested_z", 2));
        secondSchema.put("z", new LinkedHashSet<>(List.of("first", "second")));

        PlatformToolCatalog.Entry first = customEntry("hash-order", "same description", firstSchema);
        PlatformToolCatalog.Entry second = customEntry("hash-order", "same description", secondSchema);
        assertEquals(first.contentHash(), second.contentHash());
        assertTrue(first.contentHash().matches("[0-9a-f]{64}"));

        assertNotEquals(first.contentHash(),
                customEntry("hash-order-changed", "same description", firstSchema).contentHash());
        assertNotEquals(first.contentHash(),
                customEntry("hash-order", "changed description", firstSchema).contentHash());
        assertNotEquals(first.contentHash(),
                customEntry("hash-order", "same description", Map.of("changed", true)).contentHash());
    }

    @Test
    void initializerOnlyPublishesBridgeRequestsAndRepeatsIdentically() {
        CapabilityProducerPublicationBridge bridge = mock(CapabilityProducerPublicationBridge.class);
        PlatformToolDefinitionInitializer initializer = new PlatformToolDefinitionInitializer(bridge, catalog);

        initializer.run(null);
        initializer.run(null);

        ArgumentCaptor<CapabilityProducerPublicationRequest> captor =
                ArgumentCaptor.forClass(CapabilityProducerPublicationRequest.class);
        verify(bridge, times(catalog.entries().size() * 2)).publish(captor.capture());
        List<CapabilityProducerPublicationRequest> requests = captor.getAllValues();
        for (int index = 0; index < catalog.entries().size(); index++) {
            CapabilityProducerPublicationRequest first = requests.get(index);
            CapabilityProducerPublicationRequest repeated =
                    requests.get(index + catalog.entries().size());
            assertEquals(first, repeated);
            assertEquals(CapabilityCatalogScope.platform(), first.scope());
            assertEquals(CapabilityType.TOOL, first.capabilityType());
            assertEquals("tool:" + first.name(), first.capabilityKey());
            assertEquals(catalog.entries().get(index).publishedVersionKey(), first.versionKey());
            assertEquals(first.descriptor().keySet(), DESCRIPTOR_FIELDS);
        }

        Constructor<?>[] constructors = PlatformToolDefinitionInitializer.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertEquals(CapabilityProducerPublicationBridge.class, constructors[0].getParameterTypes()[0]);
        assertFalse(Arrays.stream(constructors[0].getParameterTypes())
                .map(Class::getName)
                .anyMatch(name -> name.contains("ToolDefinitionMapper")));
    }

    @Test
    void initializerRetriesKnownCatalogRaceWithTheIdenticalPublication() {
        CapabilityProducerPublicationBridge bridge = mock(CapabilityProducerPublicationBridge.class);
        when(bridge.publish(any(CapabilityProducerPublicationRequest.class)))
                .thenThrow(CapabilityCatalogErrors.exception(
                        HttpStatus.CONFLICT, CapabilityCatalogErrors.DUPLICATE,
                        "concurrent definition publication"))
                .thenAnswer(invocation -> null);
        PlatformToolDefinitionInitializer initializer = new PlatformToolDefinitionInitializer(bridge, catalog);

        initializer.run(null);

        ArgumentCaptor<CapabilityProducerPublicationRequest> captor =
                ArgumentCaptor.forClass(CapabilityProducerPublicationRequest.class);
        verify(bridge, times(catalog.entries().size() + 1)).publish(captor.capture());
        assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
        assertEquals(catalog.entries().getFirst().contentHash(), captor.getAllValues().get(1).contentHash());
    }

    @Test
    void initializerPropagatesConflictAfterTheSingleRetry() {
        CapabilityProducerPublicationBridge bridge = mock(CapabilityProducerPublicationBridge.class);
        ApiException conflict = CapabilityCatalogErrors.exception(
                HttpStatus.CONFLICT, CapabilityCatalogErrors.CONFLICT,
                "published version has different content");
        when(bridge.publish(any(CapabilityProducerPublicationRequest.class)))
                .thenThrow(conflict);
        PlatformToolDefinitionInitializer initializer = new PlatformToolDefinitionInitializer(bridge, catalog);

        ApiException actual = assertThrows(ApiException.class, () -> initializer.run(null));

        assertSame(conflict, actual);
        verify(bridge, times(2)).publish(any(CapabilityProducerPublicationRequest.class));
    }

    @Test
    void initializerDoesNotRetryOtherCatalogFailures() {
        CapabilityProducerPublicationBridge bridge = mock(CapabilityProducerPublicationBridge.class);
        ApiException invalid = CapabilityCatalogErrors.exception(
                HttpStatus.UNPROCESSABLE_ENTITY, CapabilityCatalogErrors.INVALID_ARGUMENT,
                "invalid publication");
        when(bridge.publish(any(CapabilityProducerPublicationRequest.class)))
                .thenThrow(invalid);
        PlatformToolDefinitionInitializer initializer = new PlatformToolDefinitionInitializer(bridge, catalog);

        ApiException actual = assertThrows(ApiException.class, () -> initializer.run(null));

        assertSame(invalid, actual);
        verify(bridge).publish(any(CapabilityProducerPublicationRequest.class));
    }

    private void assertGovernance(
            String name,
            String effect,
            boolean idempotent,
            String confirmationPolicy,
            long timeoutMs,
            boolean modelInvocable,
            boolean userInvocable,
            boolean parallelSafe,
            Set<String> sensitiveFields) {
        PlatformToolCatalog.Entry entry = catalog.entries().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
        Map<String, Object> descriptor = entry.descriptor();
        assertEquals(effect, descriptor.get("effect"), name);
        assertEquals(idempotent, descriptor.get("idempotent"), name);
        assertEquals(confirmationPolicy, descriptor.get("confirmation_policy"), name);
        assertEquals(timeoutMs, descriptor.get("timeout_ms"), name);
        assertEquals(modelInvocable, descriptor.get("model_invocable"), name);
        assertEquals(userInvocable, descriptor.get("user_invocable"), name);
        assertEquals(parallelSafe, descriptor.get("parallel_safe"), name);
        assertEquals(sensitiveFields, descriptor.get("sensitive_fields"), name);
    }

    private void assertOutputFields(String name, Set<String> fields) {
        PlatformToolCatalog.Entry entry = catalog.entries().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
        Map<String, Object> output = entry.outputSchema();
        assertEquals("object", output.get("type"), name);
        assertEquals(false, output.get("additionalProperties"), name);
        assertEquals(fields, outputProperties(name).keySet(), name);
    }

    @SuppressWarnings("unchecked")
    private void assertAttachmentIdsInputSchema(String name) {
        Map<String, Object> schema = (Map<String, Object>) inputProperties(name).get("attachment_ids");
        assertEquals("array", schema.get("type"), name);
        assertEquals(com.skillforge.core.chat.contract.ChatAttachmentLimits.MAX_FILES_PER_MESSAGE, schema.get("maxItems"), name);
        assertEquals(true, schema.get("uniqueItems"), name);
        assertEquals(Map.of("type", "string", "format", "uuid"), schema.get("items"), name);
    }

    @SuppressWarnings("unchecked")
    private void assertAttachmentInputsOutputSchema(String name) {
        Map<String, Object> array = (Map<String, Object>) outputProperties(name).get("attachment_inputs");
        assertEquals("array", array.get("type"), name);
        Map<String, Object> item = (Map<String, Object>) array.get("items");
        assertEquals(false, item.get("additionalProperties"), name);
        assertEquals(Set.of("id", "name", "input_path", "size", "sha256", "expires_at"),
                ((Map<String, Object>) item.get("properties")).keySet(), name);
        assertEquals(List.of("id", "name", "input_path", "size", "sha256", "expires_at"),
                item.get("required"), name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputProperties(String name) {
        PlatformToolCatalog.Entry entry = entry(name);
        return (Map<String, Object>) entry.outputSchema().get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inputProperties(String name) {
        return (Map<String, Object>) entry(name).parametersSchema().get("properties");
    }

    private PlatformToolCatalog.Entry entry(String name) {
        return catalog.entries().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private PlatformToolCatalog.Entry customEntry(
            String name, String description, Map<String, Object> parametersSchema) {
        return PlatformToolCatalog.Entry.builder()
                .name(name)
                .title("测试工具")
                .publishedVersionKey("test:1")
                .description(description)
                .parametersSchema(parametersSchema)
                .outputSchema(Map.of("type", "object"))
                .effect(ToolEffect.READ_ONLY)
                .idempotent(true)
                .confirmationPolicy(ToolConfirmationPolicy.NEVER)
                .timeoutMs(5_000)
                .modelInvocable(true)
                .userInvocable(false)
                .parallelSafe(false)
                .sensitiveFields(Set.of())
                .callIdentityPolicy(ToolCallIdentityPolicy.ARGUMENTS)
                .build();
    }
}
