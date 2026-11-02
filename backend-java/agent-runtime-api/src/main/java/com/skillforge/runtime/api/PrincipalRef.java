package com.skillforge.runtime.api;

import com.skillforge.runtime.api.EndUserId;
import com.skillforge.runtime.api.TenantId;

/** 表示经身份边界验证的终端用户、服务或系统主体引用。 */
public sealed interface PrincipalRef
        permits PrincipalRef.EndUserPrincipal,
                PrincipalRef.ServicePrincipal,
                PrincipalRef.SystemPrincipal {

    /** 返回主体所属租户。 */
    TenantId tenantId();

    /** 表示直接发起调用的终端用户。 */
    record EndUserPrincipal(TenantId tenantId, EndUserId endUserId) implements PrincipalRef {
        /** 拒绝缺失租户或用户身份。 */
        public EndUserPrincipal {
            tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
            endUserId = ApiContracts.requireNonNull(endUserId, "endUserId");
        }
    }

    /** 表示经显式授权代办终端用户的集成服务。 */
    record ServicePrincipal(
            TenantId tenantId,
            String principalId,
            String integrationId,
            long credentialVersion) implements PrincipalRef {
        /** 拒绝缺失身份及非正凭据版本。 */
        public ServicePrincipal {
            tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
            principalId = ApiContracts.requireText(principalId, "principalId");
            integrationId = ApiContracts.requireText(integrationId, "integrationId");
            credentialVersion = ApiContracts.requirePositive(credentialVersion, "credentialVersion");
        }
    }

    /** 表示由宿主信任边界签发的内部系统主体。 */
    record SystemPrincipal(TenantId tenantId, String principalId) implements PrincipalRef {
        /** 拒绝缺失系统主体身份。 */
        public SystemPrincipal {
            tenantId = ApiContracts.requireNonNull(tenantId, "tenantId");
            principalId = ApiContracts.requireText(principalId, "principalId");
        }
    }
}
