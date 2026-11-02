package com.skillforge.institution.execution;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import com.skillforge.runtime.api.RunOriginRef;
import com.skillforge.runtime.api.RunRequester;
import com.skillforge.runtime.session.spi.ExecutionStore;
import com.skillforge.runtime.session.AgentExecutionCodec;
import com.skillforge.runtime.json.CodecLimits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.billing.invocation.ExternalInvocationGuard;
import com.skillforge.core.capability.connector.PlatformToolExecutionContext;
import com.skillforge.institution.artifact.ArtifactAccess;
import com.skillforge.institution.artifact.DeliverableService;
import com.skillforge.institution.artifact.ArtifactStorage;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.RunRef;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TraceEnvelope;
import com.skillforge.runtime.api.port.InvocationControl;
import com.skillforge.runtime.api.port.PortCallContext;
import com.skillforge.runtime.api.PrincipalRef;
import com.skillforge.runtime.api.port.RunIdentity;
import com.skillforge.runtime.api.port.SessionOwner;
import com.skillforge.runtime.api.port.VerifiedIdentity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 本地 HTTP 假 Runner 只验证宿主协议边界；容器真实执行由 Sandbox 模块证据覆盖。 */
class CodeExecutionToolHandlerTest {
    private static final AgentExecutionCodec CODEC=new AgentExecutionCodec(new CodecLimits(1_000_000,100_000,64));
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private HttpServer server;

    @Test
    void uploadedAttachmentBytesReachRunnerWithoutModelRecopying() throws Exception {
        String id = "12345678-1234-1234-1234-123456789abc";
        String path = "attachments/" + id + "/input.csv";
        byte[] bytes = "order,amount\nA,120\n".getBytes(StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var mapper = mock(com.skillforge.institution.attachment.SessionAttachmentMapper.class);
        var attachment = new com.skillforge.institution.attachment.SessionAttachmentRow(id, "1", "session", "orders.csv",
                path, bytes.length, hash, bytes, Instant.now(), Instant.now().plusSeconds(3600));
        org.mockito.Mockito.when(mapper.find("1", "session", id)).thenReturn(attachment);
        org.mockito.Mockito.when(mapper.findMetadataBatch("1","session",List.of(id))).thenReturn(List.of(attachment));
        var attachments = new com.skillforge.institution.attachment.SessionAttachmentService(mapper, mock(ArtifactAccess.class), authorizedBindings(),
                mock(com.skillforge.institution.attachment.SessionAttachmentBodyStore.class), executionStore(),CODEC,com.skillforge.institution.runtime.ProgramResourceOriginFixture.ordinary(executionStore()));
        var posted = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        startRunner(exchange -> {
            posted.set(readExecution(exchange));
            respond(exchange, 200, completed(0, "120", ""));
        });
        var result = handler(true, attachments).handler().execute(Map.of("mode", "inline", "language", "python",
                "code", "print(open('/input/data/" + path + "').read())", "attachment_ids", java.util.List.of(id)), context());
        assertEquals(true, result.get("success"));
        assertEquals(new String(bytes, StandardCharsets.UTF_8), posted.get().path("__fileContents").path("data/"+path).asText());
        var evidence = (com.skillforge.institution.attachment.SessionAttachmentService.FrozenMetadata)
                ((java.util.List<?>) result.get("attachment_inputs")).getFirst();
        assertEquals(hash, evidence.sha256());
        assertEquals(id, evidence.id());
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains("order,amount"));
        var mapperWithTime = new ObjectMapper().findAndRegisterModules();
        var publicResult = mapperWithTime.readValue(mapperWithTime.writeValueAsBytes(result), Map.class);
        publicResult.remove("content"); // PLATFORM 媒体 Provider 将 content 与 structuredContent 分开校验。
        var schema = new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                .filter(entry -> entry.name().equals("code_execute")).findFirst().orElseThrow().outputSchema();
        var validation = new com.skillforge.core.capability.connector.JsonSchemaValidateToolHandler(mapperWithTime)
                .execute(Map.of("value", publicResult, "schema", schema), context());
        assertEquals(true, validation.get("valid"), validation.toString());
    }

    @Test
    void unauthorizedAttachmentNeverReachesRunner() throws Exception {
        var calls = new AtomicInteger();
        startRunner(exchange -> { calls.incrementAndGet(); respond(exchange, 200, completed(0, "", "")); });
        var attachments = new com.skillforge.institution.attachment.SessionAttachmentService(
                mock(com.skillforge.institution.attachment.SessionAttachmentMapper.class), mock(ArtifactAccess.class), authorizedBindings(),
                mock(com.skillforge.institution.attachment.SessionAttachmentBodyStore.class), executionStore(),CODEC,com.skillforge.institution.runtime.ProgramResourceOriginFixture.ordinary(executionStore()));
        var result = handler(true, attachments).handler().execute(Map.of("mode", "inline", "language", "python",
                "code", "print('unused')", "attachment_ids", java.util.List.of("12345678-1234-1234-1234-123456789abc")), context());
        assertEquals(false, result.get("success"));
        assertEquals("code_execution_resources_rejected", result.get("error"));
        assertEquals("RESOURCE_ACCESS_REJECTED", ((Map<?,?>)result.get("diagnostic")).get("code"));
        assertEquals(0, calls.get());
    }

    @Test void binaryPdfReachesRunnerFromBackgroundWorkerWithoutHttpLogin() throws Exception {
        String id = "12345678-1234-1234-1234-123456789abc";
        String path = "attachments/" + id + "/input.pdf";
        byte[] bytes;
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var output = new java.io.ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            pdf.save(output);
            bytes = output.toByteArray();
        }
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var mapper = mock(com.skillforge.institution.attachment.SessionAttachmentMapper.class);
        var row = new com.skillforge.institution.attachment.SessionAttachmentRow(id, "1", "session", "report.pdf",
                path, bytes.length, hash, bytes, Instant.now(), Instant.now().plusSeconds(3600));
        org.mockito.Mockito.when(mapper.findMetadataBatch("1", "session", List.of(id))).thenReturn(List.of(row));
        org.mockito.Mockito.when(mapper.find("1", "session", id)).thenReturn(row);
        var httpAccess = mock(ArtifactAccess.class);
        org.mockito.Mockito.doThrow(new SecurityException("http_login_missing")).when(httpAccess)
                .authorizeCurrent(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        var bindings = authorizedBindings();
        var attachments = new com.skillforge.institution.attachment.SessionAttachmentService(mapper, httpAccess, bindings,
                mock(com.skillforge.institution.attachment.SessionAttachmentBodyStore.class), executionStore(),CODEC,com.skillforge.institution.runtime.ProgramResourceOriginFixture.ordinary(executionStore()));
        var posted = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        startRunner(exchange -> { posted.set(readExecution(exchange)); respond(exchange, 200, completed(0, "", "")); });
        var fixture = handler(true, attachments);
        try (var worker = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = worker.<Map<String, Object>>submit(() -> fixture.handler().execute(Map.of("mode", "inline", "language", "python",
                    "code", "print('read pdf')", "attachment_ids", List.of(id)), context()))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(true, result.get("success"));
            var evidence = (com.skillforge.institution.attachment.SessionAttachmentService.FrozenMetadata)
                    ((List<?>) result.get("attachment_inputs")).getFirst();
            assertEquals(hash, evidence.sha256());
            assertEquals(bytes.length, evidence.size());
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, java.util.Base64.getDecoder()
                .decode(posted.get().path("__fileBase64").path("data/" + path).asText()));
        verifyNoInteractions(httpAccess);
        org.mockito.Mockito.verify(bindings, org.mockito.Mockito.times(2)).authorizeRead(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("7"));
    }

    @Test void missingOrRevokedBoundRunPermissionNeverSubmitsToRunner() throws Exception {
        var calls = new AtomicInteger();
        startRunner(exchange -> { calls.incrementAndGet(); respond(exchange, 200, completed(0, "", "")); });
        var mapper = mock(com.skillforge.institution.attachment.SessionAttachmentMapper.class);
        var bindings = mock(com.skillforge.institution.runtime.AgentRunBindings.class);
        var attachments = new com.skillforge.institution.attachment.SessionAttachmentService(mapper, mock(ArtifactAccess.class), bindings,
                mock(com.skillforge.institution.attachment.SessionAttachmentBodyStore.class), executionStore(),CODEC,com.skillforge.institution.runtime.ProgramResourceOriginFixture.ordinary(executionStore()));
        var args = Map.<String, Object>of("mode", "inline", "language", "python", "code", "print('unused')",
                "attachment_ids", List.of("12345678-1234-1234-1234-123456789abc"));
        var missing = handler(true, attachments).handler().execute(args, context());
        assertEquals(false, missing.get("success"));
        assertEquals("code_execution_resources_rejected", missing.get("error"));
        var binding = mock(com.skillforge.institution.runtime.AgentRunBindings.Binding.class);
        org.mockito.Mockito.when(bindings.find(context().run().runRef())).thenReturn(Optional.of(binding));
        org.mockito.Mockito.when(binding.owner()).thenReturn(context().run().owner());
        org.mockito.Mockito.when(binding.actor()).thenReturn(context().run().agentId());
        org.mockito.Mockito.doThrow(new SecurityException("requester_revoked")).when(bindings).authorizeRead(binding, "7");
        var revoked = handler(true, attachments).handler().execute(args, context());
        assertEquals(false, revoked.get("success"));
        assertEquals("code_execution_resources_rejected", revoked.get("error"));
        assertEquals(0, calls.get());
        verifyNoInteractions(mapper);
    }

