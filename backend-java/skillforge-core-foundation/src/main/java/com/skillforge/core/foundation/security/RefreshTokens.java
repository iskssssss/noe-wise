package com.skillforge.core.foundation.security;

import java.security.SecureRandom;
import java.util.Base64;

/** 刷新凭据是不可预测的不透明随机值；仅其摘要进入服务端存储。 */
public final class RefreshTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private RefreshTokens() {}

    public static String create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
