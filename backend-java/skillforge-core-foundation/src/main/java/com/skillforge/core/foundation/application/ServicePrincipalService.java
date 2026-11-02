package com.skillforge.core.foundation.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.core.shared.security.TokenHash;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tenant Open Platform ServicePrincipal 的签发、查询、暂停和密钥轮换服务。 */
@Service
public class ServicePrincipalService {

    public static final String ACTIVE = "active";
    public static final String SUSPENDED = "suspended";
    public static final String REVOKED = "revoked";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ServicePrincipalMapper principals;
    private final CryptoService crypto;

    public ServicePrincipalService(ServicePrincipalMapper principals, CryptoService crypto) {
        this.principals = principals;
        this.crypto = crypto;
    }

    /**
     * 当前派发或读取边界核验固定执行主体；精确查询租户、integration、主体、状态、凭据版本和scope。
     * 只消费认证边界已形成的identity，不把凭据生命周期查询或整机构目录扫描作为授权依据。
     */
    @Transactional(readOnly = true)
    public void requireCurrent(com.skillforge.common.context.ServicePrincipalContext.Identity identity, String scope) {
        if (identity == null || scope == null || scope.isBlank() || !identity.scopes().contains(scope))
            throw new ApiException(HttpStatus.FORBIDDEN, "ServicePrincipal 缺少当前scope");
        String previous = com.skillforge.common.context.TenantContext.getTenantId();
        if (previous != null && !previous.equals(identity.tenantId()))
            throw new ApiException(HttpStatus.FORBIDDEN, "ServicePrincipal 与当前机构不一致");
        try {
            com.skillforge.common.context.TenantContext.setTenantId(identity.tenantId());
            var current = principals.selectOne(new QueryWrapper<ServicePrincipalDO>()
                    .eq("tenant_id", tenant(identity.tenantId())).eq("integration_id", identity.integrationId())
                    .eq("principal_id", identity.principalId()).eq("status", ACTIVE)
                    .eq("credential_version", identity.credentialVersion())
                    .apply("scopes @> jsonb_build_array(CAST({0} AS text))", scope));
            if (current == null || current.getScopes() == null || !current.getScopes().contains(scope))
                throw new ApiException(HttpStatus.FORBIDDEN, "ServicePrincipal 当前授权已失效");
        } finally {
            if (previous == null) com.skillforge.common.context.TenantContext.clear();
            else com.skillforge.common.context.TenantContext.setTenantId(previous);
        }
    }

    /** 首次签发；API key 和签名 secret 仅通过返回值交付一次。 */
    @Transactional(rollbackFor = Exception.class)
    public IssuedPrincipal issue(String tenantId, String integrationId, Set<String> scopes) {
        long tenant = tenant(tenantId);
        String integration = required(integrationId, "integrationId");
        Set<String> normalizedScopes = scopes(scopes);
        Credentials credentials = credentials();
        ServicePrincipalDO row = new ServicePrincipalDO();
        row.setId(SnowflakeIds.nextId());
        row.setTenantId(tenant);
        row.setPrincipalId("sp_" + UUID.randomUUID());
        row.setIntegrationId(integration);
        row.setApiKeyHash(TokenHash.sha256Hex(credentials.apiKey()));
        row.setApiKeyPrefix(credentials.apiKey().substring(0, 12));
        row.setSigningSecretEncrypted(crypto.encrypt(credentials.signingSecret()));
        row.setScopes(List.copyOf(normalizedScopes));
        row.setStatus(ACTIVE);
        row.setCredentialVersion(1L);
        Instant now = Instant.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        principals.insert(row);
        // 安全持久字段取同租户权威行；本次明文凭据保留原样，不因回读重新签发。
        ServicePrincipalDO persisted = principals.selectOne(new QueryWrapper<ServicePrincipalDO>()
                .eq("tenant_id", tenant).eq("id", row.getId()));
        if (persisted == null) throw new IllegalStateException("service_principal_receipt_missing");
        return new IssuedPrincipal(persisted, credentials.apiKey(), credentials.signingSecret());
    }

    /** 受信宿主幂等建立内部集成身份；不向调用方暴露任何凭据。 */
    @Transactional(rollbackFor = Exception.class)
    public TrustedPrincipal ensureInternal(String tenantId, String integrationId, Set<String> requiredScopes) {
        long owner = tenant(tenantId);
        String integration = required(integrationId, "integrationId");
        Set<String> normalized = scopes(requiredScopes);
        principals.lockIntegration(tenantId, integration);
        var row = principals.selectOne(new QueryWrapper<ServicePrincipalDO>()
                .eq("tenant_id", owner).eq("integration_id", integration)
                .orderByDesc("created_at").last("LIMIT 1"));
        if (row == null) row = issue(tenantId, integration, normalized).principal();
        if (!ACTIVE.equals(row.getStatus()) || row.getScopes() == null || !row.getScopes().containsAll(normalized)) {
            throw new IllegalStateException("internal_service_principal_unavailable");
        }
        return new TrustedPrincipal(row.getPrincipalId(), row.getIntegrationId(), row.getCredentialVersion());
    }

