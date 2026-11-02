package com.skillforge.institution.builder;
import com.skillforge.core.workflow.builder.WorkflowExportService;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 可视化构建器导出服务单元测试。
 *
 * <p>验证导出结果为单一 workflow.yaml（含 meta 段且不携带画布布局），
 * 且 workflow 携带入口节点。
 */
class WorkflowExportServiceTest {

    /** 被测导出服务。 */
    private final WorkflowExportService service = new WorkflowExportService();

    /**
     * 导出结果应包含 meta 段且不包含 Builder layout。
     */
    @Test
    void exportContainsMetaWithoutLayout() {
        Map<String, Object> canvas = Map.of(
                "name", "demo",
                "display_name", "演示工作流",
                "version", "1.0.0",
                "description", "演示",
                "entry", "start",
                "nodes", List.of(
                        Map.of("id", "start", "type", "output", "x", 120, "y", 80)),
                "edges", List.of());
        Map<String, Object> result = service.export(canvas);
        String yaml = String.valueOf(result.get("workflow_yaml"));
        assertTrue(yaml.contains("entry: start"));
        // meta 段：包名（无 -workflow 后缀）、display_name
        assertTrue(yaml.contains("meta:"));
        assertTrue(yaml.contains("name: demo"));
        assertTrue(yaml.contains("display_name: 演示工作流"));
        assertTrue(yaml.contains("version: 1.0.0"));
        assertTrue(!yaml.contains("layout:"));
        assertTrue(!yaml.contains("name: demo-workflow"));
    }
}
