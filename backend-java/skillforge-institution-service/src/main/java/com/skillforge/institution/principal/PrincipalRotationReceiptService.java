package com.skillforge.institution.principal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.skillforge.core.capability.CapabilityCatalogDatabaseContext;
import com.skillforge.core.foundation.application.ServicePrincipalService;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.shared.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 凭据签发与不含明文的回执同事务提交。 */
@Service
public class PrincipalRotationReceiptService {
    private final FoundationAccess access;
    private final ServicePrincipalService principals;
    private final PrincipalRotationReceiptMapper receipts;
    private final CapabilityCatalogDatabaseContext context;

    public PrincipalRotationReceiptService(FoundationAccess access, ServicePrincipalService principals,
            PrincipalRotationReceiptMapper receipts, CapabilityCatalogDatabaseContext context) {
        this.access = access; this.principals = principals; this.receipts = receipts; this.context = context;
    }

    @Transactional
    public Result rotate(long tenant, UUID requestId, long principalId) {
        access.require("foundation.service_principals.update");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var existing = receipts.find(tenant, requestId.toString());
        if (existing != null) return replay(requestId, principalId, actor, existing);
        if (receipts.claim(tenant, requestId.toString(), actor, principalId) == 0) {
            var original = receipts.find(tenant, requestId.toString());
            if (original == null) throw new IllegalStateException("服务身份凭据原回执缺失");
            return replay(requestId, principalId, actor, original);
        }
        ServicePrincipalService.IssuedPrincipal issued;
        try {
            issued = principals.rotate(Long.toString(tenant), principalId);
        } catch (ApiException failure) {
            if (!failure.getStatus().is4xxClientError() || failure.getStatus().value() == 408) throw failure;
            throw new ApiException(failure.getStatus(), "PRINCIPAL_ROTATION_REJECTED", String.valueOf(failure.getDetail()), false);
        }
        return new Result(requestId, Long.toString(principalId), issued.apiKey(), issued.signingSecret());
    }

    @Transactional(readOnly = true)
    public Result result(long tenant, UUID requestId) {
        access.require("foundation.service_principals.read");
        String actor = subject(tenant);
        context.bindTenant(tenant);
        var original = receipts.find(tenant, requestId.toString());
        if (original == null || !actor.equals(original.actorRef())) return null;
        return new Result(requestId, Long.toString(original.principalId()), null, null);
    }

    private Result replay(UUID requestId, long principalId, String actor, PrincipalRotationReceiptMapper.Row original) {
        if (!actor.equals(original.actorRef()) || principalId != original.principalId())
            throw new ApiException(HttpStatus.CONFLICT, "服务身份凭据请求标识已用于其他操作");
        return new Result(requestId, Long.toString(principalId), null, null);
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
