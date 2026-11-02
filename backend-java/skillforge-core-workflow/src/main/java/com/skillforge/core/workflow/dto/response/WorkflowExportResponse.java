package com.skillforge.core.workflow.dto.response;

import java.util.List;

/**
 * 工作流包导出响应 DTO。
 *
 * @param status       导出状态
 * @param workflowYaml workflow.yaml 内容（含 meta 段，原 WORKFLOW.md 已合并）
 * @param files        导出文件列表
 */
public record WorkflowExportResponse(
        String status,
        String workflowYaml,
        List<ExportFileResponse> files) {
}
