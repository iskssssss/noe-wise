package com.skillforge.core.chat.access;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.integration.OpenPlatformAccessService;
import com.skillforge.core.chat.session.SessionView;
import com.skillforge.core.foundation.integration.InstitutionIntegrationService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Session 历史与输入边界：后台使用成员/部门所有权，渠道和服务主体继续使用各自独立认证协议。 */
@Component
public final class SessionAccessPolicy {
    private final FoundationAccess access;
    private final FoundationOrganizationService organization;
    private final OpenPlatformAccessService openPlatform;
    private final SessionAccessMapper mapper;
    private final InstitutionIntegrationService integrations;
    private final com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests;
    /** 身份服务不再反向依赖会话，当前凭证与会话归属分别在所属领域校验。 */
    public SessionAccessPolicy(FoundationAccess access,FoundationOrganizationService organization,OpenPlatformAccessService openPlatform,SessionAccessMapper mapper,InstitutionIntegrationService integrations,com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation tests){this.access=access;this.organization=organization;this.openPlatform=openPlatform;this.mapper=mapper;this.integrations=integrations;this.tests=tests;}
    public void requireClosingTest(String tenant,String session) {
        var test=tests.session(Long.parseLong(tenant),session).orElseThrow(SessionAccessPolicy::denied);
        if(!"closing".equals(test.getStatus()))throw denied();
    }
    public void requireEndUser(String tenant,String session,Long user) {tests.requireEndUser(Long.parseLong(tenant),session,user);}
    public void requireTarget(String tenant,String session,Long agent) {tests.requireTarget(Long.parseLong(tenant),session,agent);}
    /** 真实已认证主体在创建前校验；TenantContext本身不等于已认证主体。 */
    public void requireCreation(String tenant){
        if(ServicePrincipalContext.get()!=null){var principal=ServicePrincipalContext.get();if(!tenant.equals(principal.tenantId()))throw denied();openPlatform.require(principal,"sessions.write");return;}
        if(ChannelContext.getChannelId()!=null){requireChannel(tenant);return;}
        var subject=member(tenant);access.require(subject,"foundation.sessions.write");
    }
    /** 已有会话查询前只核验可信身份和精确租户，不读取权限；完整当前写权仍由 requireCurrent 判定。 */
    public void requireExistingIdentity(String tenant){
        String current=TenantContext.getTenantId();
        if(current==null)throw new ApiException(HttpStatus.UNAUTHORIZED,"缺少租户上下文");
        if(!current.equals(tenant))throw denied();
        var principal=ServicePrincipalContext.get();
        if(principal!=null){if(!tenant.equals(principal.tenantId()))throw denied();return;}
        String channel=ChannelContext.getChannelId();
        if(channel!=null){if(!channel.matches("[1-9][0-9]{0,18}"))throw denied();return;}
        member(tenant);
    }
    /** 新Session与成员所有权在同库创建事务内登记，外部Session不伪装成机构成员。 */
    public void registerCreated(String tenant,String sessionId){if(ServicePrincipalContext.get()!=null||ChannelContext.getChannelId()!=null)return;member(tenant);organization.registerSessionOwnership(sessionId);}
    /** 在每次查询/写入前重新检查当前账号与所有权；Agent共享不会授予Session历史。 */
    public void requireCurrent(SessionView session,String action){
        requirePersonal(session);
        String tenant=session.getTenantId().toString();
        tests.session(session.getTenantId(),session.getSessionId()).ifPresent(tests::requireMember);
        if(ServicePrincipalContext.get()!=null){var principal=ServicePrincipalContext.get();if(!tenant.equals(principal.tenantId()))throw denied();String scope="write".equals(action)?"sessions.write":principal.scopes().contains("sessions.read")?"sessions.read":"sessions.write";var grant=openPlatform.require(principal,scope);OpenPlatformSessionOwnership.require(session,grant,integrations);return;}
        if(ChannelContext.getChannelId()!=null){requireChannel(tenant);if(session.getChannelId()==null||!ChannelContext.getChannelId().equals(session.getChannelId().toString()))throw denied();return;}
        access.requireSession(member(tenant),session.getSessionId(),action);
    }
    /** 订阅线程使用受理时认证的成员身份；动作和范围由本次数据库事实重新判定。 */
    public void requireCurrentRead(SessionView session,FoundationAccess.Subject subject){
        requirePersonal(session);
        tests.session(session.getTenantId(),session.getSessionId()).ifPresent(test->{
            if(subject==null||subject.platform()||!test.getTenantId().toString().equals(subject.tenantId())||!test.getInitiatorMemberId().toString().equals(subject.memberId()))throw denied();
        });
        if(subject==null||subject.platform()||!session.getTenantId().toString().equals(subject.tenantId())
                ||ServicePrincipalContext.get()!=null||ChannelContext.getChannelId()!=null)throw denied();
        access.requireSession(subject,session.getSessionId(),"read");
    }
    /** 有限列表作用域；NULL 仅来自已授权all或已认证渠道的固定渠道过滤，不从任意tenant推断。 */
    public List<String> visibleSessionIds(String tenant){
        if(ServicePrincipalContext.get()!=null)throw new ApiException(HttpStatus.FORBIDDEN,"开放平台会话列表必须使用其专属授权入口");
        if(ChannelContext.getChannelId()!=null){requireChannel(tenant);return null;}
        return access.visibleSessionIds(member(tenant),"read");
    }
    /** 绑定后台成员作用域，平台账号不能隐式访问机构Session。 */
    private FoundationAccess.Subject member(String tenant){var subject=access.currentSubject();if(subject.platform()||!tenant.equals(subject.tenantId()))throw denied();return subject;}
    /** 只接受认证器留下的渠道身份，并复验当前渠道启用状态。 */
    private void requireChannel(String tenant){String channel=ChannelContext.getChannelId();if(!tenant.equals(TenantContext.getTenantId())||channel==null||!channel.matches("[1-9][0-9]{0,18}")||!mapper.activeChannel(tenant,channel))throw denied();}
    /** 个人历史/资源入口不能把群管理凭证当作成员读取权。 */
    private static void requirePersonal(SessionView session){if(session.getGroupBindingId()!=null)throw denied();}
    /** 不公开跨作用域对象存在性。 */ private static ApiException denied(){return new ApiException(HttpStatus.FORBIDDEN,"session_access_denied","没有会话访问权限",false);}
}
