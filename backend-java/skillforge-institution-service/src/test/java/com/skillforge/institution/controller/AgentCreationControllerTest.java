package com.skillforge.institution.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.skillforge.core.shared.exception.ApiException;
import com.skillforge.institution.service.AgentCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentCreationControllerTest {
    @Test void distinguishesTransactionalRejectionFromRequestIdentityConflict() {
        var service = mock(AgentCreationService.class);
        var controller = new AgentCreationController(service);
        when(service.create(null)).thenThrow(new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "无效"))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "AGENT_CREATE_IDENTITY_CONFLICT", "冲突", false));
        assertEquals("AGENT_CREATE_REJECTED", assertThrows(ApiException.class, () -> controller.create(null)).getCode());
        assertEquals("AGENT_CREATE_IDENTITY_CONFLICT", assertThrows(ApiException.class, () -> controller.create(null)).getCode());
    }
}
