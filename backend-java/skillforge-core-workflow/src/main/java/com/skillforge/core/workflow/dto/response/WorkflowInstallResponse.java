package com.skillforge.core.workflow.dto.response;
/**
 * 工作流安装响应 DTO。
 *
 * @param status     安装状态
 * @param workflowId    工作流业务 ID
 * @param tenantId   租户 ID
 * @param instanceId 实例业务 ID
 */
public record WorkflowInstallResponse(
        String status,
        String workflowId,
        String tenantId,
        String instanceId,
        Long installationRevision) {
}
