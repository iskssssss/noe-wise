package com.skillforge.institution;

import com.skillforge.core.shared.config.SkillForgeProperties;
import com.skillforge.institution.browser.BrowserMapper;
import com.skillforge.institution.media.SessionMediaMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 灵知 机构管理后台服务启动入口。
 *
 * <p>启动包只承载接口层（控制器、Web 配置、安全配置），核心业务逻辑位于
 * {@code com.skillforge.core} 包，由本服务按需组合。
 */
@SpringBootApplication(scanBasePackages = {"com.skillforge.institution", "com.skillforge.core"})
@EnableScheduling
@EnableConfigurationProperties(SkillForgeProperties.class)
@MapperScans({
@MapperScan({"com.skillforge.institution.chat", "com.skillforge.institution.artifact", "com.skillforge.institution.attachment", "com.skillforge.institution.team", "com.skillforge.institution.workflow", "com.skillforge.core.agent.mapper", "com.skillforge.core.workflow.mapper", "com.skillforge.core.knowledge.mapper"}),
@MapperScan(basePackageClasses = {com.skillforge.institution.runtime.AgentRunBindingsMapper.class,
        com.skillforge.institution.memory.MemoryExtractionPolicyMapper.class,
        com.skillforge.institution.tool.ToolOnboardingReceiptMapper.class,
        com.skillforge.institution.skill.SkillPublishReceiptMapper.class,
        com.skillforge.institution.channel.ChannelCreateReceiptMapper.class,
        com.skillforge.institution.principal.PrincipalRotationReceiptMapper.class,
        com.skillforge.institution.webhook.WebhookCreateReceiptMapper.class,
        com.skillforge.institution.knowledge.JdbcKnowledgeWriteProposalSourceMapper.class,
        com.skillforge.institution.controller.HealthMapper.class,
        SessionMediaMapper.class, BrowserMapper.class, com.skillforge.institution.browser.BrowserConnectionMapper.class, com.skillforge.institution.service.HandoffReplyMapper.class,
        com.skillforge.institution.workspace.SessionWorkspaceMapper.class},
        annotationClass = org.apache.ibatis.annotations.Mapper.class)
})
public class InstitutionApplication {

    /**
     * 启动机构管理后台服务。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(InstitutionApplication.class, args);
    }
}
