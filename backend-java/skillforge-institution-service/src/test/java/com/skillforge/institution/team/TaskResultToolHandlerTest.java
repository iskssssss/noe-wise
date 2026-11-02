package com.skillforge.institution.team;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.skillforge.runtime.adapter.jdbc.session.JdbcDagWorkPort;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskResultToolHandlerTest {

    @Test
    void rejectsClientSuppliedExecutionIdentityBeforeReadingDagWork() {
        JdbcDagWorkPort works = mock(JdbcDagWorkPort.class);
        TeamResultService results = mock(TeamResultService.class);
        TaskResultToolHandler handler = new TaskResultToolHandler(works, results);

        Map<String, Object> response = handler.execute(Map.of("outputs", List.of(Map.of(
                "output_name", "answer", "title", "答复", "kind", "text", "status", "succeeded",
                "summary", "摘要", "content", "正文", "representations", List.of(),
                "limitations", List.of("纯文本"), "task_id", "forged-task"))), null);

        assertEquals(false, response.get("success"));
        assertEquals("task_result_output_rejected", response.get("error"));
        verifyNoInteractions(works, results);
    }

    @Test
    void rejectsOperationWithoutReceiptBeforeReadingDagWork() {
        assertRejected(output("operation"), "operation result requires receipt reference");
    }

    @Test
    void rejectsIncompleteOrExtendedOperationReceiptBeforeReadingDagWork() {
        Map<String, Object> incomplete = output("operation");
        incomplete.put("operation_receipt", Map.of(
                "action_ref", "action:1", "invocation_id", "invocation:1", "attempt_id", "attempt:1"));
        assertRejected(incomplete, "task_result_operation_receipt_rejected");

        Map<String, Object> extended = output("operation");
        extended.put("operation_receipt", Map.of(
                "action_ref", "action:1", "invocation_id", "invocation:1", "attempt_id", "attempt:1",
                "fact_ref", "fact:1", "url", "https://model.example.org/result"));
        assertRejected(extended, "task_result_operation_receipt_rejected");
    }

    @Test
    void rejectsReceiptOnNonOperationOutputBeforeReadingDagWork() {
        Map<String, Object> output = output("text");
        output.put("operation_receipt", Map.of(
                "action_ref", "action:1", "invocation_id", "invocation:1", "attempt_id", "attempt:1",
                "fact_ref", "fact:1"));
        assertRejected(output, "operation result requires receipt reference");
    }

    private static Map<String, Object> output(String kind) {
        Map<String, Object> output = new HashMap<>();
        output.put("output_name", "answer");
        output.put("title", "答复");
        output.put("kind", kind);
        output.put("status", "succeeded");
        output.put("summary", "摘要");
        output.put("representations", List.of());
        output.put("limitations", List.of("仅包含公开事实"));
        if ("text".equals(kind)) output.put("content", "正文");
        return output;
    }

    private static void assertRejected(Map<String, Object> output, String error) {
        JdbcDagWorkPort works = mock(JdbcDagWorkPort.class);
        TeamResultService results = mock(TeamResultService.class);
        TaskResultToolHandler handler = new TaskResultToolHandler(works, results);

        Map<String, Object> response = handler.execute(Map.of("outputs", List.of(output)), null);

        assertEquals(false, response.get("success"));
        assertEquals(error, response.get("error"));
        verifyNoInteractions(works, results);
    }
}