    @Test
    void doesNotPublishUndeclaredTemporaryFiles() throws Exception {
        startRunner(exchange -> respond(exchange, 200,
                "{\"state\":\"completed\",\"exitCode\":0,\"files\":[{\"path\":\"document-summary.json\",\"size\":1,\"sha256\":\"" + "0".repeat(64) + "\"}]}"));
        var fixture = handler(true);
        var result = fixture.handler().execute(inline(), context());
        assertEquals("undeclared_artifact_file", result.get("delivery_error"));
        assertEquals(java.util.List.of(), result.get("artifacts"));
        org.mockito.Mockito.verify(fixture.storage(),org.mockito.Mockito.never()).publish(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedExecutionCannotPublishPartialDeliverable() throws Exception {
        startRunner(exchange -> respond(exchange, 200,
                "{\"state\":\"failed\",\"exitCode\":1,\"files\":[{\"path\":\"report.txt\",\"size\":1,\"sha256\":\"" + "0".repeat(64) + "\"}]}"));
        var fixture = handler(true);
        var result = fixture.handler().execute(inline(), context());
        assertEquals(false, result.get("success"));
        assertEquals(java.util.List.of(), result.get("artifacts"));
        org.mockito.Mockito.verify(fixture.storage(),org.mockito.Mockito.never()).publish(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any());
    }

    @Test void programRpcUsesFixedPathsAndStructuredResultBody() throws Exception {
        var task = new java.util.concurrent.atomic.AtomicReference<String>();
        var resultPosted = new java.util.concurrent.atomic.AtomicBoolean();
        var detailReads = new AtomicInteger();
        var emptyTerminalPolls = new AtomicInteger();
        startRunner(exchange -> {
            switch (exchange.getRequestURI().getPath()) {
                case "/internal/executions/artifact-inputs" -> {
                    var request = readExecution(exchange);
                    task.set(request.path("taskId").asText());
                    assertEquals(true, request.path("programTools").asBoolean());
                    respond(exchange, 202, "{\"state\":\"running\"}");
                }
                case "/internal/executions/tool-calls" -> {
                    assertEquals("taskId=" + task.get(), exchange.getRequestURI().getRawQuery());
                    if (resultPosted.get()) {
                        emptyTerminalPolls.incrementAndGet();
                        respond(exchange, 200, "{\"calls\":[]}");
                    } else {
                        respond(exchange, 200, "{\"calls\":[{\"ordinal\":1,\"handle\":\"tool:clock\",\"arguments\":{}}]}");
                    }
                }
                case "/internal/executions/tool-results" -> {
                    assertEquals("POST", exchange.getRequestMethod());
                    var request = json.readTree(exchange.getRequestBody());
                    assertEquals(task.get(), request.path("taskId").asText());
                    assertEquals(1, request.path("ordinal").asInt());
                    assertEquals(true, request.path("result").path("success").asBoolean());
                    resultPosted.set(true);
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                }
                case "/internal/executions/detail" -> respond(exchange, 200,
                        detailReads.incrementAndGet() == 1 ? "{\"state\":\"running\"}" : completed(0, "ok", ""));
                default -> respond(exchange, 404, "{}");
            }
        });
        var fixture = handler(true);
        var context = context();
        var bindings=new AtomicInteger();
        var result = fixture.handler().executeProgram(
                Map.of("mode", "inline", "language", "node", "code", "await tools.call('tool:clock', {});"),
                context, parentContext -> {
                    bindings.incrementAndGet();
                    return (ordinal, handle, arguments) -> {
                        assertEquals(context.run(), parentContext.run());
                        assertEquals(context.invocationId(), parentContext.invocationId());
                        assertEquals("tool:clock", handle);
                        return new com.skillforge.runtime.api.TypedPayload("application/json", "{\"success\":true}");
                    };
                });
        assertEquals(1,bindings.get());
        assertEquals(true, resultPosted.get());
        assertEquals(1, emptyTerminalPolls.get());
        assertEquals(true, result.get("success"));
    }

    @Test void programRpcAdmissionRejectionReturnsExplicitChildErrorWithoutCancellingParent() throws Exception {
        var posted = new java.util.concurrent.atomic.AtomicBoolean();
        var invocations = new AtomicInteger(); var cancellations = new AtomicInteger();
        startRunner(exchange -> {
            switch (exchange.getRequestURI().getPath()) {
                case "/internal/executions/artifact-inputs" -> {
                    readExecution(exchange);
                    respond(exchange, 202, "{\"state\":\"running\"}");
                }
                case "/internal/executions/tool-calls" -> respond(exchange, 200, posted.get() ? "{\"calls\":[]}"
                        : "{\"calls\":[{\"ordinal\":1,\"handle\":\"sf_run_code\",\"arguments\":{}}]}");
                case "/internal/executions/tool-results" -> {
                    var request = json.readTree(exchange.getRequestBody());
                    assertEquals(1, request.path("ordinal").asInt());
                    var error = request.path("result");
                    assertEquals(true, error.path("isError").asBoolean());
                    assertEquals("complete", error.path("resultType").asText());
                    assertEquals(false, error.path("structuredContent").path("success").asBoolean());
                    assertEquals("program_tool_requires_direct_call", error.path("structuredContent").path("error").asText());
                    posted.set(true); exchange.sendResponseHeaders(204, -1); exchange.close();
                }
                case "/internal/executions/detail" -> respond(exchange, 200, posted.get()
                        ? completed(0, "", "") : "{\"state\":\"running\"}");
                case "/internal/executions/delete" -> {
                    cancellations.incrementAndGet(); respond(exchange, 200, "{\"state\":\"cancelled\"}");
                }
                default -> respond(exchange, 404, "{}");
            }
        });
        var result = handler(true).handler().executeProgram(Map.of("mode", "inline", "language", "node", "code", "await tools.call('sf_run_code', {});"),
                context(), parent -> (ordinal, handle, arguments) -> {
                    invocations.incrementAndGet();
                    throw new com.skillforge.core.capability.connector.ProgramToolInvoker.AdmissionRejected("program_tool_requires_direct_call");
                });
        assertEquals(true, posted.get()); assertEquals(1, invocations.get()); assertEquals(0, cancellations.get());
        assertEquals(true, result.get("success"));
    }

    @Test void missingSecondProgramToolPreservesTheCompletedFirstCallAndParentExecution() throws Exception {
        var invocations = new AtomicInteger(); var posted = new AtomicInteger(); var cancellations = new AtomicInteger();
        startRunner(exchange -> {
            switch (exchange.getRequestURI().getPath()) {
                case "/internal/executions/artifact-inputs" -> {
                    readExecution(exchange); respond(exchange, 202, "{\"state\":\"running\"}");
                }
                case "/internal/executions/tool-calls" -> respond(exchange, 200, posted.get() == 2 ? "{\"calls\":[]}"
                        : "{\"calls\":[{\"ordinal\":1,\"handle\":\"sf_list_artifacts\",\"arguments\":{}},{\"ordinal\":2,\"handle\":\"__noop__\",\"arguments\":{}}]}");
                case "/internal/executions/tool-results" -> {
                    var request = json.readTree(exchange.getRequestBody());
                    int ordinal = posted.incrementAndGet();
                    assertEquals(ordinal, request.path("ordinal").asInt());
                    var result = request.path("result");
                    if (ordinal == 1) assertEquals("observed-first-result", result.path("value").asText());
                    else {
                        assertEquals(true, result.path("isError").asBoolean());
                        assertEquals("complete", result.path("resultType").asText());
                        assertEquals("program_tool_not_available", result.path("structuredContent").path("error").asText());
                    }
                    exchange.sendResponseHeaders(204, -1); exchange.close();
                }
                case "/internal/executions/detail" -> respond(exchange, 200, posted.get() == 2
                        ? completed(0, "", "") : "{\"state\":\"running\"}");
                case "/internal/executions/delete" -> {
                    cancellations.incrementAndGet(); respond(exchange, 200, "{\"state\":\"cancelled\"}");
                }
                default -> respond(exchange, 404, "{}");
            }
        });
        var result = handler(true).handler().executeProgram(Map.of("mode", "inline", "language", "node", "code",
                "await tools.call('sf_list_artifacts', {}); await tools.call('__noop__', {}).catch(() => {});"),
                context(), parent -> (ordinal, handle, arguments) -> {
                    invocations.incrementAndGet();
                    if (ordinal == 1) return new com.skillforge.runtime.api.TypedPayload("application/json", "{\"value\":\"observed-first-result\"}");
                    throw new com.skillforge.core.capability.connector.ProgramToolInvoker.AdmissionRejected("program_tool_not_available");
                });
        assertEquals(2, invocations.get()); assertEquals(2, posted.get()); assertEquals(0, cancellations.get());
        assertEquals(true, result.get("success"));
    }

    @Test void untypedStoreRejectionStillPreservesUnknownWithoutPostingOrReplay() throws Exception {
        var failure = new com.skillforge.runtime.session.spi.ExecutionStore.Rejection("program_tool_requires_direct_call");
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("external.store.Store", "read", "Store.java", 1)
        });
        assertProgramRpcFailureDiagnostic(failure, "program_tool_requires_direct_call", "unknown");
    }

