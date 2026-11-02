package com.skillforge.runtime.api.port;

import com.skillforge.runtime.api.AuthenticationRef;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.SessionTarget;
import com.skillforge.runtime.api.SessionSubject;
import com.skillforge.runtime.api.InputId;
import com.skillforge.runtime.api.RequestId;
import com.skillforge.runtime.api.ResultRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.TypedPayload;
import java.util.Optional;

/**
 * 在 Runtime 进入业务账本前验证认证事实并评估同一已验证身份的动作授权。
 * 实现必须独立提交安全审计事实，且不得从正文、线程上下文或资源存在性补造身份。
 */
public interface IdentityPolicyPort {

    /**
     * 验证不可逆认证引用与入口声明归属，返回不可变验证事实或安全失败。
     *
     * @param request 完整认证引用、声明归属、渠道与追踪上下文
     * @return 已验证身份或不泄露凭据和主体信息的拒绝结果
     */
    IdentityResult verify(IdentityRequest request);

    /**
     * 验证只读 façade 访问；Session 身份只能来自认证事实，不能为定位 Run 而预读业务资源。
     *
     * @param request 认证引用、声明 tenant/agent/user、入口与追踪上下文
     * @return 携带原认证完整 owner 的已验证身份或安全失败
     */
    IdentityResult verifyAccess(AccessIdentityRequest request);

    /**
     * 对请求携带的同一已验证身份和资源执行策略评估，不允许实现替换 principal 或 owner。
     *
     * @param request 已验证身份、动作、资源与追踪上下文
     * @return 可追溯的允许、确定性拒绝或策略基础设施失败
     */
    AuthorizationResult authorize(AuthorizationRequest request);

    /** 表示一次入口身份验证所需的完整认证、归属、渠道和追踪信息。 */
    record IdentityRequest(
            AuthenticationRef authenticationRef,
            SessionOwner claimedOwner,
            String channel,
            String source,
            RequestId requestId,
            Optional<InputId> inputId,
            String traceId,
            String spanId) {

        /** 拒绝缺失认证、归属、渠道、请求、Optional 或追踪字段。 */
        public IdentityRequest {
            authenticationRef = PortContracts.requireNonNull(authenticationRef, "authenticationRef");
            claimedOwner = PortContracts.requireNonNull(claimedOwner, "claimedOwner");
            channel = PortContracts.requireText(channel, "channel");
            source = PortContracts.requireText(source, "source");
            requestId = PortContracts.requireNonNull(requestId, "requestId");
            inputId = PortContracts.requireNonNull(inputId, "inputId");
            traceId = PortContracts.requireText(traceId, "traceId");
            spanId = PortContracts.requireText(spanId, "spanId");
        }
    }

    /** 只读访问在认证前没有可信 SessionRef，因此仅声明可独立核验的主体字段。 */
    record AccessIdentityRequest(
            AuthenticationRef authenticationRef,
            TenantId claimedTenantId,
            SessionTarget claimedTarget,
            Optional<AgentId> claimedAgentId,
            SessionSubject claimedSubject,
            String channel,
            String source,
            RequestId requestId,
            String traceId,
            String spanId) {

        public AccessIdentityRequest {
            authenticationRef = PortContracts.requireNonNull(authenticationRef, "authenticationRef");
            claimedTenantId = PortContracts.requireNonNull(claimedTenantId, "claimedTenantId");
            claimedTarget = PortContracts.requireNonNull(claimedTarget, "claimedTarget");
            claimedAgentId = PortContracts.requireNonNull(claimedAgentId, "claimedAgentId");
            claimedSubject = PortContracts.requireNonNull(claimedSubject, "claimedSubject");
            if (claimedTarget.kind() == SessionTarget.Kind.AGENT && (claimedAgentId.isEmpty()
                    || !claimedTarget.id().equals(claimedAgentId.orElseThrow().value())))
                throw new IllegalArgumentException("access Agent must match direct target");
            if (claimedSubject instanceof SessionSubject.User && (claimedAgentId.isEmpty() || claimedTarget.kind() == SessionTarget.Kind.GROUP))
                throw new IllegalArgumentException("personal access requires an actual Agent and personal target");
            if (claimedSubject instanceof SessionSubject.Group group && (claimedTarget.kind() != SessionTarget.Kind.GROUP
                    || !claimedTarget.id().equals(group.groupBindingRef().groupBindingId())
                    || !claimedTenantId.equals(group.groupBindingRef().tenantId())))
                throw new IllegalArgumentException("group access must reference exact tenant and target");
            channel = PortContracts.requireText(channel, "channel");
            source = PortContracts.requireText(source, "source");
            requestId = PortContracts.requireNonNull(requestId, "requestId");
            traceId = PortContracts.requireText(traceId, "traceId");
            spanId = PortContracts.requireText(spanId, "spanId");
        }


    }

