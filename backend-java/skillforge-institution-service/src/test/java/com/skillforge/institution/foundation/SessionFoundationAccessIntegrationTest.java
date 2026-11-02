package com.skillforge.institution.foundation;

import static org.junit.jupiter.api.Assertions.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.common.context.RequestContext;
import com.skillforge.core.shared.config.SnowflakeIds;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.billing.persistence.PlanMapper;
import com.skillforge.core.foundation.persistence.TenantMapper;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.integration.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 真实 PostgreSQL 验证会话所有权、列表、创建事务及每次订阅读取的已提交当前授权。
 * 默认复用基类 Testcontainers 业务连接，仅证明真实 SQL/事务消费边界，不宣称受限角色或 RLS 验收。
 */
class SessionFoundationAccessIntegrationTest extends AbstractIntegrationTest {
    @Autowired FoundationAccess access;
    @Autowired SessionService sessions;
    @Autowired JdbcTemplate jdbc;
    @Autowired TenantMapper tenants;
    @Autowired PlanMapper plans;
    @Autowired PlatformTransactionManager manager;
    @AfterEach void clear(){TenantContext.clear();RequestContext.clear();}

    @Test void creationReceiptIsAtomicAndOnlyVisibleToItsCurrentAuthorizedActor() {
        String tenant=createTenant(tenants,plans,"session-create-receipt-"+UUID.randomUUID());
        TenantContext.setTenantId(tenant);
        long owner=member(tenant,"self"),other=member(tenant,"all"),user=SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?)",user,Long.parseLong(tenant));
        long agent=createTestAgent(tenant,"session-create-receipt");
        String request=UUID.randomUUID().toString();
        RequestContext.setUserId(Long.toString(owner));
        var row=access.withSubject(subject(tenant,owner),()->sessions.createWithReceipt(tenant,user,agent,null,null,null,request));
        assertEquals(row.getSessionId(),access.withSubject(subject(tenant,owner),()->sessions.creationResult(tenant,request)));
        RequestContext.setUserId(Long.toString(other));
        assertNull(access.withSubject(subject(tenant,other),()->sessions.creationResult(tenant,request)));
        RequestContext.setUserId(Long.toString(owner));
        String rolledBack=UUID.randomUUID().toString();
        access.withSubject(subject(tenant,owner),()->new TransactionTemplate(manager).execute(status->{
            sessions.createWithReceipt(tenant,user,agent,null,null,null,rolledBack);status.setRollbackOnly();return null;
        }));
        assertNull(access.withSubject(subject(tenant,owner),()->sessions.creationResult(tenant,rolledBack)));
        committedChange(tenant,"DELETE FROM nw_foundation_role_permissions WHERE scope_key=? AND permission_code='foundation.sessions.read' AND role_id IN (SELECT role_id FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=?)",tenant,tenant,owner);
        assertThrows(ApiException.class,()->access.withSubject(subject(tenant,owner),()->sessions.creationResult(tenant,request)));
    }

    /** 普通读/写权入口使用真实短事务，返回后可提交撤权；新调用不得复用上一回授权。 */
    @Test void ordinaryReadAndWritableChecksEndBeforeCallbackAndRecheckCommittedRevocation(){
        assertTrue(AopUtils.isAopProxy(sessions));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        String tenant=createTenant(tenants,plans,"session-short-authorization-"+UUID.randomUUID());TenantContext.setTenantId(tenant);
        long owner=member(tenant,"self"),reader=member(tenant,"all"),user=SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?)",user,Long.parseLong(tenant));
        long agent=createTestAgent(tenant,"session-short-authorization");
        var ownerSubject=subject(tenant,owner);var readerSubject=subject(tenant,reader);
        var created=access.withSubject(ownerSubject,()->sessions.getOrCreate(tenant,user,null,agent,null,null));
        access.withSubject(readerSubject,()->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"withSubject 不能包住任意业务回调");
            assertEquals(created.getSessionId(),sessions.require(tenant,created.getSessionId()).getSessionId());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(created.getSessionId(),sessions.requireWritable(tenant,created.getSessionId()).getSessionId());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"外部调用开始前须结束授权事务");
            return null;
        });
        committedChange(tenant,"UPDATE nw_foundation_roles SET data_scope='self',lock_version=lock_version+1 WHERE scope_key=? AND id IN (SELECT role_id FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=?)",tenant,tenant,reader);
        assertEquals("foundation.session.denied",assertThrows(ApiException.class,()->access.withSubject(readerSubject,()->sessions.require(tenant,created.getSessionId()))).getCode());
        assertEquals("foundation.session.denied",assertThrows(ApiException.class,()->access.withSubject(readerSubject,()->sessions.requireWritable(tenant,created.getSessionId()))).getCode());
        committedChange(tenant,"DELETE FROM nw_foundation_role_permissions WHERE scope_key=? AND permission_code='foundation.sessions.write' AND role_id IN (SELECT role_id FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=?)",tenant,tenant,owner);
        assertEquals(created.getSessionId(),access.withSubject(ownerSubject,()->sessions.require(tenant,created.getSessionId())).getSessionId());
        assertEquals("foundation.permission.denied",assertThrows(ApiException.class,()->access.withSubject(ownerSubject,()->sessions.requireWritable(tenant,created.getSessionId()))).getCode());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test void sameAgentShareDoesNotGrantAnotherMembersSessionAndListFiltersBeforeReturn(){
        String tenant=createTenant(tenants,plans,"session-access-"+UUID.randomUUID());TenantContext.setTenantId(tenant);
        long owner=member(tenant,"self"),other=member(tenant,"self"),all=member(tenant,"all"),user=SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?)",user,Long.parseLong(tenant));long agent=createTestAgent(tenant,"session-access");
        var ownerSubject=subject(tenant,owner);var otherSubject=subject(tenant,other);
        var created=access.withSubject(ownerSubject,()->sessions.getOrCreate(tenant,user,null,agent,null,null));
        assertEquals(owner,jdbc.queryForObject("SELECT o.owner_member_id FROM nw_foundation_resource_ownership o JOIN nw_chat_sessions s ON s.id=o.resource_id AND s.tenant_id::text=o.scope_key WHERE o.scope_key=? AND o.kind='session' AND s.session_id=?",Long.class,tenant,created.getSessionId()));
        jdbc.update("INSERT INTO nw_foundation_resource_ownership(scope_key,kind,resource_id,owner_member_id) VALUES(?,'agent',?,?)",tenant,agent,owner);
        jdbc.update("INSERT INTO nw_foundation_resource_grants(id,scope_key,kind,resource_id,grantee_type,grantee_id,action,created_by_id) VALUES(?,?,'agent',?,'member',?,'read',?)",SnowflakeIds.nextId(),tenant,agent,other,owner);
        assertTrue(access.canAccessResource(otherSubject,"agent",Long.toString(agent),"read"));
        assertThrows(ApiException.class,()->access.withSubject(otherSubject,()->sessions.require(tenant,created.getSessionId())));
        assertThrows(ApiException.class,()->access.withSubject(otherSubject,()->sessions.requireWritable(tenant,created.getSessionId())));
        assertEquals(List.of(),access.withSubject(otherSubject,()->sessions.list(tenant,null,agent)));
        assertEquals(List.of(created.getSessionId()),access.withSubject(ownerSubject,()->sessions.list(tenant,null,agent)).stream().map(row->row.sessionId()).toList());
        String unowned="historic-"+UUID.randomUUID();jdbc.update("INSERT INTO nw_chat_sessions(id,session_id,tenant_id,end_user_id,agent_id,origin_kind,actor_ref,responsibility_ref) VALUES(?,?,?,?,?,'native',?,?)",SnowflakeIds.nextId(),unowned,Long.parseLong(tenant),user,agent,"end_user:"+user,"tenant:"+tenant);
        assertThrows(ApiException.class,()->access.withSubject(ownerSubject,()->sessions.require(tenant,unowned)));
        assertEquals(unowned,access.withSubject(subject(tenant,all),()->sessions.require(tenant,unowned)).getSessionId());
        jdbc.update("DELETE FROM nw_foundation_role_permissions WHERE scope_key=? AND permission_code='foundation.sessions.read' AND role_id IN (SELECT role_id FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=?)",tenant,tenant,owner);
        assertThrows(ApiException.class,()->access.withSubject(ownerSubject,()->sessions.require(tenant,created.getSessionId())));
    }
    @Test void rollbackDoesNotLeaveSessionOrOwnershipAndUnauthenticatedTenantCreatesNothing(){
        String tenant=createTenant(tenants,plans,"session-transaction-"+UUID.randomUUID());TenantContext.setTenantId(tenant);long owner=member(tenant,"self"),user=SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?)",user,Long.parseLong(tenant));long agent=createTestAgent(tenant,"session-transaction");
        var rolledBack=access.withSubject(subject(tenant,owner),()->new TransactionTemplate(manager).execute(status->{var row=sessions.getOrCreate(tenant,user,null,agent,null,null);status.setRollbackOnly();return row;}));
        assertNotNull(rolledBack);assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM nw_chat_sessions WHERE session_id=?",Integer.class,rolledBack.getSessionId()));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM nw_foundation_resource_ownership WHERE scope_key=? AND kind='session'",Integer.class,tenant));
        assertThrows(ApiException.class,()->sessions.getOrCreate(tenant,user,null,agent,null,null));
    }
    /** 独立的真实 Spring 代理调用必须看到上一页之后已提交的范围、权限和账号变化。 */
    @Test void subscriptionReadRechecksCommittedScopePermissionAndAccountBeforeEveryNewCall(){
        assertTrue(AopUtils.isAopProxy(sessions),"必须调用带事务的真实 SessionService Spring 代理");
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"测试不能用外层事务冻结后续授权");
        String tenant=createTenant(tenants,plans,"session-current-read-"+UUID.randomUUID());TenantContext.setTenantId(tenant);
        long owner=member(tenant,"self"),scopeReader=member(tenant,"all"),permissionReader=member(tenant,"all"),disabledReader=member(tenant,"all"),user=SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?)",user,Long.parseLong(tenant));long agent=createTestAgent(tenant,"session-subscription-current");
        var created=access.withSubject(subject(tenant,owner),()->sessions.getOrCreate(tenant,user,null,agent,null,null));
        assertEquals(owner,jdbc.queryForObject("SELECT o.owner_member_id FROM nw_foundation_resource_ownership o JOIN nw_chat_sessions s ON s.id=o.resource_id AND s.tenant_id::text=o.scope_key WHERE o.scope_key=? AND o.kind='session' AND s.session_id=?",Long.class,tenant,created.getSessionId()));
        for(long reader:List.of(scopeReader,permissionReader,disabledReader)){
            assertEquals(created.getSessionId(),sessions.requireCurrentRead(tenant,created.getSessionId(),subject(tenant,reader)).getSessionId());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"授权事务须在下一次调用前结束");
            assertEquals(tenant,TenantContext.getTenantId());
        }

        committedChange(tenant,"UPDATE nw_foundation_roles SET data_scope='self',lock_version=lock_version+1 WHERE scope_key=? AND id IN (SELECT role_id FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=?)",tenant,tenant,scopeReader);
        var scopeDenied=assertThrows(ApiException.class,()->sessions.requireCurrentRead(tenant,created.getSessionId(),subject(tenant,scopeReader)));
        assertEquals("foundation.session.denied",scopeDenied.getCode());assertEquals(403,scopeDenied.getStatus().value());
        assertEquals(tenant,TenantContext.getTenantId());

        committedChange(tenant,"DELETE FROM nw_foundation_role_permissions WHERE scope_key=? AND permission_code='foundation.sessions.read' AND role_id IN (SELECT role_id FROM nw_foundation_member_roles WHERE scope_key=? AND member_id=?)",tenant,tenant,permissionReader);
        var permissionDenied=assertThrows(ApiException.class,()->sessions.requireCurrentRead(tenant,created.getSessionId(),subject(tenant,permissionReader)));
        assertEquals("foundation.permission.denied",permissionDenied.getCode());assertEquals(403,permissionDenied.getStatus().value());
        assertEquals(tenant,TenantContext.getTenantId());

        committedChange(tenant,"UPDATE nw_tenant_members SET status='disabled' WHERE tenant_id=? AND id=?",Long.parseLong(tenant),disabledReader);
        var inactive=assertThrows(ApiException.class,()->sessions.requireCurrentRead(tenant,created.getSessionId(),subject(tenant,disabledReader)));
        assertEquals("foundation.subject.inactive",inactive.getCode());assertEquals(401,inactive.getStatus().value());
        assertEquals(tenant,TenantContext.getTenantId());assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(created.getEndUserId(),sessions.requireCurrentRead(tenant,created.getSessionId(),subject(tenant,owner)).getEndUserId());
    }
    /** 显式身份只能读取精确租户会话，成功、拒绝及不存在路径均须恢复原租户上下文。 */
    @Test void subscriptionReadRestoresExactTenantAndRejectsMismatchedSubjectOrForeignSession(){
        String tenant=createTenant(tenants,plans,"session-subscription-scope-"+UUID.randomUUID());TenantContext.setTenantId(tenant);
        long owner=member(tenant,"self"),user=SnowflakeIds.nextId();
        jdbc.update("INSERT INTO nw_end_users(id,tenant_id) VALUES(?,?)",user,Long.parseLong(tenant));long agent=createTestAgent(tenant,"session-subscription-scope");
        var ownerSubject=subject(tenant,owner);var created=access.withSubject(ownerSubject,()->sessions.getOrCreate(tenant,user,null,agent,null,null));
        String outer=createTenant(tenants,plans,"session-subscription-outer-"+UUID.randomUUID());TenantContext.setTenantId(outer);long foreign=member(outer,"all");
        var foreignSubject=subject(outer,foreign);

        assertEquals(created.getSessionId(),sessions.requireCurrentRead(tenant,created.getSessionId(),ownerSubject).getSessionId());
        assertEquals(outer,TenantContext.getTenantId());
        var mismatch=assertThrows(ApiException.class,()->sessions.requireCurrentRead(tenant,created.getSessionId(),foreignSubject));
        assertEquals("session_access_denied",mismatch.getCode());assertEquals(403,mismatch.getStatus().value());
        assertEquals(outer,TenantContext.getTenantId());
        var foreignSession=assertThrows(ApiException.class,()->sessions.requireCurrentRead(outer,created.getSessionId(),foreignSubject));
        assertEquals(404,foreignSession.getStatus().value());assertEquals(outer,TenantContext.getTenantId());

        TenantContext.clear();assertEquals(created.getSessionId(),sessions.requireCurrentRead(tenant,created.getSessionId(),ownerSubject).getSessionId());
        assertNull(TenantContext.getTenantId());
        assertThrows(ApiException.class,()->sessions.requireCurrentRead(tenant,created.getSessionId(),foreignSubject));
        assertNull(TenantContext.getTenantId());assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
    /** 每次变更独立提交后才发起下一页授权；同连接绑定仅用于真实 fixture 写入，不替代 RLS 验收。 */
    private void committedChange(String tenant,String sql,Object...arguments){
        var writer=new TransactionTemplate(manager);writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writer.executeWithoutResult(status->{
            jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant);
            assertEquals(1,jdbc.update(sql,arguments),"负控变更必须实际命中唯一成员的授权行");
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"撤权必须在新授权前提交");
    }
    private long member(String tenant,String scope){
        long id=SnowflakeIds.nextId(),role=SnowflakeIds.nextId();jdbc.update("INSERT INTO nw_tenant_members(id,tenant_id,username,role,status) VALUES(?,?,?,'developer','active')",id,Long.parseLong(tenant),"session-member-"+id);
        jdbc.update("INSERT INTO nw_foundation_access_scopes(scope_key,tenant_id) VALUES(?,?) ON CONFLICT DO NOTHING",tenant,Long.parseLong(tenant));jdbc.update("INSERT INTO nw_foundation_member_profiles(scope_key,member_id,display_name) VALUES(?,?,'Session member')",tenant,id);
        jdbc.update("INSERT INTO nw_foundation_roles(id,scope_key,code,name,data_scope) VALUES(?,?,?,'Session test role',?)",role,tenant,"session_role_"+role,scope);jdbc.update("INSERT INTO nw_foundation_member_roles(scope_key,member_id,role_id) VALUES(?,?,?)",tenant,id,role);
        for(String permission:List.of("foundation.sessions.read","foundation.sessions.write","foundation.resource.agent.read"))jdbc.update("INSERT INTO nw_foundation_role_permissions(scope_key,role_id,permission_code) VALUES(?,?,?)",tenant,role,permission);return id;
    }
    private long createTestAgent(String tenant,String purpose){long id=SnowflakeIds.nextId();jdbc.update("INSERT INTO nw_agents(id,agent_key,tenant_id,name,status,published_at) VALUES(?,?,?,?,'published',now())",id,"session-agent-"+id,Long.parseLong(tenant),purpose);return id;}
    private static FoundationAccess.Subject subject(String tenant,long member){return new FoundationAccess.Subject(false,tenant,Long.toString(member));}
}
