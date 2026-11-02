package com.skillforge.platform.controller;

import com.skillforge.core.chat.observation.SessionObservationAuthorizer;
import com.skillforge.core.chat.trajectory.TrajectoryListQuery;
import com.skillforge.core.chat.trajectory.TrajectoryListService;
import com.skillforge.core.chat.trajectory.TrajectoryProjectionService;
import com.skillforge.core.chat.trajectory.TrajectoryQuery;
import com.skillforge.core.chat.trajectory.TrajectoryView;
import com.skillforge.core.foundation.access.FoundationAccess;
import com.skillforge.core.foundation.access.RequirePermission;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformTrajectoryControllerTest {
    @Test void bindsSnakeCaseFiltersAndNodeIdentityThroughActualHttp() throws Exception {
        var access = mock(FoundationAccess.class);
        when(access.currentSubject()).thenReturn(new FoundationAccess.Subject(true, null, "99"));
        var lists = mock(TrajectoryListService.class);
        var projection = mock(TrajectoryProjectionService.class);
        when(lists.list(any(), any())).thenReturn(new TrajectoryView.ListPage(List.of(), 1, 20, 0,
                Instant.parse("2026-10-25T08:00:00Z")));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new PlatformTrajectoryController(new SessionObservationAuthorizer(access), lists, projection)).build();
        mvc.perform(get("/api/v1/platform/trajectories").param("tenant_id", "7")
                .param("trace_id", "trace-A").param("input_keyword", "%_")
                .param("started_from", "2026-10-24T08:00:00+08:00")
                .param("duration_min_ms", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page_size").value(20));
        var captured = org.mockito.ArgumentCaptor.forClass(TrajectoryListQuery.class);
        verify(lists).list(any(), captured.capture());
        assertEquals(7L, captured.getValue().tenantId());
        assertEquals("trace-A", captured.getValue().traceId());
        assertEquals("%_", captured.getValue().inputKeyword());
        assertEquals(0L, captured.getValue().durationMinMs());
        assertEquals(Instant.parse("2026-10-24T00:00:00Z"), captured.getValue().startedFrom());
        mvc.perform(get("/api/v1/platform/trajectories/nodes").param("tenant_id", "7")
                .param("session_id", "session").param("root_input_id", "root")
                .param("node_id", "target").param("snapshot", "snapshot-A").param("query", "检索"))
                .andExpect(status().isOk());
        verify(projection).nodes(any(), org.mockito.ArgumentMatchers.eq(new TrajectoryQuery.Nodes(
                7L, "session", "root", "snapshot-A", null, 50, "target", "检索")));
        assertTrue(Arrays.stream(PlatformTrajectoryController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .allMatch(method -> "foundation.sessions.read".equals(method.getAnnotation(RequirePermission.class).value())));
    }
}
