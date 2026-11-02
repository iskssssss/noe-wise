package com.skillforge.core.shared.vault;

/**
 * Vault 密钥提供者抽象。
 *
 * <p>连接器凭证可声明 vault_secret_path，由具体提供者解析真实密钥；
 * 本地开发使用环境变量回退，避免硬编码明文。
 */
public interface VaultProvider {

    /**
     * 解析密钥路径为明文值。
     *
     * @param secretPath 密钥路径
     * @return 明文密钥
     */
    String resolve(String secretPath);
}
