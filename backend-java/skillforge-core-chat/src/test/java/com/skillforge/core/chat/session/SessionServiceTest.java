package com.skillforge.core.chat.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import com.skillforge.core.foundation.settings.OperationalSettingsService.Snapshot;
import java.util.Map;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SessionServiceTest {

    /** 公开读/写权查询都在返回前释放短读事务，拒绝也结束事务，不把后续外部调用包入。 */
    @Test void publicAuthorizationQueriesEndTheirReadTransactionBeforeReturning() throws SQLException {
        var fixture=new AuthorizationFixture(true);
        assertThat(fixture.service.require("7","session-1")).isEqualTo(SessionService.view(fixture.session));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(fixture.service.requireWritable("7","session-1")).isEqualTo(SessionService.view(fixture.session));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(fixture.source,times(2)).getConnection();
        verify(fixture.connection,times(2)).commit();
        doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"revoked"))
                .when(fixture.policy).requireCurrent(SessionService.view(fixture.session),"write");
        assertThrows(ApiException.class,()->fixture.service.requireWritable("7","session-1"));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(fixture.connection).rollback();
    }

    /** 已有写服务内部调用 requireWritable 保留原写事务和连接，不能被短读声明改为只读。 */
    @Test void writeServiceSelfInvocationKeepsItsOriginalWritableTransaction() throws SQLException {
        var fixture=new AuthorizationFixture(false);
        doAnswer(call->{
            fixture.assertCurrentTransaction();
            return 1;
        }).when(fixture.mapper).updateById(fixture.session);
        var original = SessionService.view(fixture.session);
        fixture.service.closeForTenant("7","session-1","closed");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        verify(fixture.source).getConnection();
        verify(fixture.connection).commit();
        verify(fixture.connection,never()).setReadOnly(true);
        verify(fixture.policy).requireCurrent(original,"write");
    }

    /** 真实 Spring 代理与 JDBC 事务管理器，Mapper/Policy 只提供精确会话事实。 */
    private final class AuthorizationFixture {
        final DataSource source=mock(DataSource.class);
        final Connection connection=mock(Connection.class);
        final SessionMapper mapper=mock(SessionMapper.class);
        final com.skillforge.core.chat.access.SessionAccessPolicy policy=mock(com.skillforge.core.chat.access.SessionAccessPolicy.class);
        final SessionDO session=new SessionDO();
        final SessionService service;
        final boolean readOnly;
        AuthorizationFixture(boolean readOnly) throws SQLException {
            this.readOnly=readOnly;
            when(source.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            session.setTenantId(7L);session.setSessionId("session-1");session.setMetadata(Map.of());
            when(mapper.selectOne(any())).thenAnswer(call->{assertCurrentTransaction();return session;});
            doAnswer(call->{assertCurrentTransaction();return null;}).when(policy).requireCurrent(org.mockito.ArgumentMatchers.eq(SessionService.view(session)),org.mockito.ArgumentMatchers.anyString());
            var proxy=new ProxyFactory(new SessionService(mapper,settings(),policy));proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
            service=(SessionService)proxy.getProxy();
        }
        void assertCurrentTransaction(){
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isEqualTo(readOnly);
            assertThat(DataSourceUtils.getConnection(source)).isSameAs(connection);
        }
    }

    /** 已有会话只校验其当前写权，撤权仍在本次续会话进入任何写入前拒绝。 */
    @Test void existingSessionUsesCurrentWriteAuthorizationWithoutRepeatingCreationAuthorization(){
        var mapper=mock(SessionMapper.class);var policy=mock(com.skillforge.core.chat.access.SessionAccessPolicy.class);
        var session=new SessionDO();session.setTenantId(7L);session.setSessionId("session-1");session.setAgentId(7L);session.setEndUserId(99L);
        when(mapper.selectOne(any())).thenReturn(session);var service=new SessionService(mapper,settings(),policy);
        assertThat(service.getOrCreate("7",99L,"session-1",7L,null,null)).isEqualTo(SessionService.view(session));
        verify(policy).requireCurrent(SessionService.view(session),"write");org.mockito.Mockito.verify(policy,org.mockito.Mockito.never()).requireCreation(any());
        assertThrows(ApiException.class,()->service.getOrCreate("7",100L,"session-1",7L,null,null));
        assertThrows(ApiException.class,()->service.getOrCreate("7",99L,"session-1",8L,null,null));
        org.mockito.Mockito.doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"revoked")).when(policy).requireCurrent(SessionService.view(session),"write");
        assertThrows(ApiException.class,()->service.getOrCreate("7",99L,"session-1",7L,null,null));
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).insert(any(SessionDO.class));
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).updateById(any(SessionDO.class));
    }

    /** 新建会话仍须先通过创建权限，失败不能登记会话或所有权。 */
    @Test void newSessionStillRequiresCreationAuthorizationBeforeAnyInsert(){
        var mapper=mock(SessionMapper.class);var policy=mock(com.skillforge.core.chat.access.SessionAccessPolicy.class);
        org.mockito.Mockito.doThrow(new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"revoked")).when(policy).requireCreation("7");
        var service=new SessionService(mapper,settings(),policy);
        assertThrows(ApiException.class,()->service.getOrCreate("7",99L,null,7L,null,null));
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).insert(any(SessionDO.class));
        org.mockito.Mockito.verify(policy,org.mockito.Mockito.never()).registerCreated(any(),any());
    }

    /** 缺认证、缺租户或租户错配都先于真实会话查询；会话是否存在不能改变这些拒绝结果。 */
    @Test void existingSessionRejectsMissingOrMismatchedIdentityBeforeAnyPersistentLookup(){
        var foundation=mock(com.skillforge.core.foundation.access.FoundationAccess.class);
        var organization=mock(com.skillforge.core.foundation.access.FoundationOrganizationService.class);
        var provider=mock(com.skillforge.core.foundation.integration.OpenPlatformAccessService.class);
        var accessMapper=mock(com.skillforge.core.chat.access.SessionAccessMapper.class);
        var policy=new com.skillforge.core.chat.access.SessionAccessPolicy(foundation,organization,provider,accessMapper,mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class),mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));
        var mapper=mock(SessionMapper.class);var service=new SessionService(mapper,settings(),policy);
        String previous=com.skillforge.common.context.TenantContext.getTenantId();
        try{
            com.skillforge.common.context.TenantContext.setTenantId("7");
            when(foundation.currentSubject()).thenThrow(new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,"foundation.authentication.required","请先登录",false));
            for(String session:java.util.List.of("existing-session","missing-session"))assertThat(assertThrows(ApiException.class,
                    ()->service.getOrCreate("7",99L,session,7L,null,null)).getStatus().value()).isEqualTo(401);
            org.mockito.Mockito.doReturn(new com.skillforge.core.foundation.access.FoundationAccess.Subject(false,"7","9")).when(foundation).currentSubject();
            com.skillforge.common.context.TenantContext.clear();
            assertThat(assertThrows(ApiException.class,()->service.getOrCreate("7",99L,"existing-session",7L,null,null)).getStatus().value()).isEqualTo(401);
            com.skillforge.common.context.TenantContext.setTenantId("8");
            assertThat(assertThrows(ApiException.class,()->service.getOrCreate("7",99L,"existing-session",7L,null,null)).getStatus().value()).isEqualTo(403);
            com.skillforge.common.context.TenantContext.setTenantId("7");
            org.mockito.Mockito.doReturn(new com.skillforge.core.foundation.access.FoundationAccess.Subject(false,"8","9")).when(foundation).currentSubject();
            assertThat(assertThrows(ApiException.class,()->service.getOrCreate("7",99L,"existing-session",7L,null,null)).getStatus().value()).isEqualTo(403);
            org.mockito.Mockito.doReturn(new com.skillforge.core.foundation.access.FoundationAccess.Subject(true,null,"9")).when(foundation).currentSubject();
            assertThat(assertThrows(ApiException.class,()->service.getOrCreate("7",99L,"existing-session",7L,null,null)).getStatus().value()).isEqualTo(403);
            org.mockito.Mockito.verifyNoInteractions(mapper,organization,provider,accessMapper);
            org.mockito.Mockito.verify(foundation,org.mockito.Mockito.never()).snapshot(any());
            org.mockito.Mockito.verify(foundation,org.mockito.Mockito.never()).requireSession(any(),any(),any());
        }finally{if(previous==null)com.skillforge.common.context.TenantContext.clear();else com.skillforge.common.context.TenantContext.setTenantId(previous);}
    }

    @Test
    void sameExternalRoomCreatesSeparateAgentInstancesAndFrozenBusinessSource() {
        externalIdentity();
        var mapper=mock(SessionMapper.class);when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.ownsAgent(org.mockito.ArgumentMatchers.eq(7L),any())).thenReturn(true);
        var service=new SessionService(mapper,settings(),mock(com.skillforge.core.chat.access.SessionAccessPolicy.class));
        var first=service.getOrCreateOpenPlatform("7","crm","user","room",null,99L,7L,"store");
        var second=service.getOrCreateOpenPlatform("7","crm","user","room",null,99L,8L,"store");
        assertThat(first.getSessionId()).isNotEqualTo(second.getSessionId());
        assertThat(first.getOriginKind()).isEqualTo("external");assertThat(first.getActorRef()).isEqualTo("service_principal:sp-test");assertThat(first.getBusinessScopeId()).isEqualTo("store");
        verify(mapper).lockOpenPlatform("7:crm:room:7");verify(mapper).lockOpenPlatform("7:crm:room:8");
    }

    @Test
    void publicOwnershipRejectsAnotherIntegration() {
        var mapper=mock(SessionMapper.class);var existing=new SessionDO();existing.setIntegrationId("crm");
        existing.setScopeType("open-platform:crm");when(mapper.selectOne(any())).thenReturn(existing);
        var service=new SessionService(mapper,settings(),mock(com.skillforge.core.chat.access.SessionAccessPolicy.class));
        assertThat(service.requireOpenPlatform("7","s","crm")).isEqualTo(SessionService.view(existing));
        assertThrows(ApiException.class,()->service.requireOpenPlatform("7","s","other"));
    }

    @Test
    void registeredConversationCannotChangeBusinessSourceOnResume() {
        externalIdentity();
        var mapper=mock(SessionMapper.class);var existing=new SessionDO();existing.setAgentId(7L);existing.setEndUserId(99L);
        existing.setIntegrationId("crm");existing.setBusinessScopeId("store");
        existing.setMetadata(Map.of("external_user_ref","user"));when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.ownsAgent(7L,7L)).thenReturn(true);
        var service=new SessionService(mapper,settings(),mock(com.skillforge.core.chat.access.SessionAccessPolicy.class));
        assertThrows(ApiException.class,()->service.getOrCreateOpenPlatform("7","crm","user","room",null,99L,7L,"other"));
        org.mockito.Mockito.verify(mapper,org.mockito.Mockito.never()).updateById(any(SessionDO.class));
    }

    @Test
    void openPlatformSessionFreezesConversationIdentityAndMetadata() {
        externalIdentity();
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.ownsAgent(7L,7L)).thenReturn(true);
        SessionService service = new SessionService(mapper, settings(), mock(com.skillforge.core.chat.access.SessionAccessPolicy.class));

        SessionView session = service.getOrCreateOpenPlatform(
                "7", "crm", "user-1", "conversation-1", null, 99L, 7L);

        assertThat(session.getScopeType()).isEqualTo("open-platform:crm");
        assertThat(session.getScopeId()).isEqualTo("conversation-1");
        assertThat(session.getEndUserId()).isEqualTo(99L);
        assertThat(session.getMetadata()).containsEntry("external_user_ref", "user-1");
        verify(mapper).insert(org.mockito.ArgumentMatchers.argThat((SessionDO row) -> SessionService.view(row).equals(session)));
        verify(mapper, never()).updateById(any(SessionDO.class));
        assertThat(session.getOriginKind()).isEqualTo("external");
        assertThat(session.getIntegrationId()).isEqualTo("crm");
        assertThat(session.getResponsibilityRef()).isEqualTo("tenant:7");
    }

    @Test
    void existingOpenPlatformSessionRejectsDifferentMappedUserOrAgent() {
        externalIdentity();
        SessionMapper mapper = mock(SessionMapper.class);
        when(mapper.ownsAgent(org.mockito.ArgumentMatchers.eq(7L),any())).thenReturn(true);
        SessionDO existing = new SessionDO();
        existing.setEndUserId(99L);
        existing.setAgentId(7L);
        existing.setMetadata(Map.of("external_user_ref", "user-1"));
        when(mapper.selectOne(any())).thenReturn(existing);
        SessionService service = new SessionService(mapper, settings(), mock(com.skillforge.core.chat.access.SessionAccessPolicy.class));

        assertThrows(ApiException.class, () -> service.getOrCreateOpenPlatform(
                "7", "crm", "user-2", "conversation-1", null, 99L, 7L));
        assertThrows(ApiException.class, () -> service.getOrCreateOpenPlatform(
                "7", "crm", "user-1", "conversation-1", null, 100L, 7L));
        assertThrows(ApiException.class, () -> service.getOrCreateOpenPlatform(
                "7", "crm", "user-1", "conversation-1", null, 99L, 8L));
    }

    @Test
    void openPlatformRequiresExternalIdentity() {
        SessionService service = new SessionService(mock(SessionMapper.class), settings(), mock(com.skillforge.core.chat.access.SessionAccessPolicy.class));

        assertThrows(IllegalArgumentException.class, () -> service.getOrCreateOpenPlatform(
                "7", "", "user-1", "conversation-1", null, 99L, 7L));
    }

    @Test
    void closeOpenPlatformSessionIsIdempotentAndStoresBoundedReason() {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionDO session = new SessionDO();
        session.setSessionId("s-1");
        session.setScopeType("open-platform:crm");session.setIntegrationId("crm");
        session.setMetadata(Map.of("external_user_ref", "user-1"));
        when(mapper.selectOne(any())).thenReturn(session);
        SessionService service = new SessionService(mapper, settings(), mock(com.skillforge.core.chat.access.SessionAccessPolicy.class));

        SessionView closed = service.closeOpenPlatform("7", "s-1", "crm", "x".repeat(300));
        SessionView replay = service.closeOpenPlatform("7", "s-1", "crm", "other");

        assertThat(closed).isEqualTo(replay);
        assertThat(service.isOpenPlatformClosed(closed)).isTrue();
        assertThat(String.valueOf(closed.getMetadata().get("open_platform_close_reason"))).hasSize(256);
        verify(mapper).updateById(session);
    }

    @Test
    void closeOpenPlatformRejectsOtherIntegration() {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionDO session = new SessionDO();
        session.setSessionId("s-1");
        session.setScopeType("open-platform:crm");session.setIntegrationId("crm");
        session.setMetadata(Map.of());
        when(mapper.selectOne(any())).thenReturn(session);
        SessionService service = new SessionService(mapper, settings(), mock(com.skillforge.core.chat.access.SessionAccessPolicy.class));

        assertThrows(ApiException.class,
                () -> service.closeOpenPlatform("7", "s-1", "other", "reason"));
    }

    @Test
    void closeForTenantIsIdempotentAndMarksSessionClosed() {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionDO session = new SessionDO();
        session.setSessionId("s-1");
        session.setMetadata(Map.of());
        when(mapper.selectOne(any())).thenReturn(session);
        SessionService service = new SessionService(mapper, settings(), mock(com.skillforge.core.chat.access.SessionAccessPolicy.class));

        service.closeForTenant("7", "s-1", "staging smoke cleanup");
        service.closeForTenant("7", "s-1", "replay");

        assertThat(service.isOpenPlatformClosed(SessionService.view(session))).isTrue();
        assertThat(session.getMetadata()).containsEntry("close_reason", "staging smoke cleanup");
        verify(mapper).updateById(session);
    }

    @Test
    void teamSessionFreezesTargetAcrossContinuation() {
        SessionMapper mapper = mock(SessionMapper.class);
        SessionDO existing = new SessionDO();
        existing.setEndUserId(99L);
        existing.setAgentId(null);
        existing.setTeamId("team-a");
        when(mapper.selectOne(any())).thenReturn(existing);
        SessionService service = new SessionService(mapper, settings(), mock(com.skillforge.core.chat.access.SessionAccessPolicy.class));

        org.junit.jupiter.api.Assertions.assertEquals(SessionService.view(existing), service.getOrCreateTeam(
                "7", 99L, "session-1", "team-a", null, null));
        assertThrows(ApiException.class, () -> service.getOrCreateTeam(
                "7", 99L, "session-1", "team-b", null, null));
        assertThrows(ApiException.class, () -> service.getOrCreate(
                "7", 99L, "session-1", 10L, null, null));
    }

    /** 群身份不伪造个人owner或固定Agent，且关闭与个人入口相互隔离。 */
    @Test void groupSessionUsesOnlyItsBindingAndCannotEnterPersonalSessionAccess() {
        com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity(
                "sp-test", "7", "crm", 1, java.util.Set.of("groups.write")));
        var mapper = mock(SessionMapper.class);
        var policy = mock(com.skillforge.core.chat.access.SessionAccessPolicy.class);
        var service = new SessionService(mapper, settings(), policy);
        var group = service.createGroup("7", "crm", "store", "31");
        assertThat(group.getGroupBindingId()).isEqualTo(31L);
        assertThat(group.getEndUserId()).isNull();
        assertThat(group.getAgentId()).isNull();
        assertThat(group.getTeamId()).isNull();
        assertThat(group.getActorRef()).isEqualTo("service_principal:sp-test");
        var saved = org.mockito.ArgumentCaptor.forClass(SessionDO.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getExpiredAt()).isNull();
        org.mockito.Mockito.verifyNoInteractions(policy);
        when(mapper.selectOne(any())).thenReturn(saved.getValue());
        assertThrows(ApiException.class, () -> service.requireOpenPlatform("7", group.getSessionId(), "crm"));
        assertThrows(ApiException.class, () -> service.closeOpenPlatform("7", group.getSessionId(), "crm", "close"));
        assertThrows(ApiException.class, () -> service.closeGroup("7", "crm", "32", group.getSessionId()));
        service.closeGroup("7", "crm", "31", group.getSessionId());
        service.closeGroup("7", "crm", "31", group.getSessionId());
        assertThat(service.isOpenPlatformClosed(SessionService.view(saved.getValue()))).isTrue();
        verify(mapper).updateById(saved.getValue());
    }

    /** 当前SP固定域和群scope先于写入；不能走个人身份回退。 */
    @Test void groupSessionCreationRejectsMissingScopeOrDifferentExecutionDomain() {
        var mapper = mock(SessionMapper.class);
        var service = new SessionService(mapper, settings(), mock(com.skillforge.core.chat.access.SessionAccessPolicy.class));
        externalIdentity();
        assertThrows(ApiException.class, () -> service.createGroup("7", "crm", "store", "31"));
        com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity(
                "sp-test", "7", "crm", 1, java.util.Set.of("groups.write")));
        assertThrows(ApiException.class, () -> service.createGroup("7", "other", "store", "31"));
        assertThrows(ApiException.class, () -> service.createGroup("8", "crm", "store", "31"));
        assertThrows(ApiException.class, () -> service.createGroup("7", "crm", "store", "0"));
        com.skillforge.common.context.ServicePrincipalContext.clear();
        assertThrows(ApiException.class, () -> service.createGroup("7", "crm", "store", "31"));
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    /** 每例清理可信执行上下文，避免线程复用污染原生Session用例。 */
    @org.junit.jupiter.api.AfterEach
    void clearExternalIdentity() { com.skillforge.common.context.ServicePrincipalContext.clear(); }

    /** 声明真实执行身份来源，Session服务不接受无认证的外部创建。 */
    private void externalIdentity() {
        com.skillforge.common.context.ServicePrincipalContext.set(new com.skillforge.common.context.ServicePrincipalContext.Identity(
                "sp-test", "7", "crm", 1L, java.util.Set.of("sessions.write")));
    }

    private OperationalSettingsService settings() {
        OperationalSettingsService settings = mock(OperationalSettingsService.class);
        when(settings.read("memory")).thenReturn(
                new Snapshot(1, Map.of("session_ttl_minutes", 1_440L)));
        return settings;
    }
}
