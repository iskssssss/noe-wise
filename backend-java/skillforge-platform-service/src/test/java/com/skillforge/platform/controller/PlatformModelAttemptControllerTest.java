package com.skillforge.platform.controller;

import com.skillforge.platform.sessionmodelattempt.ModelAttemptDiagnosticAuthorizer;
import com.skillforge.platform.sessionmodelattempt.ModelAttemptDiagnosticScope;
import com.skillforge.platform.sessionmodelattempt.ModelAttemptDiagnosticService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformModelAttemptControllerTest {
    @Test void childDiagnosticKeepsActualSourceAndForwardsExplicitParentAuthorizationPath() throws Exception {
        var authorization = mock(ModelAttemptDiagnosticAuthorizer.class);
        var scope = mock(ModelAttemptDiagnosticScope.class);
        var diagnostics = mock(ModelAttemptDiagnosticService.class);
        when(authorization.platformAdmin()).thenReturn(scope);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new PlatformModelAttemptController(authorization, diagnostics)).build();
        mvc.perform(get("/api/v1/platform/sessions/model-attempt/detail")
                .param("tenant_id", "101").param("session_id", "delegation:child")
                .param("root_input_id", "source-root").param("attempt_id", "child-attempt")
                .param("parent_session_id", "product-session").param("parent_root_input_id", "parent-root"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(diagnostics).detail(scope, 101L, "delegation:child", "source-root", "child-attempt",
                "product-session", "parent-root");
    }
}
