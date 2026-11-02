package com.skillforge.core.chat.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.chat.persistence.SessionDO;
import com.skillforge.core.chat.persistence.SessionMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 扫描游标属于 chat；无效行不能堵住轮转，游标也不能借到其他租户。 */
class SessionOutboxRecoveryScanTest {
    private final SessionMapper sessions = mock(SessionMapper.class);
    private final SessionOutboxRecoveryScan scan = new SessionOutboxRecoveryScan(sessions);

    @BeforeAll static void mapperMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SessionDO.class);
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void invalidOwnershipStillAdvancesStableCursorAndEmptyTailRotatesToStart() {
        TenantContext.setTenantId("7");
        var valid = session(10, "valid");
        var invalid = session(11, "invalid"); invalid.setEndUserId(null);
        var foreign = session(12, "foreign"); foreign.setTenantId(8L);
        when(sessions.selectList(any())).thenReturn(List.of(valid, invalid, foreign), List.of(), List.of(valid));

        var first = scan.scan("7", null, 10);
        assertEquals(List.of("valid"), first.sessions().stream().map(SessionView::getSessionId).toList());
        valid.setSessionId("changed-after-read");
        assertEquals("valid", first.sessions().getFirst().getSessionId());
        assertThrows(UnsupportedOperationException.class, () -> first.sessions().clear());
        var next = scan.scan("7", first.nextCursor(), 10);
        assertEquals("changed-after-read", next.sessions().getFirst().getSessionId());

        org.mockito.ArgumentCaptor<LambdaQueryWrapper<SessionDO>> queries = org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(sessions, times(3)).selectList(queries.capture());
        for (var query : queries.getAllValues()) {
            assertTrue(query.getSqlSegment().contains("ORDER BY id ASC"));
            assertTrue(query.getSqlSegment().contains("LIMIT 10"));
            assertTrue(query.getParamNameValuePairs().containsValue(7L));
        }
        assertTrue(queries.getAllValues().get(1).getParamNameValuePairs().containsValue(12L));
        assertTrue(queries.getAllValues().get(2).getParamNameValuePairs().containsValue(0L));
    }

    @Test void cursorCannotCrossTenantOrRunWithoutTheBoundTenant() {
        TenantContext.setTenantId("7");
        when(sessions.selectList(any())).thenReturn(List.of(session(10, "session")));
        var first = scan.scan("7", null, 10);
        TenantContext.setTenantId("8");
        assertThrows(SecurityException.class, () -> scan.scan("8", first.nextCursor(), 10));
        assertThrows(SecurityException.class, () -> scan.scan("7", null, 10));
        verify(sessions).selectList(any());
    }

    @Test void groupOwnershipPreservesItsRealScopeWithoutAPersonalOwner() {
        TenantContext.setTenantId("7");
        var group = session(31, "group-session"); group.setEndUserId(null); group.setAgentId(null);
        group.setGroupBindingId(41L); group.setIntegrationId("integration"); group.setBusinessScopeId("scope");
        group.setScopeType("group:integration"); group.setScopeId("41");
        var mismatched = session(32, "mismatched"); mismatched.setGroupBindingId(41L);
        when(sessions.selectList(any())).thenReturn(List.of(group, mismatched));
        var result = scan.scan("7", null, 10);
        assertEquals(List.of("group-session"), result.sessions().stream().map(SessionView::getSessionId).toList());
        assertNull(result.sessions().getFirst().getEndUserId());
    }

    private static SessionDO session(long id, String sessionId) {
        var session = new SessionDO(); session.setId(id); session.setTenantId(7L); session.setSessionId(sessionId);
        session.setAgentId(3L); session.setEndUserId(9L); return session;
    }
}
