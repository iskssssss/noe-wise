package com.skillforge.runtime.session;

import com.skillforge.runtime.json.RuntimeJson;
import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.port.ModelPort.ModelTool;
import com.skillforge.runtime.api.KnowledgeOperationLimits;
import com.skillforge.runtime.core.AgentAction;
import com.skillforge.runtime.core.AgentDecisionDecoder;
import com.skillforge.runtime.json.CodecLimits;
import java.util.*;
import org.junit.jupiter.api.Test;

class SessionNativeConversationTest {
    private static final CodecLimits LIMITS=new CodecLimits(262144,20000,64);
    /** 资源读取可同轮生成多个调用，但所有原生控制的实际并行提示仍为false。 */
    @Test void onlyNativeResourceReadDeclaresMultiCallGenerationAmongAgentActions() {
        var tools = SessionNativeConversation.actionTools(true, Set.of("ask_user", "activate_skill", "read_skill_resource", "dispatch",
                "knowledge_query", "knowledge_list_documents", "knowledge_read_document"));
        assertTrue(tools.stream().anyMatch(tool -> tool.wireName().equals("read_skill_resource")));
        for (var tool : tools) {
            assertEquals(com.skillforge.runtime.api.port.ModelPort.ModelToolKind.AGENT_ACTION, tool.kind());
            assertEquals(tool.wireName().equals("read_skill_resource"), tool.batchEligible(), tool.wireName());
            assertTrue(tool.description().startsWith("parallel_safe=false"), tool.wireName());
        }
    }

    @Test void knowledgeSchemasAndDecodedDefaultsUseTheOperationContract() throws Exception {
        var tools = SessionNativeConversation.actionTools(false, Set.of("knowledge_list_documents", "knowledge_read_document"));
        var mapper = new ObjectMapper();
        var list = mapper.readTree(tools.stream().filter(tool -> tool.wireName().equals("knowledge_list_documents"))
                .findFirst().orElseThrow().inputSchema().canonicalJson()).path("properties");
        var read = mapper.readTree(tools.stream().filter(tool -> tool.wireName().equals("knowledge_read_document"))
                .findFirst().orElseThrow().inputSchema().canonicalJson()).path("properties");
        assertEquals(KnowledgeOperationLimits.MAX_TITLE_FILTER_CHARACTERS, list.path("title_filter").path("maxLength").intValue());
        assertEquals(KnowledgeOperationLimits.MAX_CURSOR_CHARACTERS, list.path("page_cursor").path("maxLength").intValue());
        assertEquals(KnowledgeOperationLimits.MAX_PAGE_SIZE, list.path("page_size").path("maximum").intValue());
        assertEquals(KnowledgeOperationLimits.MAX_READ_CHARACTERS, read.path("max_characters").path("maximum").intValue());
        var decoder = new AgentDecisionDecoder(LIMITS);
        var decodedList = (AgentAction.KnowledgeListDocuments) ((AgentDecisionDecoder.Decoded) decoder.decodeWire(
                "{\"type\":\"knowledge_list_documents\",\"knowledge_handle\":\"knowledge:one\"}")).action();
        var decodedRead = (AgentAction.KnowledgeReadDocument) ((AgentDecisionDecoder.Decoded) decoder.decodeWire(
                "{\"type\":\"knowledge_read_document\",\"knowledge_handle\":\"knowledge:one\",\"source_id\":\"source\",\"document_id\":\"document\",\"source_version\":\"v1\"}")).action();
        assertEquals(list.path("page_size").path("default").intValue(), decodedList.operation().pageSize());
        assertEquals(read.path("offset").path("default").intValue(), decodedRead.operation().offset());
        assertEquals(read.path("max_characters").path("default").intValue(), decodedRead.operation().maxCharacters());
        assertInstanceOf(AgentDecisionDecoder.Failure.class, decoder.decodeWire(
                "{\"type\":\"knowledge_list_documents\",\"knowledge_handle\":\"knowledge:one\",\"page_size\":0}"));
        assertInstanceOf(AgentDecisionDecoder.Failure.class, decoder.decodeWire(
                "{\"type\":\"knowledge_read_document\",\"knowledge_handle\":\"knowledge:one\",\"source_id\":\"source\",\"document_id\":\"document\",\"source_version\":\"v1\",\"max_characters\":0}"));
    }

