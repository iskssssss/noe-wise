package com.skillforge.institution.workspace;

import com.skillforge.execution.WorkspaceBinding;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 数据库事务只包围归属登记；容器与云端调用不占用宿主数据库事务。 */
@Service
public class SessionWorkspaceRegistry {
    private static final Logger LOG=LoggerFactory.getLogger(SessionWorkspaceRegistry.class);
    private final SessionWorkspaceMapper mapper;
    /** 装配MyBatis归属入口，数据库身份由调用方租户上下文提供。 */
    public SessionWorkspaceRegistry(SessionWorkspaceMapper mapper){
        LOG.debug("工作区归属服务装配进入");this.mapper=mapper;LOG.debug("工作区归属服务装配退出");
    }

    /** 产品会话仍可写时延迟登记，复用记录必须完全匹配可信Run且未被删除。 */
    @Transactional
    public boolean requireActive(WorkspaceBinding binding,String operation) {
        LOG.debug("工作区归属登记进入");
        try {
            if(!mapper.productActive(binding.tenantId(),binding.productSessionId(),binding.endUserId()))
                throw new SecurityException("workspace_session_closed");
            mapper.insert(new SessionWorkspaceMapper.Row(binding.workspaceId(),binding.tenantId(),binding.sessionId(),
                    binding.productSessionId(),binding.endUserId(),"active",operation,false));
            var actual=mapper.find(binding.tenantId(),binding.sessionId());
            if(actual==null||!actual.workspaceId().equals(binding.workspaceId())||!actual.productSessionId().equals(binding.productSessionId())
                    ||actual.endUserId()!=binding.endUserId()||!"active".equals(actual.cleanupState()))
                throw new SecurityException("workspace_identity_rejected");
            if(actual.initialized())return false;
            if(!operation.equals(actual.initialOperationId()))throw new SecurityException("workspace_initialization_pending");
            return true;
        }finally{LOG.debug("工作区归属登记退出");}
    }

    /** 只在Sandbox初始化回执成功后确认；此事务提交前不允许执行任何文件操作。 */
    @Transactional
    public void confirmInitialization(WorkspaceBinding binding,String operation) {
        LOG.debug("工作区初始化确认进入");
        try {
            if(!mapper.productActive(binding.tenantId(),binding.productSessionId(),binding.endUserId()))
                throw new SecurityException("workspace_session_closed");
            mapper.confirmInitialization(binding.tenantId(),binding.workspaceId(),operation);
            var actual=mapper.find(binding.tenantId(),binding.sessionId());
            if(actual==null||!actual.initialized()||!operation.equals(actual.initialOperationId())||!"active".equals(actual.cleanupState()))
                throw new SecurityException("workspace_initialization_rejected");
        }finally{LOG.debug("工作区初始化确认退出");}
    }

    /** 展示仅查询归属，不延迟创建文件或改变执行状态。 */
    @Transactional(readOnly=true)
    public List<SessionWorkspaceMapper.Row> list(String tenant,String productSession) {
        LOG.debug("工作区归属列表进入");
        try{return mapper.list(tenant,productSession);}finally{LOG.debug("工作区归属列表退出");}
    }

    /** 来源证据按可信operation幂等登记，无文件内容。 */
    @Transactional
    public void recordImport(WorkspaceBinding binding,String operation,String evidence) {
        LOG.debug("工作区导入来源登记进入");
        try{mapper.recordImport(binding.tenantId(),binding.workspaceId(),operation,evidence);}
        finally{LOG.debug("工作区导入来源登记退出");}
    }

    /** 先提交清理意图再调用Sandbox；产品删除不会级联丢失待清理身份。 */
    @Transactional
    public List<SessionWorkspaceMapper.Row> cleanupCandidates(String tenant) {
        LOG.debug("工作区清理意图进入");
        try{mapper.markDeletedProducts(tenant);return mapper.pendingCleanup(tenant);}
        finally{LOG.debug("工作区清理意图退出");}
    }

    /** 只有Sandbox确认清理完成才能推进墓碑，不删除稳定身份记录。 */
    @Transactional
    public void completeCleanup(String tenant,String workspace) {
        LOG.debug("工作区清理完成登记进入");
        try{mapper.completeCleanup(tenant,workspace);}finally{LOG.debug("工作区清理完成登记退出");}
    }
}
