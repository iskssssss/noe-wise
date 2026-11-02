package com.skillforge.core.foundation.dto.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ServicePrincipal API DTO 保持凭证只在签发响应中出现。 */
class ServicePrincipalDtoTest {

    @Test
    void issuedCredentialEnvelopeKeepsTheSafePrincipalViewSeparate() {
        CreateServicePrincipalRequest request = new CreateServicePrincipalRequest(
                "crm", List.of("sessions.events.read"));
        ServicePrincipalResponse principal = new ServicePrincipalResponse(
                12L, "sp-1", request.integrationId(), "sfsp_1234567", request.scopes(),
                "active", 1L, Instant.parse("2026-08-24T12:00:00Z"));
        IssuedServicePrincipalResponse issued = new IssuedServicePrincipalResponse(
                principal, "sfsp_plaintext", "secret_plaintext");

        assertEquals("crm", issued.principal().integrationId());
        assertEquals("sfsp_plaintext", issued.apiKey());
        assertEquals("secret_plaintext", issued.signingSecret());
    }
}
