package com.skillforge.sandbox.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.skillforge.execution.ArtifactFile;
import com.skillforge.execution.ExecuteRequest;
import com.skillforge.execution.ExecutionAdmissionRejection;
import com.skillforge.execution.ExecutionLimits;
import com.skillforge.execution.ExecutionManifest;
import com.skillforge.execution.ExecutionPolicy;
import com.skillforge.execution.ExecutionProfile;
import com.skillforge.execution.ExecutionResponse;
import com.skillforge.execution.ExecutionState;
import com.skillforge.execution.Language;
import com.skillforge.execution.PublicResults;
import com.skillforge.sandbox.execution.ArtifactCollector;
import com.skillforge.sandbox.execution.ExecutionCoordinator;
import jakarta.validation.Validator;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 实际 MVC/ZIP 元数据边界：提交前拒绝才有 NOT_SUBMITTED 语义。 */
class InternalExecutionControllerTest {
    private static final String ENDPOINT = "/internal/executions/artifact-inputs";
    private static final String ZIP_CONTENT_TYPE = "application/zip";
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final ExecutionCoordinator executions = mock(ExecutionCoordinator.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        var validator = mock(Validator.class);
        when(validator.validate(any(ExecuteRequest.class))).thenReturn(Set.of());
        var controller = new InternalExecutionController(executions, mock(ArtifactCollector.class), json, validator);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
    }

