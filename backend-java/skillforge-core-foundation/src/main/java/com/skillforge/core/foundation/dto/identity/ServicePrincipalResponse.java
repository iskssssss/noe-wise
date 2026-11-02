package com.skillforge.core.foundation.dto.identity;

import java.time.Instant;
import java.util.List;

/** ServicePrincipal 的安全视图；不包含 API key 哈希或签名密文。 */
public record ServicePrincipalResponse(
        Long id,
        String principalId,
        String integrationId,
        String apiKeyPrefix,
        List<String> scopes,
        String status,
        Long credentialVersion,
        Instant createdAt) {
}
