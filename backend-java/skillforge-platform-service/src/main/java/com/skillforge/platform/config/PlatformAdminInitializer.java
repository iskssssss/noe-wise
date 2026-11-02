package com.skillforge.platform.config;
import com.skillforge.core.shared.config.SnowflakeIds;

import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.core.foundation.constant.AuthConstants;
import com.skillforge.core.foundation.persistence.PlatformMemberMapper;
import com.skillforge.core.foundation.persistence.PlatformMemberDO;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 平台初始管理员初始化器。
 *
 * <p>当 {@code platform_members} 为空且配置了 {@code PLATFORM_ADMIN_USERNAME /
 * PLATFORM_ADMIN_PASSWORD} 时，在启动阶段创建首个平台管理员；密码仅来自环境变量，
 * 不写入 git 或迁移文件。
 */
@Component
public class PlatformAdminInitializer implements CommandLineRunner {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(PlatformAdminInitializer.class);

    /** 平台账号映射器。 */
    private final PlatformMemberMapper platformMemberMapper;

    /** 应用配置。 */
    private final SkillForgeProperties properties;

    /** 密码编码器。 */
    private final PasswordEncoder passwordEncoder;
    private final FoundationOrganizationService organization;

    /**
     * 构造平台初始管理员初始化器。
     *
     * @param platformMemberMapper 平台账号映射器
     * @param properties           应用配置
     * @param passwordEncoder      密码编码器
     */
    public PlatformAdminInitializer(
            PlatformMemberMapper platformMemberMapper,
            SkillForgeProperties properties,
            PasswordEncoder passwordEncoder, FoundationOrganizationService organization) {
        this.platformMemberMapper = platformMemberMapper;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        this.organization = organization;
    }

    /**
     * 启动时初始化首个平台管理员。
     *
     * @param args 命令行参数
     */
    @Override
    public void run(String... args) {
        String username = properties.getPlatformAdminUsername();
        String password = properties.getPlatformAdminPassword();
        if (username == null || username.isBlank()
                || password == null || password.isBlank()) {
            return;
        }
        PlatformMemberDO member = new PlatformMemberDO();
        member.setId(SnowflakeIds.nextId());
        member.setUsername(username.trim());
        member.setPasswordHash(passwordEncoder.encode(password));
        member.setEmail(blankToNull(properties.getPlatformAdminEmail()));
        member.setStatus(AuthConstants.MEMBER_STATUS_ACTIVE);
        if (organization.initializePlatformAdministrator(member.getId().toString(), () -> platformMemberMapper.insert(member)))
            log.info("Initialized first platform admin: {}", username.trim());
    }

    /**
     * 将空白字符串转为 null。
     *
     * @param value 原始值
     * @return 空白时返回 null，否则原样返回
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
