package com.skillforge.core.foundation.workflowtest;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.ChannelContext;
import java.time.Instant;
import java.util.Optional;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 隔离是后台持久归属，业务 scope 标签或客户端 context 不授予测试权限。 */
@Service
public class WorkflowTestIsolation {
    private final WorkflowTestRegistry mapper;
    private final FoundationAccess access;
    public WorkflowTestIsolation(WorkflowTestRegistry mapper,FoundationAccess access) { this.mapper=mapper;this.access=access; }
    public Optional<WorkflowTestRegistry.Snapshot> session(long tenant,String session) { return Optional.ofNullable(mapper.bySession(tenant,session)); }
    public boolean isolated(long tenant,String session) { return mapper.bySession(tenant,session)!=null; }
    public void requireOrdinary(long tenant,String kind,String id) {
        if(mapper.ownedResource(tenant,kind,id))throw denied("workflow_test_resource_managed","测试资源只能通过其测试 Session 管理");
    }
    public void requireMember(WorkflowTestRegistry.Snapshot test) {
        if(ServicePrincipalContext.get()!=null||ChannelContext.getChannelId()!=null)throw denied("workflow_test_member_required","测试会话只允许原发起成员访问");
        var subject=access.currentSubject();
        if(subject.platform()||!test.getTenantId().toString().equals(subject.tenantId())||!test.getInitiatorMemberId().toString().equals(subject.memberId()))
            throw denied("workflow_test_owner_mismatch","测试会话不属于当前成员");
    }
    public void requireLive(WorkflowTestRegistry.Snapshot test) {
        if(!"ready".equals(test.getStatus())||!test.getExpiresAt().isAfter(Instant.now()))
            throw new ApiException(HttpStatus.CONFLICT,"workflow_test_not_live","测试会话已关闭、关闭中或过期",false);
    }
    /** 测试 Agent 仅能进入其唯一已登记 Session，普通新建入口无法借用。 */
    public void requireTarget(long tenant,String sessionId,Long agentId) {
        if(agentId==null)return;
        var test=mapper.byAgent(tenant,agentId);
        if(test==null)return;
        if(sessionId==null||!sessionId.equals(test.getSessionId()))throw denied("workflow_test_target_mismatch","测试 Agent 只能用于其原测试会话");
        requireMember(test);requireLive(test);
    }
    public void requireEndUser(long tenant,String sessionId,Long user) {
        if(user==null)return;var test=mapper.byEndUser(tenant,user);if(test==null)return;
        if(!Objects.equals(sessionId,test.getSessionId()))throw denied("workflow_test_target_mismatch","测试终端用户只能用于其原测试会话");
        requireMember(test);requireLive(test);
    }
    public static ApiException denied(String code,String message) { return new ApiException(HttpStatus.FORBIDDEN,code,message,false); }
}