    @Test void actualMetadataParserAcceptsHostMapperAndStreamsFilesIntoSingleSubmission() throws Exception {
        byte[] content = "print(17*23)".getBytes(StandardCharsets.UTF_8);
        var file = new ArtifactFile("calculate.py", content.length, "a".repeat(64));
        var expected = request();
        var manifest = new ExecutionManifest(expected, List.of(file));
        var response = new ExecutionResponse(expected.taskId(), ExecutionState.QUEUED, "queued", null,
                false, false, null, null, List.of(), List.of(), null,
                new PublicResults(List.of(new PublicResults.Text("plain", "业务结果"))));
        when(executions.submit(any(ExecuteRequest.class), anyList(), any(ZipInputStream.class))).thenAnswer(call -> {
            assertEquals(expected, call.getArgument(0));
            assertEquals(List.of(file), call.getArgument(1));
            ZipInputStream archive = call.getArgument(2);
            assertEquals(file.path(), archive.getNextEntry().getName());
            assertArrayEquals(content, archive.readAllBytes());
            return new ExecutionCoordinator.Submission(response, true);
        });
        mvc.perform(post(ENDPOINT).contentType(ZIP_CONTENT_TYPE)
                        .content(archive(ExecutionLimits.REQUEST_ENTRY, json.writeValueAsBytes(manifest), file.path(), content)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.taskId").value(expected.taskId()))
                .andExpect(jsonPath("$.publicResults.results[0].text").value("业务结果"))
                .andExpect(jsonPath("$.public_results").doesNotExist());
        verify(executions, times(1)).submit(any(ExecuteRequest.class), anyList(), any(ZipInputStream.class));
    }

    @Test void malformedMissingDuplicateAndSnakeCaseMetadataNeverReachExecutionSubmission() throws Exception {
        String valid = json.writeValueAsString(new ExecutionManifest(request(), List.of()));
        for (String body : List.of("{", "{}", "null", valid + " {}",
                valid.replace("\"taskId\"", "\"task_id\""),
                valid.replace("\"taskId\":\"controller-test\"", "\"taskId\":\"controller-test\",\"publicResultFile\":\"result.json\""),
                valid.replace("\"taskId\":\"controller-test\"", "\"taskId\":\"controller-test\",\"taskId\":\"other\""),
                valid.substring(0, valid.length() - 1) + ",\"owner\":\"forged\"}")) {
            mvc.perform(post(ENDPOINT).contentType(ZIP_CONTENT_TYPE)
                            .content(archive(ExecutionLimits.REQUEST_ENTRY, body.getBytes(StandardCharsets.UTF_8), null, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ExecutionAdmissionRejection.Code.INVALID_EXECUTION_MANIFEST.name()))
                    .andExpect(jsonPath("$.submission").value(ExecutionAdmissionRejection.Submission.NOT_SUBMITTED.name()))
                    .andExpect(jsonPath("$.message").value(ExecutionAdmissionRejection.Code.INVALID_EXECUTION_MANIFEST.message()));
        }
        verifyNoInteractions(executions);
    }

    @Test void missingArchiveMetadataIsTypedPreSubmissionRejection() throws Exception {
        mvc.perform(post(ENDPOINT).contentType(ZIP_CONTENT_TYPE)
                        .content(archive("unexpected.json", "{}".getBytes(StandardCharsets.UTF_8), null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ExecutionAdmissionRejection.Code.INVALID_ARTIFACT_ARCHIVE.name()))
                .andExpect(jsonPath("$.submission").value(ExecutionAdmissionRejection.Submission.NOT_SUBMITTED.name()));
        verifyNoInteractions(executions);
    }

    @Test void failureAfterEnteringSubmissionDoesNotClaimExecutionWasNotSubmitted() throws Exception {
        when(executions.submit(any(ExecuteRequest.class), anyList(), any(ZipInputStream.class)))
                .thenThrow(new IllegalStateException("submission-outcome-unknown"));
        mvc.perform(post(ENDPOINT).contentType(ZIP_CONTENT_TYPE)
                        .content(archive(ExecutionLimits.REQUEST_ENTRY,
                                json.writeValueAsBytes(new ExecutionManifest(request(), List.of())), null, null)))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.submission").doesNotExist());
        verify(executions, times(1)).submit(any(ExecuteRequest.class), anyList(), any(ZipInputStream.class));
    }

    @Test void coordinatorAdmissionReceiptSurvivesHttpBoundary() throws Exception {
        when(executions.submit(any(ExecuteRequest.class), anyList(), any(ZipInputStream.class)))
                .thenThrow(new ExecutionAdmissionException(ExecutionAdmissionRejection.Code.INVALID_EXECUTION_POLICY));
        mvc.perform(post(ENDPOINT).contentType(ZIP_CONTENT_TYPE)
                        .content(archive(ExecutionLimits.REQUEST_ENTRY,
                                json.writeValueAsBytes(new ExecutionManifest(request(), List.of())), null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXECUTION_POLICY"))
                .andExpect(jsonPath("$.submission").value("NOT_SUBMITTED"));
    }

    @Test void controlDtosKeepCamelCaseUnderHostNamingStrategy() throws Exception {
        var result = new InternalExecutionController.ToolResultRequest("controller-test", 1, json.createObjectNode().put("order_id", 17));
        var body = json.readTree(json.writeValueAsBytes(result));
        assertTrue(body.has("taskId")); assertFalse(body.has("task_id"));
        assertEquals(result, json.readValue(body.toString(), InternalExecutionController.ToolResultRequest.class));
        var page = json.valueToTree(new InternalExecutionController.LogPage("stdout", "internal", 4, true, false));
        assertTrue(page.has("nextOffset")); assertTrue(page.has("hasMore"));
        assertFalse(page.has("next_offset")); assertFalse(page.has("has_more"));
        var cancellation = json.valueToTree(new InternalExecutionController.CancelRequest("controller-test"));
        assertTrue(cancellation.has("taskId")); assertFalse(cancellation.has("task_id"));
    }

    private static ExecuteRequest request() {
        var policy = new ExecutionPolicy(1, ExecutionLimits.DEFAULT_TIMEOUT_SECONDS, 30, 24, 60,
                ExecutionLimits.OUTPUT_CAPTURE_BYTES, ExecutionLimits.MAX_FILES, ExecutionLimits.CONCURRENT_EXECUTIONS,
                ExecutionLimits.MEMORY_LIMIT, new BigDecimal("2"), ExecutionLimits.PIDS_LIMIT);
        return new ExecuteRequest("controller-test", Language.NODE, "console.log(17*23)", List.of(), List.of(),
                null, true, Instant.parse("2026-10-01T00:05:00Z"), policy, List.of("report.txt"),
                ExecutionProfile.STANDARD, "test-node", "{\"order_id\":17}");
    }

    private static byte[] archive(String metadataName, byte[] metadata, String path, byte[] file) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(metadataName)); zip.write(metadata); zip.closeEntry();
            if (path != null) { zip.putNextEntry(new ZipEntry(path)); zip.write(file); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
}
