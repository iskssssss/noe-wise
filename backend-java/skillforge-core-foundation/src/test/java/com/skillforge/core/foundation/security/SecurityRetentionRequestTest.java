package com.skillforge.core.foundation.security;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

/** 同一JSON绑定及校验边界区分遗漏属性与管理员显式关闭。 */
class SecurityRetentionRequestTest {
    private final ObjectMapper json=new ObjectMapper();

    /** 实际Controller请求绑定必须在应用副作用前拒绝遗漏/越界，显式null仍传入管理员策略服务。 */
    @Test void httpBindingRejectsOmittedOrInvalidDaysBeforeCallingService() throws Exception {
        var security=mock(FoundationSecurityService.class);
        var http=MockMvcBuilders.standaloneSetup(new FoundationSecurityController(security)).build();
        for(String prefix:new String[]{"/api/v1/admin","/api/v1/tenant"}) {
            String endpoint=prefix+"/foundation/security/sessions/retention";
            for(String body:new String[]{"{}","{\"days\":0}","{\"days\":36501}"})
                http.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            verifyNoInteractions(security);
        }
        http.perform(post("/api/v1/admin/foundation/security/sessions/retention").contentType(MediaType.APPLICATION_JSON).content("{\"days\":null}")).andExpect(status().isOk());
        verify(security).setSessionRetention(null);
    }

    @Test void omittedDaysIsRejectedByDefaultObjectMapper() {
        var failure=assertThrows(MismatchedInputException.class,
                ()->json.readValue("{}",SecurityRequests.SessionRetention.class));
        assertTrue(failure.getMessage().contains("days"));
    }

    @Test void explicitNullCanDisableRetention() throws Exception {
        var request=json.readValue("{\"days\":null}",SecurityRequests.SessionRetention.class);
        assertNull(request.days());
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test void positiveDaysAreAcceptedAndOutOfRangeDaysAreRejected() throws Exception {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            for(int days:new int[]{1,90,36500}) {
                var request=json.readValue("{\"days\":"+days+"}",SecurityRequests.SessionRetention.class);
                assertEquals(days,request.days());
                assertTrue(validator.validate(request).isEmpty());
            }
            for(int days:new int[]{0,-1,36501}) {
                var request=json.readValue("{\"days\":"+days+"}",SecurityRequests.SessionRetention.class);
                var violations=validator.validate(request);
                assertEquals(1,violations.size());
                assertEquals("days",violations.iterator().next().getPropertyPath().toString());
            }
        }
    }
}
