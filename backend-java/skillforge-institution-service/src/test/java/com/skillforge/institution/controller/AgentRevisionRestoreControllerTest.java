package com.skillforge.institution.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.skillforge.institution.service.AgentRevisionRestoreService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentRevisionRestoreControllerTest {
    @Test void missingReceiptIs204AndKnownReceiptUsesExactOriginalTarget() throws Exception {
        var service=mock(AgentRevisionRestoreService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AgentRevisionRestoreController(service)).build();
        var id=UUID.randomUUID();
        mvc.perform(get("/api/v1/agents/restore-revision/result").param("agentId","9007199254740993").param("requestId",id.toString()))
                .andExpect(status().isNoContent());
        when(service.result("9007199254740993",id)).thenReturn(new AgentRevisionRestoreService.Result(id,"9007199254740993",2));
        mvc.perform(get("/api/v1/agents/restore-revision/result").param("agentId","9007199254740993").param("requestId",id.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.request_id").value(id.toString()))
                .andExpect(jsonPath("$.data.agent_id").value("9007199254740993"))
                .andExpect(jsonPath("$.data.revision").value(2));
        verify(service,never()).restore(anyString(),anyInt(),any());
    }
    @Test void postRequiresRequestIdentityAndReturnsReceiptRatherThanCurrentAgent() throws Exception {
        var service=mock(AgentRevisionRestoreService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new AgentRevisionRestoreController(service)).build();
        var id=UUID.randomUUID();
        when(service.restore("9",2,id)).thenReturn(new AgentRevisionRestoreService.Result(id,"9",2));
        mvc.perform(post("/api/v1/agents/restore-revision").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agent_id\":\"9\",\"revision\":2,\"request_id\":\""+id+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.system_prompt").doesNotExist());
        mvc.perform(post("/api/v1/agents/restore-revision").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agent_id\":\"9\",\"revision\":2}"))
                .andExpect(status().isBadRequest());
        verify(service).restore("9",2,id);
    }
}
