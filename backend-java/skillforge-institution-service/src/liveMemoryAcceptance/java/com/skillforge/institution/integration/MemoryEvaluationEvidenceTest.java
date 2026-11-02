package com.skillforge.institution.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.chat.dto.response.ChatHistoryResponse;
import com.skillforge.core.chat.message.ChatMessageRecord;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 离线约束证据消费者边界：旧回答和未暴露的恶意事实不能被误判为本轮成功。 */
class MemoryEvaluationEvidenceTest {
    /** 第二轮没有final时，第一轮的有效回答不能被拿来评分。 */
    @Test void missingFinalNeverReusesPreviousRunAnswer() {
        var history = history(List.of(message("old", "run:first", "旧回答3500元")));
        assertEquals("", MemoryLiveAcceptance.answerForRun("run:second", history));
        assertEquals("旧回答3500元", MemoryLiveAcceptance.answerForRun("run:first", history));
    }

    /** 中间正文和不同Run回答被排除，final引用由公开metadata精确绑定。 */
    @Test void finalAnswerBelongsToExactRunOnly() {
        var middle = new ChatMessageRecord("middle", "assistant", "中间推测", Instant.EPOCH, Map.of("phase", "analysis", "run_id", "run:second"));
        var history = history(List.of(message("old", "run:first", "旧回答"), middle, message("new", "run:second", "最终回答")));
        assertEquals("最终回答", MemoryLiveAcceptance.answerForRun("run:second", history));
    }

    /** CANDIDATE和其他事实都不能证明恶意正文已进入主模型。 */
    @Test void injectionExposureRequiresInjectedExactFactIdentity() throws Exception {
        var json = new ObjectMapper();
        var candidate = json.readTree("{\"phase\":\"CANDIDATE\",\"facts\":[{\"ref\":{\"factId\":\"evil\"}}]}");
        var unrelated = json.readTree("{\"phase\":\"INJECTED\",\"facts\":[{\"ref\":{\"factId\":\"other\"}}]}");
        var injected = json.readTree("{\"phase\":\"INJECTED\",\"facts\":[{\"ref\":{\"factId\":\"evil\"}}]}");
        assertFalse(MemoryLiveAcceptance.injectedFactPresent(List.of(candidate, unrelated), Set.of("evil")));
        assertTrue(MemoryLiveAcceptance.injectedFactPresent(List.of(candidate, injected), Set.of("evil")));
    }

    /** Frozen Action目标按mount精确匹配；生成相似名字或未批准Workflow均不得放行。 */
    @Test void unauthorizedActionCannotPassByToolNameOrEmptyEventFilter() {
        var model = Map.<String, Object>of("execute_call_kind", "MODEL_STREAM", "execute_target_handle", "model:fixture");
        var allowed = Map.<String, Object>of("execute_call_kind", "TOOL_INVOKE", "execute_target_handle", "tool:123:input:456");
        var forged = Map.<String, Object>of("execute_call_kind", "TOOL_INVOKE", "execute_target_handle", "tool:search_memory-forged");
        var prefixCollision = Map.<String, Object>of("execute_call_kind", "TOOL_INVOKE", "execute_target_handle", "tool:1234");
        var workflow = Map.<String, Object>of("execute_call_kind", "WORKFLOW_DISPATCH", "execute_target_handle", "workflow:evil");
        assertTrue(MemoryLiveAcceptance.authorizedActions(List.of(model, allowed), Set.of("tool:123")));
        assertFalse(MemoryLiveAcceptance.authorizedActions(List.of(model, forged), Set.of("tool:123")));
        assertFalse(MemoryLiveAcceptance.authorizedActions(List.of(model, prefixCollision), Set.of("tool:123")));
        assertFalse(MemoryLiveAcceptance.authorizedActions(List.of(model, workflow), Set.of("tool:123")));
    }

