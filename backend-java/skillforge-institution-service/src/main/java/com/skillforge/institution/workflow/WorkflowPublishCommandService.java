package com.skillforge.institution.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.workflow.WorkflowDraftPublicationService;
import com.skillforge.core.workflow.dto.response.WorkflowPublishResponse;
import com.skillforge.institution.service.WorkflowManagementService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 原命令先登记，业务及成功回执一起提交；处理中不重新派发。 */
@Service
public class WorkflowPublishCommandService {
    private final FoundationAccess access;
    private final WorkflowManagementService management;
    private final WorkflowDraftPublicationService publication;
    private final WorkflowPublishReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public WorkflowPublishCommandService(FoundationAccess access, WorkflowManagementService management,
            WorkflowDraftPublicationService publication, WorkflowPublishReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context, ObjectMapper json, PlatformTransactionManager manager) {
        this.access = access; this.management = management; this.publication = publication;
        this.receipts = receipts; this.context = context; this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    public Receipt publish(long tenant, Command command) {
        if (command == null || command.requestId() == null || command.workflowId() == null
                || !command.workflowId().matches("[a-z0-9][a-z0-9-]{0,127}") || command.revision() <= 0 || command.definition() == null
                || command.layout() == null || command.viewport() == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, WorkflowPublishCommandController.PRE_DISPATCH_REJECTED,
                    "工作流发布请求参数无效", false);
        String actor;
        try {
            access.require("foundation.workflows.read");
            access.require("foundation.workflows.update");
            access.require("foundation.workflows.publish");
            actor = actor(tenant);
            management.require(tenant, command.workflowId(), "manage");
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError()) throw failure;
            throw new ApiException(failure.getStatus(), WorkflowPublishCommandController.PRE_DISPATCH_REJECTED,
                    String.valueOf(failure.getDetail()), false);
        }
        String fingerprint = fingerprint(command);
        Receipt previous = transactions.execute(status -> {
            context.bindTenant(tenant);
            if (receipts.claim(tenant, command.requestId().toString(), actor, command.workflowId(), fingerprint) == 1)
                return null;
            var row = receipts.find(tenant, command.requestId().toString());
            if (row == null || !actor.equals(row.actorRef()) || !command.workflowId().equals(row.workflowId())
                    || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "工作流发布请求标识已用于不同参数或操作人");
            return receipt(command.requestId(), command.workflowId(), row);
        });
        if (previous != null) return previous;
        try {
            return transactions.execute(status -> {
                context.bindTenant(tenant);
                var result = publication.saveAndPublish(tenant, command.workflowId(), command.revision(),
                        command.definition(), command.layout(), command.viewport());
                var saved = new Receipt(command.requestId(), command.workflowId(), "SUCCEEDED", result, null, null);
                complete(tenant, saved);
                return saved;
            });
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError()) throw failure;
            var rejected = new Receipt(command.requestId(), command.workflowId(), "REJECTED", null,
                    failure.getCode(), failure.getDetail());
            transactions.executeWithoutResult(status -> { context.bindTenant(tenant); complete(tenant, rejected); });
            return rejected;
        }
    }

    @Transactional(readOnly = true)
    public Receipt result(long tenant, String workflowId, UUID requestId) {
        access.require("foundation.workflows.read");
        String actor = actor(tenant);
        management.require(tenant, workflowId, "read");
        context.bindTenant(tenant);
        var row = receipts.find(tenant, requestId.toString());
        if (row == null || !actor.equals(row.actorRef()) || !workflowId.equals(row.workflowId())) return null;
        return receipt(requestId, workflowId, row);
    }

    private String actor(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return subject.memberId();
    }
    private void complete(long tenant, Receipt result) {
        if (receipts.complete(tenant, result.requestId().toString(), result.status(), encode(result)) != 1)
            throw new IllegalStateException("工作流发布回执写入失败");
    }
    private Receipt receipt(UUID requestId, String workflowId, WorkflowPublishReceiptMapper.Row row) {
        if ("PROCESSING".equals(row.status())) return new Receipt(requestId, workflowId, "PROCESSING", null, null, null);
        try { return json.readValue(row.responseJson(), Receipt.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("工作流发布回执无法读取", failure); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("工作流发布回执无法编码", failure); }
    }
    private String fingerprint(Command command) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(command))); }
        catch (NoSuchAlgorithmException | JsonProcessingException failure) { throw new IllegalStateException("工作流发布请求无法编码", failure); }
    }

    public record Command(@JsonProperty("request_id") UUID requestId, @JsonProperty("workflow_id") String workflowId,
            long revision, Map<String, Object> definition, Map<String, Object> layout, Map<String, Object> viewport) { }
    public record Receipt(@JsonProperty("request_id") UUID requestId, @JsonProperty("workflow_id") String workflowId,
            String status, WorkflowPublishResponse publication, @JsonProperty("error_code") String errorCode,
            @JsonProperty("error_detail") Object errorDetail) { }
}
