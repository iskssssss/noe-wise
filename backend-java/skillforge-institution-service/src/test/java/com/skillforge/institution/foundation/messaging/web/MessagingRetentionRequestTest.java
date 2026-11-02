package com.skillforge.institution.foundation.messaging.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import com.skillforge.core.foundation.messaging.application.NotificationService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

/** 机构消息输入允许显式关闭，但不把缺失days当作关闭。 */
class MessagingRetentionRequestTest {
    private final ObjectMapper json=new ObjectMapper();

    /** HTTP遗漏和非法天数在域入口前拒绝；显式null沿机构宿主边界传递，认证/RLS另由组合测试验证。 */
    @Test void httpBindingRejectsOmittedOrInvalidDaysBeforeCallingService() throws Exception {
        var service=mock(NotificationService.class);
        var http=MockMvcBuilders.standaloneSetup(new MessagingController(service)).build();
        String endpoint="/api/v1/tenant/foundation/messaging/retention";
        for(String body:new String[]{"{}","{\"days\":0}","{\"days\":36501}"})
            http.perform(put(endpoint).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        http.perform(put(endpoint).contentType(MediaType.APPLICATION_JSON).content("{\"days\":null}")).andExpect(status().isOk());
        verify(service).requireHost(false); verify(service).setHistoryRetention(null);
    }

    @Test void omittedDaysIsRejectedByDefaultObjectMapper() {
        var failure=assertThrows(MismatchedInputException.class,
                ()->json.readValue("{}",MessagingRequests.Retention.class));
        assertTrue(failure.getMessage().contains("days"));
    }

    @Test void explicitNullCanDisableRetention() throws Exception {
        var request=json.readValue("{\"days\":null}",MessagingRequests.Retention.class);
        assertNull(request.days());
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test void positiveDaysAreAcceptedAndOutOfRangeDaysAreRejected() throws Exception {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            for(int days:new int[]{1,30,36500}) {
                var request=json.readValue("{\"days\":"+days+"}",MessagingRequests.Retention.class);
                assertEquals(days,request.days());
                assertTrue(validator.validate(request).isEmpty());
            }
            for(int days:new int[]{0,-1,36501}) {
                var request=json.readValue("{\"days\":"+days+"}",MessagingRequests.Retention.class);
                var violations=validator.validate(request);
                assertEquals(1,violations.size());
                assertEquals("days",violations.iterator().next().getPropertyPath().toString());
            }
        }
    }

    @Test void openApiRequiresNullableDays() {
        var schema=ModelConverters.getInstance().resolveAsResolvedSchema(
                new AnnotatedType(MessagingRequests.Retention.class)).schema;
        assertTrue(schema.getRequired().contains("days"));
        assertEquals(Boolean.TRUE,((io.swagger.v3.oas.models.media.Schema<?>)schema.getProperties().get("days")).getNullable());
    }
}
