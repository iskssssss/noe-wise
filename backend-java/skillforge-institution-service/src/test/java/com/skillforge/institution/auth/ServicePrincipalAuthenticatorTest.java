package com.skillforge.institution.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.persistence.ServicePrincipalMapper;
import com.skillforge.core.foundation.persistence.ServicePrincipalDO;
import com.skillforge.core.foundation.application.CryptoService;
import com.skillforge.institution.config.ExternalAuthPaths;
import com.skillforge.institution.security.HmacSignatureVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** ServicePrincipal 认证使用持久 scope，拒绝正文伪造身份和失效签名。 */
class ServicePrincipalAuthenticatorTest {

    private final ServicePrincipalMapper mapper = mock(ServicePrincipalMapper.class);
    private final CryptoService crypto = mock(CryptoService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ServicePrincipalAuthenticator authenticator = new ServicePrincipalAuthenticator(mapper, crypto);

    @BeforeEach
    void setUp() {
        when(request.getMethod()).thenReturn("GET");
        when(request.getQueryString()).thenReturn("sessionId=s1");
        when(request.getRequestURI()).thenReturn("/api/v1/open-platform/sessions/events");
        when(crypto.decrypt(anyString())).thenReturn("secret");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        ServicePrincipalContext.clear();
    }

    @Test
    void validHmacInjectsFrozenTenantAndScopes() {
        when(mapper.selectForAuthByApiKeyHash(anyString())).thenReturn(principal());
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = HmacSignatureVerifier.sign("secret", HmacSignatureVerifier.canonical(
                timestamp, "GET", "/api/v1/open-platform/sessions/events?sessionId=s1", ""));

        authenticator.authenticate("sfsp_key", timestamp, signature, request, new byte[0]);

        assertEquals("7", TenantContext.getTenantId());
        assertEquals("sp-1", ServicePrincipalContext.get().principalId());
        assertTrue(ServicePrincipalContext.hasScope("sessions.events.read"));
        org.mockito.Mockito.verify(request).setAttribute(
                ExternalAuthPaths.ATTR_AUTH_SOURCE, ExternalAuthPaths.SOURCE_SERVICE_PRINCIPAL);
    }

    @Test
    void rejectsReplacingQueryResourceWithPreviouslyValidSignature() {
        when(mapper.selectForAuthByApiKeyHash(anyString())).thenReturn(principal());
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = HmacSignatureVerifier.sign("secret", HmacSignatureVerifier.canonical(
                timestamp, "GET", "/api/v1/open-platform/sessions/events?sessionId=s1", ""));
        when(request.getQueryString()).thenReturn("sessionId=s2");
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> authenticator.authenticate("sfsp_key", timestamp, signature, request, new byte[0])).getStatus());
    }

    @Test
    void rejectsMissingPrincipalStaleTimestampAndBadSignature() {
        when(mapper.selectForAuthByApiKeyHash(anyString())).thenReturn(null);
        ApiException missing = assertThrows(ApiException.class,
                () -> authenticator.authenticate("key", "1", "sig", request, new byte[0]));
        assertEquals(HttpStatus.UNAUTHORIZED, missing.getStatus());

        when(mapper.selectForAuthByApiKeyHash(anyString())).thenReturn(principal());
        ApiException stale = assertThrows(ApiException.class,
                () -> authenticator.authenticate("key", "1", "sig", request, new byte[0]));
        assertEquals(HttpStatus.UNAUTHORIZED, stale.getStatus());
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        ApiException invalid = assertThrows(ApiException.class,
                () -> authenticator.authenticate("key", timestamp, "wrong", request, new byte[0]));
        assertEquals(HttpStatus.UNAUTHORIZED, invalid.getStatus());
    }

    @Test
    void rejectsMalformedPersistedPrincipalBeforeDecryptingSecret() {
        ServicePrincipalDO malformed = principal();
        malformed.setSigningSecretEncrypted(null);
        when(mapper.selectForAuthByApiKeyHash(anyString())).thenReturn(malformed);

        ApiException failure = assertThrows(ApiException.class,
                () -> authenticator.authenticate("key", String.valueOf(System.currentTimeMillis() / 1000),
                        "sig", request, new byte[0]));

        assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatus());
        org.mockito.Mockito.verifyNoInteractions(crypto);
    }

    @Test
    void contextCopiesScopesAndCanBeCleared() {
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity(
                "sp", "7", "crm", 1L, java.util.Set.of("sessions.events.read")));
        assertTrue(ServicePrincipalContext.hasScope("sessions.events.read"));
        ServicePrincipalContext.clear();
        org.junit.jupiter.api.Assertions.assertFalse(ServicePrincipalContext.hasScope("sessions.events.read"));
    }

    /** 任意二进制按原字节认证；正文中的租户字段不能成为认证身份。 */
    @Test
    void authenticatesRawBinaryAndJsonWithoutChangingDatabaseIdentity() {
        when(mapper.selectForAuthByApiKeyHash(anyString())).thenReturn(principal());
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/open-platform/sessions/attachments");
        when(request.getQueryString()).thenReturn("sessionId=s1&filename=fixture.bin");
        String target = "/api/v1/open-platform/sessions/attachments?sessionId=s1&filename=fixture.bin";
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        for (byte[] body : List.of(new byte[]{0, (byte) 0xff, (byte) 0xc3, 0x28, 10},
                "{\"tenant_id\":\"999\",\"integration_id\":\"forged\"}".getBytes(StandardCharsets.UTF_8))) {
            String signature = HmacSignatureVerifier.sign("secret", HmacSignatureVerifier.canonicalBytes(timestamp, "POST", target, body));
            authenticator.authenticate("sfsp_key", timestamp, signature, request, body);
            assertEquals("7", TenantContext.getTenantId());
            assertEquals("crm", ServicePrincipalContext.get().integrationId());
            assertEquals(3L, ServicePrincipalContext.get().credentialVersion());
            clearContext();
        }
    }

    /** 改一个原始字节、签空正文、缺签名或缺缓存均拒绝，且不能留下授权上下文。 */
    @Test
    void rejectsTamperedBinaryEmptyBodySignatureAndMissingRawBody() {
        when(mapper.selectForAuthByApiKeyHash(anyString())).thenReturn(principal());
        byte[] body = {0, (byte) 0xff, (byte) 0xfe};
        String timestamp = Long.toString(System.currentTimeMillis() / 1000);
        String target = "/api/v1/open-platform/sessions/events?sessionId=s1";
        String signature = HmacSignatureVerifier.sign("secret", HmacSignatureVerifier.canonicalBytes(timestamp, "GET", target, body));
        byte[] tampered = body.clone(); tampered[1] ^= 1;
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> authenticator.authenticate("sfsp_key", timestamp, signature, request, tampered)).getStatus());
        String empty = HmacSignatureVerifier.sign("secret", HmacSignatureVerifier.canonicalBytes(timestamp, "GET", target, new byte[0]));
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> authenticator.authenticate("sfsp_key", timestamp, empty, request, body)).getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> authenticator.authenticate("sfsp_key", timestamp, null, request, body)).getStatus());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> authenticator.authenticate("sfsp_key", timestamp, signature, request, null)).getStatus());
        org.junit.jupiter.api.Assertions.assertNull(TenantContext.getTenantId());
        org.junit.jupiter.api.Assertions.assertNull(ServicePrincipalContext.get());
    }

    private ServicePrincipalDO principal() {
        ServicePrincipalDO principal = new ServicePrincipalDO();
        principal.setTenantId(7L);
        principal.setPrincipalId("sp-1");
        principal.setIntegrationId("crm");
        principal.setSigningSecretEncrypted("enc");
        principal.setCredentialVersion(3L);
        principal.setScopes(List.of("sessions.events.read"));
        return principal;
    }
}
