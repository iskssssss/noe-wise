package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.AccessRows;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.FoundationAccessMapper;
import com.skillforge.core.foundation.access.FoundationOrganizationService;
import com.skillforge.core.foundation.access.FoundationPermission;
import com.skillforge.core.foundation.access.FoundationPermissionCatalogue;
import com.skillforge.core.foundation.access.FoundationPermissionContributor;
import com.skillforge.core.chat.persistence.SessionMapper;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.session.SessionOutboxEntry;
import com.skillforge.core.chat.session.SessionMailboxService;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.core.chat.access.SessionAccessMapper;
import com.skillforge.core.chat.access.SessionAccessPolicy;
import com.skillforge.core.foundation.settings.OperationalSettingsService;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 订阅节流仅削减空闲授权；每页通过真实成员/角色/所有权链后才发送，事务不跨网络发送。 */
class ChatSubscriptionPollingTest {
    /** 订阅夹具不能向其他测试泄漏认证体系或租户。 */
    @AfterEach void clearContext() { TenantContext.clear(); ChannelContext.clear(); ServicePrincipalContext.clear(); }

    @Test void idleTicksReadCandidatesWithoutRepeatingAuthorizationUntilFiveSeconds() {
        var fixture = new Fixture();
        fixture.polling.authorized(() -> { });
        for (int tick = 1; tick < 20; tick++) {
            fixture.clock.set(tick * 250_000_000L);
            assertEquals(17, fixture.polling.poll(17, output -> fail("空闲页不得发送业务内容")));
        }
        fixture.verifyCurrentReads(1);
        verify(fixture.sessionMapper, times(1)).selectOne(any());
        verify(fixture.mailbox, times(19)).listOutputsAfterSequence("7", "session-1", 17, 100);
        fixture.clock.set(5_000_000_000L);
        fixture.polling.poll(17, output -> fail("空闲页不得发送业务内容"));
        fixture.verifyCurrentReads(2);
    }

    @Test void revokedBusinessPageIsRejectedBeforeAnyOutputEvenInsideIdleInterval() {
        var fixture = new Fixture();
        fixture.polling.authorized(() -> { });
        fixture.clock.set(250_000_000L);
        fixture.businessPage();
        when(fixture.accessMapper.memberPermissions("7", 11L)).thenReturn(List.of());
        var sends = new AtomicInteger();
        assertThrows(ApiException.class, () -> fixture.polling.poll(17, ignored -> sends.incrementAndGet()));
        assertEquals(0, sends.get());
        fixture.verifyCurrentReads(2);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("7", TenantContext.getTenantId());
    }

    @Test void everyNonemptyPageReadsOneCurrentSubjectAndClosesAuthorizationTransactionBeforeSend() {
        var fixture = new Fixture();
        fixture.polling.authorized(() -> { });
        var output = fixture.businessPage();
        var sends = new AtomicInteger();
        assertEquals(18, fixture.polling.poll(17, entry -> {
            assertSame(output, entry);
            assertEquals("7", TenantContext.getTenantId());
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(2, fixture.transactions.commits);
            sends.incrementAndGet();
        }));
        assertEquals(1, sends.get());
        fixture.verifyCurrentReads(2);
        verify(fixture.accessMapper, never()).scopeVersion(anyString());
    }

    @Test void nextPageUsesReducedDataScopeAndDisabledSubjectWithoutReleasingContents() {
        var fixture = new Fixture();
        var all = new AccessRows.Role(); all.setCode("reader"); all.setDataScope("all");
        when(fixture.accessMapper.memberRoles("7", 11L)).thenReturn(List.of(all));
        fixture.ownership.setOwnerMemberId(22L);
        fixture.polling.authorized(() -> { });
        fixture.businessPage();
        var own = new AccessRows.Role(); own.setCode("reader"); own.setDataScope("own");
        when(fixture.accessMapper.memberRoles("7", 11L)).thenReturn(List.of(own));
        assertThrows(ApiException.class, () -> fixture.polling.poll(17, ignored -> fail("范围缩小后不得发送")));
        fixture.verifyCurrentReads(2);
        fixture.member.setStatus("disabled");
        assertThrows(ApiException.class, () -> fixture.polling.poll(17, ignored -> fail("账号停用后不得发送")));
        verify(fixture.accessMapper, times(3)).member("7", 11L);
        verify(fixture.accessMapper, times(2)).memberRoles("7", 11L);
    }

