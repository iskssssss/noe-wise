package com.skillforge.core.workflow.dto.request;
import java.util.Map;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
/**
 * 按路径工作流 ID 安装工作流请求 DTO。
 *
 * @param config       实例配置
 * @param customPrompt 自定义提示词
 */
public record WorkflowInstallByWorkflowIdRequest(
        Map<String, Object> config,
        String customPrompt,
        WorkflowInstallationBindings bindings) {
}
