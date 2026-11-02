package com.skillforge.institution.tool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.agent.dto.request.CapabilityMountRequest;
import com.skillforge.core.agent.dto.request.WorkflowMountConfigRequest;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.capability.CapabilityType;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.WorkflowBuilderInstallationService;
import com.skillforge.core.workflow.WorkflowInstallationBindings;
import com.skillforge.institution.service.CapabilityMountManagementService;
import com.skillforge.institution.service.InstitutionResourceAccess;
import com.skillforge.institution.service.WorkflowManagementService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 原请求身份与终态回执持久化；安装、挂载和成功回执共同提交。 */
@Service
public class WorkflowOnboardingService {
    private final FoundationAccess access;
    private final InstitutionResourceAccess resources;
    private final WorkflowManagementService workflows;
    private final WorkflowBuilderInstallationService installer;
    private final CapabilityMountManagementService mounts;
    private final CapabilityCatalogDatabaseContext context;
    private final WorkflowOnboardingReceiptMapper receipts;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public WorkflowOnboardingService(FoundationAccess access, InstitutionResourceAccess resources,
            WorkflowManagementService workflows, WorkflowBuilderInstallationService installer,
            CapabilityMountManagementService mounts, CapabilityCatalogDatabaseContext context,
            WorkflowOnboardingReceiptMapper receipts, ObjectMapper json, PlatformTransactionManager manager) {
        this.access = access; this.resources = resources; this.workflows = workflows; this.installer = installer;
        this.mounts = mounts; this.context = context; this.receipts = receipts; this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    public Receipt onboard(long tenant, Command command) {
        access.require("foundation.workflows.create");
        access.require(command.mountId() == null ? "foundation.agents.create" : "foundation.agents.update");
        long agent = positive(command.agentId());
        long definition = positive(command.expectedDefinitionVersionId());
        if (command.requestId() == null || command.workflowId() == null || command.workflowId().isBlank()
                || command.workflowId().length() > 128 || command.mode() == null
                || command.expectedInstallationRevision() == null || command.expectedInstallationRevision() < 0
                || (command.mode() == Mode.INSTALL) != (command.expectedInstallationRevision() == 0)
                || (command.mountId() == null) != (command.expectedMountRevision() == null)
                || (command.mountId() != null && (command.mountId().isBlank() || command.expectedMountRevision() <= 0)))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "工作流接入参数无效");
        String actor = access.currentSubject().memberId();
        String fingerprint = fingerprint(command);
        Receipt previous = transactions.execute(status -> {
            context.bindTenant(tenant);
            workflows.require(tenant, command.workflowId(), "manage");
            resources.require("agent", command.agentId(), "manage");
            if (receipts.claim(tenant, command.requestId().toString(), actor, fingerprint,
                    command.agentId(), command.workflowId()) == 1) return null;
            var row = receipts.find(tenant, command.requestId().toString());
            if (row == null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "工作流接入请求标识已用于不同参数或操作人");
            return receipt(command.requestId(), row);
        });
        if (previous != null) return previous;
        try {
            return transactions.execute(status -> {
                context.bindTenant(tenant);
                workflows.require(tenant, command.workflowId(), "manage");
                var selected = installer.selectForMount(tenant, command.workflowId(), definition,
                        command.mode().name(), command.expectedInstallationRevision(),
                        command.config() == null ? Map.of() : command.config(), command.customPrompt(),
                        command.bindings() == null ? null : command.bindings().toBindings());
                var request = new CapabilityMountRequest(CapabilityType.WORKFLOW, selected.capabilityDefinitionId(),
                        null, null, null, new WorkflowMountConfigRequest(Long.toString(selected.instanceId()), null),
                        null, 0);
                var result = command.mountId() == null ? mounts.create(tenant, agent, request)
                        : mounts.updateWorkflowReference(tenant, agent, command.mountId(), command.expectedMountRevision(), request);
                var mount = result.mounts().stream().filter(value -> value.capability().capabilityId()
                        .equals(Long.toString(selected.capabilityDefinitionId()))).findFirst().orElseThrow();
                var receipt = new Receipt(command.requestId(), "SUCCEEDED", command.agentId(), command.workflowId(),
                        Long.toString(selected.instanceId()), selected.installationRevision(), mount.mountId(), mount.revision(), null);
                complete(tenant, receipt);
                return receipt;
            });
        } catch (ApiException failure) {
            var receipt = new Receipt(command.requestId(), "REJECTED", command.agentId(), command.workflowId(),
                    null, null, null, null, String.valueOf(failure.getDetail()));
            transactions.executeWithoutResult(status -> { context.bindTenant(tenant); complete(tenant, receipt); });
            return receipt;
        }
    }

