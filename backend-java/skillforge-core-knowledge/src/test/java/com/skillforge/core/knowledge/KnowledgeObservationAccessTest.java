package com.skillforge.core.knowledge;

import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.PlatformObservationAccess;
import com.skillforge.core.knowledge.mapper.KnowledgeObservationAccessMapper;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.runtime.api.AgentId;
import com.skillforge.runtime.api.CapabilityRef;
import com.skillforge.runtime.api.CapabilityType;
import com.skillforge.runtime.api.KnowledgeOperatorType;
import com.skillforge.runtime.api.KnowledgeSourceVersion;
import com.skillforge.runtime.api.SessionRef;
import com.skillforge.runtime.api.TenantId;
import com.skillforge.runtime.api.port.KnowledgePortal.FrozenKnowledgeTarget;
import com.skillforge.runtime.api.port.KnowledgePortal.KnowledgeHandle;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 会话读取权不替代当前知识对象读取权；平台特权也不替代来源可用性。 */
class KnowledgeObservationAccessTest {
    private static final TenantId TENANT = new TenantId("1");
    private static final AgentId AGENT = new AgentId("11");
    private static final SessionRef SESSION = new SessionRef(TENANT, "session-original");
    private static final KnowledgeSourceVersion FIRST = new KnowledgeSourceVersion("10", "v1");
    private static final KnowledgeSourceVersion SECOND = new KnowledgeSourceVersion("10", "v2");
    private static final FrozenKnowledgeTarget TARGET = new FrozenKnowledgeTarget(new KnowledgeHandle("knowledge:17"),
            new CapabilityRef(CapabilityType.KNOWLEDGE_SOURCE, "20", "30"), "10", 7,
            Set.of(FIRST, SECOND), Set.of(KnowledgeOperatorType.KEYWORD), Duration.ofSeconds(3));
    private final FoundationAccess access = mock(FoundationAccess.class);
    private final PlatformObservationAccess platformAccess = mock(PlatformObservationAccess.class);
    private final KnowledgeObservationAccessMapper mapper = mock(KnowledgeObservationAccessMapper.class);
    private final KnowledgeObservationAccess service = new KnowledgeObservationAccess(access, platformAccess, mapper);

    @BeforeEach void authorizedMapperWindow() {
        when(platformAccess.read(eq(1L), any())).thenAnswer(call ->
                call.<java.util.function.Supplier<Boolean>>getArgument(1).get());
    }

    @Test void tenantRequiresCurrentKnowledgeObjectReadAndOnlyChecksSelectedSourceVersions() {
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "1", "101"));
        when(mapper.readable("1", TARGET, List.of(FIRST, SECOND), List.of(FIRST))).thenReturn(true);
        assertDoesNotThrow(() -> service.requireRead(TENANT, AGENT, SESSION, TARGET, Set.of(FIRST), false));
        verify(access).requireResource("knowledge", "10", "read");
        verify(mapper).readable("1", TARGET, List.of(FIRST, SECOND), List.of(FIRST));
        verify(platformAccess).read(eq(1L), any());

        var denied = new ApiException(HttpStatus.FORBIDDEN, "没有资源访问权限");
        doThrow(denied).when(access).requireResource("knowledge", "10", "read");
        clearInvocations(mapper);
        assertSame(denied, assertThrows(ApiException.class,
                () -> service.requireRead(TENANT, AGENT, SESSION, TARGET, Set.of(FIRST), false)));
        verifyNoInteractions(mapper);
    }

    @Test void platformPrivilegeMustComeFromCurrentPlatformSubjectAndStillChecksSourceState() {
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "1", "101"));
        assertThrows(ApiException.class, () -> service.requireRead(TENANT, AGENT, SESSION, TARGET, Set.of(FIRST), true));
        verifyNoInteractions(mapper);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "901"));
        var rejected = assertThrows(ApiException.class,
                () -> service.requireRead(TENANT, AGENT, SESSION, TARGET, Set.of(FIRST), true));
        assertEquals("knowledge.observation.denied", rejected.getCode());
        verify(access).require("foundation.sessions.read");
        verify(access, never()).requireResource(anyString(), anyString(), anyString());
    }

    @Test void crossTenantSessionAndSourcesOutsideFrozenManifestAreRejectedBeforeDatabaseRead() {
        assertThrows(ApiException.class, () -> service.requireRead(TENANT, AGENT,
                new SessionRef(new TenantId("2"), "session-original"), TARGET, Set.of(FIRST), false));
        assertThrows(ApiException.class, () -> service.requireRead(TENANT, AGENT, SESSION, TARGET,
                Set.of(new KnowledgeSourceVersion("10", "v3")), false));
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "2", "201"));
        assertThrows(ApiException.class, () -> service.requireRead(TENANT, AGENT, SESSION, TARGET, Set.of(FIRST), false));
        verifyNoInteractions(mapper);
    }

    @Test void technicalReadFailureKeepsItsCauseInsteadOfBeingReportedAsSourceRevocation() {
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "1", "101"));
        var failure = new IllegalStateException("synthetic storage failure");
        when(mapper.readable(anyString(), any(), anyList(), anyList())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.requireRead(TENANT, AGENT, SESSION, TARGET, Set.of(FIRST), false)));
    }
}
