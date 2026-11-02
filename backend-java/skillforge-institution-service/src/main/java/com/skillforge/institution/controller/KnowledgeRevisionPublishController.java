package com.skillforge.institution.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.knowledge.KnowledgeBaseRevisionService;
import com.skillforge.core.knowledge.dto.request.MarkKnowledgeBaseRevisionReadyRequest;
import com.skillforge.core.knowledge.dto.response.KnowledgeBaseRevisionResponse;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.web.ApiResponse;
import com.skillforge.core.shared.web.ApiResponses;
import com.skillforge.institution.common.TenantIds;
import com.skillforge.institution.service.KnowledgeManagementService;
import jakarta.validation.Validator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 文档范围准备、能力发布及成功回执共同提交。 */
@RestController
@RequestMapping("/api/v1/knowledge/bases/revisions/prepare-and-publish")
public class KnowledgeRevisionPublishController {
    public static final String PRE_DISPATCH_REJECTED = "KNOWLEDGE_PUBLISH_PRE_DISPATCH_REJECTED";
    private final FoundationAccess access;
    private final KnowledgeManagementService management;
    private final KnowledgeBaseRevisionService revisions;
    private final KnowledgeRevisionPublishReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final ObjectMapper json;
    private final Validator validator;
    private final TransactionTemplate transactions;

    public KnowledgeRevisionPublishController(FoundationAccess access, KnowledgeManagementService management,
            KnowledgeBaseRevisionService revisions, KnowledgeRevisionPublishReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context, ObjectMapper json, Validator validator, PlatformTransactionManager manager) {
        this.access = access; this.management = management; this.revisions = revisions; this.receipts = receipts;
        this.context = context; this.json = json; this.validator = validator; this.transactions = new TransactionTemplate(manager);
    }

    @PostMapping
    public ApiResponse<Receipt> publish(@RequestBody Command command) {
        if (command == null || command.requestId() == null || command.kbId() == null || command.kbId() <= 0
                || command.payload() == null || command.payload().sourceVersions() == null || command.payload().policy() == null
                || command.payload().policy().allowedOperators() == null || !validator.validate(command.payload()).isEmpty()
                || command.payload().sourceVersions().stream().anyMatch(java.util.Objects::isNull))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, PRE_DISPATCH_REJECTED, "知识库发布请求参数无效", false);
        String tenantId = TenantIds.requireTenantId();
        long tenant = Long.parseLong(tenantId);
        FoundationAccess.Subject subject;
        try {
            access.require("foundation.knowledge.create");
            access.require("foundation.knowledge.update");
            access.require("foundation.knowledge.publish");
            subject = access.currentSubject();
            if (subject.platform() || !tenantId.equals(subject.tenantId()))
                throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
            management.requireBase(tenantId, command.kbId(), "manage");
        } catch (ApiException failure) {
            throw new ApiException(failure.getStatus(), PRE_DISPATCH_REJECTED, String.valueOf(failure.getDetail()), false);
        }
        String fingerprint = fingerprint(command);
        Receipt previous = transactions.execute(status -> {
            context.bindTenant(tenant);
            if (receipts.claim(tenant, command.requestId().toString(), command.kbId(), subject.memberId(), fingerprint) == 1)
                return null;
            var row = receipts.find(tenant, command.requestId().toString());
            if (row == null || !subject.memberId().equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "知识库发布请求标识已用于不同参数或操作人");
            return receipt(command.requestId(), row);
        });
        if (previous != null) return ApiResponses.wrap(previous);
        try {
            Receipt result = transactions.execute(status -> {
                context.bindTenant(tenant);
                var ready = revisions.prepare(tenantId, command.kbId(), command.payload().sourceVersions(), command.payload().policy());
                var published = revisions.publish(tenantId, command.kbId(), ready.revision());
                var success = new Receipt(command.requestId(), "SUCCEEDED", published, null);
                complete(tenant, success);
                return success;
            });
            return ApiResponses.wrap(result);
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError()) throw failure;
            var rejected = new Receipt(command.requestId(), "REJECTED", null, String.valueOf(failure.getDetail()));
            transactions.executeWithoutResult(status -> { context.bindTenant(tenant); complete(tenant, rejected); });
            return ApiResponses.wrap(rejected);
        }
    }

    /** 204 只表示没有可见回执，不证明操作失败。 */
    @GetMapping("/result")
    public ResponseEntity<ApiResponse<Receipt>> result(@RequestParam UUID requestId) {
        String tenantId = TenantIds.requireTenantId();
        long tenant = Long.parseLong(tenantId);
        access.require("foundation.knowledge.read");
        var subject = access.currentSubject();
        if (subject.platform() || !tenantId.equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        Receipt result = transactions.execute(status -> {
            context.bindTenant(tenant);
            var row = receipts.find(tenant, requestId.toString());
            if (row == null || !subject.memberId().equals(row.actorRef())) return null;
            management.requireBase(tenantId, row.kbId(), "read");
            return receipt(requestId, row);
        });
        return result == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ApiResponses.wrap(result));
    }

    private void complete(long tenant, Receipt receipt) {
        try {
            if (receipts.complete(tenant, receipt.requestId().toString(), receipt.status(), json.writeValueAsString(receipt)) != 1)
                throw new IllegalStateException("知识库发布回执写入失败");
        } catch (JsonProcessingException failure) { throw new IllegalStateException("知识库发布回执无法编码", failure); }
    }
    private Receipt receipt(UUID requestId, KnowledgeRevisionPublishReceiptMapper.Row row) {
        if ("PROCESSING".equals(row.status())) return new Receipt(requestId, "PROCESSING", null, null);
        try { return json.readValue(row.responseJson(), Receipt.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("知识库发布回执无法读取", failure); }
    }
    private String fingerprint(Command command) {
        var policy = command.payload().policy();
        try {
            byte[] input = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(
                    List.of(command.requestId(), command.kbId(), command.payload().sourceVersions(),
                            policy.modelInvocable(), policy.userInvocable(), policy.allowedOperators().stream().sorted().toList(),
                            policy.freshnessPolicy(), policy.citationRequired()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw new IllegalStateException("知识库发布请求无法编码", failure);
        }
    }
    public record Command(@JsonProperty("request_id") UUID requestId, @JsonProperty("kb_id") Long kbId,
            MarkKnowledgeBaseRevisionReadyRequest payload) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(@JsonProperty("request_id") UUID requestId, String status,
            KnowledgeBaseRevisionResponse revision, @JsonProperty("error_message") String errorMessage) { }
}