    @Test void idleRevocationIsDetectedAtFiveSecondsAndNeverReleasesAnOutput() {
        var fixture = new Fixture();
        fixture.polling.authorized(() -> { });
        when(fixture.accessMapper.memberPermissions("7", 11L)).thenReturn(List.of());
        fixture.clock.set(4_999_999_999L);
        assertEquals(17, fixture.polling.poll(17, output -> fail("no business page")));
        fixture.clock.set(5_000_000_000L);
        assertThrows(ApiException.class, () -> fixture.polling.poll(17, output -> fail("no business page")));
        fixture.verifyCurrentReads(2);
    }

    @Test void explicitSubscriptionSubjectDoesNotReplacePriorIdentityAndFailureRestoresScope() {
        var fixture = new Fixture();
        var prior = new FoundationAccess.Subject(false, "9", "99");
        when(fixture.accessMapper.member("9", 99L)).thenReturn(fixture.member);
        when(fixture.accessMapper.memberRoles("9", 99L)).thenReturn(List.of());
        when(fixture.accessMapper.memberPermissions("9", 99L)).thenReturn(List.of());
        fixture.foundation.withSubject(prior, () -> {
            assertEquals("9", TenantContext.getTenantId());
            assertThrows(IllegalStateException.class, () -> fixture.polling.authorized(() -> {
                assertEquals(prior, fixture.foundation.currentSubject());
                assertEquals("9", TenantContext.getTenantId());
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                throw new IllegalStateException("transport closed");
            }));
            assertEquals(prior, fixture.foundation.currentSubject());
            assertEquals("9", TenantContext.getTenantId());
            return null;
        });
        assertEquals("7", TenantContext.getTenantId());
        assertThrows(IllegalArgumentException.class, () -> new ChatSubscriptionPolling("8", "session-1",
                Fixture.SUBJECT, fixture.sessions, fixture.mailbox, fixture.clock::get));
        assertThrows(IllegalArgumentException.class, () -> new ChatSubscriptionPolling("7", "session-1",
                new FoundationAccess.Subject(true, null, "11"), fixture.sessions, fixture.mailbox, fixture.clock::get));
    }

    @Test void channelAndPrincipalSubscriptionsKeepTheirExistingCurrentSessionAuthorization() {
        var sessions = mock(SessionService.class);
        var mailbox = mock(SessionMailboxService.class);
        var polling = new ChatSubscriptionPolling("7", "session-1", null, sessions, mailbox, () -> 0L);
        var sends = new AtomicInteger();
        polling.authorized(sends::incrementAndGet);
        assertEquals(1, sends.get());
        verify(sessions).require("7", "session-1");
        verify(sessions, never()).requireCurrentRead(anyString(), anyString(), any());
    }