    @Transactional(readOnly = true)
    public Receipt result(long tenant, UUID requestId) {
        access.require("foundation.agents.read");
        access.require("foundation.workflows.read");
        context.bindTenant(tenant);
        var row = receipts.find(tenant, requestId.toString());
        if (row == null || !access.currentSubject().memberId().equals(row.actorRef())) return null;
        resources.require("agent", row.agentId(), "read");
        workflows.require(tenant, row.workflowId(), "read");
        return receipt(requestId, row);
    }

    private void complete(long tenant, Receipt receipt) {
        if (receipts.complete(tenant, receipt.requestId().toString(), receipt.status(), encode(receipt)) != 1)
            throw new IllegalStateException("工作流接入回执写入失败");
    }
    private Receipt receipt(UUID requestId, WorkflowOnboardingReceiptMapper.Row row) {
        if ("PROCESSING".equals(row.status()))
            return new Receipt(requestId, "PROCESSING", row.agentId(), row.workflowId(), null, null, null, null, null);
        try { return json.readValue(row.responseJson(), Receipt.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("工作流接入回执无法读取", failure); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("工作流接入请求无法编码", failure); }
    }
    private String fingerprint(Command command) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .writeValueAsBytes(command))); }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) { throw new IllegalStateException("工作流接入请求无法编码", failure); }
    }
    private long positive(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0 && Long.toString(parsed).equals(value)) return parsed;
        } catch (RuntimeException ignored) { }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "资源身份必须为正十进制字符串");
    }

    public enum Mode { REUSE, INSTALL, UPDATE }
    public record Command(@JsonProperty("request_id") UUID requestId, @JsonProperty("agent_id") @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictStringDeserializer.class) String agentId,
            @JsonProperty("workflow_id") String workflowId,
            @JsonProperty("expected_definition_version_id") @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictStringDeserializer.class) String expectedDefinitionVersionId,
            Mode mode, @JsonProperty("expected_installation_revision") Long expectedInstallationRevision,
            Map<String, Object> config, @JsonProperty("custom_prompt") String customPrompt,
            Bindings bindings, @JsonProperty("mount_id") String mountId,
            @JsonProperty("expected_mount_revision") Long expectedMountRevision) {}
    public record Bindings(List<WorkflowInstallationBindings.CapabilityBinding> capabilities,
            List<ChildBinding> workflows, List<WorkflowInstallationBindings.AgentBinding> agents) {
        WorkflowInstallationBindings toBindings() {
            try {
                return new WorkflowInstallationBindings(capabilities, workflows == null ? List.of() : workflows.stream()
                        .map(value -> new WorkflowInstallationBindings.WorkflowBinding(value.workflow(),
                                parseId(value.instanceId()), parseId(value.definitionVersionId()), value.installationRevision())).toList(), agents);
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "工作流安装绑定无效", invalid);
            }
        }
        private static long parseId(String value) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed > 0 && Long.toString(parsed).equals(value)) return parsed;
            } catch (RuntimeException ignored) { }
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "子图资源身份必须为正十进制字符串");
        }
    }
    public record ChildBinding(String workflow, @JsonProperty("instance_id") @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictStringDeserializer.class) String instanceId,
            @JsonProperty("definition_version_id") @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictStringDeserializer.class) String definitionVersionId,
            @JsonProperty("installation_revision") long installationRevision) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(@JsonProperty("request_id") UUID requestId, String status,
            @JsonProperty("agent_id") @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictStringDeserializer.class) String agentId, @JsonProperty("workflow_id") String workflowId,
            @JsonProperty("instance_id") @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=com.skillforge.core.shared.web.StrictStringDeserializer.class) String instanceId, @JsonProperty("installation_revision") Long installationRevision,
            @JsonProperty("mount_id") String mountId, @JsonProperty("mount_revision") Long mountRevision,
            @JsonProperty("error_message") String errorMessage) {}
}