    @Test void submitSchemaDescribesCompleteFutureSetAndAllowsEmptyRevisionWithoutRelaxingTasks() throws Exception {
        var tool = SessionNativeConversation.actionTools(false, Set.of()).stream()
                .filter(t -> t.wireName().equals("plan_submit")).findFirst().orElseThrow();
        var schema = new ObjectMapper().readTree(tool.inputSchema().canonicalJson());
        var plan = schema.path("properties").path("plan");
        var tasks = plan.path("properties").path("tasks");
        assertEquals(0, tasks.path("minItems").intValue());
        assertEquals(100, tasks.path("maxItems").intValue());
        assertTrue(tasks.path("description").asText().contains("首次为全部初始任务且不能为空"));
        assertTrue(tasks.path("description").asText().contains("宿主补回原已准入定义"));
        assertFalse(tasks.path("items").path("additionalProperties").booleanValue());
        assertEquals(9, tasks.path("items").path("required").size());
        var replacement = tasks.path("items").path("properties").path("replaces_task_id");
        assertEquals("string", replacement.path("type").asText());
        assertTrue(replacement.path("description").asText().contains("普通同ID的未准入步骤修订填空字符串"));
        assertTrue(replacement.path("description").asText().contains("新建不同ID步骤替代旧步骤时填写旧ID，不得等于自身id"));
        assertTrue(replacement.path("description").asText().contains("原步骤已有执行时必须已终结且结果非UNKNOWN"));
        assertTrue(java.util.stream.StreamSupport.stream(tasks.path("items").path("required").spliterator(), false)
                .anyMatch(field -> field.asText().equals("replaces_task_id")));
        assertEquals(3, tasks.path("items").path("properties").path("inputs").path("items").path("required").size());
        assertTrue(tool.description().contains("修订可为空"));
    }

