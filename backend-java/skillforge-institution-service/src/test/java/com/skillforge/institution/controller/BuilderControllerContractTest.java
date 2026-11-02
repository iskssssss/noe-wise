package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.skillforge.core.workflow.builder.WorkflowExportService;
import com.skillforge.core.workflow.dto.request.WorkflowExportRequest;
import com.skillforge.core.workflow.dto.response.WorkflowExportResponse;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

/** Builder 控制面只能生成制品，不能建立 Workflow 执行旁路。 */
class BuilderControllerContractTest {

    @Test
    void exportsTheOnlyBuilderArtifactThroughTheExportService() {
        WorkflowExportService exportService = mock(WorkflowExportService.class);
        BuilderController controller = new BuilderController(exportService);
        WorkflowExportRequest request = new WorkflowExportRequest();
        request.put("nodes", List.of(Map.of("id", "start")));
        when(exportService.export(request.canvasData())).thenReturn(Map.of(
                "status", "exported",
                "workflow_yaml", "entry: start\n",
                "files", List.of(Map.of("name", "workflow.yaml", "content", "entry: start\n"))));

        WorkflowExportResponse response = controller.export(request).data();

        assertTrue(response.status().equals("exported"));
        assertTrue(response.workflowYaml().contains("entry: start"));
        assertTrue(response.files().stream().anyMatch(file -> file.name().equals("workflow.yaml")));
        verify(exportService).export(request.canvasData());
    }

    @Test
    void treatsAnUnexpectedExportFileShapeAsAnEmptyArtifactList() {
        WorkflowExportService exportService = mock(WorkflowExportService.class);
        BuilderController controller = new BuilderController(exportService);
        WorkflowExportRequest request = new WorkflowExportRequest();
        when(exportService.export(request.canvasData())).thenReturn(Map.of(
                "status", "exported",
                "workflow_yaml", "entry: start\n",
                "files", Map.of("unexpected", "object")));

        WorkflowExportResponse response = controller.export(request).data();

        assertTrue(response.files().isEmpty());
    }

    @Test
    void builderDoesNotExposeRawDryRunOrExecutionEndpoint() {
        assertFalse(Arrays.stream(BuilderController.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equals("dryRun") || name.equals("run") || name.equals("resume")));
        assertFalse(Arrays.stream(BuilderController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch(path -> path.equals("/dry-run") || path.equals("/run") || path.equals("/resume")));
    }

    @Test
    void builderKeepsOnlyArtifactExportEndpoint() {
        assertTrue(Arrays.stream(BuilderController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .anyMatch("/export"::equals));
    }
}
