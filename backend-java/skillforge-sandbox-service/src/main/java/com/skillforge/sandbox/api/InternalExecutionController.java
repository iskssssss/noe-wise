package com.skillforge.sandbox.api;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.skillforge.execution.ExecutionAdmissionRejection;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.execution.ExecutionManifest;
import com.skillforge.execution.ExecutionResponse;
import com.skillforge.sandbox.execution.ArtifactCollector;
import com.skillforge.sandbox.execution.ExecutionCoordinator;
import com.skillforge.sandbox.persistence.TaskRecord;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.zip.ZipInputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/internal/executions")
public class InternalExecutionController {
    private static final int MAX_LOG_PAGE_CHARACTERS = 32_768;

    private final ExecutionCoordinator executions;
    private final ArtifactCollector artifacts;
    private final ObjectMapper json;
    private final jakarta.validation.Validator validator;

    public InternalExecutionController(ExecutionCoordinator executions, ArtifactCollector artifacts,
            ObjectMapper json,jakarta.validation.Validator validator) {
        this.executions = executions;
        this.artifacts = artifacts;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.validator = validator;
    }

    @GetMapping("/detail")
    public ExecutionResponse status(@RequestParam("taskId") String taskId) {
        return executions.get(taskId);
    }

    @DeleteMapping("/delete")
    public ExecutionResponse cancel(@Valid @RequestBody CancelRequest request) {
        return executions.cancel(request.taskId());
    }

    /** 首条 ZIP 记录仅携带有界元数据，后续文件在调度前流式验证，禁止 JSON 内嵌正文。 */
    @PostMapping(value="/artifact-inputs",consumes="application/zip")
    public ResponseEntity<ExecutionResponse> executeArtifacts(jakarta.servlet.http.HttpServletRequest request)throws IOException {
        ZipInputStream archive;
        try { archive = new ZipInputStream(request.getInputStream()); }
        catch (IOException unreadable) {
            throw new ExecutionAdmissionException(ExecutionAdmissionRejection.Code.INVALID_ARTIFACT_ARCHIVE);
        }
        try(var zip=archive) {
            var envelope=readManifest(zip);
            // Coordinator 仅在锁内确认尚未登记时给出准入回执；入口不把普通异常改写为 NOT_SUBMITTED。
            ExecutionCoordinator.Submission submission=executions.submit(envelope.execution(),envelope.artifacts(),zip);
            return ResponseEntity.status(submission.created()?HttpStatus.ACCEPTED:HttpStatus.OK).body(submission.response());
        }
    }

    /** 只在执行提交边界之前形成未执行回执，解析错误不回显请求正文或 Jackson cause。 */
    private ExecutionManifest readManifest(ZipInputStream zip) {
        byte[] header;
        try {
            var first=zip.getNextEntry();
            if(first==null||first.isDirectory()||!ExecutionLimits.REQUEST_ENTRY.equals(first.getName()))
                throw new ExecutionAdmissionException(ExecutionAdmissionRejection.Code.INVALID_ARTIFACT_ARCHIVE);
            header=zip.readNBytes(ExecutionLimits.CONTROL_RESPONSE_BYTES+1);
            if(header.length>ExecutionLimits.CONTROL_RESPONSE_BYTES)
                throw new ExecutionAdmissionException(ExecutionAdmissionRejection.Code.INVALID_ARTIFACT_ARCHIVE);
        } catch (IOException unreadable) {
            throw new ExecutionAdmissionException(ExecutionAdmissionRejection.Code.INVALID_ARTIFACT_ARCHIVE);
        }
        try {
            var envelope=json.readValue(header,ExecutionManifest.class);
            if(envelope==null||!validator.validate(envelope.execution()).isEmpty())
                throw new ExecutionAdmissionException(ExecutionAdmissionRejection.Code.INVALID_EXECUTION_MANIFEST);
            return envelope;
        } catch (IOException | IllegalArgumentException malformed) {
            throw new ExecutionAdmissionException(ExecutionAdmissionRejection.Code.INVALID_EXECUTION_MANIFEST);
        }
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record CancelRequest(@jakarta.validation.constraints.NotBlank String taskId) { }

    @GetMapping("/tool-calls")
    public ToolCallsResponse toolCalls(@RequestParam("taskId") String taskId) {
        return new ToolCallsResponse(executions.pendingToolCalls(taskId).stream()
                .map(call -> new ToolCall(call.ordinal(), call.handle(), call.arguments()))
                .toList());
    }

    @PostMapping("/tool-results")
    public ResponseEntity<Void> completeToolCall(@Valid @RequestBody ToolResultRequest request) {
        executions.completeToolCall(request.taskId(), request.ordinal(), request.result());
        return ResponseEntity.noContent().build();
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ToolCallsResponse(java.util.List<ToolCall> calls) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ToolCall(int ordinal, String handle, com.fasterxml.jackson.databind.JsonNode arguments) { }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record ToolResultRequest(@jakarta.validation.constraints.NotBlank String taskId,
                                    @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(com.skillforge.execution.ExecutionLimits.PROGRAM_CALLS) int ordinal,
                                    @jakarta.validation.constraints.NotNull
                                    com.fasterxml.jackson.databind.JsonNode result) { }

    /** 内部按页读取捕获日志；公开聊天不能自动采用日志正文。 */
    @GetMapping("/logs")
    public LogPage logs(@RequestParam("taskId") String taskId, @RequestParam("stream") String stream,
            @RequestParam(defaultValue="0") int offset, @RequestParam(defaultValue="32768") int limit) {
        if(offset<0||limit<1||limit>MAX_LOG_PAGE_CHARACTERS||!java.util.Set.of("stdout","stderr").contains(stream))
            throw new SandboxRequestException("INVALID_LOG_PAGE","日志页参数不合法");
        TaskRecord task=executions.stoppedTask(taskId);
        String text="stdout".equals(stream)?task.stdout():task.stderr();
        int begin=Math.min(offset,text.length()),end=Math.min(text.length(),begin+limit);
        return new LogPage(stream,text.substring(begin,end),end,end<text.length(),
                "stdout".equals(stream)?task.stdoutTruncated():task.stderrTruncated());
    }
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record LogPage(String stream,String text,int nextOffset,boolean hasMore,boolean truncated) { }

    @GetMapping(value = "/files", produces = "application/zip")
    public ResponseEntity<StreamingResponseBody> files(@RequestParam("taskId") String taskId) {
        TaskRecord task = executions.stoppedTask(taskId);
        StreamingResponseBody body = output -> artifacts.streamZip(taskId, task.files(), output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"artifacts-" + taskId + ".zip\"")
                .body(body);
    }
}
