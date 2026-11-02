package com.skillforge.institution.execution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.runtime.api.TypedPayload;
import com.skillforge.execution.*;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import com.skillforge.runtime.api.port.InvocationControl;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Workflow 脚本节点与 Sandbox 的单次认证传输边界。
 *
 * <p>调用方负责调度、重试决策和状态轮询；本组件绝不重放 submit 请求。</p>
 */
@Component
public final class WorkflowScriptSandboxClient {
    private static final Pattern TASK_ID = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_RESPONSE_BYTES = ExecutionLimits.CONTROL_RESPONSE_BYTES;
    private static final int MAX_RESULT_BYTES = ExecutionLimits.WORKFLOW_RESULT_BYTES;
    private static final ObjectMapper STRICT_JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final HttpClient http;
    private final URI endpoint;
    private final String serviceToken;
    private final String pythonEnvironmentVersion;
    private final String nodeEnvironmentVersion;

    public WorkflowScriptSandboxClient(
            @Value("$" + "{skillforge.code-execution.endpoint:}") String endpoint,
            @Value("$" + "{skillforge.code-execution.service-token:}") String serviceToken,
            @Value("$" + "{skillforge.code-execution.workflow-python-environment-version:workflow-python-v1}")
            String pythonEnvironmentVersion,
            @Value("$" + "{skillforge.code-execution.workflow-node-environment-version:workflow-node-v1}")
            String nodeEnvironmentVersion) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.endpoint = normalizeEndpoint(endpoint);
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.pythonEnvironmentVersion = requireText(pythonEnvironmentVersion, "workflow python environment version");
        this.nodeEnvironmentVersion = requireText(nodeEnvironmentVersion, "workflow node environment version");
    }

    /** 已验证 taskId 与请求体一致的固定提交载荷。 */
    public record PreparedRequest(String taskId, String canonicalJson,List<ExecutionInputSource> files) {
        public PreparedRequest {
            files=List.copyOf(Objects.requireNonNull(files,"files"));
            validateTaskId(taskId);
            canonicalJson = requireText(canonicalJson, "canonicalJson");
            JsonNode request = parseJson(canonicalJson, "workflow_script_sandbox_request_invalid");
            if (!request.isObject()
                    || !request.path("taskId").isTextual()
                    || !taskId.equals(request.path("taskId").textValue())) {
                throw new IllegalArgumentException("workflow_script_sandbox_request_task_id_invalid");
            }
        }
    }

    /** Sandbox 返回的封闭任务状态。 */
    public enum State {
        QUEUED("queued"),
        RUNNING("running"),
        COMPLETED("completed"),
        FAILED("failed"),
        CANCELLED("cancelled"),
        TIMED_OUT("timed_out"),
        UNKNOWN("unknown");

        private final String wireValue;

        State(String wireValue) {
            this.wireValue = wireValue;
        }

        static State fromWire(String wireValue) {
            for (State candidate : values()) {
                if (candidate.wireValue.equals(wireValue)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("workflow_script_sandbox_state_invalid");
        }
    }

    /** 经严格校验的单次 Sandbox 观察结果。 */
    public record Status(
            String taskId,
            State state,
            Optional<TypedPayload> jsonResult,
            Optional<String> error) {
        public Status {
            validateTaskId(taskId);
            state = Objects.requireNonNull(state, "state");
            jsonResult = Objects.requireNonNull(jsonResult, "jsonResult");
            error = Objects.requireNonNull(error, "error");
            if (state != State.COMPLETED && jsonResult.isPresent()) {
                throw new IllegalArgumentException("workflow_script_sandbox_result_on_non_completed");
            }
        }
    }

    /** 指示已发生或可能已发生的请求不能被本地调用方当作成功。 */
    public static final class OutcomeUnknownException extends IllegalStateException {
        public OutcomeUnknownException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 单次 POST 提交；确定准入拒绝与结果未知分开采用，两者均不自动重发。 */
    public Status submit(PreparedRequest request, InvocationControl control) {
        Objects.requireNonNull(request, "request");
        Path archive=null;
        try {
            archive=Files.createTempFile("skillforge-workflow-input-",".zip");
            writeArchive(request,archive,control);
            return sendStatus("POST","/internal/executions/artifact-inputs",null,archive,request.taskId(),control,false)
                    .orElseThrow(()->unknown("workflow_script_sandbox_submit_missing",null));
        } catch(IOException invalid) { throw unknown("workflow_script_sandbox_outcome_unknown",invalid); }
        finally { if(archive!=null)try{Files.deleteIfExists(archive);}catch(IOException ignored){/* 临时文件不影响持久任务事实。 */} }
    }

    private static void writeArchive(PreparedRequest request,Path target,InvocationControl control)throws IOException {
        var json=STRICT_JSON.copy().findAndRegisterModules();
        var execution=json.readValue(request.canonicalJson(),ExecuteRequest.class);
        var manifest=new ExecutionManifest(execution,request.files().stream().map(ExecutionInputSource::file).toList());
        byte[] header=json.writeValueAsBytes(manifest);
        if(header.length>ExecutionLimits.CONTROL_RESPONSE_BYTES)throw new IOException("workflow_manifest_too_large");
        long total=0;var paths=new java.util.HashSet<String>();
        try(var zip=new ZipOutputStream(Files.newOutputStream(target))) {
            zip.putNextEntry(new ZipEntry(ExecutionLimits.REQUEST_ENTRY));zip.write(header);zip.closeEntry();
            for(var source:request.files()) {
                var file=source.file();total=Math.addExact(total,file.size());
                if(total>ExecutionLimits.TRANSFER_BYTES||!paths.add(file.path()))throw new IOException("workflow_file_limit");
                zip.putNextEntry(new ZipEntry(file.path()));long size=0;
                java.security.MessageDigest digest;
                try { digest=java.security.MessageDigest.getInstance("SHA-256"); }
                catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
                try(var input=source.open()) {
                    byte[] buffer=new byte[ExecutionLimits.STREAM_BUFFER_BYTES];int read;
                    while((read=input.read(buffer))!=-1) {
                        if(!controlActive(control))throw new IOException("workflow_input_deadline_elapsed");
                        size=Math.addExact(size,read);if(size>file.size())throw new IOException("workflow_input_size");
                        zip.write(buffer,0,read);digest.update(buffer,0,read);
                    }
                }
                if(size!=file.size()||!java.util.HexFormat.of().formatHex(digest.digest()).equals(file.sha256()))
                    throw new IOException("workflow_input_integrity");
                zip.closeEntry();
            }
        }
    }

    /** 单次 GET 查询；仅 404 表示该任务尚不可见。 */
    public Optional<Status> query(String taskId, InvocationControl control) {
        validateTaskId(taskId);
        return sendStatus("GET", "/internal/executions/detail?taskId=" + taskId, null, null, taskId, control, true);
    }

    /** 单次 DELETE 取消请求。 */
    public Status cancel(String taskId, InvocationControl control) {
        validateTaskId(taskId);
        return sendStatus("DELETE", "/internal/executions/delete", "{\"taskId\":\""+taskId+"\"}", null, taskId, control, false)
                .orElseThrow(() -> unknown("workflow_script_sandbox_cancel_missing", null));
    }

    /** 返回部署时冻结的环境标识，不查询 Sandbox 的 current 或默认环境。 */
    public String environmentVersion(String language) {
        return switch (requireText(language, "language").toLowerCase(Locale.ROOT)) {
            case "python" -> pythonEnvironmentVersion;
            case "node" -> nodeEnvironmentVersion;
            default -> throw new IllegalArgumentException("workflow_script_sandbox_language_unsupported");
        };
    }

    private Optional<Status> sendStatus(
            String method,
            String pathAndQuery,
            String body,
            Path archive,
            String taskId,
            InvocationControl control,
            boolean absentOnNotFound) {
        requireReady(control);
        HttpRequest.Builder request = HttpRequest.newBuilder(requestUri(pathAndQuery))
                .timeout(remaining(control))
                .header("Authorization", "Bearer " + serviceToken);
        request.header("Content-Type",archive==null?"application/json":"application/zip");

        HttpRequest.BodyPublisher publisher;
        try { publisher=archive!=null?HttpRequest.BodyPublishers.ofFile(archive):body==null
                ?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8); }
        catch(IOException invalid) { throw unknown("workflow_script_sandbox_outcome_unknown",invalid); }
        CompletableFuture<HttpResponse<InputStream>> pending = http.sendAsync(
                request.method(method,publisher).build(),HttpResponse.BodyHandlers.ofInputStream());
        try {
            HttpResponse<InputStream> response = awaitResponse(pending, control);
            byte[] responseBody = readResponseBody(response.body(), control);
            if (absentOnNotFound && response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (archive != null && "POST".equals(method) && response.statusCode() == 400) {
                    var receipt = STRICT_JSON.copy()
                            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(responseBody, ExecutionAdmissionRejection.class);
                    throw new ExecutionSubmissionRejectedException(receipt);
                }
                throw unknown("workflow_script_sandbox_outcome_unknown", null);
            }
            return Optional.of(parseStatus(responseBody, taskId));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unknown("workflow_script_sandbox_outcome_unknown", interrupted);
        } catch (IOException failed) {
            throw unknown("workflow_script_sandbox_outcome_unknown", failed);
        } catch (RuntimeException rejected) {
            if (rejected instanceof ExecutionSubmissionRejectedException admission) {
                throw admission;
            }
            if (rejected instanceof OutcomeUnknownException unknown) {
                throw unknown;
            }
            throw unknown("workflow_script_sandbox_outcome_unknown", rejected);
        } finally {
            if (!pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    private static HttpResponse<InputStream> awaitResponse(
            CompletableFuture<HttpResponse<InputStream>> pending, InvocationControl control)
            throws IOException, InterruptedException {
        while (true) {
            if (!controlActive(control)) {
                pending.cancel(true);
                throw unknown("workflow_script_sandbox_outcome_unknown", null);
            }
            try {
                long waitMillis = Math.max(1, Math.min(25, remainingMillis(control)));
                return pending.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                // 仅等待同一 HTTP 传输，不发起轮询请求。
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                if (cause instanceof RuntimeException rejected) {
                    throw rejected;
                }
                throw new IOException("workflow_script_sandbox_transport_failed", cause);
            }
        }
    }

    private static byte[] readResponseBody(InputStream stream, InvocationControl control) throws IOException {
        AtomicBoolean aborted = new AtomicBoolean();
        try (stream;
                ScheduledExecutorService timer = new ScheduledThreadPoolExecutor(1,
                        Thread.ofPlatform().name("workflow-script-sandbox-body-watch").daemon(true).factory())) {
            ScheduledFuture<?> watcher = timer.scheduleAtFixedRate(() -> {
                if (!controlActive(control)) {
                    aborted.set(true);
                    closeQuietly(stream);
                }
            }, 0, 10, TimeUnit.MILLISECONDS);
            try {
                byte[] body = readBounded(stream, MAX_RESPONSE_BYTES);
                if (aborted.get() || !controlActive(control)) {
                    throw unknown("workflow_script_sandbox_outcome_unknown", null);
                }
                return body;
            } finally {
                watcher.cancel(false);
            }
        }
    }

    private static long remainingMillis(InvocationControl control) {
        return Duration.between(Instant.now(), control.deadlineAt()).toMillis();
    }

    private static boolean controlActive(InvocationControl control) {
        try {
            return !control.cancellationSignal().isCancellationRequested()
                    && Instant.now().isBefore(control.deadlineAt());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // 截止时间后关闭响应流无需覆盖结果未知语义。
        }
    }

    private Status parseStatus(byte[] responseBody, String taskId) {
        JsonNode response;
        try {
            response = parseJson(decodeUtf8(responseBody), "workflow_script_sandbox_response_invalid");
        } catch (IllegalArgumentException invalid) {
            throw unknown("workflow_script_sandbox_outcome_unknown", invalid);
        }
        if (!response.isObject()
                || !response.path("taskId").isTextual()
                || !taskId.equals(response.path("taskId").textValue())
                || !response.path("state").isTextual()) {
            throw unknown("workflow_script_sandbox_outcome_unknown", null);
        }

        State state;
        try {
            state = State.fromWire(response.path("state").textValue());
        } catch (IllegalArgumentException invalid) {
            throw unknown("workflow_script_sandbox_outcome_unknown", invalid);
        }

        Optional<String> error = optionalError(response);
        if (state != State.COMPLETED) {
            if (response.has("jsonResult") && !response.path("jsonResult").isNull()) {
                throw unknown("workflow_script_sandbox_outcome_unknown", null);
            }
            return new Status(taskId, state, Optional.empty(), error);
        }

        if (!response.path("exitCode").isIntegralNumber()
                || !response.path("exitCode").canConvertToInt()
                || response.path("exitCode").intValue() != 0
                || !response.path("stdoutTruncated").isBoolean()
                || response.path("stdoutTruncated").booleanValue()
                || !response.path("jsonResult").isObject()) {
            throw unknown("workflow_script_sandbox_outcome_unknown", null);
        }

        String canonicalResult;
        try {
            canonicalResult = STRICT_JSON.writeValueAsString(response.path("jsonResult"));
        } catch (IOException invalid) {
            throw unknown("workflow_script_sandbox_outcome_unknown", invalid);
        }
        if (canonicalResult.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw unknown("workflow_script_sandbox_outcome_unknown", null);
        }
        return new Status(taskId, state, Optional.of(new TypedPayload("application/json", canonicalResult)), error);
    }

    private static Optional<String> optionalError(JsonNode response) {
        if (!response.has("error") || response.path("error").isNull()) {
            return Optional.empty();
        }
        JsonNode error = response.path("error");
        if (!error.isTextual() || error.textValue().isBlank()) {
            throw unknown("workflow_script_sandbox_outcome_unknown", null);
        }
        return Optional.of(error.textValue());
    }

    private static byte[] readBounded(InputStream stream, int limit) throws IOException {
        byte[] buffer = new byte[8192];
        try (var output = new java.io.ByteArrayOutputStream()) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                if (output.size() > limit - read) {
                    throw new IOException("workflow_script_sandbox_response_too_large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("workflow_script_sandbox_response_utf8_invalid", invalid);
        }
    }

    private static JsonNode parseJson(String value, String error) {
        try {
            return STRICT_JSON.readTree(value);
        } catch (IOException invalid) {
            throw new IllegalArgumentException(error, invalid);
        }
    }

    /** 准备阶段验证本地部署配置，不进行网络探测。 */
    public void requireConfiguration() {
        if (endpoint == null || serviceToken.length() < 32)
            throw new IllegalStateException("workflow_script_sandbox_unavailable");
    }

    private void requireReady(InvocationControl control) {
        Objects.requireNonNull(control, "control");
        requireConfiguration();
        try {
            if (control.cancellationSignal().isCancellationRequested()
                    || !Instant.now().isBefore(control.deadlineAt())) {
                throw unknown("workflow_script_sandbox_outcome_unknown", null);
            }
        } catch (OutcomeUnknownException unknown) {
            throw unknown;
        } catch (RuntimeException unavailable) {
            throw unknown("workflow_script_sandbox_outcome_unknown", unavailable);
        }
    }

    private Duration remaining(InvocationControl control) {
        Duration remaining = Duration.between(Instant.now(), control.deadlineAt());
        if (remaining.isZero() || remaining.isNegative()) {
            throw unknown("workflow_script_sandbox_outcome_unknown", null);
        }
        return remaining;
    }

    private URI requestUri(String pathAndQuery) {
        if (endpoint == null) {
            throw new IllegalStateException("workflow_script_sandbox_unavailable");
        }
        return URI.create(endpoint + pathAndQuery);
    }

    private static URI normalizeEndpoint(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            URI value = URI.create(configured.trim());
            String scheme = value.getScheme();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || value.getHost() == null
                    || value.getUserInfo() != null
                    || value.getQuery() != null
                    || value.getFragment() != null) {
                throw new IllegalArgumentException("workflow_script_sandbox_endpoint_invalid");
            }
            String path = value.getRawPath() == null ? "" : value.getRawPath().replaceFirst("/+$", "");
            return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + value.getRawAuthority() + path);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("workflow_script_sandbox_endpoint_invalid", invalid);
        }
    }

    private static void validateTaskId(String taskId) {
        if (taskId == null || !TASK_ID.matcher(taskId).matches()) {
            throw new IllegalArgumentException("workflow_script_sandbox_task_id_invalid");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static OutcomeUnknownException unknown(String message, Throwable cause) {
        return new OutcomeUnknownException(message, cause);
    }
}