    /** Spring 真实事务代理验证每次授权独立提交及只读隔离定义；不接触数据库。 */
    private static final class AuthorizationTransactions extends AbstractPlatformTransactionManager {
        int commits;
        private AuthorizationTransaction active;
        @Override protected Object doGetTransaction() { return active == null ? new AuthorizationTransaction() : active; }
        @Override protected boolean isExistingTransaction(Object transaction) { return transaction == active; }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {
            if (definition.getName() == null) {
                // withSubject 的前置 snapshot 使用独立短读模板，不能误当作 SSE 授权事务。
                assertEquals(TransactionDefinition.ISOLATION_DEFAULT, definition.getIsolationLevel());
            } else {
                assertEquals(SessionService.class.getName() + ".requireCurrentRead", definition.getName());
                assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, definition.getIsolationLevel());
            }
            assertTrue(definition.isReadOnly());
            active = (AuthorizationTransaction) transaction;
        }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
        @Override protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((AuthorizationTransaction) status.getTransaction()).rollbackOnly = true;
        }
        @Override protected void doCleanupAfterCompletion(Object transaction) { active = null; }

        /** 内层 REQUIRED 授权拒绝标记原事务，异常类型与外层回滚遵循真实 Spring 参与语义。 */
        private static final class AuthorizationTransaction implements SmartTransactionObject {
            private boolean rollbackOnly;
            @Override public boolean isRollbackOnly() { return rollbackOnly; }
            @Override public void flush() { }
        }
    }

    /** 真实 Session→Policy→Foundation 链配合明确数据库行；每页不建立权限缓存。 */
    private static final class Fixture {
        static final FoundationAccess.Subject SUBJECT = new FoundationAccess.Subject(false, "7", "11");
        final FoundationAccessMapper accessMapper = mock(FoundationAccessMapper.class);
        final SessionMapper sessionMapper = mock(SessionMapper.class);
        final AccessRows.Member member = new AccessRows.Member();
        final AccessRows.Resource ownership = new AccessRows.Resource();
        final FoundationAccess foundation;
        final SessionService sessions;
        final AuthorizationTransactions transactions = new AuthorizationTransactions();
        final SessionMailboxService mailbox = mock(SessionMailboxService.class);
        final AtomicLong clock = new AtomicLong();
        final ChatSubscriptionPolling polling;
        @SuppressWarnings("unchecked") Fixture() {
            TenantContext.setTenantId("7");
            member.setId(11L); member.setStatus("active");
            ownership.setOwnerMemberId(11L);
            when(accessMapper.member("7", 11L)).thenAnswer(ignored -> {
                assertEquals("7", TenantContext.getTenantId());
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                return member;
            });
            when(accessMapper.memberRoles("7", 11L)).thenReturn(List.of());
            when(accessMapper.memberPermissions("7", 11L)).thenReturn(List.of("foundation.sessions.read"));
            when(accessMapper.sessionPrimaryId("7", "session-1")).thenReturn(81L);
            when(accessMapper.resource("7", "session", "81")).thenReturn(ownership);
            FoundationPermissionContributor contributor = () -> List.of(new FoundationPermission(
                    "foundation.sessions.read", "会话读取", FoundationPermission.Audience.TENANT));
            foundation = new FoundationAccess(accessMapper, new FoundationPermissionCatalogue(List.of(contributor)), transactions);
            var policy = new SessionAccessPolicy(foundation, mock(FoundationOrganizationService.class),
                    mock(com.skillforge.core.foundation.integration.OpenPlatformAccessService.class), mock(SessionAccessMapper.class),
                    mock(com.skillforge.core.foundation.integration.InstitutionIntegrationService.class), org.mockito.Mockito.mock(com.skillforge.core.foundation.workflowtest.WorkflowTestIsolation.class));
            var session = new SessionDO(); session.setTenantId(7L); session.setSessionId("session-1"); session.setEndUserId(99L);
            when(sessionMapper.selectOne(any())).thenAnswer(ignored -> {
                assertEquals("7", TenantContext.getTenantId());
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return session;
            });
            var target = new SessionService(sessionMapper, mock(OperationalSettingsService.class), policy);
            var proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
            sessions = (SessionService) proxy.getProxy();
            when(mailbox.listOutputsAfterSequence("7", "session-1", 17, 100)).thenReturn(List.of());
            polling = new ChatSubscriptionPolling("7", "session-1", SUBJECT, sessions, mailbox, clock::get);
        }
        /** 每次公开发送授权仅重读一次成员、角色和动作权限。 */
        void verifyCurrentReads(int count) {
            verify(accessMapper, times(count)).member("7", 11L);
            verify(accessMapper, times(count)).memberRoles("7", 11L);
            verify(accessMapper, times(count)).memberPermissions("7", 11L);
        }
        /** 固定范围输出页用于撤权前后的真实发送边界负控。 */
        SessionOutboxEntry businessPage() {
            var outputBuilder = SessionOutboxEntry.builder(); outputBuilder.sessionSequence(18L);
            var output = outputBuilder.build();
            when(mailbox.listOutputsAfterSequence("7", "session-1", 17, 100)).thenReturn(List.of(output));
            return output;
        }
    }
}
