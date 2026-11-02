package com.skillforge.core.foundation.security;

import com.skillforge.common.context.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 安全会话公平有界回收；只扫描显式策略作用域，保护/刷新竞争由数据库锁和RLS处理。 */
@Component
public class SecuritySessionCleanupScheduler {
    private static final Logger log=LoggerFactory.getLogger(SecuritySessionCleanupScheduler.class);
    private final SecurityMapper mapper;
    private final FoundationSecurityService security;
    /** 使用正式代理执行每批独立事务，不通过自调用绕过事务边界。 */
    public SecuritySessionCleanupScheduler(SecurityMapper mapper,FoundationSecurityService security){this.mapper=mapper;this.security=security;}
    /** 每作用域最多四批100行，数据库轮询水位支持跨宿主公平续接；失败保留下一轮重新扫描。 */
    @Scheduled(fixedDelayString="${skillforge.foundation.security.cleanup-ms:60000}")
    public void cleanup(){String previous=TenantContext.getTenantId();try{TenantContext.clear();for(String scope:mapper.sessionCleanupScopes()){if(!scope.equals("platform")&&!scope.matches("tenant:[1-9][0-9]{0,18}"))throw new IllegalStateException("非法安全清理作用域");TenantContext.setTenantId(scope.equals("platform")?null:scope.substring(7));int removed=0;for(int batch=0;batch<4;batch++){int count=security.cleanupSessions(scope);removed+=count;if(count<100)break;}if(removed>0)log.info("安全会话回收 scope={} removed={}",scope,removed);}}catch(RuntimeException failure){log.warn("安全会话回收失败，保留重试，异常类型={}",failure.getClass().getSimpleName());}finally{if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);}}
}
