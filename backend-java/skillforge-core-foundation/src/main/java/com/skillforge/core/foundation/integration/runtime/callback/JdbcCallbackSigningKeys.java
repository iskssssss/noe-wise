package com.skillforge.core.foundation.integration.runtime.callback;

import com.skillforge.runtime.api.port.CallbackRegistrationPort.CredentialRef;
import com.skillforge.runtime.api.PrincipalRef;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/**
 * 从原服务身份目录解析版本化签名凭据；不读旧 callback 明文 secret，也不复制密钥。
 * 宿主注入原密文解密器，返回的独占 byte[] 在验签后清零；签名本身不构成资源授权。
 */
public final class JdbcCallbackSigningKeys {
    private static final String PREFIX = "service-principal-v1.";
    private final SqlSessionFactory sessions;
    private final Function<String, byte[]> decrypt;

    public JdbcCallbackSigningKeys(DataSource dataSource, Function<String, byte[]> decrypt) {
        this.decrypt = Objects.requireNonNull(decrypt);
        var configuration = new Configuration(new Environment("callback-signing-keys",
                new JdbcTransactionFactory(), Objects.requireNonNull(dataSource)));
        String resource = "mapper/AgentRuntimeCallbackSigningKeyMapper.xml";
        try (InputStream xml = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (xml == null) throw new IllegalStateException("callback_signing_mapper_missing");
            new XMLMapperBuilder(xml, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (Exception failure) { throw new IllegalStateException("callback_signing_initialization_failure", failure); }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    /** 引用包含租户、原 principalId 与确切凭据版本，没有 API key 或可反解 secret。 */
    public static CredentialRef reference(PrincipalRef.ServicePrincipal principal) {
        Objects.requireNonNull(principal);
        return new CredentialRef(principal.tenantId(), PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(principal.principalId().getBytes(StandardCharsets.UTF_8))
                + "." + principal.credentialVersion());
    }

    /** 原始 signed bytes 的 HMAC-SHA256 校验；调用方必须包含完整投递目标与原始请求内容。 */
    public boolean verify(CredentialRef reference, byte[] signedBytes, String signature) {
        Objects.requireNonNull(reference); Objects.requireNonNull(signedBytes);
        if (signature == null || !signature.matches("[0-9a-f]{64}")) return false;
        KeyIdentity identity = parse(reference);
        if (identity == null) return false;
        byte[] key = null;
        byte[] message = signedBytes.clone();
        try {
            String encrypted;
            try (var session = sessions.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                var mapper = session.getMapper(SigningKeyMapper.class);
                mapper.bindTenant(reference.tenantId().value());
                encrypted = mapper.find(reference.tenantId().value(), identity.principalId, identity.version);
                session.commit();
            }
            if (encrypted == null) return false;
            key = Objects.requireNonNull(decrypt.apply(encrypted), "callback_signing_key_missing");
            if (key.length == 0) throw new IllegalStateException("callback_signing_key_missing");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(message), HexFormat.of().parseHex(signature));
        } catch (Exception failure) {
            throw new IllegalStateException("callback_signing_unavailable", failure);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            Arrays.fill(message, (byte) 0);
        }
    }

    private static KeyIdentity parse(CredentialRef reference) {
        if (!reference.value().startsWith(PREFIX)) return null;
        try {
            String[] parts = reference.value().substring(PREFIX.length()).split("\\.", -1);
            if (parts.length != 2) return null;
            var principal = new PrincipalRef.ServicePrincipal(reference.tenantId(),
                    new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8), "reference", Long.parseLong(parts[1]));
            if (!reference(principal).equals(reference)) return null;
            return new KeyIdentity(principal.principalId(), principal.credentialVersion());
        } catch (RuntimeException malformed) { return null; }
    }

    private record KeyIdentity(String principalId, long version) { }
}

interface SigningKeyMapper {
    String bindTenant(@Param("tenant") String tenant);
    String find(@Param("tenant") String tenant, @Param("principal") String principal, @Param("version") long version);
}

