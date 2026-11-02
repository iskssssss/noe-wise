package com.skillforge.core.foundation.messaging.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationRecipientDirectory;
import com.skillforge.core.foundation.messaging.application.NotificationService;
import com.skillforge.core.foundation.messaging.application.NotificationWorker;
import com.skillforge.core.foundation.messaging.application.NotificationHistoryCleanupScheduler;
import com.skillforge.core.foundation.messaging.contract.NotificationRecipientDirectory;
import com.skillforge.core.foundation.messaging.integration.NotificationMailTransport;
import com.skillforge.core.foundation.messaging.integration.SmtpNotificationMailTransport;
import com.skillforge.core.foundation.messaging.persistence.MessagingMapper;
import com.skillforge.core.foundation.application.CryptoService;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** 两个宿主复用消息生产装配；机构通过core扫描，平台需显式import。 */
@Configuration(proxyBeanMethods=false)
@MapperScan(basePackageClasses=MessagingMapper.class,annotationClass=Mapper.class)
public class MessagingConfiguration {
    /** 配置唯一注册权限贡献者，避免宿主扫描与import重复装配。 */
    @Bean MessagingPermissions messagingPermissions() { return new MessagingPermissions(); }
    /** 收件范围只通过授权域公开目录解析，不直接读取IAM持久化表。 */
    @Bean NotificationRecipientDirectory notificationRecipientDirectory(FoundationAccess access,FoundationRecipientDirectory directory) {
        return (scope,audience,ids)->{
            var subject=access.currentSubject(); String expected=subject.platform()?"platform":"tenant:"+subject.tenantId();
            if(!expected.equals(scope)) throw new IllegalArgumentException("收件范围与当前主体不匹配");
            String target=switch(audience) { case "members"->"member"; case "departments"->"department"; case "roles"->"role"; case "all"->"all"; default->throw new IllegalArgumentException("收件范围非法"); };
            return directory.resolve(subject,target,ids).stream().map(r->new NotificationRecipientDirectory.Recipient(r.memberId(),r.email())).toList();
        };
    }
    /** 生产服务拥有校验、授权和事务，控制器不直接操作Mapper。 */
    @Bean NotificationService notificationService(MessagingMapper mapper,FoundationAccess access,NotificationRecipientDirectory directory,CryptoService crypto,ObjectMapper json) { return new NotificationService(mapper,access,directory,crypto,json); }
    /** 默认使用TLS SMTP协议适配器，无静默模拟投递。 */
    @Bean NotificationMailTransport notificationMailTransport(CryptoService crypto) { return new SmtpNotificationMailTransport(crypto); }
    /** 数据库租约可被两个宿主并行消费，外部调用不持有数据库事务。 */
    @Bean NotificationWorker notificationWorker(MessagingMapper mapper,NotificationMailTransport transport,CryptoService crypto,PlatformTransactionManager transactions,FoundationAccess access) { return new NotificationWorker(mapper,transport,crypto,transactions,access); }
    /** 批准默认30日终态策略，通过精确引用及保护判断普通回收候选。 */
    @Bean NotificationHistoryCleanupScheduler notificationHistoryCleanupScheduler(MessagingMapper mapper,NotificationService notifications){return new NotificationHistoryCleanupScheduler(mapper,notifications);}
}
