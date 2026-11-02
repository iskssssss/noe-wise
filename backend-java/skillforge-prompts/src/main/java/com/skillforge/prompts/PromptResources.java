package com.skillforge.prompts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 直接读取完整系统正文；有效 UTF-8 字节决定冻结版本，不解析章节或模板。 */
final class PromptResources {
    private PromptResources() { }

    static String read(String path) {
        String resource = "/com/skillforge/prompts/" + path + ".md";
        try (var input = PromptResources.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("prompt_resource_missing: " + path);
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (content.isBlank() || content.charAt(0) == '\uFEFF' || content.indexOf('\r') >= 0) {
                throw new IllegalStateException("prompt_resource_invalid: " + path);
            }
            return content;
        } catch (IOException failure) {
            throw new IllegalStateException("prompt_resource_unreadable: " + path, failure);
        }
    }

    static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("prompt_digest_unavailable", impossible);
        }
    }
}
