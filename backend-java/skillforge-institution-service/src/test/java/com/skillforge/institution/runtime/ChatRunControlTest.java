package com.skillforge.institution.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.skillforge.common.context.TenantContext;
import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.core.chat.session.SessionService;
import com.skillforge.institution.controller.ChatRunController;
import org.junit.jupiter.api.Test;

/** 每个页面控制都先校当前租户产品Session；执行只经唯一typed controls。 */
class ChatRunControlTest {
    @Test void controllerChecksTenantOwnershipBeforeEnteringAnyControl() {
        var sessions=mock(SessionService.class);var domain=mock(AgentRuntimeInboundGateway.class);var controls=mock(AgentRuntimeRunControls.class);
        var controller=new ChatRunController(sessions,domain,controls);TenantContext.setTenantId("2");
        try {
            when(sessions.require("2","session-1")).thenThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"不存在"));
            when(sessions.requireWritable("2","session-1")).thenThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"不存在"));
            assertThrows(ApiException.class,()->controller.cancel(new ChatRunController.CancelRequest("session-1","run-1")));
            assertThrows(ApiException.class,()->controller.status("session-1","run-1"));
            assertThrows(ApiException.class,()->controller.collaboration("session-1"));
            assertThrows(ApiException.class,()->controller.selectPlan(new ChatRunController.PlanModeRequest("session-1","command",true)));
            verifyNoInteractions(domain,controls);
        }finally{TenantContext.clear();}
    }
}
