package com.skillforge.institution.controller;

import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;

import com.skillforge.core.workflow.builder.WorkflowExportService;
import com.skillforge.core.shared.constant.ApiConstants;
import com.skillforge.core.workflow.constant.BuilderConstants;
import com.skillforge.core.workflow.dto.response.ExportFileResponse;
import com.skillforge.core.workflow.dto.request.WorkflowExportRequest;
import com.skillforge.core.workflow.dto.response.WorkflowExportResponse;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可视化构建器接口控制器。
 *
 * <p>只提供工作流包导出。Builder 的测试必须经 Unified Inbound、Agent Runtime
 * 与 WorkflowPortal，不能在控制面直接模拟或执行 Workflow。
 */
@RestController
@RequestMapping("/api/v1/builder")
public class BuilderController {

    /** 工作流包导出服务。 */
    private final WorkflowExportService exportService;

    /**
     * 构造构建器控制器。
     *
     * @param exportService 导出服务
     */
    public BuilderController(WorkflowExportService exportService) {
        this.exportService = exportService;
    }

    /**
     * 导出 workflow.yaml（含 meta 段，原 WORKFLOW.md 已合并废弃）。
     *
     * @param request 画布数据请求
     * @return 导出结果
     */
    @PostMapping("/export")
    public ApiResponse<WorkflowExportResponse> export(@RequestBody WorkflowExportRequest request) {
        Map<String, Object> result = exportService.export(request.canvasData());
        return ApiResponses.wrap(new WorkflowExportResponse(
                String.valueOf(result.get(ApiConstants.STATUS)),
                String.valueOf(result.get(BuilderConstants.WORKFLOW_YAML)),
                toFiles(result.get(BuilderConstants.FILES))));
    }

    /**
     * 将导出文件列表转换为响应 DTO。
     *
     * @param raw 原始文件列表
     * @return 文件响应列表
     */
    @SuppressWarnings("unchecked")
    private List<ExportFileResponse> toFiles(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(item -> {
            Map<String, Object> file = (Map<String, Object>) item;
            return new ExportFileResponse(
                    String.valueOf(file.get(BuilderConstants.FILE_NAME)),
                    String.valueOf(file.get(BuilderConstants.FILE_CONTENT)));
        }).toList();
    }
}
