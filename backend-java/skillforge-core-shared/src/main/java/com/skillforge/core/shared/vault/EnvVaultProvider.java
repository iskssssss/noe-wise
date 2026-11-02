package com.skillforge.core.shared.vault;

import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * 环境变量 Vault 提供者。
 *
 * <p>支持 {@code env:VAR_NAME} 与 {@code vault:PATH} 两种路径：前者直接读取环境变量，
 * 后者映射为 {@code VAULT_<PATH>} 环境变量；未配置时抛错，避免静默降级为明文。
 */
@Component
public class EnvVaultProvider implements VaultProvider {

    /**
     * 解析密钥路径。
     *
     * @param secretPath 密钥路径
     * @return 明文密钥
     */
    @Override
    public String resolve(String secretPath) {
        if (secretPath == null || secretPath.isBlank()) {
            throw new IllegalArgumentException("secret path 为空");
        }
        if (secretPath.startsWith("env:")) {
            String key = secretPath.substring(4);
            String value = System.getenv(key);
            if (value == null) {
                throw new IllegalStateException("环境变量未配置: " + key);
            }
            return value;
        }
        if (secretPath.startsWith("vault:")) {
            String key = "VAULT_" + secretPath.substring(6)
                    .toUpperCase(Locale.ROOT).replace('/', '_').replace('-', '_');
            String value = System.getenv(key);
            if (value == null) {
                throw new IllegalStateException("Vault secret 未配置: " + key);
            }
            return value;
        }
        return secretPath;
    }
}