    /** 内部宿主身份引用，不含数据库对象或凭据。 */
    public record TrustedPrincipal(String principalId, String integrationId, long credentialVersion) {}

    /** 轮换会同时替换 API key 与签名 secret，并提高 credential version。 */
    @Transactional(rollbackFor = Exception.class)
    public IssuedPrincipal rotate(String tenantId, long id) {
        ServicePrincipalDO current = requireActive(tenantId, id);
        Credentials credentials = credentials();
        long version = current.getCredentialVersion() == null ? 1L : current.getCredentialVersion() + 1;
        Instant now = Instant.now();
        int updated = principals.update(null, new UpdateWrapper<ServicePrincipalDO>()
                .eq("tenant_id", tenant(tenantId)).eq("id", id).eq("status", ACTIVE)
                .eq("credential_version", current.getCredentialVersion())
                .set("api_key_hash", TokenHash.sha256Hex(credentials.apiKey()))
                .set("api_key_prefix", credentials.apiKey().substring(0, 12))
                .set("signing_secret_encrypted", crypto.encrypt(credentials.signingSecret()))
                .set("credential_version", version).set("updated_at", now));
        if (updated == 0) {
            throw new IllegalStateException("ServicePrincipal credential rotation lost concurrency");
        }
        current.setApiKeyHash(TokenHash.sha256Hex(credentials.apiKey()));
        current.setApiKeyPrefix(credentials.apiKey().substring(0, 12));
        current.setSigningSecretEncrypted(null);
        current.setCredentialVersion(version);
        current.setUpdatedAt(now);
        return new IssuedPrincipal(current, credentials.apiKey(), credentials.signingSecret());
    }

    @Transactional(readOnly = true)
    public List<ServicePrincipalDO> list(String tenantId) {
        return principals.selectList(new QueryWrapper<ServicePrincipalDO>()
                .eq("tenant_id", tenant(tenantId)).orderByDesc("created_at"));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean suspend(String tenantId, long id) {
        return setStatus(tenantId, id, SUSPENDED);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean revoke(String tenantId, long id) {
        return setStatus(tenantId, id, REVOKED);
    }

    private boolean setStatus(String tenantId, long id, String status) {
        return principals.update(null, new UpdateWrapper<ServicePrincipalDO>()
                .eq("tenant_id", tenant(tenantId)).eq("id", id).eq("status", ACTIVE)
                .set("status", status).set("updated_at", Instant.now())) > 0;
    }

    /** 当前租户不存在与已停用分别拒绝；拒绝在新凭据生成及任何写入之前发生。 */
    private ServicePrincipalDO requireActive(String tenantId, long id) {
        ServicePrincipalDO row = principals.selectOne(new QueryWrapper<ServicePrincipalDO>()
                .eq("tenant_id", tenant(tenantId)).eq("id", id));
        if (row == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ServicePrincipal 不存在");
        }
        if (!ACTIVE.equals(row.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "仅 active ServicePrincipal 可以轮换凭据");
        }
        return row;
    }

    private Credentials credentials() {
        byte[] apiKey = new byte[24];
        byte[] secret = new byte[32];
        RANDOM.nextBytes(apiKey);
        RANDOM.nextBytes(secret);
        return new Credentials("sfsp_" + HexFormat.of().formatHex(apiKey), HexFormat.of().formatHex(secret));
    }

    /** scope 格式是本服务拥有的输入边界；格式拒绝不生成或保存凭据。 */
    private Set<String> scopes(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "至少需要一个 scope");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null || scope.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "scope 不能为空");
            }
            String value = required(scope, "scope");
            if (!value.matches("[a-z][a-z0-9._-]{1,63}")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "scope 格式无效");
            }
            normalized.add(value);
        }
        return Set.copyOf(normalized);
    }

    private long tenant(String tenantId) {
        return Long.parseLong(required(tenantId, "tenantId"));
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private record Credentials(String apiKey, String signingSecret) {
    }

    /** 凭证只在 issue/rotate 响应中可见；持久实体中的密文字段不应被 API 回显。 */
    public record IssuedPrincipal(
            ServicePrincipalDO principal, String apiKey, String signingSecret) {
    }
}
