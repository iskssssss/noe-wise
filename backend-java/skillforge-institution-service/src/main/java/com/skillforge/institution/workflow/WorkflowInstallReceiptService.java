package com.skillforge.institution.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.WorkflowBuilderInstallationService;
import com.skillforge.core.workflow.dto.request.WorkflowInstallByWorkflowIdRequest;
import com.skillforge.institution.service.WorkflowManagementService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 安装与窄回执同事务提交；读取只返回原请求的修订。 */
@Service
public class WorkflowInstallReceiptService {
    private final FoundationAccess access;
    private final WorkflowManagementService management;
    private final WorkflowBuilderInstallationService installations;
    private final WorkflowInstallReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;

    public WorkflowInstallReceiptService(FoundationAccess access, WorkflowManagementService management,
            WorkflowBuilderInstallationService installations, WorkflowInstallReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context) {
        this.access = access; this.management = management; this.installations = installations;
        this.receipts = receipts; this.context = context;
    }

    @Transactional(rollbackFor = Exception.class)
    public Receipt install(long tenant, Command command) {
        if (command == null || command.requestId() == null || command.workflowId() == null
                || command.workflowId().isBlank() || command.payload() == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "工作流安装请求无效");
        access.require("foundation.workflows.create");
        String actor = member(tenant);
        management.require(tenant, command.workflowId(), "manage");
        context.bindTenant(tenant);
        var payload = command.payload();
        var result = installations.installOrUpgrade(tenant, command.workflowId(),
                payload.config() == null ? Map.of() : payload.config(), payload.customPrompt(), payload.bindings());
        if (receipts.insert(tenant, command.requestId().toString(), actor, command.workflowId(),
                result.instanceId(), result.installationRevision()) != 1)
            throw new IllegalStateException("工作流安装回执写入失败");
        return new Receipt(command.requestId(), command.workflowId(), result.instanceId(), result.installationRevision());
    }

    @Transactional(readOnly = true)
    public Receipt result(long tenant, String workflowId, UUID requestId) {
        access.require("foundation.workflows.read");
        String actor = member(tenant);
        management.require(tenant, workflowId, "read");
        context.bindTenant(tenant);
        var row = receipts.find(tenant, requestId.toString(), actor, workflowId);
        return row == null ? null : new Receipt(requestId, workflowId, row.instanceId(), row.revision());
    }

    private String member(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return subject.memberId();
    }
    public record Command(@JsonProperty("request_id") UUID requestId, @JsonProperty("workflow_id") String workflowId,
            WorkflowInstallByWorkflowIdRequest payload) { }
    public record Receipt(@JsonProperty("request_id") UUID requestId, @JsonProperty("workflow_id") String workflowId,
            @JsonProperty("instance_id") String instanceId,
            @JsonProperty("installation_revision") @JsonSerialize(using = ToStringSerializer.class) long revision) { }
}
