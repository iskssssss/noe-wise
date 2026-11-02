package com.skillforge.institution.principal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.shared.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 状态变更及原操作回执同事务提交。 */
@Service
public class PrincipalStatusReceiptService {
    private final FoundationAccess access;
    private final ServicePrincipalService principals;
    private final PrincipalStatusReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;

    public PrincipalStatusReceiptService(FoundationAccess access, ServicePrincipalService principals,
            PrincipalStatusReceiptMapper receipts, CapabilityCatalogDatabaseContext context) {
        this.access = access; this.principals = principals; this.receipts = receipts; this.context = context;
    }

    @Transactional
    public Result change(long tenant, UUID requestId, long principalId, String action) {
        access.require("foundation.service_principals.update");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var existing = receipts.find(tenant, requestId.toString());
        if (existing != null) return replay(requestId, principalId, action, actor, existing);
        if (receipts.claim(tenant, requestId.toString(), actor, principalId, action) == 0) {
            var original = receipts.find(tenant, requestId.toString());
            if (original == null) throw new IllegalStateException("服务身份状态原回执缺失");
            return replay(requestId, principalId, action, actor, original);
        }
        boolean changed = switch (action) {
            case "suspend" -> principals.suspend(Long.toString(tenant), principalId);
            case "revoke" -> principals.revoke(Long.toString(tenant), principalId);
            default -> throw new IllegalArgumentException("未知服务身份操作");
        };
        if (receipts.complete(tenant, requestId.toString(), changed) != 1)
            throw new IllegalStateException("服务身份状态回执未完成");
        return new Result(requestId, Long.toString(principalId), action, changed);
    }

    @Transactional(readOnly = true)
    public Result result(long tenant, UUID requestId) {
        access.require("foundation.service_principals.read");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var original = receipts.find(tenant, requestId.toString());
        if (original == null || !actor.equals(original.actorRef())) return null;
        if (original.changed() == null) throw new IllegalStateException("服务身份状态回执未完成");
        return new Result(requestId, Long.toString(original.principalId()), original.action(), original.changed());
    }

    private Result replay(UUID requestId, long principalId, String action, String actor, PrincipalStatusReceiptMapper.Row original) {
        if (!actor.equals(original.actorRef()) || principalId != original.principalId() || !action.equals(original.action()))
            throw new ApiException(HttpStatus.CONFLICT, "服务身份状态请求标识已用于其他操作");
        if (original.changed() == null) throw new IllegalStateException("服务身份状态回执未完成");
        return new Result(requestId, Long.toString(principalId), action, original.changed());
    }

    private String subject(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return subject.memberId();
    }

    public record Result(@JsonProperty("request_id") UUID requestId,
            @JsonProperty("principal_id") String principalId,
            @JsonProperty("action") String action, @JsonProperty("changed") boolean changed) { }
}
