package com.skillforge.platform.foundation.messaging.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.skillforge.core.foundation.security.SecurityRequests;
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

/** 平台消息和共享安全输入发布相同的必填可空契约。 */
class MessagingRetentionRequestTest {
    private final ObjectMapper json=new ObjectMapper();

    /** 平台真实Controller绑定与机构同契约；缺字段拒绝必须发生在宿主/域副作用之前。 */
    @Test void httpBindingRejectsOmittedOrInvalidDaysBeforeCallingService() throws Exception {
        var service=mock(NotificationService.class);
        var http=MockMvcBuilders.standaloneSetup(new MessagingController(service)).build();
        String endpoint="/api/v1/admin/foundation/messaging/retention";
        for(String body:new String[]{"{}","{\"days\":0}","{\"days\":36501}"})
            http.perform(put(endpoint).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        http.perform(put(endpoint).contentType(MediaType.APPLICATION_JSON).content("{\"days\":null}")).andExpect(status().isOk());
        verify(service).requireHost(true); verify(service).setHistoryRetention(null);
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

    @Test void openApiRequiresNullableDaysInMessagingAndSecurity() {
        for(Class<?> type:new Class<?>[]{MessagingRequests.Retention.class,SecurityRequests.SessionRetention.class}) {
            var schema=ModelConverters.getInstance().resolveAsResolvedSchema(new AnnotatedType(type)).schema;
            assertTrue(schema.getRequired().contains("days"));
            assertEquals(Boolean.TRUE,((io.swagger.v3.oas.models.media.Schema<?>)schema.getProperties().get("days")).getNullable());
        }
    }
}
