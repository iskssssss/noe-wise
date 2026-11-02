package com.skillforge.runtime.api.port;
import com.skillforge.runtime.api.PrincipalRef;

import com.skillforge.runtime.api.ResultRef;
import java.util.Set;

/** 保存一次不可变的主体验证、会话归属、scope 与策略版本事实。 */
public record VerifiedIdentity(
        PrincipalRef principal,
        SessionOwner owner,
        Set<String> scopes,
        long policyRevision,
        ResultRef verificationRef) {
    /** 拒绝身份漂移、空白 scope 与非正策略版本。 */
    public VerifiedIdentity {
        principal = PortContracts.requireNonNull(principal, "principal");
        owner = PortContracts.requireNonNull(owner, "owner");
        scopes = PortContracts.immutableTextSet(scopes, "scopes");
        policyRevision = PortContracts.requirePositive(policyRevision, "policyRevision");
        verificationRef = PortContracts.requireNonNull(verificationRef, "verificationRef");
        PortContracts.requireSameTenant(
                owner.sessionRef().tenantId(), principal.tenantId(), "principal.tenantId");
        if (owner.subject() instanceof com.skillforge.runtime.api.SessionSubject.User user
                && principal instanceof PrincipalRef.EndUserPrincipal endUser
                && !endUser.endUserId().equals(user.endUserId())) {
            throw new IllegalArgumentException("end-user principal must match owner endUserId");
        }
    }
}
