package com.skillforge.core.foundation.integration.runtime.callback;


import static org.junit.jupiter.api.Assertions.*;

import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.CallbackRegistrationPort.CredentialRef;
import com.skillforge.runtime.api.PrincipalRef;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 原 IAM 密文经真实 AES-GCM 解密，再对原消息进行 HMAC 验签和临时密钥清零。 */
@Testcontainers(disabledWithoutDocker = false)
final class JdbcCallbackSigningKeysTest {
    private static final String ROLE = "callback_signing_reader";
    private static final String PASSWORD = "callback_signing_test_password";
    private static final TenantId TENANT = new TenantId("1");
    private static final PrincipalRef.ServicePrincipal PRINCIPAL = new PrincipalRef.ServicePrincipal(TENANT, "principal-甲", "integration", 7);
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("skillforge").withUsername("signing_admin").withPassword("signing_admin_password");
    private final AtomicReference<byte[]> decrypted = new AtomicReference<>();
    private final AtomicInteger decryptions = new AtomicInteger();
    private byte[] masterKey;
    private byte[] signingKey;
    private String encrypted;
    private JdbcCallbackSigningKeys keys;

    @BeforeAll
    static void initializeDatabase() {
        execute("CREATE TABLE nw_service_principals (tenant_id bigint NOT NULL,principal_id text NOT NULL,credential_version bigint NOT NULL,"
                + "status text NOT NULL,signing_secret_encrypted text NOT NULL,PRIMARY KEY(tenant_id,principal_id))");
        execute("ALTER TABLE nw_service_principals ENABLE ROW LEVEL SECURITY");
        execute("ALTER TABLE nw_service_principals FORCE ROW LEVEL SECURITY");
        execute("CREATE POLICY tenant_isolation ON nw_service_principals USING "
                + "(tenant_id::text = COALESCE(current_setting('app.tenant_id',true),''))");
        execute("CREATE ROLE " + ROLE + " LOGIN PASSWORD '" + PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
        execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        execute("GRANT SELECT ON nw_service_principals TO " + ROLE);
    }

    @BeforeEach
    void prepareEncryptedSource() throws Exception {
        execute("TRUNCATE nw_service_principals");
        masterKey = randomBytes(32); signingKey = randomBytes(32);
        byte[] iv = randomBytes(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] ciphertext = cipher.doFinal(signingKey);
        byte[] wire = Arrays.copyOf(iv, iv.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, wire, iv.length, ciphertext.length);
        encrypted = Base64.getEncoder().encodeToString(wire);
        try (var connection = admin(); var insert = connection.prepareStatement("INSERT INTO nw_service_principals VALUES (1,?,7,'active',?)")) {
            insert.setString(1, PRINCIPAL.principalId()); insert.setString(2, encrypted); insert.executeUpdate();
        }
        decrypted.set(null); decryptions.set(0);
        keys = new JdbcCallbackSigningKeys(dataSource(), this::decrypt);
    }

    @Test
    void realEncryptedIamKeyVerifiesHmacAndClearsReturnedKeyWithoutChangingCallerMessage() throws Exception {
        byte[] message = "目标|callback-甲|原始请求🧭".getBytes(StandardCharsets.UTF_8);
        byte[] original = message.clone();
        String signature = signature(message);
        assertTrue(keys.verify(JdbcCallbackSigningKeys.reference(PRINCIPAL), message, signature));
        assertArrayEquals(original, message);
        assertEquals(1, decryptions.get());
        assertArrayEquals(new byte[32], decrypted.get());
        assertTrue(new JdbcCallbackSigningKeys(dataSource(), this::decrypt)
                .verify(JdbcCallbackSigningKeys.reference(PRINCIPAL), message, signature));
        assertEquals(2, decryptions.get());
        assertArrayEquals(new byte[32], decrypted.get());
    }

    @Test
    void signatureAndMessageTamperingAreRejectedAndDecryptedKeyStillCleared() throws Exception {
        byte[] message = "original-message".getBytes(StandardCharsets.UTF_8);
        String valid = signature(message);
        String altered = (valid.charAt(0) == '0' ? "1" : "0") + valid.substring(1);
        CredentialRef ref = JdbcCallbackSigningKeys.reference(PRINCIPAL);
        assertFalse(keys.verify(ref, message, altered));
        assertArrayEquals(new byte[32], decrypted.get());
        byte[] changed = message.clone(); changed[0] ^= 1;
        assertFalse(keys.verify(ref, changed, valid));
        assertArrayEquals(new byte[32], decrypted.get());
        int before = decryptions.get();
        for (String malformed : List.of("A".repeat(64), "0".repeat(63), "not-hex")) {
            assertFalse(keys.verify(ref, message, malformed));
        }
        assertFalse(keys.verify(ref, message, null));
        assertEquals(before, decryptions.get());
    }

    @Test
    void tenantVersionPrincipalAndNamespaceMustMatchCurrentActiveCredential() throws Exception {
        byte[] message = "signed".getBytes(StandardCharsets.UTF_8);
        String signature = signature(message);
        CredentialRef original = JdbcCallbackSigningKeys.reference(PRINCIPAL);
        for (CredentialRef wrong : List.of(new CredentialRef(new TenantId("2"), original.value()),
                JdbcCallbackSigningKeys.reference(new PrincipalRef.ServicePrincipal(TENANT, PRINCIPAL.principalId(), "integration", 8)),
                JdbcCallbackSigningKeys.reference(new PrincipalRef.ServicePrincipal(TENANT, "missing-principal", "integration", 7)),
                new CredentialRef(TENANT, "unknown-namespace.secret"),
                new CredentialRef(TENANT, original.value().replace(".7", ".07")))) {
            assertFalse(keys.verify(wrong, message, signature));
        }
        assertEquals(0, decryptions.get());
        execute("UPDATE nw_service_principals SET status='revoked'");
        assertFalse(keys.verify(original, message, signature));
        execute("UPDATE nw_service_principals SET status='active',credential_version=8");
        assertFalse(keys.verify(original, message, signature));
        assertEquals(0, decryptions.get());
    }

    @Test
    void corruptedCiphertextCannotProduceSuccessfulVerification() throws Exception {
        byte[] wire = Base64.getDecoder().decode(encrypted); wire[wire.length - 1] ^= 1;
        try (var connection = admin(); var update = connection.prepareStatement("UPDATE nw_service_principals SET signing_secret_encrypted=?")) {
            update.setString(1, Base64.getEncoder().encodeToString(wire)); update.executeUpdate();
        }
        byte[] message = "signed".getBytes(StandardCharsets.UTF_8);
        String signature = signature(message);
        var failure = assertThrows(IllegalStateException.class,
                () -> keys.verify(JdbcCallbackSigningKeys.reference(PRINCIPAL), message, signature));
        assertEquals("callback_signing_unavailable", failure.getMessage());
        assertEquals(1, decryptions.get()); assertNull(decrypted.get());
    }

    @Test
    void restrictedRoleCannotReadOtherTenantSigningCiphertext() throws Exception {
        try (var connection = dataSource().getConnection(); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user")) {
                assertTrue(row.next()); assertFalse(row.getBoolean(1)); assertFalse(row.getBoolean(2));
            }
            for (String tenant : List.of("", "2", "1")) {
                try (var setting = connection.prepareStatement("SELECT set_config('app.tenant_id',?,false)")) {
                    setting.setString(1, tenant); setting.execute();
                }
                try (var row = statement.executeQuery("SELECT count(*) FROM nw_service_principals")) {
                    assertTrue(row.next()); assertEquals(tenant.equals("1") ? 1 : 0, row.getLong(1));
                }
            }
        }
    }

    private byte[] decrypt(String value) {
        decryptions.incrementAndGet();
        try {
            byte[] wire = Base64.getDecoder().decode(value);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, Arrays.copyOf(wire, 12)));
            byte[] result = cipher.doFinal(wire, 12, wire.length - 12);
            decrypted.set(result); return result;
        } catch (Exception failure) { throw new IllegalStateException("test_decryption_failed", failure); }
    }
    private String signature(byte[] message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message));
    }
    private static byte[] randomBytes(int length) { byte[] value = new byte[length]; new SecureRandom().nextBytes(value); return value; }
    private static DataSource dataSource() { var source = new PGSimpleDataSource(); source.setURL(POSTGRES.getJdbcUrl()); source.setUser(ROLE); source.setPassword(PASSWORD); return source; }
    private static Connection admin() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private static void execute(String sql) { try (var connection = admin(); var statement = connection.createStatement()) { statement.execute(sql); } catch (SQLException failure) { throw new IllegalStateException(failure); } }
}