    @Test void programRpcLogsSafeRuntimeReasonAndKeepsOutcomeUnknownWithoutRetry() throws Exception {
        var failure=new IllegalArgumentException("studio_tool_no_longer_authorized",
                new IllegalStateException("sensitive-cause "+TOKEN));
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("external.client.Client","call","sensitive-http-body",12),
                new StackTraceElement("com.skillforge.runtime.starter.AgentRuntimeProgramTools","invokeInScope",
                        "AgentRuntimeProgramTools.java",67)
        });
        assertProgramRpcFailureDiagnostic(failure,"studio_tool_no_longer_authorized",
                "com.skillforge.runtime.starter.AgentRuntimeProgramTools.invokeInScope:67");
    }

    @Test void programRpcWithholdsSensitiveMessageAndForeignStackWithoutChangingUnknown() throws Exception {
        var failure=new IllegalStateException("program_tool_contract_changed\nsensitive-exception-message "+TOKEN);
        failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("external.client.Client","call","sensitive-http-body",12)
        });
        assertProgramRpcFailureDiagnostic(failure,"unknown","unknown");
    }

    @Test void programRpcPrecheckFailureIsDiagnosedBeforeInvokerAndRemainsUnknown() throws Exception {
        assertProgramRpcFailureDiagnostic(null,"program_rpc_invalid",
                "com.skillforge.institution.execution.CodeExecutionToolHandler.serviceProgramCalls:",RpcFailurePhase.PRECHECK);
    }

    @Test void programRpcResponseParseFailureWithholdsHttpBodyBeforeInvoker() throws Exception {
        assertProgramRpcFailureDiagnostic(null,"unknown",
                "com.skillforge.institution.execution.CodeExecutionToolHandler.response:",RpcFailurePhase.RESPONSE_PARSE);
    }

    @Test void programRpcCancellationIsDiagnosedBeforeInvokerAndPreservesInterrupt() throws Exception {
        try {
            assertProgramRpcFailureDiagnostic(null,"unknown",
                    "com.skillforge.institution.execution.CodeExecutionToolHandler.serviceProgramCalls:",RpcFailurePhase.CANCELLATION);
            org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    private void assertProgramRpcFailureDiagnostic(RuntimeException failure,String reason,String source) throws Exception {
        assertProgramRpcFailureDiagnostic(failure,reason,source,RpcFailurePhase.INVOCATION);
    }

    private void assertProgramRpcFailureDiagnostic(RuntimeException failure,String reason,String source,RpcFailurePhase phase) throws Exception {
        var task=new java.util.concurrent.atomic.AtomicReference<String>();
        var submissions=new AtomicInteger();
        var calls=new AtomicInteger();
        var polls=new AtomicInteger();
        var cancellations=new AtomicInteger();
        var resultPosts=new AtomicInteger();
        var detailReads=new AtomicInteger();
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        startRunner(exchange -> {
            switch(exchange.getRequestURI().getPath()) {
                case "/internal/executions/artifact-inputs" -> {
                    submissions.incrementAndGet();
                    task.set(readExecution(exchange).path("taskId").asText());
                    respond(exchange,202,"{\"state\":\"running\"}");
                }
                case "/internal/executions/tool-calls" -> {
                    polls.incrementAndGet();
                    if(phase==RpcFailurePhase.RESPONSE_PARSE) {
                        respond(exchange,200,"sensitive-http-body "+TOKEN);
                        break;
                    }
                    if(phase==RpcFailurePhase.PRECHECK) {
                        respond(exchange,200,"{\"calls\":[{\"ordinal\":1,\"handle\":\"sensitive-handle\",\"arguments\":\"sensitive-http-body\"}]}");
                        break;
                    }
                    if(phase==RpcFailurePhase.CANCELLATION)cancelled.set(true);
                    respond(exchange,200,"{\"calls\":[{\"ordinal\":1,\"handle\":\"sensitive-handle\","
                            +"\"arguments\":{\"token\":\""+TOKEN+"\",\"body\":\"sensitive-http-body\"}}]}");
                }
                case "/internal/executions/delete" -> {
                    assertEquals("DELETE",exchange.getRequestMethod());
                    assertEquals(task.get(),json.readTree(exchange.getRequestBody()).path("taskId").asText());
                    cancellations.incrementAndGet();
                    respond(exchange,200,"{\"state\":\"cancelled\"}");
                }
                case "/internal/executions/tool-results" -> {
                    resultPosts.incrementAndGet();
                    respond(exchange,200,"{}");
                }
                case "/internal/executions/detail" -> {
                    detailReads.incrementAndGet();
                    respond(exchange,200,completed(0,"sensitive-http-body",""));
                }
                default -> respond(exchange,404,"{}");
            }
        });
        var fixture=handler(true);
        var base=context();
        var context=new PlatformToolExecutionContext(new PortCallContext(base.run(),base.callContext().identity(),
                new InvocationControl(base.deadlineAt(),cancelled::get)),base.invocationId(),base.attemptId());
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CodeExecutionToolHandler.class);
        var previousLevel=logger.getLevel();
        var captured=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();logger.addAppender(captured);logger.setLevel(ch.qos.logback.classic.Level.WARN);
        try {
            var outcome=assertThrows(ExternalInvocationGuard.OutcomeUnknownException.class,
                    () -> fixture.handler().executeProgram(Map.of("mode","inline","language","node","code","await tools.call();"),
                            context,parentContext -> (ordinal,handle,arguments) -> {
                                calls.incrementAndGet();
                                throw failure==null?new IllegalStateException("unexpected_rpc_invocation"):failure;
                            }));
            assertEquals("code_execution_outcome_unknown",outcome.getMessage());
            org.junit.jupiter.api.Assertions.assertNull(outcome.getCause());
            assertEquals(1,submissions.get());
            assertEquals(phase==RpcFailurePhase.INVOCATION?1:0,calls.get());
            assertEquals(1,polls.get());
            assertEquals(1,cancellations.get());
            assertEquals(0,resultPosts.get());
            assertEquals(0,detailReads.get());
            org.mockito.Mockito.verify(fixture.storage(),org.mockito.Mockito.never()).publish(
                    org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any());
            assertEquals(1,captured.list.size());
            var event=captured.list.getFirst();
            String diagnostic=event.getFormattedMessage();
            org.junit.jupiter.api.Assertions.assertTrue(diagnostic.contains("phase=program_rpc"));
            org.junit.jupiter.api.Assertions.assertTrue(diagnostic.contains("parentInvocationId="+context.invocationId()));
            org.junit.jupiter.api.Assertions.assertTrue(diagnostic.contains("taskId="+task.get()));
            int expectedOrdinal=phase==RpcFailurePhase.RESPONSE_PARSE?0:1;
            org.junit.jupiter.api.Assertions.assertTrue(diagnostic.contains("ordinal="+expectedOrdinal+" reason="+reason+" "));
            String exceptionClass=switch(phase) {
                case INVOCATION -> failure.getClass().getName();
                case PRECHECK -> IOException.class.getName();
                case RESPONSE_PARSE -> com.fasterxml.jackson.core.JsonParseException.class.getName();
                case CANCELLATION -> InterruptedException.class.getName();
            };
            org.junit.jupiter.api.Assertions.assertTrue(diagnostic.contains("exceptionClass="+exceptionClass));
            org.junit.jupiter.api.Assertions.assertTrue(diagnostic.contains("sourceLocation="+source));
            org.junit.jupiter.api.Assertions.assertNull(event.getThrowableProxy());
            for(String secret:List.of(TOKEN,"sensitive-exception-message","sensitive-cause","sensitive-handle","sensitive-http-body")) {
                org.junit.jupiter.api.Assertions.assertFalse(diagnostic.contains(secret));
                org.junit.jupiter.api.Assertions.assertFalse(java.util.Arrays.toString(event.getArgumentArray()).contains(secret));
                org.junit.jupiter.api.Assertions.assertFalse(outcome.toString().contains(secret));
            }
        } finally { logger.detachAppender(captured);captured.stop();logger.setLevel(previousLevel); }
    }
    private enum RpcFailurePhase { INVOCATION, PRECHECK, RESPONSE_PARSE, CANCELLATION }

    @Test void artifactInputUsesStreamingEndpointAndPreservesTrustedArchive()throws Exception {
        var transfer=java.nio.file.Files.createTempFile("artifact-input-test-",".zip");
        try {
            java.nio.file.Files.writeString(transfer,"verified archive");
            startRunner(exchange->{
                assertEquals("/internal/executions/artifact-inputs",exchange.getRequestURI().getPath());
                assertEquals("application/zip",exchange.getRequestHeaders().getFirst("Content-Type"));
                assertEquals("verified archive",new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                respond(exchange,200,completed(0,"reused",""));
            });
            var fixture=handler(true);
            org.mockito.Mockito.when(fixture.storage().executionArchive(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any(com.skillforge.execution.ExecuteRequest.class),org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(new ArtifactStorage.ExecutionArchive(transfer,java.util.List.of(
                            new com.skillforge.institution.artifact.ArtifactProvenance(
                                    "12345678-1234-1234-1234-123456789abc","records.json",16,"0".repeat(64)))));
            var args=new java.util.HashMap<String,Object>(inline());
            args.put("artifact_inputs",java.util.List.of(Map.of("artifact_id","12345678-1234-1234-1234-123456789abc","path","records.json")));
            assertEquals(true,fixture.handler().execute(args,context()).get("success"));
            org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(transfer));
        } finally {java.nio.file.Files.deleteIfExists(transfer);}
    }

    @Test
    void publishedArtifactReferencesMatchCatalogWithoutLeakingManifestOrOwnership() throws Exception {
        startRunner(exchange -> {
            if(exchange.getRequestURI().getPath().endsWith("/files"))respond(exchange,200,"archive");
            else respond(exchange,200,"{\"state\":\"completed\",\"phase\":\"execution\",\"exitCode\":0,\"files\":[{\"path\":\"report.txt\",\"size\":1,\"sha256\":\""+"0".repeat(64)+"\"}]}");
        });
        Fixture fixture=handler(true);
        org.mockito.Mockito.when(fixture.storage().publish(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.skillforge.institution.artifact.ArtifactMetadata("artifact","session","run","invocation","file","report.txt",1,"0".repeat(64),Instant.now().plusSeconds(60),"ready",java.util.List.of(),java.util.List.of()));
        Map<String,Object> result;
        result=fixture.handler().execute(inline(),context());
        var schema=new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream().filter(entry->entry.name().equals("code_execute")).findFirst().orElseThrow().outputSchema();
        var structured = new java.util.LinkedHashMap<>(result);
        structured.remove("content");
        var validation=new com.skillforge.core.capability.connector.JsonSchemaValidateToolHandler(json).execute(Map.of("value",structured,"schema",schema),context());
        assertEquals(true,validation.get("valid"),validation.toString());
        assertEquals("ready",result.get("delivery_status"));
        var reference=(Map<?,?>)((java.util.List<?>)result.get("artifacts")).getFirst();
        assertEquals(java.util.Set.of("id","type","name","size","sha256","expires_at","status","paths"),reference.keySet());
    }

    @Test void publishesVerifiedInputProvenanceWithTheNewArtifact()throws Exception {
        var transfer=java.nio.file.Files.createTempFile("artifact-provenance-test-",".zip");
        var provenance=new com.skillforge.institution.artifact.ArtifactProvenance(
                "12345678-1234-1234-1234-123456789abc","records.json",2,"0".repeat(64));
        try {
            java.nio.file.Files.writeString(transfer,"verified archive");
            startRunner(exchange->{
                if(exchange.getRequestURI().getPath().endsWith("/files"))respond(exchange,200,"artifact archive");
                else respond(exchange,200,"{\"state\":\"completed\",\"phase\":\"execution\",\"exitCode\":0,\"files\":[{\"path\":\"report.txt\",\"size\":1,\"sha256\":\""+"0".repeat(64)+"\"}]}");
            });
            var fixture=handler(true);
            org.mockito.Mockito.when(fixture.storage().executionArchive(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any(com.skillforge.execution.ExecuteRequest.class),org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(new ArtifactStorage.ExecutionArchive(transfer,java.util.List.of(provenance)));
            org.mockito.Mockito.when(fixture.storage().publish(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(java.util.List.of(provenance)),
                    org.mockito.ArgumentMatchers.any())).thenReturn(new com.skillforge.institution.artifact.ArtifactMetadata(
                            "artifact","session","run","invocation","file","report.txt",1,"0".repeat(64),
                            Instant.now().plusSeconds(60),"ready",java.util.List.of(),java.util.List.of(provenance)));
            var args=new java.util.HashMap<String,Object>(inline());
            args.put("artifact_inputs",java.util.List.of(Map.of("artifact_id",provenance.artifactId(),"path",provenance.path())));

            assertEquals("ready",fixture.handler().execute(args,context()).get("delivery_status"));

            org.mockito.Mockito.verify(fixture.storage()).publish(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(java.util.List.of(provenance)),
                    org.mockito.ArgumentMatchers.any());
        } finally {java.nio.file.Files.deleteIfExists(transfer);}
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test void outputBodyAndStorageShareTransferDeadlineInsteadOfFullCallerTimeout() throws Exception {
        var release=new java.util.concurrent.CountDownLatch(1);
        var received=new java.util.concurrent.atomic.AtomicReference<PlatformToolExecutionContext>();
        startRunner(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("/files")) {
                exchange.sendResponseHeaders(200,7);
                try { release.await(5,java.util.concurrent.TimeUnit.SECONDS); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            } else respond(exchange,200,"{\"state\":\"completed\",\"phase\":\"execution\",\"exitCode\":0,\"files\":[{\"path\":\"report.txt\",\"size\":1,\"sha256\":\""+"0".repeat(64)+"\"}]}");
        });
        var fixture=handler(true);
        var frozen=fixture.settings().captureTool("1","run","invocation");
        org.mockito.Mockito.when(fixture.settings().read(frozen,"artifacts")).thenReturn(
                new com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot(1,
                        Map.of("retention_days",7,"max_files",256,"transfer_timeout_seconds",1)));
        org.mockito.Mockito.when(fixture.storage().publish(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
                    received.set(call.getArgument(0));
                    // 消费真实HTTP正文；关闭定时器必须在父调用120秒超时之前解除读取。
                    ((java.io.InputStream)call.getArgument(3)).readAllBytes();
                    throw new IOException("incomplete archive must not publish");
                });
        var original=context(); Instant before=Instant.now();
        try {
            var result=fixture.handler().execute(inline(),original);
            assertEquals("failed",result.get("delivery_status"));
            assertEquals("artifact_delivery_failed",result.get("delivery_error"));
            var bounded=received.get();
            org.junit.jupiter.api.Assertions.assertNotNull(bounded);
            org.junit.jupiter.api.Assertions.assertTrue(bounded.deadlineAt().isBefore(original.deadlineAt()));
            org.junit.jupiter.api.Assertions.assertTrue(bounded.deadlineAt().isBefore(before.plusSeconds(3)));
            org.junit.jupiter.api.Assertions.assertTrue(Instant.now().isBefore(before.plusSeconds(4)));
            assertEquals(original.run(),bounded.run());
            assertEquals(original.invocationId(),bounded.invocationId());
        } finally { release.countDown(); }
    }

    @Test
    void disabledHandlerRejectsWithoutAnyExternalOrSkillAccess() throws Exception {
        Fixture fixture = handler(false);

        Map<String, Object> result = fixture.handler().execute(inline(), context());

        assertEquals(false, result.get("success"));
        assertEquals("code_execution_disabled", result.get("error"));
        verifyNoInteractions(fixture.scripts(), fixture.storage(), fixture.access());
    }

    @Test
    void argumentConflictReturnsActionableSafeDiagnosticAndNeverSubmits() throws Exception {
        var calls = new AtomicInteger();
        startRunner(exchange -> { calls.incrementAndGet(); respond(exchange, 200, completed(0, "", "")); });
        var fixture = handler(true);
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(CodeExecutionToolHandler.class);
        var captured=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();logger.addAppender(captured);
        try {
            var args=new java.util.HashMap<String,Object>(Map.of("mode","skill","language","node",
                    "skill_handle","skill:sensitive-handle","script_path","scripts/generate.mjs"));
            for(Object code:new Object[] {"sensitive-code "+TOKEN,"",null}) {
                args.put("code",code);
                var result=fixture.handler().execute(args,context());
                assertEquals(false,result.get("success"));
                assertEquals("rejected",result.get("state"));
                assertEquals("admission",result.get("phase"));
                assertEquals("code_execution_arguments_rejected",result.get("error"));
                var diagnostic=(Map<?,?>)result.get("diagnostic");
                assertEquals("available",diagnostic.get("status"));
                assertEquals("FIELD_FORBIDDEN_FOR_MODE",diagnostic.get("code"));
                assertEquals("/code",diagnostic.get("field"));
                org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(diagnostic.get("hint")).contains("删除整个 code 字段"));
                var presentation=(com.skillforge.core.chat.contract.ProcessPresentation)result.get("public_presentation");
                org.junit.jupiter.api.Assertions.assertTrue(presentation.summary().contains("本次尚未执行"));
                assertEquals(List.of(),result.get("artifacts"));
                assertEquals("none",result.get("delivery_status"));
                String returned=json.writeValueAsString(result);
                org.junit.jupiter.api.Assertions.assertFalse(returned.contains(TOKEN)||returned.contains("sensitive-"));
                // Provider 先完成媒体结果的 JSON 序列化，再分离 content 与 structuredContent。
                var structured=json.readValue(json.writeValueAsBytes(result),Map.class);structured.remove("content");
                var schema=new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                        .filter(entry->entry.name().equals("code_execute")).findFirst().orElseThrow().outputSchema();
                var validation=new com.skillforge.core.capability.connector.JsonSchemaValidateToolHandler(json)
                        .execute(Map.of("value",structured,"schema",schema),context());
                assertEquals(true,validation.get("valid"),validation.toString());
            }
            assertEquals(0,calls.get());
            verifyNoInteractions(fixture.scripts(),fixture.storage());
            assertEquals(3,captured.list.size());
            for(var event:captured.list) {
                org.junit.jupiter.api.Assertions.assertTrue(event.getFormattedMessage().contains("reason=FIELD_FORBIDDEN_FOR_MODE field=/code"));
                org.junit.jupiter.api.Assertions.assertFalse(event.getFormattedMessage().contains(TOKEN)||event.getFormattedMessage().contains("sensitive-"));
                org.junit.jupiter.api.Assertions.assertNull(event.getThrowableProxy());
            }
        } finally {logger.detachAppender(captured);captured.stop();}
    }

    @Test
    void configurationFailureDoesNotAskModelToRepairUnrelatedArguments() throws Exception {
        var calls = new AtomicInteger();
        startRunner(exchange -> { calls.incrementAndGet(); respond(exchange, 200, completed(0, "", "")); });
        var fixture = handler(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("sensitive-configuration "+TOKEN))
                .when(fixture.access()).requireConfiguration();
        var result=fixture.handler().execute(inline(),context());
        assertEquals("code_execution_admission_unavailable",result.get("error"));
        assertEquals(Map.of("status","unavailable"),result.get("diagnostic"));
        assertEquals("admission",result.get("phase"));
        org.junit.jupiter.api.Assertions.assertFalse(json.writeValueAsString(result).contains("sensitive-"));
        assertEquals(0,calls.get());
        verifyNoInteractions(fixture.scripts(),fixture.storage());
    }

    /** 模拟真实 Worker 的空上下文及同租户嵌套入口，配置读取必须先取得可信租户身份。 */
    @Test
    void backgroundTenantScopeCoversFrozenConfigurationAndRestoresCaller() throws Exception {
        startRunner(exchange -> respond(exchange,200,completed(0,"ok","")));
        var fixture=handler(true);
        var frozen=fixture.settings().captureTool("1","run","invocation");
        var sandbox=fixture.settings().read(frozen,"sandbox");
        var artifacts=fixture.settings().read(frozen,"artifacts");
        org.mockito.Mockito.when(fixture.settings().captureTool("1","run","invocation")).thenAnswer(call -> {
            assertEquals("1",TenantContext.getTenantId());
            return frozen;
        });
        org.mockito.Mockito.when(fixture.settings().read(frozen,"sandbox")).thenAnswer(call -> {
            assertEquals("1",TenantContext.getTenantId());return sandbox;
        });
        org.mockito.Mockito.when(fixture.settings().read(frozen,"artifacts")).thenAnswer(call -> {
            assertEquals("1",TenantContext.getTenantId());return artifacts;
        });
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for(String previous:new String[]{null,"1"}) {
                workers.submit(() -> {
                    if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);
                    try {
                        assertEquals(true,fixture.handler().execute(inline(),context()).get("success"));
                        assertEquals(previous,TenantContext.getTenantId());
                    } finally {TenantContext.clear();}
                    return null;
                }).get(10,java.util.concurrent.TimeUnit.SECONDS);
            }
        }
    }

    /** 配置拒绝和未捕获故障也不能把可信 Run 租户遗留到复用线程。 */
    @Test
    void admissionFailureAndUnexpectedFailureBothRestoreTenantScope() throws Exception {
        var calls=new AtomicInteger();
        startRunner(exchange -> {calls.incrementAndGet();respond(exchange,200,completed(0,"unused",""));});
        var fixture=handler(true);
        String original=TenantContext.getTenantId();
        try {
            TenantContext.clear();
            org.mockito.Mockito.doAnswer(call -> {
                assertEquals("1",TenantContext.getTenantId());
                throw new IllegalStateException("run_settings_snapshot_missing");
            }).when(fixture.settings()).captureTool("1","run","invocation");
            assertEquals("code_execution_admission_unavailable",fixture.handler().execute(inline(),context()).get("error"));
            assertEquals(null,TenantContext.getTenantId());
            TenantContext.setTenantId("1");
            org.mockito.Mockito.doAnswer(call -> {
                assertEquals("1",TenantContext.getTenantId());
                throw new AssertionError("forced_configuration_failure");
            }).when(fixture.settings()).captureTool("1","run","invocation");
            assertThrows(AssertionError.class,()->fixture.handler().execute(inline(),context()));
            assertEquals("1",TenantContext.getTenantId());
            assertEquals(0,calls.get());
            verifyNoInteractions(fixture.scripts(),fixture.storage());
        } finally {if(original==null)TenantContext.clear();else TenantContext.setTenantId(original);}
    }

    /** 其他租户的调用者上下文不能被可信 Run 覆盖，也不能读取配置或向 Runner 派发。 */
    @Test
    void foreignTenantContextRejectsBeforeConfigurationAndRunner() throws Exception {
        var calls=new AtomicInteger();
        startRunner(exchange -> {calls.incrementAndGet();respond(exchange,200,completed(0,"unused",""));});
        var fixture=handler(true);
        String original=TenantContext.getTenantId();
        try {
            TenantContext.setTenantId("2");
            var result=fixture.handler().execute(inline(),context());
            assertEquals("code_execution_resources_rejected",result.get("error"));
            assertEquals("RESOURCE_ACCESS_REJECTED",((Map<?,?>)result.get("diagnostic")).get("code"));
            assertEquals("2",TenantContext.getTenantId());
            assertEquals(0,calls.get());
            verifyNoInteractions(fixture.settings(),fixture.scripts(),fixture.storage(),fixture.access());
        } finally {if(original==null)TenantContext.clear();else TenantContext.setTenantId(original);}
    }

    @Test
    void unavailableFrozenScriptIsAResourceFailureWithoutLeakingOriginalException() throws Exception {
        var calls = new AtomicInteger();
        startRunner(exchange -> { calls.incrementAndGet(); respond(exchange, 200, completed(0, "", "")); });
        var fixture = handler(true);
        var resolver = mock(FrozenScriptResolver.class);
        org.mockito.Mockito.when(fixture.scripts().getObject()).thenReturn(resolver);
        org.mockito.Mockito.when(resolver.resolve(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("sensitive-frozen-message "+TOKEN));
        var result=fixture.handler().execute(Map.of("mode","skill","language","node",
                "skill_handle","skill:active","script_path","scripts/generate.mjs"),context());
        assertEquals("code_execution_resources_rejected",result.get("error"));
        assertEquals("RESOURCE_UNAVAILABLE",((Map<?,?>)result.get("diagnostic")).get("code"));
        assertEquals("/skill_handle",((Map<?,?>)result.get("diagnostic")).get("field"));
        org.junit.jupiter.api.Assertions.assertFalse(json.writeValueAsString(result).contains("sensitive-"));
        assertEquals(0,calls.get());
        verifyNoInteractions(fixture.storage());
    }

    @Test
    void inlineExecutionDoesNotResolveASkill() throws Exception {
        startRunner(exchange -> respond(exchange, 200, completed(0, "inline output", "")));
        Fixture fixture = handler(true);

        Map<String, Object> result = fixture.handler().execute(inline(), context());

        assertEquals(true, result.get("success"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout")||result.containsKey("stderr"));
        verifyNoInteractions(fixture.scripts());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void renderingAndProgramCallsKeepTheirExistingOutputContracts(boolean program) throws Exception {
        var submissions=new AtomicInteger();var logReads=new AtomicInteger();
        startRunner(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("/logs")) {
                logReads.incrementAndGet();respond(exchange,503,"{}");
            } else {submissions.incrementAndGet();respond(exchange,200,completed(0,"separate-owner-output",""));}
        });
        var args=new java.util.LinkedHashMap<String,Object>(inline());
        args.put("language","node");args.put("code","console.log('separate-owner-output')");
        args.put("output_files",List.of());
        var handler=handler(true).handler();
        var invoker=mock(com.skillforge.core.capability.connector.ProgramToolInvoker.class);
        var scoped=mock(com.skillforge.core.capability.connector.ProgramToolInvoker.Calls.class);
        when(invoker.bind(any())).thenReturn(scoped);
        var result=program?handler.executeProgram(args,context(),invoker):handler.executeForRender(args,context());
        assertEquals(true,result.get("success"));assertEquals(1,submissions.get());assertEquals(0,logReads.get());
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout")||result.containsKey("stdout_unavailable"));
        if(program) { verify(invoker).bind(any()); verifyNoMoreInteractions(invoker); }
        else verifyNoInteractions(invoker);
        verifyNoInteractions(scoped);
    }

    /** 成功stdout是本次原调用的有界内部观察，不再要求另一次程序写结果文件。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"safe","truncated","protected","protected_split","credential_split","token","token_split","task","task_split","unavailable","oversized","wrong_stream","typed"})
    void successfulStdoutIsBoundedProtectedAndNeverBecomesPublicResults(String mode) throws Exception {
        var submissions=new AtomicInteger(); var logReads=new AtomicInteger();
        String value="旧文件字段:金额=42\n".repeat(320);
        startRunner(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("/logs")) {
                logReads.incrementAndGet();
                org.junit.jupiter.api.Assertions.assertTrue(exchange.getRequestURI().getRawQuery().contains("limit=32768"));
                if(mode.equals("unavailable")){respond(exchange,503,"{}");return;}
                String text=switch(mode) {
                    case "protected" -> "private-platform-ref";
                    case "protected_split" -> "private-plat"+(char)0+"form-ref";
                    case "credential_split" -> "pass"+(char)0+"word=actual-secret-value";
                    case "token" -> TOKEN;
                    case "token_split" -> TOKEN.substring(0,4)+(char)0+TOKEN.substring(4);
                    case "task","task_split" -> {
                        String task=java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8)
                                .split("taskId=",2)[1].split("&",2)[0];
                        yield mode.equals("task_split")?task.substring(0,4)+(char)0+task.substring(4):task;
                    }
                    case "oversized" -> "x".repeat(32769);
                    default -> value;
                };
                respond(exchange,200,json.writeValueAsString(Map.of("stream",mode.equals("wrong_stream")?"stderr":"stdout",
                        "text",text,"nextOffset",text.length(),"hasMore",mode.equals("truncated"),"truncated",false)));
            } else {
                submissions.incrementAndGet();
                if(mode.equals("typed"))respond(exchange,200,json.writeValueAsString(new com.skillforge.execution.ExecutionResponse(
                        null,com.skillforge.execution.ExecutionState.COMPLETED,"execution",0,false,false,null,null,List.of(),
                        List.of(new com.skillforge.execution.ExecutionResponse.LogReference("stdout",value.length(),false)),null,
                        new com.skillforge.execution.PublicResults(List.of(new com.skillforge.execution.PublicResults.Text("plain","公开业务块"))))));
                else respond(exchange,200,completed(0,value,""));
            }
        });
        var args=new java.util.LinkedHashMap<String,Object>(inline());args.put("output_files",List.of());
        var result=handler(true).handler().execute(args,context());
        assertEquals(true,result.get("success"));assertEquals("completed",result.get("state"));
        assertEquals(1,submissions.get());assertEquals(1,logReads.get());
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("diagnostic")||result.containsKey("stderr"));
        if(List.of("protected","protected_split","credential_split","token","token_split","task","task_split").contains(mode)) {
            assertEquals("",result.get("stdout"));assertEquals(true,result.get("stdout_withheld"));
        } else if(List.of("unavailable","oversized","wrong_stream").contains(mode)) {
            assertEquals(true,result.get("stdout_unavailable"));org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout"));
        } else {
            assertEquals(value,result.get("stdout"));assertEquals(mode.equals("truncated"),result.get("stdout_truncated"));
        }
        var presentation=(com.skillforge.core.chat.contract.ProcessPresentation)result.get("public_presentation");
        if(mode.equals("typed"))assertEquals("公开业务块",((com.skillforge.core.chat.contract.ProcessPresentation.Text)presentation.results().getFirst()).text());
        else {assertEquals(List.of(),presentation.results());assertEquals("代码执行完成。",presentation.summary());}
        org.junit.jupiter.api.Assertions.assertFalse(result.get("content").toString().contains("金额=42"));
        var clean=json.readValue(json.writeValueAsBytes(result),Map.class);clean.remove("content");
        var schema=new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                .filter(entry->entry.name().equals("code_execute")).findFirst().orElseThrow().outputSchema();
        // 使用真正消费工具结果的 Runtime schema 入口；json_schema_validate 工具只支持用户 schema 子集。
        com.skillforge.runtime.session.SessionRuntimePayloads.validateToolOutput(
                new com.skillforge.runtime.api.TypedPayload("json-schema", json.writeValueAsString(schema)),
                new com.skillforge.runtime.api.TypedPayload(
                        com.skillforge.runtime.session.SessionRuntimePayloads.TOOL_RESULT_SCHEMA,
                        json.writeValueAsString(Map.of("structuredContent",clean,"content",List.of()))),
                new com.skillforge.runtime.json.CodecLimits(1_000_000,100_000,128));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"python","node"})
    void invalidPublicResultWithoutStderrReturnsExecutableProtocolCorrectionAndKeepsKnownExecution(String language) throws Exception {
        var submissions=new AtomicInteger(); var logReads=new AtomicInteger();
        startRunner(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("/logs")) {
                logReads.incrementAndGet();respond(exchange,200,"{}");
            } else {
                submissions.incrementAndGet();
                respond(exchange,200,"{\"state\":\"failed\",\"phase\":\"business_result\",\"exitCode\":0,"
                        + "\"error\":\"PUBLIC_RESULT_FILE_INVALID\",\"logs\":[{\"stream\":\"stdout\",\"characters\":77,\"truncated\":false}]}");
            }
        });
        var args=new java.util.LinkedHashMap<String,Object>(inline());
        args.put("language",language); args.put("output_files",List.of());
        var result=handler(true).handler().execute(args,context());
        assertEquals(false,result.get("success")); assertEquals("failed",result.get("state"));
        assertEquals("business_result",result.get("phase")); assertEquals(0,result.get("exit_code"));
        assertEquals("PUBLIC_RESULT_FILE_INVALID",result.get("error"));
        var diagnostic=(Map<?,?>)result.get("diagnostic");
        assertEquals("available",diagnostic.get("status")); assertEquals("/workspace/output/result.json",diagnostic.get("field"));
        assertEquals("PUBLIC_RESULT_FILE_INVALID",diagnostic.get("code"));
        var hint=diagnostic.get("hint").toString();
        org.junit.jupiter.api.Assertions.assertTrue(hint.contains("无需额外调用参数"));
        org.junit.jupiter.api.Assertions.assertTrue(hint.contains("/workspace/output/result.json"));
        org.junit.jupiter.api.Assertions.assertTrue(hint.contains("attachment_ids/artifact_inputs"));
        org.junit.jupiter.api.Assertions.assertTrue(hint.contains("最小必要类型化观察"));
        org.junit.jupiter.api.Assertions.assertTrue(hint.contains("确认后再编写依赖它的计算或成果"));
        org.junit.jupiter.api.Assertions.assertTrue(hint.contains("已有可信结构和值直接复用，不重复读取，不强制增加调用回合"));
        org.junit.jupiter.api.Assertions.assertTrue(hint.contains("下载成果先生成并检查文件，用真实output_files收集候选，再由finalize_task_outputs选择，Run成功后发布"));
        org.junit.jupiter.api.Assertions.assertFalse(hint.contains("下载成果仍需计划"));
        org.junit.jupiter.api.Assertions.assertTrue(hint.contains("python".equals(language)?"def publish_result(value)":"function publishResult(value)"));
        org.junit.jupiter.api.Assertions.assertFalse(diagnostic.containsKey("text")||diagnostic.containsKey("stream"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout")||result.containsKey("stderr"));
        org.junit.jupiter.api.Assertions.assertFalse(result.get("public_presentation").toString().contains("publish_result"));
        var schema=new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                .filter(entry->entry.name().equals("code_execute")).findFirst().orElseThrow().outputSchema();
        var structured=new java.util.LinkedHashMap<>(result); structured.remove("content");
        var valid=new com.skillforge.core.capability.connector.JsonSchemaValidateToolHandler(json)
                .execute(Map.of("value",structured,"schema",schema),context());
        assertEquals(true,valid.get("valid"),valid.toString());
        assertEquals(1,submissions.get()); assertEquals(0,logReads.get());
    }

    /** 缺固定结果文件合法；纯 assert 或无关文件不触发通道诊断、日志读取或重试。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"assert 1 == 1", "from pathlib import Path; Path('/workspace/output/private.json').write_text('private-business-value')"})
    void successfulSilentAssertionOrUnrelatedFileKeepsSuccessWithoutChannelDiagnostic(String code) throws Exception {
        var submissions=new AtomicInteger(); var logReads=new AtomicInteger();
        startRunner(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("/logs")) {
                logReads.incrementAndGet();respond(exchange,200,"{}");
            } else {submissions.incrementAndGet();respond(exchange,200,completed(0,"",""));}
        });
        var args=new java.util.LinkedHashMap<String,Object>(inline());
        args.put("code",code);args.remove("output_files");
        var result=handler(true).handler().execute(args,context());
        assertEquals(true,result.get("success"));assertEquals("completed",result.get("state"));assertEquals(0,result.get("exit_code"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("diagnostic"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout")||result.containsKey("stderr"));
        org.junit.jupiter.api.Assertions.assertFalse(json.writeValueAsString(result).contains("private-business-value"));
        org.junit.jupiter.api.Assertions.assertFalse(json.writeValueAsString(result).contains("private.json"));
        var presentation=(com.skillforge.core.chat.contract.ProcessPresentation)result.get("public_presentation");
        assertEquals(java.util.List.of(),presentation.results());assertEquals("代码执行完成。",presentation.summary());
        assertEquals(1,submissions.get());assertEquals(0,logReads.get());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true, false", "false, false", "false, true"})
    void fixedTypedResultOrMissingResultDoesNotRequireChannelSelection(
            boolean businessResult,boolean outputFiles) throws Exception {
        var results=new com.skillforge.execution.PublicResults(businessResult?List.of(
                new com.skillforge.execution.PublicResults.Text("plain","实际业务值")):List.of());
        startRunner(exchange->respond(exchange,200,json.writeValueAsString(new com.skillforge.execution.ExecutionResponse(
                null,com.skillforge.execution.ExecutionState.COMPLETED,"execution",0,false,false,null,null,
                List.of(),List.of(new com.skillforge.execution.ExecutionResponse.LogReference("stdout",0,false)),null,results))));
        var args=new java.util.LinkedHashMap<String,Object>(inline());
        args.put("output_files",outputFiles?List.of("report.txt"):List.of());
        var result=handler(true).handler().execute(args,context());
        assertEquals(true,result.get("success"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("diagnostic"));
        if(businessResult)assertEquals("实际业务值",((Map<?,?>)((List<?>)result.get("content")).getFirst()).get("text"));
    }

    /** 成功stdout无需结果文件补写提示；原类型化公开业务块保持独立。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0, false", "1, true", "1, false"})
    void stdoutObservationDoesNotRequireFixedBusinessResultOrProduceCorrectionHint(
            int characters,boolean businessResult) throws Exception {
        var results=new com.skillforge.execution.PublicResults(businessResult?java.util.List.of(
                new com.skillforge.execution.PublicResults.Text("plain","业务结果")):java.util.List.of());
        startRunner(exchange->respond(exchange,200,json.writeValueAsString(new com.skillforge.execution.ExecutionResponse(
                null,com.skillforge.execution.ExecutionState.COMPLETED,"execution",0,false,false,null,null,
                java.util.List.of(),java.util.List.of(new com.skillforge.execution.ExecutionResponse.LogReference("stdout",characters,false)),null,results))));
        var result=handler(true).handler().execute(inline(),context());
        assertEquals(true,result.get("success"));assertEquals(false,result.containsKey("diagnostic"));
        if(businessResult)assertEquals("业务结果",((Map<?,?>)((java.util.List<?>)result.get("content")).getFirst()).get("text"));
    }

    @Test
    void shortRemainingBudgetStillRunsWithTheOriginalDeadlineAndPolicy() throws Exception {
        Instant deadline = Instant.now().plusSeconds(20);
        var posted = new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        startRunner(exchange -> {
            posted.set(readExecution(exchange));
            respond(exchange, 200, completed(0, "", ""));
        });
        var result = handler(true).handler().execute(inline(), context(deadline, false));
        assertEquals(true, result.get("success"));
        var execution = (com.fasterxml.jackson.databind.node.ObjectNode) posted.get().deepCopy();
        execution.remove("__fileContents");
        execution.remove("__fileBase64");
        var request = json.treeToValue(execution, com.skillforge.execution.ExecuteRequest.class);
        assertEquals(deadline, request.deadlineAt());
        assertEquals(60, request.executionPolicy().executionTimeoutSeconds());
    }

    @Test
    void expiredOrCancelledInvocationNeverReachesRunner() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startRunner(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, completed(0, "unexpected", ""));
        });
        var fixture = handler(true);
        for (var context : java.util.List.of(context(Instant.now().minusSeconds(1), false),
                context(Instant.now().plusSeconds(120), true))) {
            var result = fixture.handler().execute(inline(), context);
            assertEquals(false, result.get("success"));
            assertEquals("code_execution_deadline_insufficient", result.get("error"));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void failedExecutionReturnsBoundedDiagnosticOnlyToModelAndDoesNotReplay() throws Exception {
        var submissions=new AtomicInteger();var reads=new AtomicInteger();
        var task=new java.util.concurrent.atomic.AtomicReference<String>();
        String tail="ValueError: sections[0] requires nonempty paragraphs or bullets";
        String diagnosticText=" ".repeat(4096-tail.length())+tail;
        startRunner(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("/logs")) {
                reads.incrementAndGet();
                assertEquals("taskId="+task.get()+"&stream=stderr&offset=904&limit=4096",exchange.getRequestURI().getQuery());
                assertEquals("Bearer "+TOKEN,exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange,200,json.writeValueAsString(Map.of("stream","stderr","text",diagnosticText,
                        "nextOffset",5000,"hasMore",false,"truncated",false)));
            } else {
                submissions.incrementAndGet();task.set(readExecution(exchange).path("taskId").asText());
                respond(exchange,200,completed(1,"", "x".repeat(5000)).replace("completed","failed"));
            }
        });
        var result=handler(true).handler().execute(inline(),context());
        var diagnostic=(Map<?,?>)result.get("diagnostic");
        assertEquals(false,result.get("success"));assertEquals("failed",result.get("state"));
        assertEquals(diagnosticText,diagnostic.get("text"));assertEquals(true,diagnostic.get("truncated"));
        assertEquals(1,submissions.get());assertEquals(1,reads.get());
        org.junit.jupiter.api.Assertions.assertFalse(result.toString().contains(task.get()));
        org.junit.jupiter.api.Assertions.assertFalse(result.get("public_presentation").toString().contains(tail));
        var schema=new com.skillforge.core.capability.connector.PlatformToolCatalog(new com.skillforge.core.chat.contract.ChatToolSchemaContributor()).entries().stream()
                .filter(entry->entry.name().equals("code_execute")).findFirst().orElseThrow().outputSchema();
        var wire=json.readValue(json.writeValueAsBytes(result),Map.class);
        wire.remove("content");
        assertEquals(true,new com.skillforge.core.capability.connector.JsonSchemaValidateToolHandler(json)
                .execute(Map.of("value",wire,"schema",schema),context()).get("valid"));
    }

    @Test
    void missingUnsafeOversizedOrUnavailableDiagnosticsKeepKnownFailure() throws Exception {
        var mode=new java.util.concurrent.atomic.AtomicReference<String>("empty");
        var submits=new AtomicInteger();var reads=new AtomicInteger();
        startRunner(exchange->{
            if(!exchange.getRequestURI().getPath().endsWith("/logs")) {
                submits.incrementAndGet();respond(exchange,200,completed(1,"",mode.get().equals("empty")?"":"x".repeat(6000))
                        .replace("completed","failed"));return;
            }
            reads.incrementAndGet();
            if(mode.get().equals("unavailable")){respond(exchange,503,"{}");return;}
            String task=java.net.URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8)
                    .split("taskId=",2)[1].split("&",2)[0];
            String value=switch(mode.get()) {
                case "unsafe" -> "private-platform-ref";
                case "unsafe_split" -> "private-plat"+(char)0+"form-ref";
                case "credential_split" -> "pass"+(char)0+"word=actual-secret-value";
                case "token" -> TOKEN;
                case "token_split" -> TOKEN.substring(0,4)+(char)0+TOKEN.substring(4);
                case "task_split" -> task.substring(0,4)+(char)0+task.substring(4);
                default -> "x".repeat(4097);
            };
            if(value.length()<4096)value=" ".repeat(4096-value.length())+value;
            respond(exchange,200,json.writeValueAsString(Map.of("stream","stderr","text",value,
                    "nextOffset",1904+value.length(),"hasMore",false,"truncated",false)));
        });
        var fixture=handler(true);
        for(String current:List.of("empty","unsafe","unsafe_split","credential_split","token","token_split","task_split","oversized","unavailable")) {
            mode.set(current);var result=fixture.handler().execute(inline(),context());
            assertEquals(false,result.get("success"));assertEquals("failed",result.get("state"));assertEquals(1,result.get("exit_code"));
            assertEquals(Map.of("status",Set.of("unsafe","unsafe_split","credential_split","token","token_split","task_split")
                    .contains(current)?"withheld":"unavailable"),result.get("diagnostic"));
        }
        assertEquals(9,submits.get());assertEquals(8,reads.get());
    }

    @Test
    void exhaustedDiagnosticDeadlineDoesNotReadLogsOrChangeKnownFailure() throws Exception {
        var reads=new AtomicInteger();startRunner(exchange->{reads.incrementAndGet();respond(exchange,200,"{}");});
        var handler=handler(true).handler();
        var method=CodeExecutionToolHandler.class.getDeclaredMethod("failureDiagnostic",String.class,
                com.skillforge.execution.ExecutionResponse.class,PlatformToolExecutionContext.class);
        method.setAccessible(true);
        var state=json.readValue(completed(1,"","error").replace("completed","failed"),com.skillforge.execution.ExecutionResponse.class);
        assertEquals(Map.of("status","unavailable"),method.invoke(handler,"trusted-task",state,context(Instant.now().plusMillis(50),false)));
        assertEquals(0,reads.get());
    }

    @Test
    void knownNonZeroExitPreservesBoundedLogsAsACompleteObservation() throws Exception {
        startRunner(exchange -> respond(exchange, 200, completed(7, "partial output", "stack trace")));
        Fixture fixture = handler(true);

        Map<String, Object> result = fixture.handler().execute(inline(), context());

        assertEquals(false, result.get("success"));
        assertEquals("completed", result.get("state"));
        assertEquals(7, result.get("exit_code"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout")||result.containsKey("stderr"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout")||result.containsKey("stderr"));
        assertEquals(false, result.get("stdout_truncated"));
        assertEquals(false, result.get("stderr_truncated"));
    }

    @Test
    void preservesCompleteCapturedStreamsBeyondPreviewSizeAndReportsRunnerTruncationSeparately() throws Exception {
        String stdout = "标准输出🙂".repeat(900);
        String stderr = "错误输出𠮷".repeat(900);
        startRunner(exchange -> respond(exchange, 200, json.writeValueAsString(Map.of(
                "state", "completed", "phase", "execution", "exitCode", 0,
                "logs", java.util.List.of(Map.of("stream","stdout","characters",stdout.length(),"truncated",true),Map.of("stream","stderr","characters",stderr.length(),"truncated",false)),
                "stdoutTruncated", true, "stderrTruncated", false))));
        Fixture fixture = handler(true);

        Map<String, Object> result = fixture.handler().execute(inline(), context());

        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout")||result.containsKey("stderr"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout")||result.containsKey("stderr"));
        assertEquals(true, result.get("stdout_truncated"));
        assertEquals(false, result.get("stderr_truncated"));
    }

    @Test
    void explicitAdmissionRejectionDoesNotQueryCancelOrResubmit() throws Exception {
        var posts = new AtomicInteger();
        var otherCalls = new AtomicInteger();
        startRunner(exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 400, json.writeValueAsString(com.skillforge.execution.ExecutionAdmissionRejection
                        .notSubmitted(com.skillforge.execution.ExecutionAdmissionRejection.Code.INVALID_EXECUTION_MANIFEST)));
            } else {
                otherCalls.incrementAndGet();
                respond(exchange, 404, "{}");
            }
        });
        var fixture = handler(true);
        var failure = assertThrows(ExecutionSubmissionRejectedException.class,
                () -> fixture.handler().execute(inline(), context()));
        assertEquals(com.skillforge.execution.ExecutionAdmissionRejection.Submission.NOT_SUBMITTED,
                failure.rejection().submission());
        assertEquals(1, posts.get());
        assertEquals(0, otherCalls.get());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"code\":\"INTERNAL_ERROR\",\"message\":\"failed\"}",
            "{\"code\":\"INVALID_EXECUTION_MANIFEST\",\"message\":\"failed\",\"submission\":\"NOT_SUBMITTED\",\"extra\":true}"
    })
    void unprovenHttpFailureRemainsUnknownAndNeverResubmits(String body) throws Exception {
        var posts = new AtomicInteger();
        var queries = new AtomicInteger();
        startRunner(exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                respond(exchange, 400, body);
            } else {
                queries.incrementAndGet();
                respond(exchange, 404, "{}");
            }
        });
        var fixture = handler(true);
        assertThrows(ExternalInvocationGuard.OutcomeUnknownException.class,
                () -> fixture.handler().execute(inline(), context()));
        assertEquals(1, posts.get());
        assertEquals(1, queries.get());
    }

    @Test
    void disconnectedPostQueriesTheKnownTaskWithoutSubmittingAgain() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        AtomicInteger gets = new AtomicInteger();
        AtomicInteger logReads = new AtomicInteger();
        startRunner(exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.close();
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())) {
                if (exchange.getRequestURI().getPath().endsWith("/logs")) {
                    logReads.incrementAndGet();
                    respond(exchange, 200, "{\"stream\":\"stdout\",\"text\":\"recovered\",\"nextOffset\":9,\"hasMore\":false,\"truncated\":false}");
                    return;
                }
                gets.incrementAndGet();
                respond(exchange, 200, completed(0, "recovered", ""));
                return;
            }
            respond(exchange, 405, "{}");
        });
        Fixture fixture = handler(true);

        Map<String, Object> result = fixture.handler().execute(inline(), context());

        assertEquals(true, result.get("success"));
        assertEquals(1, posts.get());
        assertEquals(1, gets.get());
        assertEquals(1, logReads.get());
        assertEquals("recovered", result.get("stdout"));
    }

    @Test
    void unrecognizedRunnerStateRemainsOutcomeUnknown() throws Exception {
        startRunner(exchange -> respond(exchange, 200, "{\"state\":\"lost\"}"));
        Fixture fixture = handler(true);

        assertThrows(ExternalInvocationGuard.OutcomeUnknownException.class,
                () -> fixture.handler().execute(inline(), context()));
    }

    @Test
    void artifactDeliveryFailureKeepsTheExecutionObservation() throws Exception {
        String manifest = "[{\"path\":\"report.txt\",\"size\":1,\"sha256\":\""
                + "0".repeat(64) + "\"}]";
        startRunner(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/files")) {
                respond(exchange, 503, "{}");
            } else {
                respond(exchange, 200, "{\"state\":\"completed\",\"phase\":\"execution\","
                        + "\"exitCode\":0,\"files\":"
                        + manifest + "}");
            }
        });
        Fixture fixture = handler(true);

        Map<String, Object> result;
        result=fixture.handler().execute(inline(),context());

        assertEquals(true, result.get("success"));
        assertEquals(0, result.get("exit_code"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout"));
        assertEquals("failed", result.get("delivery_status"));
        assertEquals("artifact_delivery_failed", result.get("delivery_error"));
        org.mockito.Mockito.verify(fixture.storage(),org.mockito.Mockito.never()).publish(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any());
    }

    @Test
    void collectionFailureExplainsOutputDirectoryWithoutLeakingRunnerErrorOrRepeatingExecution() throws Exception {
        var submissions = new AtomicInteger();
        startRunner(exchange -> {
            submissions.incrementAndGet();
            respond(exchange, 200, "{\"state\":\"completed\",\"phase\":\"completed\",\"exitCode\":0,"
                    + "\"deliveryError\":\"private-runner-path-and-error\",\"files\":[]}");
        });
        var fixture = handler(true);
        var args = new java.util.HashMap<String,Object>(inline());
        args.put("output_files", List.of("body.html"));
        var result = fixture.handler().execute(args, context());

        assertEquals(true, result.get("success"));
        assertEquals(0, result.get("exit_code"));
        assertEquals("failed", result.get("delivery_status"));
        assertEquals("artifact_collection_failed", result.get("delivery_error"));
        var presentation = (com.skillforge.core.chat.contract.ProcessPresentation) result.get("public_presentation");
        assertEquals("代码已执行，声明的文件未能收集。", presentation.summary());
        assertTrue(presentation.references().isEmpty());
        var diagnostic = (Map<?,?>) result.get("diagnostic");
        assertEquals("OUTPUT_COLLECTION_FAILED", diagnostic.get("code"));
        assertEquals("/output_files", diagnostic.get("field"));
        assertTrue(diagnostic.get("hint").toString().contains("/workspace/output"));
        assertTrue(diagnostic.get("hint").toString().contains("/workspace/work"));
        assertTrue(diagnostic.get("hint").toString().contains("artifact_inputs"));
        assertFalse(result.toString().contains("private-runner-path-and-error"));
        assertEquals(1, submissions.get());
        org.mockito.Mockito.verify(fixture.storage(),org.mockito.Mockito.never()).publish(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any());
    }

    @Test void inspectionRejectionPreservesExecutionEvidenceAndDoesNotRunAgain() throws Exception {
        AtomicInteger submissions=new AtomicInteger();
        startRunner(exchange->{
            if(exchange.getRequestURI().getPath().endsWith("/files"))respond(exchange,200,"archive");
            else {
                submissions.incrementAndGet();
                respond(exchange,200,"{\"state\":\"completed\",\"phase\":\"execution\",\"exitCode\":0,\"files\":[{\"path\":\"report.txt\",\"size\":1,\"sha256\":\""+"0".repeat(64)+"\"}]}");
            }
        });
        var fixture=handler(true);
        org.mockito.Mockito.when(fixture.storage().publish(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any()))
                .thenThrow(com.skillforge.institution.artifact.ArtifactPublicInspection.rejected());
        Map<String,Object> result;
        result=fixture.handler().execute(inline(),context());
        assertEquals(false,result.get("success"));
        assertEquals("failed",result.get("delivery_status"));
        assertEquals("PUBLIC_OUTPUT_REJECTED",result.get("delivery_error"));
        assertEquals("completed",result.get("state")); assertEquals(0,result.get("exit_code"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout"));
        assertEquals(1,submissions.get());
        var publicResult=(com.skillforge.core.chat.contract.ProcessPresentation)result.get("public_presentation");
        assertEquals(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.SAFE_MESSAGE,publicResult.summary());
    }

    @Test void diagnosticLogsNeverBecomePublicBusinessResults() throws Exception {
        startRunner(exchange->respond(exchange,200,completed(0,"private-platform-ref","diagnostic trace")));
        var result=handler(true).handler().execute(inline(),context());
        assertEquals(true,result.get("success"));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey("stdout")||result.containsKey("stderr"));
        var presentation=(com.skillforge.core.chat.contract.ProcessPresentation)result.get("public_presentation");
        assertEquals(java.util.List.of(),presentation.results());
        org.junit.jupiter.api.Assertions.assertFalse(presentation.toString().contains("private-platform-ref"));
        assertEquals(java.util.List.of(Map.of("type", "text", "text", presentation.summary())), result.get("content"));
        org.junit.jupiter.api.Assertions.assertFalse(result.get("content").toString().contains("diagnostic trace"));
    }
    @Test void explicitBusinessChannelPreservesTechnicalTextAndBlocksActualPrivateValuesBeforeFiles() throws Exception {
        String text="const request={artifact_id:'order-20260930',run_id:'business-job'};";
        startRunner(exchange->respond(exchange,200,json.writeValueAsString(new com.skillforge.execution.ExecutionResponse(null,
                com.skillforge.execution.ExecutionState.COMPLETED,"execution",0,false,false,null,null,
                java.util.List.of(),java.util.List.of(),null,new com.skillforge.execution.PublicResults(java.util.List.of(
                        new com.skillforge.execution.PublicResults.Text("plain",text)))))));
        var result=handler(true).handler().execute(inline(),context());
        var presentation=(com.skillforge.core.chat.contract.ProcessPresentation)result.get("public_presentation");
        assertEquals(text,((com.skillforge.core.chat.contract.ProcessPresentation.Text)presentation.results().getFirst()).text());
        assertEquals(text, ((Map<?,?>) ((java.util.List<?>) result.get("content")).getFirst()).get("text"));
        org.junit.jupiter.api.Assertions.assertTrue(com.skillforge.core.capability.connector.PlatformMediaToolHandler.class
                .isAssignableFrom(CodeExecutionToolHandler.class));
        server.stop(0);
        AtomicInteger submissions=new AtomicInteger();
        startRunner(exchange->{submissions.incrementAndGet();respond(exchange,200,json.writeValueAsString(
                new com.skillforge.execution.ExecutionResponse(null,com.skillforge.execution.ExecutionState.COMPLETED,
                        "execution",0,false,false,null,null,java.util.List.of(),java.util.List.of(),null,
                        new com.skillforge.execution.PublicResults(java.util.List.of(
                                new com.skillforge.execution.PublicResults.Text("plain","private-platform-ref"))))));});
        var fixture=handler(true);result=fixture.handler().execute(inline(),context());
        assertEquals(false,result.get("success"));assertEquals("PUBLIC_OUTPUT_REJECTED",result.get("delivery_error"));
        org.mockito.Mockito.verify(fixture.storage(),org.mockito.Mockito.never()).publish(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any());
        presentation=(com.skillforge.core.chat.contract.ProcessPresentation)result.get("public_presentation");
        assertEquals(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.SAFE_MESSAGE,
                ((com.skillforge.core.chat.contract.ProcessPresentation.Text)presentation.results().getFirst()).text());
        assertEquals(com.skillforge.core.chat.integration.publicread.PublicOutputGuard.SAFE_MESSAGE,
                ((Map<?,?>) ((java.util.List<?>) result.get("content")).getFirst()).get("text"));
        org.junit.jupiter.api.Assertions.assertFalse(result.get("content").toString().contains("private-platform-ref"));
        assertEquals(1,submissions.get());
    }

    @Test void workspaceAdmissionFailureExplainsIndependentFilesWithoutDispatchOrSecrets() throws Exception {
        var calls = new AtomicInteger();
        startRunner(exchange -> { calls.incrementAndGet(); respond(exchange, 200, completed(0, "", "")); });
        for (boolean accessDenied : java.util.List.of(true, false)) {
            var fixture = handler(true);
            var workspace = mock(com.skillforge.institution.workspace.SessionWorkspaceService.class);
            org.mockito.Mockito.when(fixture.workspaces().getObject()).thenReturn(workspace);
            org.mockito.Mockito.when(workspace.forCode(org.mockito.ArgumentMatchers.any())).thenThrow(accessDenied
                    ? new SecurityException("private-workspace-secret") : new IllegalStateException("private-workspace-secret"));
            var args = new java.util.HashMap<String,Object>(inline()); args.put("use_workspace", true);
            var result = fixture.handler().execute(args, context());
            assertEquals("rejected", result.get("state"));
            assertEquals("admission", result.get("phase"));
            var diagnostic = (Map<?,?>) result.get("diagnostic");
            assertEquals(accessDenied ? "WORKSPACE_ACCESS_REJECTED" : "WORKSPACE_UNAVAILABLE", diagnostic.get("code"));
            assertEquals("/use_workspace", diagnostic.get("field"));
            assertTrue(diagnostic.get("hint").toString().contains("artifact_inputs"));
            assertTrue(diagnostic.get("hint").toString().contains("不能直接读取前次/workspace/output"));
            assertFalse(json.writeValueAsString(result).contains("private-workspace-secret"));
        }
        assertEquals(0, calls.get());
    }

    /** Shell 使用真实冻结包清单和传输字节；目录事实只随已确认的执行回执返回。 */
    @Test void workspaceShellMountsActivatedPackageWithoutCopyingCodeOrInstallingDependencies() throws Exception {
        var posted=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        startRunner(exchange->{posted.set(readExecution(exchange));respond(exchange,200,completed(0,"",""));});
        var fixture=handler(true);
        var resolver=mock(FrozenScriptResolver.class);
        org.mockito.Mockito.when(fixture.scripts().getObject()).thenReturn(resolver);
        String body="cat \"$SKILL_DIR/assets/template.txt\"";
        byte[] bytes="原始模板".getBytes(StandardCharsets.UTF_8);
        var resource=new com.skillforge.runtime.session.FrozenSkillResource("assets/template.txt","UTF8",
                new String(bytes,StandardCharsets.UTF_8),java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),bytes.length);
        org.mockito.Mockito.when(resolver.resolveResources(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("skill:mounted")))
                .thenReturn(List.of(resource));
        var workspace=new com.skillforge.execution.WorkspaceBinding("a".repeat(64),"1","session",9,"session",
                new com.skillforge.execution.WorkspacePolicy(1,300,24,10));

        var result=fixture.handler().executeWorkspaceShell(body,30,workspace,"skill:mounted",context());

        assertEquals(true,result.get("success"));assertEquals("/input",result.get("skill_resource_base"));
        var request=posted.get();
        assertEquals("WORKSPACE_SHELL_V1",request.path("profile").asText());
        assertEquals("shell",request.path("language").asText());
        assertEquals("export SKILL_DIR=/input\n"+body,request.path("code").asText());
        assertTrue(request.path("scriptPath").isNull());
        assertEquals(0,request.path("dependencies").size());
        assertEquals("assets/template.txt",request.path("inputs").get(0).path("path").asText());
        assertEquals(resource.content(),request.path("__fileContents").path(resource.path()).asText());
        org.mockito.Mockito.verify(resolver,org.mockito.Mockito.never()).resolve(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
    }

    /** 未激活或来源不可读时不向 Runner 派发，不用推测的目录冒充挂载成功。 */
    @Test void workspaceShellRejectedSkillSourceNeverDispatchesOrClaimsResourceDirectory() throws Exception {
        var posts=new AtomicInteger();
        startRunner(exchange->{posts.incrementAndGet();respond(exchange,200,completed(0,"",""));});
        var fixture=handler(true);
        var resolver=mock(FrozenScriptResolver.class);
        org.mockito.Mockito.when(fixture.scripts().getObject()).thenReturn(resolver);
        var workspace=new com.skillforge.execution.WorkspaceBinding("a".repeat(64),"1","session",9,"session",
                new com.skillforge.execution.WorkspacePolicy(1,300,24,10));
        for(var reason:List.of(new SecurityException("private-source"),new IllegalStateException("private-source"))) {
            org.mockito.Mockito.doThrow(reason).when(resolver).resolveResources(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
            var result=fixture.handler().executeWorkspaceShell("true",30,workspace,"skill:missing",context());
            assertEquals(false,result.get("success"));assertFalse(result.containsKey("skill_resource_base"));
            assertFalse(result.toString().contains("private-source"));
        }
        assertEquals(0,posts.get());
        org.mockito.Mockito.verify(fixture.storage(),org.mockito.Mockito.never()).executionArchive(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList());
    }

    private Fixture handler(boolean enabled) throws Exception {
        return handler(enabled, new com.skillforge.institution.attachment.SessionAttachmentService(
                mock(com.skillforge.institution.attachment.SessionAttachmentMapper.class),mock(ArtifactAccess.class),
                mock(com.skillforge.institution.runtime.AgentRunBindings.class),
                mock(com.skillforge.institution.attachment.SessionAttachmentBodyStore.class), executionStore(),CODEC,com.skillforge.institution.runtime.ProgramResourceOriginFixture.ordinary(executionStore())));
    }

    /** 正式工具上下文对应已受理typed Run，附件仍由真实绑定和每次读取鉴权。 */
    private static ExecutionStore executionStore() {
        var store=mock(ExecutionStore.class);var run=mock(ExecutionStore.Run.class);var identity=context().run();
        org.mockito.Mockito.when(run.identity()).thenReturn(identity);
        org.mockito.Mockito.when(store.load(identity.runRef())).thenReturn(Optional.of(run));
        var input=new ExecutionStore.Material(new ResultRef("opaque-admission"),CODEC.encodeInput(List.of(),
                new com.skillforge.runtime.api.TypedPayload("input.v1","{\"extraParameters\":{\"attachment_ids\":[\"12345678-1234-1234-1234-123456789abc\"]}}")));
        org.mockito.Mockito.when(store.originalInput(identity.runRef())).thenReturn(Optional.of(input));return store;
    }

    private static com.skillforge.institution.runtime.AgentRunBindings authorizedBindings() {
        var bindings = mock(com.skillforge.institution.runtime.AgentRunBindings.class);
        var binding = mock(com.skillforge.institution.runtime.AgentRunBindings.Binding.class);
        var run = context().run();
        org.mockito.Mockito.when(bindings.find(run.runRef())).thenReturn(Optional.of(binding));
        org.mockito.Mockito.when(binding.owner()).thenReturn(run.owner());
        org.mockito.Mockito.when(binding.actor()).thenReturn(run.agentId());
        return bindings;
    }

    private Fixture handler(boolean enabled, com.skillforge.institution.attachment.SessionAttachmentService attachments) throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<FrozenScriptResolver> scripts = mock(ObjectProvider.class);
        ArtifactStorage storage = mock(ArtifactStorage.class);
        org.mockito.Mockito.when(storage.executionArchive(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(com.skillforge.execution.ExecuteRequest.class),org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(call->{
                    com.skillforge.execution.ExecuteRequest request=call.getArgument(2);
                    java.util.List<com.skillforge.execution.ExecutionInputSource> files=call.getArgument(3);
                    var transfer=java.nio.file.Files.createTempFile("code-handler-test-",".zip");
                    try(var zip=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(transfer))) {
                        zip.putNextEntry(new java.util.zip.ZipEntry(com.skillforge.execution.ExecutionLimits.REQUEST_ENTRY));
                        zip.write(json.writeValueAsBytes(new com.skillforge.execution.ExecutionManifest(request,
                                files.stream().map(com.skillforge.execution.ExecutionInputSource::file).toList())));zip.closeEntry();
                        for(var file:files) {zip.putNextEntry(new java.util.zip.ZipEntry(file.file().path()));
                            try(var input=file.open()){input.transferTo(zip);}zip.closeEntry();}
                    }
                    return new ArtifactStorage.ExecutionArchive(transfer,java.util.List.of());
                });
        org.mockito.Mockito.when(storage.publicScope(org.mockito.ArgumentMatchers.any())).thenReturn(
                new com.skillforge.core.chat.integration.publicread.PublicOutputGuard.Scope(java.util.List.of("private-platform-ref")));
        ArtifactAccess access = mock(ArtifactAccess.class);
        DeliverableService deliverables = mock(DeliverableService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<com.skillforge.institution.workspace.SessionWorkspaceService> workspaces = mock(ObjectProvider.class);
        String endpoint = enabled ? "http://127.0.0.1:" + server.getAddress().getPort() : "";
        var settings=com.skillforge.institution.TestOperationalSettings.runs(enabled);
        var runtimeProperties = new com.skillforge.runtime.starter.AgentRuntimeProperties();
        runtimeProperties.setWorkerLeaseDuration(Duration.ofSeconds(201));
        return new Fixture(new CodeExecutionToolHandler(json, scripts, storage, access, deliverables, endpoint,
                enabled ? TOKEN : "", settings, runtimeProperties, 8091, attachments,workspaces),
                scripts, storage, access,settings,workspaces);
    }

    private void startRunner(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/executions", handler);
        server.start();
    }

    private static Map<String, Object> inline() {
        return Map.of("mode", "inline", "language", "python", "code", "print('ok')", "output_files", java.util.List.of("report.txt"));
    }

    private static PlatformToolExecutionContext context() {
        return context(Instant.now().plusSeconds(120), false);
    }

    private static PlatformToolExecutionContext context(Instant deadline, boolean cancelled) {
        TenantId tenant = new TenantId("1");
        InputId input = new InputId("input");
        SessionOwner owner = SessionOwner.user(new SessionRef(tenant, "session"), new AgentId("7"),
                new EndUserId(9));
        RunIdentity run = new RunIdentity(owner, new AgentId((owner).target().id()), input, new RunRef(tenant, "run"), new TraceEnvelope("trace", "span", input, Optional.empty()), new ResultRef("result"), new RunRequester.EndUser((owner).requireUser()), java.util.Optional.empty(), new RunOriginRef((owner).sessionRef().tenantId(), "input:" + (input).value()));
        var identity = new VerifiedIdentity(new PrincipalRef.EndUserPrincipal(tenant, owner.requireUser()), owner,
                Set.of(), 1, new ResultRef("verification"));
        return new PlatformToolExecutionContext(
                new PortCallContext(run, identity, new InvocationControl(deadline, () -> cancelled)),
                "invocation", "attempt");
    }

    private static String completed(int exitCode, String stdout, String stderr) {
        return "{\"state\":\"completed\",\"phase\":\"execution\",\"exitCode\":"+exitCode
                +",\"logs\":[{\"stream\":\"stdout\",\"characters\":"+stdout.length()+",\"truncated\":false},"
                +"{\"stream\":\"stderr\",\"characters\":"+stderr.length()+",\"truncated\":false}]}";
    }
    private com.fasterxml.jackson.databind.JsonNode readExecution(HttpExchange exchange)throws IOException {
        try(var zip=new java.util.zip.ZipInputStream(exchange.getRequestBody())) {
            assertEquals("request.json",zip.getNextEntry().getName());
            var manifest=json.readTree(zip.readAllBytes());
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("execution");
            var files=request.putObject("__fileContents");
            var binary=request.putObject("__fileBase64");java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                byte[] bytes=zip.readAllBytes();
                files.put(entry.getName(),new String(bytes,StandardCharsets.UTF_8));
                binary.put(entry.getName(),java.util.Base64.getEncoder().encodeToString(bytes));
            }
            return request;
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Fixture(CodeExecutionToolHandler handler, ObjectProvider<FrozenScriptResolver> scripts,
            ArtifactStorage storage, ArtifactAccess access,
            com.skillforge.core.foundation.runtimeconfig.RunSettingsService settings,
            ObjectProvider<com.skillforge.institution.workspace.SessionWorkspaceService> workspaces) { }
}
