package com.skillforge.institution.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.execution.ExecuteRequest;
import com.skillforge.execution.ExecutionAdmissionRejection;
import com.skillforge.execution.ExecutionPolicy;
import com.skillforge.execution.ExecutionProfile;
import com.skillforge.execution.Language;
import com.skillforge.runtime.api.port.InvocationControl;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** HTTP 准入回执采用只证明未提交，普通失败和未知响应不能取得同样的权限。 */
class WorkflowScriptSandboxClientAdmissionTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void singleSubmissionDistinguishesProvenAdmissionRejectionFromServerFailure(boolean proven) throws Exception {
        var calls = new AtomicInteger();
        var json = new ObjectMapper().findAndRegisterModules();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/executions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            String response = proven
                    ? json.writeValueAsString(ExecutionAdmissionRejection.notSubmitted(
                            ExecutionAdmissionRejection.Code.INVALID_EXECUTION_MANIFEST))
                    : "{\"code\":\"INTERNAL_ERROR\",\"message\":\"failed\"}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(proven ? 400 : 500, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            var client = new WorkflowScriptSandboxClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    TOKEN, "workflow-python-v1", "workflow-node-v1");
            Instant deadline = Instant.now().plusSeconds(10);
            var execution = new ExecuteRequest("a".repeat(64), Language.PYTHON, "pass", List.of(),
                    List.of(), null, false, deadline, new ExecutionPolicy(1, 900, 15, 24, 60,
                    4 * 1024 * 1024, 2048, 4, "1g", new java.math.BigDecimal("2"), 256),
                    List.of(), ExecutionProfile.STANDARD, null, null);
            var request = new WorkflowScriptSandboxClient.PreparedRequest(execution.taskId(),
                    json.writeValueAsString(execution), List.of());
            if (proven) {
                var failure = assertThrows(ExecutionSubmissionRejectedException.class,
                        () -> client.submit(request, new InvocationControl(deadline, () -> false)));
                assertEquals(ExecutionAdmissionRejection.Submission.NOT_SUBMITTED, failure.rejection().submission());
            } else {
                assertThrows(WorkflowScriptSandboxClient.OutcomeUnknownException.class,
                        () -> client.submit(request, new InvocationControl(deadline, () -> false)));
            }
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
