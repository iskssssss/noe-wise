package com.skillforge.platform.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.platform.dto.tenant.CreateTenantRequest;
import com.skillforge.platform.service.PlatformTenantAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 原请求只派发一次；成功回执与机构及首位管理员在同一事务提交。 */
@Service
public class PlatformTenantCreationService {
    private static final Logger LOG = LoggerFactory.getLogger(PlatformTenantCreationService.class);
    private final FoundationAccess access;
    private final PlatformTenantAccountService tenants;
    private final PlatformTenantCreateReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;
    private final TransactionTemplate transactions;
    private final TransactionTemplate failureTransactions;

    public PlatformTenantCreationService(FoundationAccess access, PlatformTenantAccountService tenants,
            PlatformTenantCreateReceiptMapper receipts, CapabilityCatalogDatabaseContext context,
            PlatformTransactionManager manager) {
        this.access = access; this.tenants = tenants; this.receipts = receipts; this.context = context;
        this.transactions = new TransactionTemplate(manager);
        this.failureTransactions = new TransactionTemplate(manager);
        this.failureTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Receipt create(Command command) {
        access.require("foundation.tenants.create");
        String actor = actor();
        String key = command.requestId().toString();
        transactions.executeWithoutResult(status -> {
            context.bindPlatform();
            if (receipts.claim(key, actor) != 1)
                throw new ApiException(HttpStatus.CONFLICT, "创建请求标识已使用，请查询原结果");
        });
        try {
            return transactions.execute(status -> {
                var unexpectedFailure = new AtomicBoolean();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int outcome) {
                        if (outcome != STATUS_ROLLED_BACK || !unexpectedFailure.get()) return;
                        try {
                            failureTransactions.executeWithoutResult(ignored -> {
                                context.bindPlatform();
                                receipts.complete(key, "FAILED", null, null, "机构创建失败，事务已回滚；请检查后重新创建。");
                            });
                        } catch (RuntimeException failure) {
                            LOG.warn("机构创建回滚后的回执记录失败，原请求仍待核对 requestId={}", key, failure);
                        }
                    }
                });
                try {
                    context.bindPlatform();
                    var tenant = tenants.createTenant(command.payload());
                    context.bindPlatform();
                    if (receipts.complete(key, "SUCCEEDED", tenant.id(), tenant.name(), null) != 1)
                        throw new IllegalStateException("机构创建回执写入失败");
                    return new Receipt(command.requestId(), "SUCCEEDED", tenant.id().toString(), tenant.name(), null);
                } catch (RuntimeException failure) {
                    unexpectedFailure.set(!(failure instanceof ApiException api && api.getStatus().is4xxClientError()
                            && api.getStatus().value() != 408));
                    throw failure;
                }
            });
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            String detail = "机构创建被拒绝，请检查机构标识、套餐和管理员配置。";
            transactions.executeWithoutResult(status -> {
                context.bindPlatform();
                if (receipts.complete(key, "REJECTED", null, null, detail) != 1)
                    throw new IllegalStateException("机构创建拒绝回执写入失败");
            });
            return new Receipt(command.requestId(), "REJECTED", null, null, detail);
        }
    }

    @Transactional(readOnly = true)
    public Receipt result(UUID requestId) {
        access.require("foundation.tenants.read");
        String actor = actor();
        context.bindPlatform();
        var row = receipts.find(requestId.toString());
        if (row == null || !actor.equals(row.actorRef())) return null;
        return new Receipt(requestId, row.status(), row.tenantId() == null ? null : row.tenantId().toString(),
                row.tenantName(), row.errorMessage());
    }

    private String actor() {
        var subject = access.currentSubject();
        if (!subject.platform()) throw new ApiException(HttpStatus.FORBIDDEN, "仅平台账号可创建机构");
        return subject.memberId();
    }

    public record Command(@NotNull @JsonProperty("request_id") UUID requestId,
            @NotNull @Valid CreateTenantRequest payload) { }
    public record Receipt(@JsonProperty("request_id") UUID requestId, String status,
            @JsonProperty("tenant_id") String tenantId, @JsonProperty("tenant_name") String tenantName,
            @JsonProperty("error_message") String errorMessage) { }
}
