package com.skillforge.core.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.knowledge.dto.knowledge.ReindexResponse;
import com.skillforge.core.knowledge.mapper.KnowledgeReindexReceiptMapper;
import com.skillforge.core.knowledge.mapper.KnowledgeReindexReceiptMapper.Row;
import com.skillforge.core.shared.exception.ApiException;
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

/** 重新索引请求在模型调用前登记，来源版本与新回执同事务提交。 */
@Service
public class KnowledgeReindexReceiptService {
    private final KnowledgeService knowledge;
    private final KnowledgeReindexReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public KnowledgeReindexReceiptService(
            KnowledgeService knowledge,
            KnowledgeReindexReceiptMapper receipts,
            CapabilityCatalogDatabaseContext context,
            ObjectMapper json,
            PlatformTransactionManager manager) {
        this.knowledge = knowledge;
        this.receipts = receipts;
        this.context = context;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    /**
     * 同一请求只执行一次；收到确定性拒绝时写入终态，连接中断导致的服务端错误继续保留为处理中。
     */
    @Transactional(propagation = Propagation.NEVER)
    public Receipt reindex(
            String tenantId, long documentId, String actor, UUID requestId, String expectedSourceVersion) {
        long tenant = Long.parseLong(tenantId);
        String id = requestId.toString();
        String fingerprint = fingerprint(documentId, expectedSourceVersion);
        Receipt previous = transactions.execute(status -> {
            context.bindTenant(tenant);
            if (receipts.claim(tenant, id, documentId, actor, fingerprint) == 1) return null;
            Row row = receipts.find(tenant, id);
            if (row == null || row.documentId() != documentId || !actor.equals(row.actorRef())
                    || !fingerprint.equals(row.fingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "重新索引请求标识已用于不同文档、版本或操作人");
            return receipt(requestId, row);
        });
        if (previous != null) return previous;

        try {
            KnowledgeService.PreparedReindex prepared =
                    knowledge.prepareReindexDocument(tenantId, documentId, expectedSourceVersion);
            return transactions.execute(status -> {
                context.bindTenant(tenant);
                var result = knowledge.persistPreparedReindex(tenantId, prepared);
                if (receipts.succeed(tenant, id, result.sourceVersion(), result.chunks()) != 1)
                    throw new IllegalStateException("重新索引成功回执写入失败");
                return new Receipt(requestId, "SUCCEEDED",
                        new ReindexResponse(result.documentId(), result.chunks(), result.sourceVersion()), null);
            });
        } catch (ApiException failure) {
            if (failure.getStatus().is5xxServerError() || failure.getStatus().value() == 408) throw failure;
            String message = String.valueOf(failure.getDetail());
            transactions.executeWithoutResult(status -> {
                context.bindTenant(tenant);
                if (receipts.reject(tenant, id, message) != 1)
                    throw new IllegalStateException("重新索引拒绝回执写入失败");
            });
            return new Receipt(requestId, "REJECTED", null, message);
        }
    }

    /**
     * 查询原请求回执；无可见回执只表示尚无法确认，不能据此认定操作失败。
     */
    public Receipt result(String tenantId, long documentId, String actor, UUID requestId) {
        long tenant = Long.parseLong(tenantId);
        return transactions.execute(status -> {
            context.bindTenant(tenant);
            Row row = receipts.find(tenant, requestId.toString());
            return row == null || row.documentId() != documentId || !actor.equals(row.actorRef())
                    ? null : receipt(requestId, row);
        });
    }

    private Receipt receipt(UUID requestId, Row row) {
        var document = row.sourceVersion() == null ? null
                : new ReindexResponse(row.documentId(), row.chunks(), row.sourceVersion());
        return new Receipt(requestId, row.status(), document, row.errorMessage());
    }

    private String fingerprint(long documentId, String expectedSourceVersion) {
        try {
            byte[] input = json.writeValueAsBytes(List.of(documentId, expectedSourceVersion));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("重新索引请求无法计算身份", failure);
        }
    }

    public record Receipt(
            @JsonProperty("request_id") UUID requestId,
            String status,
            ReindexResponse document,
            @JsonProperty("error_message") String errorMessage) { }
}