    /** Spring全局snake命名不能改变评测契约；嵌套生产JsonNode与未知null保留原始事实。 */
    @Test void evidenceUsesCamelCaseUnderProductionSnakeMapper() throws Exception {
        var production = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var usage = new LinkedHashMap<String, Object>(); usage.put("inputTokens", 321L); usage.put("outputTokens", null);
        var call = new LiveMemoryBudgets.RuntimeCallEvidence(1, "attempt-1", "run-1", "tenant-1", "AUXILIARY_MODEL", "STARTED", "memory.extraction", usage);
        var context = new MemoryRecordingHttpClient.AttemptContext("attempt-1", "run-1", "AUXILIARY_MODEL", "MODEL_GENERATE", "HOST_AUXILIARY", "NONE");
        var wire = new MemoryRecordingHttpClient.RequestRecord(1, context, "memory.extraction", Instant.EPOCH,
                null, null, null, null, null, null, 200, 1, false, null, "SUCCEEDED", null, null, null, null, null,
                Map.of("usage.prompt_tokens", 321L), null, "candidate-output");
        var budget = new LiveMemoryBudgets.Report(1200, 1_000_000, 7200, 1, 1, 0, 14_048, 1, null, List.of());
        var stage = production.readTree("{\"run_id\":\"run-1\",\"extracted_facts\":[]}");
        var mapper = MemoryLiveAcceptance.evidenceMapper(production);
        var result = mapper.readTree(mapper.writeValueAsBytes(Map.of("runtimeCalls", List.of(call), "wireCalls", List.of(wire), "budget", budget, "stage", stage)));
        assertEquals("attempt-1", result.path("runtimeCalls").get(0).path("attemptId").asText());
        assertEquals("tenant-1", result.path("runtimeCalls").get(0).path("tenantId").asText());
        assertTrue(result.path("runtimeCalls").get(0).path("usage").path("outputTokens").isNull());
        assertEquals(1, result.path("wireCalls").get(0).path("requestSequence").asInt());
        assertEquals("attempt-1", result.path("wireCalls").get(0).path("attempt").path("attemptId").asText());
        assertEquals(1_000_000, result.path("budget").path("maximumTokens").asLong());
        assertEquals("run-1", result.path("stage").path("run_id").asText());
        assertFalse(result.path("stage").has("runId"));
        assertSame(PropertyNamingStrategies.SNAKE_CASE, production.getPropertyNamingStrategy());
        assertTrue(production.readTree(production.writeValueAsBytes(call)).has("attempt_id"), "生产mapper不被评测写盘修改");
    }

    /** 外键失败只保留静态元数据；Postgres detail/message失败行即使包含密文也不能进入证据。 */
    @Test void databaseDiagnosticPreservesOnlyApprovedMetadataAndNeverFailedRow() throws Exception {
        var serverType = Class.forName("org.postgresql.util.ServerErrorMessage");
        var exceptionType = Class.forName("org.postgresql.util.PSQLException");
        for (var identifiers : List.of(List.of("platform_models", "platform_model_current_revision_fk"), List.of("SECRET_TABLE", "SECRET_CONSTRAINT"))) {
            String wireError = "SERROR\0C23503\0t" + identifiers.get(0) + "\0n" + identifiers.get(1)
                    + "\0MSECRET_MESSAGE\0DFailing row contains (SECRET_MODEL_CIPHERTEXT)\0";
            Object server = serverType.getConstructor(String.class).newInstance(wireError);
            var postgres = (Throwable) exceptionType.getConstructor(serverType).newInstance(server);
            var failure = new org.springframework.dao.DataIntegrityViolationException("SECRET_SPRING_SQL_PARAMETERS", postgres);
            var diagnostic = MemoryLiveAcceptance.safeDatabaseDiagnostic(failure);
            assertEquals("23503", diagnostic.get("sqlState"));
            boolean approved = identifiers.get(0).equals("platform_models");
            assertEquals(approved ? identifiers.get(0) : "UNRECOGNIZED", diagnostic.get("table"));
            assertEquals(approved ? identifiers.get(1) : "UNRECOGNIZED", diagnostic.get("constraint"));
            assertFalse(new ObjectMapper().writeValueAsString(diagnostic).contains("SECRET"));
            assertEquals(Set.of("sqlState", "table", "constraint"), diagnostic.keySet());
        }
        assertTrue(MemoryLiveAcceptance.safeDatabaseDiagnostic(new IllegalStateException("SECRET")).isEmpty());
    }

    /** 公开历史夹具沿用正式响应结构。 */
    private static ChatHistoryResponse history(List<ChatMessageRecord> messages) {
        return new ChatHistoryResponse("session", messages, List.of(), List.of(), List.of(), ChatHistoryResponse.Collaboration.empty());
    }
    /** final消息必须明确带run_id，测试不能靠列表顺序代替归属。 */
    private static ChatMessageRecord message(String id, String run, String content) {
        return new ChatMessageRecord(id, "assistant", content, Instant.EPOCH, Map.of("phase", "final", "run_id", run));
    }
}
