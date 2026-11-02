package com.skillforge.core.foundation.dto.identity;

/** 新签发或轮换的 ServicePrincipal 凭证；明文只在此响应出现一次。 */
public record IssuedServicePrincipalResponse(
        ServicePrincipalResponse principal,
        String apiKey,
        String signingSecret) {
}
