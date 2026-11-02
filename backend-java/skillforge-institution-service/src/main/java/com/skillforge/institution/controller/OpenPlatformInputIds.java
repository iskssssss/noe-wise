package com.skillforge.institution.controller;

import com.skillforge.common.context.ServicePrincipalContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 外部幂等键按完整可信命名空间冻结为有界内部身份，轮换凭证不改变输入身份。 */
final class OpenPlatformInputIds {
    private OpenPlatformInputIds() {}

    /** 长度分帧保留全部字段，含冒号的外部引用不能与另一命名空间碰撞。 */
    static String create(ServicePrincipalContext.Identity principal,String sessionId,String operation,String key) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String value:new String[]{"skillforge.open-platform.input.v1",principal.tenantId(),
                    principal.integrationId(),sessionId,operation,key.trim()}) {
                byte[] encoded=value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(encoded.length).array());
                digest.update(encoded);
            }
            return "open-platform:"+HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("open_platform_input_digest_unavailable",impossible);
        }
    }
}