    @Test void planRevisionFieldsExplainTheirDistinctVersionDomain() {
        var tools = SessionNativeConversation.actionTools(false, Set.of());
        for (String name : List.of("plan_submit", "plan_start_step", "plan_finish_step")) {
            var tool = tools.stream().filter(t -> t.wireName().equals(name)).findFirst().orElseThrow();
            var schema = (Map<?, ?>) RuntimeJson.parseCanonical(tool.inputSchema().canonicalJson(), LIMITS);
            var field = (Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("expected_revision");
            String description = (String) field.get("description");
            assertTrue(description.contains("首次plan_submit填0"));
            assertTrue(description.contains("plan_get返回的plan_revision"));
            assertTrue(description.contains("不要使用工作快照revision或run_revision"));
        }
    }


    @Test void finishSchemaMakesHostOwnedReferencesOptional() {
        var tool=SessionNativeConversation.actionTools(false,Set.of()).stream().filter(t->t.wireName().equals("plan_finish_step")).findFirst().orElseThrow();
        var schema=(Map<?,?>)RuntimeJson.parseCanonical(tool.inputSchema().canonicalJson(),LIMITS);
        assertFalse(((List<?>)schema.get("required")).contains("result_refs"));
        var refs=(Map<?,?>)((Map<?,?>)schema.get("properties")).get("result_refs");
        assertTrue(refs.get("description").toString().contains("宿主"));
    }

    @Test void ordinaryPlanToolDescriptionsExplainHostWaitAndCurrentApprovalWithoutPlanModeModule() {
        var tools=SessionNativeConversation.actionTools(false,Set.of());
        var submit=tools.stream().filter(t->t.wireName().equals("plan_submit")).findFirst().orElseThrow();
        var get=tools.stream().filter(t->t.wireName().equals("plan_get")).findFirst().orElseThrow();
        var wait=tools.stream().filter(t->t.wireName().equals("plan_wait")).findFirst().orElseThrow();
        assertTrue(submit.description().contains("宿主已自动持久挂起"));
        assertTrue(submit.description().contains("不再额外调用plan_wait"));
        assertTrue(get.description().contains("approval_pending"));
        assertTrue(get.description().contains("false即可按plan_revision继续就绪步骤"));
        assertTrue(get.description().contains("approved_revision只记录已批准的精确版本"));
        assertTrue(wait.description().contains("wait_satisfied=true"));
        assertTrue(wait.description().contains("不再重复要审批"));
    }

    @Test void independentControlsAreClosedAndFinalAnswerIsNotAModelTool() {
        var controls = SessionNativeConversation.actionTools(false, Set.of("ask_user", "activate_skill", "read_skill_resource"));
        assertEquals(Set.of("ask_user", "use_skill", "read_skill_resource", "plan_submit", "plan_get", "plan_start_step", "plan_finish_step", "plan_wait"), controls.stream().map(ModelTool::wireName).collect(java.util.stream.Collectors.toSet()));
        for (var tool : controls) {
            var schema = (Map<?, ?>) RuntimeJson.parseCanonical(tool.inputSchema().canonicalJson(), LIMITS);
            assertFalse(schema.containsKey("oneOf"));
            assertEquals(false, schema.get("additionalProperties"));
            assertFalse(((Map<?, ?>) schema.get("properties")).containsKey("type"));
            if(tool.wireName().equals("read_skill_resource")) {
                assertTrue(tool.description().contains("固定包只读资源可与其他资源读取及无需确认的普通只读工具同轮调用"));
                assertFalse(tool.description().contains("每次 assistant 响应仅允许这一个 tool_call"));
            } else {
                assertTrue(tool.description().contains("每次 assistant 响应仅允许这一个 tool_call"));
                assertTrue(tool.description().contains("不能同时返回其他函数调用，包括普通只读工具"));
            }
        }
        for (String name : List.of("use_skill", "read_skill_resource")) {
            var description = controls.stream().filter(tool -> tool.wireName().equals(name)).findFirst().orElseThrow().description();
            assertTrue(description.contains("再构造后续业务调用"));
        }
        assertTrue(controls.stream().noneMatch(tool->tool.wireName().equals("final_answer")));
        assertTrue(com.skillforge.runtime.api.port.NativeAgentTools.find("final_answer").isEmpty());
        assertThrows(java.util.NoSuchElementException.class,()->com.skillforge.runtime.api.port.NativeAgentTools.wireName("respond",Optional.empty()));
    }

    @Test void delegationGuidanceUsesTheSamePublicNamesAsItsClosedSchemas() {
        var controls = SessionNativeConversation.actionTools(true, Set.of());
        var byName = controls.stream().collect(java.util.stream.Collectors.toMap(ModelTool::wireName, t -> t));
        var wait = byName.get("wait_delegates");
        var schema = (Map<?, ?>) RuntimeJson.parseCanonical(wait.inputSchema().canonicalJson(), LIMITS);
        assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("ids"));
        assertFalse(((Map<?, ?>) schema.get("properties")).containsKey("delegation_ids"));
        assertTrue(wait.description().contains("ids"));
        assertFalse(wait.description().contains("delegation_ids"));
        assertTrue(byName.containsKey("plan_submit"));
        assertFalse(byName.containsKey("submit_plan"));
        assertTrue(byName.get("message_delegate").description().contains("delegate_status"));
        assertTrue(byName.get("delegate_status").description().contains("省略 id"));
        assertTrue(controls.stream().noneMatch(t -> t.description().matches("(?s).*\\b(?:WAIT|SUBMIT_PLAN|LIST|GET)\\b.*")));
    }

    /** 完整冻结句柄唯一地进入START枚举，原有闭合参数及其他委派控制保持一致。 */
    @Test void delegationStartBindsExactFrozenHandlesAndEmptyDirectoryRemovesOnlyStart() throws Exception {
        var declarations = SessionNativeConversation.actionTools(true,Set.of());
        var handles = List.of("member:writer:revision-2","member:862143854526469:runtime-e2e-exact");
        var projected = SessionNativeConversation.withDelegationTargets(declarations,handles);
        var start = projected.stream().filter(tool -> tool.wireName().equals("delegate")).findFirst().orElseThrow();
        var schema = new ObjectMapper().readTree(start.inputSchema().canonicalJson());
        assertEquals(new ObjectMapper().valueToTree(handles.stream().sorted().toList()),schema.path("properties").path("target_handle").path("enum"));
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertTrue(schema.path("properties").has("instruction"));
        var empty = SessionNativeConversation.withDelegationTargets(declarations,List.of());
        assertEquals(declarations.stream().filter(tool -> !tool.wireName().equals("delegate")).toList(),empty);
        assertTrue(empty.stream().anyMatch(tool -> tool.wireName().equals("wait_delegates")));
        assertThrows(IllegalArgumentException.class,() -> SessionNativeConversation.withDelegationTargets(declarations,List.of("member:writer","member:writer")));
        assertThrows(IllegalArgumentException.class,() -> SessionNativeConversation.withDelegationTargets(declarations,List.of(" ")));
    }

}
