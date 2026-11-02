package com.skillforge.institution.foundation.operations.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.operations.FoundationAuditService;
import com.skillforge.core.foundation.operations.FoundationRetentionService;
import com.skillforge.core.foundation.operations.FoundationOperationsStatus;
import com.skillforge.core.foundation.operations.OperationsContracts.*;
import com.skillforge.core.shared.exception.ApiException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FoundationAuditSummaryControllerTest {
    @Test void summaryRoutePreservesFiltersNineFieldsAndAudienceBoundary() throws Exception {
        var access = mock(FoundationAccess.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "42"));
        var audits = mock(FoundationAuditService.class);
        var json = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var mvc = MockMvcBuilders.standaloneSetup(new FoundationOperationsController(audits,
                mock(FoundationRetentionService.class), mock(FoundationOperationsStatus.class), access))
                .setControllerAdvice(new com.skillforge.institution.exception.GlobalExceptionHandler(json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        when(audits.summaries(any())).thenReturn(new AuditSummaryPage(137, 2, 50, List.of(new AuditSummary(
                "9007199254740993", null, "auth.login", "authentication", "failure", null, "说明", null,
                Instant.parse("2026-10-29T12:00:00Z")))));
        var result = mvc.perform(get("/api/v1/tenant/foundation/operations/audit/summaries")
                .param("action", "auth.login").param("actor_id", "42").param("category", "authentication")
                .param("outcome", "failure").param("resource_type", "account").param("trace_id", "trace")
                .param("from", "2026-10-29T12:00:00Z").param("to", "2026-10-30T12:00:00Z")
                .param("page", "2").param("page_size", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var data = json.readTree(result).path("data");
        assertEquals(137, data.path("total").intValue()); assertEquals(50, data.path("page_size").intValue());
        var row = data.path("items").get(0);
        assertEquals(9, row.size()); assertEquals("9007199254740993", row.path("id").textValue());
        assertTrue(row.path("actor_id").isNull()); assertFalse(row.has("payload"));
        var query = org.mockito.ArgumentCaptor.forClass(AuditQuery.class);
        verify(audits).summaries(query.capture());
        assertEquals(new AuditQuery("auth.login", "42", "authentication", "failure", "account", "trace",
                Instant.parse("2026-10-29T12:00:00Z"), Instant.parse("2026-10-30T12:00:00Z"), 2, 50), query.getValue());
        verify(audits, never()).query(any());
        clearInvocations(audits);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "42"));
        mvc.perform(get("/api/v1/tenant/foundation/operations/audit/summaries")).andExpect(status().isForbidden());
        verifyNoInteractions(audits);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(false, "7", "42"));
        when(audits.summaries(any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "拒绝"));
        mvc.perform(get("/api/v1/tenant/foundation/operations/audit/summaries")).andExpect(status().isForbidden());
    }
}
