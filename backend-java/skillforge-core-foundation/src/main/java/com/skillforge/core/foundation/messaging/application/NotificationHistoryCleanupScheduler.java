package com.skillforge.core.foundation.messaging.application;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.messaging.persistence.MessagingMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 消息终态材料的公平有界回收；数据库水位仅枚举策略作用域，正文仍受RLS保护。 */
public class NotificationHistoryCleanupScheduler {
    private static final Logger log=LoggerFactory.getLogger(NotificationHistoryCleanupScheduler.class);
    private final MessagingMapper mapper;
    private final NotificationService notifications;
    /** 每批通过正式事务代理，禁止自调用绕过引用保护与原子释放。 */
    public NotificationHistoryCleanupScheduler(MessagingMapper mapper,NotificationService notifications){this.mapper=mapper;this.notifications=notifications;}
    /** 每作用域最多四批，单批最多100通知/公告/版本/引用及1000尝试，失败保留材料供后续续接。 */
    @Scheduled(fixedDelayString="${skillforge.foundation.messaging.cleanup-ms:60000}")
    public void cleanup(){String previous=TenantContext.getTenantId();try{TenantContext.clear();for(String scope:mapper.historyCleanupScopes()){if(!scope.equals("platform")&&!scope.matches("tenant:[1-9][0-9]{0,18}"))throw new IllegalStateException("非法消息清理作用域");TenantContext.setTenantId(NotificationService.tenant(scope));for(int batch=0;batch<4;batch++)if(notifications.cleanupHistory(scope)==0)break;}}catch(RuntimeException failure){log.warn("消息终态材料回收失败，保留重试，异常类型={}",failure.getClass().getSimpleName());}finally{if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);}}
}
