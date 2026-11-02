package com.skillforge.institution.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.knowledge.dto.request.CreateKnowledgeBaseRequest;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.service.KnowledgeManagementService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 知识库、初始配置和原请求回执共同提交。 */
@Service
public class KnowledgeCreationService {
    private final FoundationAccess access;
    private final KnowledgeManagementService management;
    private final KnowledgeCreateReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final ObjectMapper json;

    public KnowledgeCreationService(FoundationAccess access, KnowledgeManagementService management,
            KnowledgeCreateReceiptMapper receipts, CapabilityCatalogDatabaseContext context, ObjectMapper json) {
        this.access = access; this.management = management; this.receipts = receipts; this.context = context; this.json = json;
    }

    @Transactional
    public Result create(long tenant, Command command) {
        if (command == null || command.requestId() == null || command.payload() == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "知识库创建请求无效");
        access.require("foundation.knowledge.create");
        String actor = subject(tenant), key = command.requestId().toString();
        var request = command.payload();
        var settings = KnowledgeManagementService.advancedSettings(request);
        if (settings != null) access.require("foundation.knowledge.update");
        String fingerprint = fingerprint(request);
        context.bindTenant(tenant);
        if (receipts.claim(tenant, key, actor, fingerprint) == 0) {
            var row = receipts.find(tenant, key);
            if (row == null || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "知识库创建请求标识已用于不同内容或操作人");
            return new Result(command.requestId(), row.kbId().toString());
        }
        var created = management.create(Long.toString(tenant), request.name(), request.description(),
                request.indexingMode(), request.retrievalMode(), request.rerankEnabled(), settings);
        if (receipts.complete(tenant, key, created.id()) != 1)
            throw new IllegalStateException("知识库创建回执写入失败");
        return new Result(command.requestId(), created.id().toString());
    }

    @Transactional(readOnly = true)
    public Result result(long tenant, UUID requestId) {
        access.require("foundation.knowledge.read");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var row = receipts.find(tenant, requestId.toString());
        return row == null || !actor.equals(row.actorRef()) ? null : new Result(requestId, row.kbId().toString());
    }

    private String subject(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return subject.memberId();
    }
    private String fingerprint(CreateKnowledgeBaseRequest request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(request)));
        } catch (NoSuchAlgorithmException | JsonProcessingException failure) {
            throw new IllegalStateException("知识库创建请求无法编码", failure);
        }
    }
    public record Command(@JsonProperty("request_id") UUID requestId, CreateKnowledgeBaseRequest payload) { }
    public record Result(@JsonProperty("request_id") UUID requestId, @JsonProperty("kb_id") String kbId) { }
}
