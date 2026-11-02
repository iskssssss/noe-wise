package com.skillforge.institution.workspace;

import com.skillforge.common.context.TenantContext;
import com.skillforge.core.foundation.persistence.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 产品会话关闭/删除后先提交清理意图，Sandbox收敛执行和云端删除后保留墓碑。 */
@Component
public final class SessionWorkspaceCleanup {
    private static final Logger LOG=LoggerFactory.getLogger(SessionWorkspaceCleanup.class);
    private final TenantMapper tenants;
    private final SessionWorkspaceRegistry registry;
    private final WorkspaceSandboxClient sandbox;
    /** 装配清理队列和可信Sandbox控制入口，不携带HTTP用户身份。 */
    public SessionWorkspaceCleanup(TenantMapper tenants,SessionWorkspaceRegistry registry,WorkspaceSandboxClient sandbox) {
        LOG.debug("工作区清理任务装配进入");
        this.tenants=tenants;this.registry=registry;this.sandbox=sandbox;
        LOG.debug("工作区清理任务装配退出");
    }
    /** 调度周期只处理持久pending记录；网络失败保留意图供后续重试。 */
    @Scheduled(fixedDelay=60_000,initialDelay=60_000)
    public void clean() {
        LOG.debug("工作区宿主清理进入");
        String previous=TenantContext.getTenantId();
        try {
            for(var tenant:tenants.selectList(null)) {
                try {
                    String id=tenant.getId().toString();TenantContext.setTenantId(id);
                    for(var row:registry.cleanupCandidates(id)) {
                        try{if(sandbox.delete(row.workspaceId()))registry.completeCleanup(id,row.workspaceId());}
                        catch(Exception failure){LOG.warn("工作区清理待重试 failureType={}",failure.getClass().getSimpleName());}
                    }
                }catch(Exception failure){LOG.warn("工作区清理批次待重试 failureType={}",failure.getClass().getSimpleName());}
                finally{TenantContext.clear();}
            }
        }finally {
            if(previous==null)TenantContext.clear();else TenantContext.setTenantId(previous);
            LOG.debug("工作区宿主清理退出");
        }
    }
}