    /** 封闭身份验证的成功与拒绝结果，避免供应商异常穿透公开边界。 */
    sealed interface IdentityResult permits IdentityResult.Verified, IdentityResult.Rejected {

        /** 表示认证引用已解析为与声明归属一致的不可变身份事实。 */
        record Verified(VerifiedIdentity identity) implements IdentityResult {

            /** 拒绝缺失的已验证身份。 */
            public Verified {
                identity = PortContracts.requireNonNull(identity, "identity");
            }
        }

        /** 表示认证无效、身份漂移或身份边界无法形成可信验证事实。 */
        record Rejected(PortFailure failure) implements IdentityResult {

            /** 拒绝缺失的稳定失败事实。 */
            public Rejected {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }
    }

    /** 表示针对同一已验证身份、动作和 schema 化资源的策略评估请求。 */
    record AuthorizationRequest(
            VerifiedIdentity identity,
            String action,
            TypedPayload resource,
            String traceId,
            String spanId) {

        /** 拒绝缺失身份、空白动作、未类型化资源或不完整追踪信息。 */
        public AuthorizationRequest {
            identity = PortContracts.requireNonNull(identity, "identity");
            action = PortContracts.requireText(action, "action");
            resource = PortContracts.requireNonNull(resource, "resource");
            traceId = PortContracts.requireText(traceId, "traceId");
            spanId = PortContracts.requireText(spanId, "spanId");
        }
    }

    /** 封闭策略允许、确定性拒绝和基础设施失败三种结果。 */
    sealed interface AuthorizationResult
            permits AuthorizationResult.Allowed,
                    AuthorizationResult.Denied,
                    AuthorizationResult.Failed {

        /** 表示策略明确允许，并携带同次评估的 revision 与审计事实引用。 */
        record Allowed(long policyRevision, ResultRef auditRef) implements AuthorizationResult {

            /** 拒绝非正策略 revision 或缺失审计引用。 */
            public Allowed {
                policyRevision = PortContracts.requirePositive(policyRevision, "policyRevision");
                auditRef = PortContracts.requireNonNull(auditRef, "auditRef");
            }
        }

        /** 表示策略确定性拒绝，并携带稳定原因和同次评估审计事实。 */
        record Denied(String reasonCode, long policyRevision, ResultRef auditRef)
                implements AuthorizationResult {

            /** 拒绝空白原因、非正策略 revision 或缺失审计引用。 */
            public Denied {
                reasonCode = PortContracts.requireText(reasonCode, "reasonCode");
                policyRevision = PortContracts.requirePositive(policyRevision, "policyRevision");
                auditRef = PortContracts.requireNonNull(auditRef, "auditRef");
            }
        }

        /** 表示 IAM 或 Policy store 不可用或结果不确定，调用方必须 fail closed。 */
        record Failed(PortFailure failure) implements AuthorizationResult {

            /** 拒绝缺失的稳定失败事实。 */
            public Failed {
                failure = PortContracts.requireNonNull(failure, "failure");
            }
        }
    }
}
