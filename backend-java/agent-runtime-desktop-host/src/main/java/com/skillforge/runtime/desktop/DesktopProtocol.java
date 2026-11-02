package com.skillforge.runtime.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillforge.runtime.session.spi.ExecutionStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;

/** 版本 1 的桌面内部 JSONL；输入校验与错误映射集中在受信宿主入口。 */
final class DesktopProtocol {
    private static final int MAX_LINE_CHARACTERS = 4 * 1024 * 1024;
    private final PrintWriter output;
    private boolean stopped;
    private final java.util.concurrent.atomic.AtomicBoolean testing = new java.util.concurrent.atomic.AtomicBoolean();
    DesktopProtocol(PrintWriter output) { this.output = output; }

    synchronized void event(ObjectNode event) { if (!stopped) output.println(DesktopJson.write(event)); }

    void serve(Reader input, DesktopRuntime runtime) throws IOException {
        var reader = new BufferedReader(input);
        var tests = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1), Thread.ofPlatform().daemon().name("desktop-model-test").factory(),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        try {
            while (true) {
                String line = line(reader); if (line == null) return;
                JsonNode id = null;
                try {
                    var request = DesktopJson.parse(line); fields(request, "id", "method", "params");
                    id = request.required("id");
                    if (!(id.isTextual() || id.isIntegralNumber()) || id.toString().length() > 256) throw invalid();
                    var method = text(request, "method", 64); var params = request.required("params");
                    if (!params.isObject()) throw invalid();
                    if (method.equals("testModel")) {
                        fields(params);
                        if (!testing.compareAndSet(false, true)) { error(id, "MODEL_TEST_BUSY", "模型连接测试正在进行，请等待结果。"); continue; }
                        final var requestId = id;
                        tests.execute(() -> {
                            try {
                                var response = DesktopJson.object(); response.set("id", requestId); response.set("result", runtime.testModel()); event(response);
                            } catch (RuntimeException failure) {
                                error(requestId, "MODEL_TEST_FAILED", "模型连接测试未能完成，请检查模型配置与连接；不会自动重试。");
                            } finally { testing.set(false); }
                        });
                        continue;
                    }
                    var result = switch (method) {
                        case "configureModel" -> {
                            if (params.has("outputTokens")) fields(params, "providerId", "baseUrl", "model", "apiKey", "outputTokens");
                            else fields(params, "providerId", "baseUrl", "model", "apiKey");
                            var outputTokens = OptionalInt.empty();
                            if (params.has("outputTokens")) {
                                var value = params.required("outputTokens");
                                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) throw invalid();
                                outputTokens = OptionalInt.of(value.intValue());
                            }
                            yield runtime.configureModel(text(params, "providerId", 64), text(params, "baseUrl", 2048),
                                    text(params, "model", 256), text(params, "apiKey", 8192), outputTokens);
                        }
                        case "listSessions" -> { fields(params); yield runtime.listSessions(); }
                        case "createSession" -> {
                            fields(params, "id", "title", "workspace");
                            yield runtime.createSession(text(params, "id", 256), text(params, "title", 256), text(params, "workspace", 32768));
                        }
                        case "getSession" -> { fields(params, "sessionId"); yield runtime.getSession(text(params, "sessionId", 256)); }
                        case "submitTask" -> {
                            fields(params, "sessionId", "text", "inputId");
                            yield runtime.submitTask(text(params, "sessionId", 256), text(params, "text", 262144), text(params, "inputId", 256));
                        }
                        case "cancelTask" -> {
                            fields(params, "runId", "inputId"); yield runtime.cancelTask(text(params, "runId", 256), text(params, "inputId", 256));
                        }
                        case "confirmTool" -> {
                            fields(params, "runId", "waitingId", "approved", "inputId");
                            if (!params.path("approved").isBoolean()) throw invalid();
                            yield runtime.confirmTool(text(params, "runId", 256), text(params, "waitingId", 256), params.required("approved").booleanValue(), text(params, "inputId", 256));
                        }
                        case "shutdown" -> { fields(params); yield DesktopJson.object().put("accepted", true); }
                        default -> throw new IllegalArgumentException("METHOD_NOT_FOUND");
                    };
                    var response = DesktopJson.object(); response.set("id", id); response.set("result", result); event(response);
                    if (method.equals("shutdown")) return;
                } catch (DesktopRequestException rejected) {
                    error(id, rejected.code().name(), rejected.code().publicMessage());
                } catch (ExecutionStore.Rejection rejected) {
                    error(id, rejected.reason(), "请求与已保存的任务状态冲突，请刷新任务状态。");
                } catch (IllegalArgumentException rejected) {
                    error(id, "INVALID_REQUEST", "请求参数、模型配置或工作目录无效。");
                } catch (IllegalStateException rejected) {
                    error(id, "LOCAL_OPERATION_FAILED", "本地操作未能完成，请检查任务状态。");
                } catch (IOException failure) {
                    error(id, "LOCAL_IO_FAILED", "无法访问指定的本地目录或文件。");
                }
            }
        } finally {
            synchronized (this) { stopped = true; }
            tests.shutdownNow();
        }
    }

    private void error(JsonNode id, String code, String message) {
        var value = DesktopJson.object(); if (id == null) value.putNull("id"); else value.set("id", id);
        value.putObject("error").put("code", code).put("message", message); event(value);
    }

    private static String line(BufferedReader reader) throws IOException {
        var value = new StringBuilder(); int next;
        while ((next = reader.read()) != -1) {
            if (next == '\n') return value.toString();
            if (next != '\r') value.append((char) next);
            if (value.length() > MAX_LINE_CHARACTERS) throw new IOException("desktop_protocol_line_too_large");
        }
        return value.isEmpty() ? null : value.toString();
    }

    private static String text(JsonNode object, String field, int maximum) {
        var value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > maximum) throw invalid();
        return value.textValue();
    }

    private static void fields(JsonNode object, String... expected) {
        if (!object.isObject()) throw invalid();
        var names = new HashSet<String>(); object.fieldNames().forEachRemaining(names::add);
        if (!names.equals(Set.of(expected))) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("desktop_request_invalid"); }
}
