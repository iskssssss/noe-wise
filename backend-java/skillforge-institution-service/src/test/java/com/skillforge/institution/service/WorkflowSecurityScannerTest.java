package com.skillforge.institution.service;
import com.skillforge.core.workflow.WorkflowAssetService;
import com.skillforge.core.workflow.WorkflowSecurityScanner;
import com.skillforge.core.workflow.WorkflowAssetService.Asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 工作流包安全扫描器单元测试。
 *
 * <p>工作流定义已结构化入库（workflow_assets），扫描对象为入库资产内容：
 * 正常包通过、无资源拒绝、危险命令模式拒绝。
 */
class WorkflowSecurityScannerTest {

    /** 工作流资源资产服务（mock，返回入库资产）。 */
    private WorkflowAssetService workflowAssetService;

    /** 被测扫描器。 */
    private WorkflowSecurityScanner scanner;

    /**
     * 初始化扫描器（mock 资产服务）。
     */
    @BeforeEach
    void setUp() {
        workflowAssetService = mock(WorkflowAssetService.class);
        scanner = new WorkflowSecurityScanner(workflowAssetService);
    }

    /** 构造 workflow.yaml 资产。 */
    private Asset workflowYamlAsset(String content) {
        return plainAsset("workflow.yaml", content);
    }

    /** 构造普通资产。 */
    private Asset plainAsset(String name, String content) {
        return new Asset(null, name, content.getBytes(StandardCharsets.UTF_8), null);
    }

    /**
     * 正常工作流包应通过扫描并返回校验和。
     */
    @Test
    void validPackagePasses() {
        when(workflowAssetService.listByWorkflow("demo")).thenReturn(List.of(
                workflowYamlAsset("meta:\n  name: demo\n  version: 1.0.0\nnodes: []\n")));
        var result = scanner.scan("demo");
        assertEquals(Boolean.TRUE, result.passed());
        assertEquals(List.of(), result.issues());
        assertTrue(result.checksum() instanceof String);
    }

    /**
     * 工作流资源不存在（未上传或未入库）时应拒绝。
     */
    @Test
    void missingPackageRejected() {
        when(workflowAssetService.listByWorkflow("missing")).thenReturn(List.of());
        var result = scanner.scan("missing");
        assertFalse(Boolean.TRUE.equals(result.passed()));
        assertTrue(String.valueOf(result.issues()).contains("工作流包资源不存在"));
    }

    /**
     * 缺少 workflow.yaml 资产时应拒绝。
     */
    @Test
    void missingWorkflowYamlRejected() {
        when(workflowAssetService.listByWorkflow("demo")).thenReturn(List.of(
                plainAsset("scripts/run.py", "print('ok')")));
        var result = scanner.scan("demo");
        assertFalse(Boolean.TRUE.equals(result.passed()));
        assertTrue(String.valueOf(result.issues()).contains("缺少 workflow.yaml"));
    }

    /**
     * 包含危险命令模式的工作流包应拒绝（workflow.yaml 资产全文参与扫描）。
     */
    @Test
    void dangerousPatternRejected() {
        when(workflowAssetService.listByWorkflow("demo")).thenReturn(List.of(
                workflowYamlAsset("nodes:\n  - id: x\n    type: script\n    args: {script: 'run os.system(\"rm -rf /\")'}\n")));
        var result = scanner.scan("demo");
        assertFalse(Boolean.TRUE.equals(result.passed()));
        assertTrue(String.valueOf(result.issues()).contains("危险模式"));
    }
}
