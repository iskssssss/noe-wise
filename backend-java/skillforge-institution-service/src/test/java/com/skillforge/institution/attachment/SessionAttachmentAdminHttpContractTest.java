package com.skillforge.institution.attachment;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillforge.common.context.ChannelContext;
import com.skillforge.common.context.ServicePrincipalContext;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.institution.exception.GlobalExceptionHandler;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 租户累计观察复核后台身份、权限和all范围，不能靠会话读取授权扩大范围。 */
class SessionAttachmentAdminHttpContractTest {
    private static final String PATH = "/api/v1/tenant/storage/chat/capacity";
    private final FoundationAccess access = mock(FoundationAccess.class);
    private final SessionAttachmentBodyStore bodies = mock(SessionAttachmentBodyStore.class);
    private final FoundationAccess.Subject actor = new FoundationAccess.Subject(false, "7", "11");
    private MockMvc mvc;
    @BeforeEach void setup() {
        TenantContext.setTenantId("7");
        when(access.currentSubject()).thenReturn(actor);
        when(access.dataScope(actor)).thenReturn(new FoundationAccess.DataScope(true, "11", Set.of()));
        var capacity = new SessionAttachmentCapacityService(access, bodies);
        mvc = MockMvcBuilders.standaloneSetup(new SessionAttachmentAdminController(capacity))
                .setControllerAdvice(new GlobalExceptionHandler(new ObjectMapper()))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())).build();
    }
    @AfterEach void clearContext() { TenantContext.clear(); ChannelContext.clear(); ServicePrincipalContext.clear(); }
    @Test void authorizedAllScopeReadsOnlyTrustedTenantAndPreservesBigintPrecision() throws Exception {
        when(bodies.inspectTenant("7")).thenReturn(new SessionAttachmentBodyStore.TenantStorageBudget("9007199254740993", "0", false));
        mvc.perform(get(PATH).param("tenant_id", "17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenant_bytes").value("9007199254740993"))
                .andExpect(jsonPath("$.data.tenant_limit_bytes").value("0"))
                .andExpect(jsonPath("$.data.tenant_configured").value(false));
        verify(access).require(actor, "foundation.entitlements.read");
        verify(bodies).inspectTenant("7"); verify(bodies, never()).inspectTenant("17");
    }
    @Test void partialDataScopeAndMissingQuotaPermissionShortCircuitBeforeAggregateQuery() throws Exception {
        when(access.dataScope(actor)).thenReturn(new FoundationAccess.DataScope(false, "11", Set.of("12")));
        mvc.perform(get(PATH)).andExpect(status().isForbidden());
        when(access.dataScope(actor)).thenReturn(new FoundationAccess.DataScope(true, "11", Set.of()));
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "没有操作权限")).when(access).require(actor, "foundation.entitlements.read");
        mvc.perform(get(PATH)).andExpect(status().isForbidden());
        verifyNoInteractions(bodies);
    }
    @Test void platformOrMismatchedTenantNeverReadsAggregate() throws Exception {
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "11"));
        mvc.perform(get(PATH)).andExpect(status().isForbidden());
        when(access.currentSubject()).thenReturn(actor); TenantContext.setTenantId("17");
        mvc.perform(get(PATH)).andExpect(status().isForbidden());
        verifyNoInteractions(bodies);
    }
    @Test void endUserChannelAndServicePrincipalCannotBorrowAdminCapacityEntry() throws Exception {
        ChannelContext.setChannelId("42"); mvc.perform(get(PATH)).andExpect(status().isForbidden());
        ChannelContext.clear();
        ServicePrincipalContext.set(new ServicePrincipalContext.Identity("integration", "7", "app", 1, Set.of("sessions.read")));
        mvc.perform(get(PATH)).andExpect(status().isForbidden());
        verifyNoInteractions(bodies); verify(access, never()).currentSubject();
    }
}
