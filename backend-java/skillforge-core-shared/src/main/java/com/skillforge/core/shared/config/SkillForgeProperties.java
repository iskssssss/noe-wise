package com.skillforge.core.shared.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工作流锻造平台应用配置。
 *
 * <p>通过 Spring Boot 的 {@link ConfigurationProperties} 绑定以 {@code skillforge.}
 * 为前缀的环境变量，只承载认证、部署资源与调试参数；运营参数由平台配置中心管理。
 */
@Data
@ConfigurationProperties(prefix = "skillforge")
public class SkillForgeProperties {

    /** 业务字段加密主密钥，对应两个宿主共享的环境变量 SECRET_KEY。 */
    @lombok.ToString.Exclude
    private String secretKey = "change-me";

    /**
     * 只用于历史业务密文解密的主密钥，最多 4 个，默认空。
     *
     * <p>每项是历史主密钥原始 UTF-8 字节的标准 Base64；编码只保护配置传输的字节完整性，
     * 不隐藏密钥。两个宿主共享 SECRET_KEY_DECRYPTION_KEYS，禁止用于签发 JWT 或加密新数据。
     */
    @lombok.ToString.Exclude
    private java.util.List<String> secretKeyDecryptionKeys = java.util.List.of();

    /** JWT 签发者（issuer），默认 skillforge。 */
    private String jwtIssuer = "skillforge";

    /** JWT 接收方（audience），默认 skillforge-api。 */
    private String jwtAudience = "skillforge-api";

    /** 机构宿主平台部署管理入口的受控平台 issuer；未配置时拒绝额外认证域。 */
    private String managementJwtIssuer;

    /** 仅与 managementJwtIssuer 成对配置，不使平台 JWT 可调用机构业务接口。 */
    private String managementJwtAudience;

    /** 精确平台代部署入口使用的平台JWT验签密钥；与realm同时配置，禁止回退机构签名密钥。 */
    @lombok.ToString.Exclude
    private String managementJwtSecretKey;

    /** 平台初始管理员用户名，对应环境变量 PLATFORM_ADMIN_USERNAME。 */
    private String platformAdminUsername = "";

    /** 平台初始管理员密码，对应环境变量 PLATFORM_ADMIN_PASSWORD。 */
    @lombok.ToString.Exclude
    private String platformAdminPassword = "";

    /** 平台初始管理员邮箱，对应环境变量 PLATFORM_ADMIN_EMAIL。 */
    private String platformAdminEmail = "";

    /** Access Token 有效期（分钟），默认 60。 */
    private int accessTokenExpireMinutes = 60;

    /** Refresh Token 有效期（天），默认 14。 */
    private int refreshTokenExpireDays = 14;

    /** 候选与 tokenizer 共用的内容字符资源上限；部署参数，随准备 Profile 冻结。 */
    private int contextContentCharacters = 32 * 1024 * 1024;

    /** 调试模式开关，控制文档与详细错误是否暴露。 */
    private boolean debug = true;


    /** 沙箱允许的域名白名单（逗号分隔），为空表示不限制域名。 */
    private String sandboxAllowedDomains = "";

    /** 是否启用 Elasticsearch 检索索引。 */
    private boolean elasticsearchEnabled = false;

    /** Elasticsearch 服务地址。 */
    private String elasticsearchUri = "http://localhost:9200";

    /** Elasticsearch 知识分块索引名。 */
    private String elasticsearchIndex = "skillforge-chunks";

}
