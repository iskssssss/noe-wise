package com.skillforge.core.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.knowledge.dto.response.UploadDocumentResponse;
import com.skillforge.core.knowledge.mapper.KnowledgeUploadReceiptMapper;
import com.skillforge.core.shared.exception.ApiException;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 上传请求在模型调用前登记，文档与成功回执同事务提交。 */
@Service
public class KnowledgeUploadReceiptService {
    private final KnowledgeService knowledge;
    private final KnowledgeUploadReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public KnowledgeUploadReceiptService(KnowledgeService knowledge, KnowledgeUploadReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context, ObjectMapper json, PlatformTransactionManager manager) {
        this.knowledge = knowledge;
        this.receipts = receipts;
        this.context = context;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    @Transactional(propagation = Propagation.NEVER)
    public Receipt upload(String tenantId, long kbId, String actor, UUID requestId, String title, MultipartFile file) {
        long tenant = Long.parseLong(tenantId);
        String fingerprint = fingerprint(kbId, title, file);
        String id = requestId.toString();
        Receipt previous = transactions.execute(status -> {
            context.bindTenant(tenant);
            if (receipts.claim(tenant, id, kbId, actor, fingerprint) == 1) return null;
            var row = receipts.find(tenant, id);
            if (row == null || row.kbId() != kbId || !actor.equals(row.actorRef()) || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "上传请求标识已用于不同文件或操作人");
            return receipt(requestId, row);
        });
        if (previous != null) return previous;
        KnowledgeService.PreparedDocument prepared;
        try {
            prepared = knowledge.prepareUploadDocument(tenantId, kbId, title, file);
        } catch (ApiException failure) {
            if (!"KNOWLEDGE_UPLOAD_REJECTED".equals(failure.getCode()) &&
                    !"KNOWLEDGE_DOCUMENT_CHUNK_LIMIT".equals(failure.getCode())) throw failure;
            String message = String.valueOf(failure.getDetail());
            transactions.executeWithoutResult(status -> {
                context.bindTenant(tenant);
                if (receipts.reject(tenant, id, message) != 1) throw new IllegalStateException("上传拒绝回执写入失败");
            });
            return new Receipt(requestId, "REJECTED", null, message);
        }
        return transactions.execute(status -> {
            context.bindTenant(tenant);
            UploadDocumentResponse document = knowledge.persistPreparedDocument(tenantId, prepared);
            if (receipts.succeed(tenant, id, document.documentId(), document.sourceVersion(), document.chunks()) != 1)
                throw new IllegalStateException("上传成功回执写入失败");
            return new Receipt(requestId, "SUCCEEDED", document, null);
        });
    }

    public Receipt result(String tenantId, long kbId, String actor, UUID requestId) {
        long tenant = Long.parseLong(tenantId);
        return transactions.execute(status -> {
            context.bindTenant(tenant);
            var row = receipts.find(tenant, requestId.toString());
            return row == null || row.kbId() != kbId || !actor.equals(row.actorRef()) ? null : receipt(requestId, row);
        });
    }

    private Receipt receipt(UUID requestId, KnowledgeUploadReceiptMapper.Row row) {
        var document = row.documentId() == null ? null : new UploadDocumentResponse(row.documentId(), row.chunks(), row.sourceVersion());
        return new Receipt(requestId, row.status(), document, row.errorMessage());
    }

    private String fingerprint(long kbId, String title, MultipartFile file) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(file.getInputStream(), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            String fileHash = HexFormat.of().formatHex(digest.digest());
            byte[] input = json.writeValueAsBytes(List.of(kbId, title == null ? "" : title,
                    file.getOriginalFilename() == null ? "" : file.getOriginalFilename(), fileHash));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("上传请求无法计算身份", failure);
        }
    }

    public record Receipt(@JsonProperty("request_id") UUID requestId, String status,
            UploadDocumentResponse document, @JsonProperty("error_message") String errorMessage) { }
}
