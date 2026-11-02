package com.skillforge.institution.principal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.shared.security.TokenHash;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 签发与不含明文的回执同事务提交。 */
@Service
public class PrincipalIssueReceiptService {
    private final FoundationAccess access;
    private final ServicePrincipalService principals;
    private final PrincipalIssueReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;

    public PrincipalIssueReceiptService(FoundationAccess access, ServicePrincipalService principals,
            PrincipalIssueReceiptMapper receipts, CapabilityCatalogDatabaseContext context) {
        this.access = access; this.principals = principals; this.receipts = receipts; this.context = context;
    }

    @Transactional
    public Result issue(long tenant, UUID requestId, String integration, List<String> scopes) {
        access.require("foundation.service_principals.create");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        String name = integration.trim();
        List<String> normalized = scopes.stream().map(String::trim).distinct().sorted().toList();
        StringBuilder canonical = new StringBuilder().append(name.length()).append(':').append(name);
        normalized.forEach(scope -> canonical.append(scope.length()).append(':').append(scope));
        String fingerprint = TokenHash.sha256Hex(canonical.toString());
        var existing = receipts.find(tenant, requestId.toString());
        if (existing != null) return replay(requestId, actor, fingerprint, existing);
        if (receipts.claim(tenant, requestId.toString(), actor, fingerprint) == 0) {
            var original = receipts.find(tenant, requestId.toString());
            if (original == null) throw new IllegalStateException("服务身份签发原回执缺失");
            return replay(requestId, actor, fingerprint, original);
        }
        ServicePrincipalService.IssuedPrincipal issued;
        try {
            issued = principals.issue(Long.toString(tenant), name, Set.copyOf(normalized));
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            throw new ApiException(failure.getStatus(), "PRINCIPAL_ISSUE_REJECTED", String.valueOf(failure.getDetail()), false);
        }
        long id = issued.principal().getId();
        if (receipts.complete(tenant, requestId.toString(), id) != 1)
            throw new IllegalStateException("服务身份签发回执未完成");
        return new Result(requestId, Long.toString(id), issued.apiKey(), issued.signingSecret());
    }

    @Transactional(readOnly = true)
    public Result result(long tenant, UUID requestId) {
        access.require("foundation.service_principals.read");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var original = receipts.find(tenant, requestId.toString());
        if (original == null || !actor.equals(original.actorRef())) return null;
        if (original.principalId() == null) throw new IllegalStateException("服务身份签发回执未完成");
        return new Result(requestId, Long.toString(original.principalId()), null, null);
    }

    private Result replay(UUID requestId, String actor, String fingerprint, PrincipalIssueReceiptMapper.Row original) {
        if (!actor.equals(original.actorRef()) || !fingerprint.equals(original.fingerprint()))
            throw new ApiException(HttpStatus.CONFLICT, "服务身份签发请求标识已用于其他操作");
        if (original.principalId() == null) throw new IllegalStateException("服务身份签发回执未完成");
        return new Result(requestId, Long.toString(original.principalId()), null, null);
    }

    private String subject(long tenant) {
        var subject = access.currentSubject();
        if (subject.platform() || !Long.toString(tenant).equals(subject.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "机构身份与当前租户不匹配");
        return subject.memberId();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(@JsonProperty("request_id") UUID requestId,
            @JsonProperty("principal_id") String principalId,
            @JsonProperty("api_key") String apiKey, @JsonProperty("signing_secret") String signingSecret) { }
}
